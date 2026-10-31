package com.dsh.goweiqi.game;

import com.dsh.goweiqi.rules.Board;
import com.dsh.goweiqi.rules.GoRules;
import com.dsh.goweiqi.rules.ScoreResult;
import com.dsh.goweiqi.rules.Scoring;
import com.dsh.goweiqi.rules.StoneColor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 对局状态机（服务端权威）。所有动作都在服务端校验，客户端只发意图。
 *
 * <p>局面合法性一律走 {@link GoRules}，数子一律走 {@link Scoring}，本类只负责流程与记账，
 * 不重复实现提子/劫争/数子公式。所有动作都返回明确的结果对象/枚举，不用异常控制流程。</p>
 *
 * <p>坐标约定与 {@link Board} 一致：{@code idx = x * 19 + y}，x 东向、y 南向，0..18。</p>
 */
public class GoGame {

    /** 数子阶段无操作自动强制结算的阈值（tick，5 分钟）。 */
    public static final long SCORING_TIMEOUT_TICKS = 6000L;

    /**
     * 进入数子阶段时蒙特卡洛初始死活判定的模拟局数。
     * 单遍有时间上限（{@link Scoring#TIME_LIMIT_MS}），这里取 40 局以免第二次 pass 卡住服务端 tick。
     */
    public static final int SCORING_GUESS_TRIALS = 40;

    /**
     * 单人模式同步兜底用的占位所有者（零 UUID）。只在「同步包里只有 solo 标记、没有 UUID」
     * 这种半截包情况下使用，保证客户端 {@link #isSolo()} 不会误判成 false。
     */
    private static final UUID SOLO_PLACEHOLDER = new UUID(0L, 0L);

    /**
     * 悔棋最多可回退的着手数。快照 <b>只活在内存里</b>（绝不进 NBT），
     * 上限存在的意义是防止超长对局把堆吃穿（每份 ≈ 361 字节 + 超劫集合的一份拷贝）。
     */
    public static final int MAX_UNDO_HISTORY = 200;

    /** 对局状态。 */
    private final Board board = new Board();
    private byte toMove = StoneColor.BLACK;
    private int moveCount = 0;
    /** 最后一手坐标（-1 表示无），供渲染红点。 */
    private int lastX = -1;
    private int lastY = -1;
    /** 超劫禁手用的历史哈希（含当前局面）。 */
    private final Set<Long> koHistory = new HashSet<>();
    private GamePhase phase = GamePhase.EMPTY;
    /** 黑/白玩家 UUID，下标 0 = 黑，1 = 白。 */
    private final UUID[] players = new UUID[2];
    /**
     * 单人模式的所有者（null = 双人模式）。非 null 时 {@code players[0] == players[1] == soloOwner}，
     * 由同一个玩家轮流执黑执白。
     */
    private UUID soloOwner;
    /** 死活标记，下标与 {@link Board} 一致；非 0 = 判死。 */
    private byte[] deadMask = new byte[Board.POINTS];
    /** 结算确认状态，下标 0 = 黑，1 = 白。 */
    private final boolean[] accepted = new boolean[2];
    private String result = "";
    private int passStreak = 0;
    private int blackCaptured = 0;
    private int whiteCaptured = 0;
    private GoStyle style = GoStyle.KAYA;
    /** {@link #tickScoringTimeout(long)} 的计时基准（-1 = 尚未开始计时）。 */
    private long scoringSinceTick = -1L;
    /** 悔棋快照栈（最新在栈顶）；每次成功落子/停一手前压入，只存内存。 */
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    /** 双人模式下待确认的悔棋发起者（null = 没有待确认申请）；单人模式永远为 null。 */
    private UUID undoRequester;

    /**
     * 客户端侧「能否悔棋」的同步副本。
     *
     * <p>快照栈只在服务端持有（绝不进同步包），所以客户端必须靠这个布尔值决定按钮是否可点；
     * 服务端自己则直接用快照栈判断，不依赖本字段。</p>
     */
    private boolean syncedUndoAvailable;

    /**
     * 手顺（SGF 导出用）：每个元素 {@code {x, y, color}}，停一手记 {@code {-1, -1, color}}。
     *
     * <p>与 {@link #moveCount} <b>严格同步</b>：只有 {@link #place} / {@link #pass} 成功时才追加，
     * 悔棋回滚到快照时按 {@link Snapshot#movesSize()} 截断（盘面与手顺永不脱节）。
     * 进 NBT 落盘；<b>不进同步包</b>（客户端不需要手顺，同步包越小越好）。</p>
     */
    private final List<int[]> moves = new ArrayList<>();

    /**
     * 本局的 SGF 是否已经落盘（终局自动保存的防重入标志）。
     *
     * <p>每次进入 {@link GamePhase#FINISHED} 都置 false，自动保存成功后置 true；
     * 进 NBT，避免重进服务器后重复写同一份棋谱。</p>
     */
    private boolean sgfSaved = false;

    public GoGame() {
        koHistory.add(board.positionHash());
    }

    // ------------------------------------------------------------------
    // 结果类型
    // ------------------------------------------------------------------

    /** 入座结果。{@link #OK} 只代表本次入座成功，是否开局看 {@link #phase()}。 */
    public enum SeatResult {
        OK,
        ALREADY_SEATED,
        SEAT_TAKEN,
        WRONG_PHASE,
        INVALID_COLOR
    }

    /** 数子阶段切换死活的结果。 */
    public enum ToggleResult {
        TOGGLED,
        /** 该串在绝对活白名单里，需要调用方确认（Shift 强制）后用 force 再调一次。 */
        REJECTED_ALIVE,
        WRONG_PHASE,
        NO_STONE
    }

    /** 悔棋相关动作的结果。 */
    public enum UndoResult {
        /** 已退一手（单人立刻生效，或对方点「同意」后执行）。 */
        OK,
        /** 双人模式：申请已记录，等待对方确认。 */
        REQUESTED,
        /** 没有可退的着手（开局第 0 手之前），或没有待确认的申请。 */
        NOTHING_TO_UNDO,
        /** 当前阶段不允许悔棋（只有 {@link GamePhase#PLAYING} 可以）。 */
        WRONG_PHASE,
        /** 已经有一条待确认的悔棋申请。 */
        PENDING_EXISTS,
        /** 想同意自己发起的申请（或座位相同）——发起者不能自己批准。 */
        NOT_OPPONENT,
        /** 不是本局玩家。 */
        NOT_PLAYER
    }

    /** 落子结果：状态 + 本手提掉的下标（OK 时非空）。 */
    public record PlaceResult(Status status, int[] captured) {

        /** 落子失败原因。 */
        public enum Status {
            OK,
            NOT_YOUR_TURN,
            WRONG_PHASE,
            ILLEGAL_OCCUPIED,
            ILLEGAL_SUICIDE,
            ILLEGAL_SUPERKO
        }

        public boolean ok() {
            return status == Status.OK;
        }

        public static PlaceResult ok(int[] captured) {
            return new PlaceResult(Status.OK, captured == null ? new int[0] : captured);
        }

        public static PlaceResult fail(Status status) {
            return new PlaceResult(status, new int[0]);
        }
    }

    // ------------------------------------------------------------------
    // 状态读取（网络全量下发 / 渲染 / 调试命令用）
    // ------------------------------------------------------------------

    /** 盘面对象（内部就是 {@code byte[361]}，规则引擎直接操作它）。 */
    public Board board() {
        return board;
    }

    /** 盘面裸数组（网络载荷用，调用方只读）。 */
    public byte[] cells() {
        return board.cells();
    }

    public byte toMove() {
        return toMove;
    }

    public int moveCount() {
        return moveCount;
    }

    public int lastX() {
        return lastX;
    }

    public int lastY() {
        return lastY;
    }

    /** 最后一手下标，-1 表示无。 */
    public int lastMove() {
        return Board.index(lastX, lastY);
    }

    public GamePhase phase() {
        return phase;
    }

    public UUID player(byte color) {
        int seat = seatIndexOf(color);
        return seat < 0 ? null : players[seat];
    }

    public UUID blackPlayer() {
        return players[0];
    }

    public UUID whitePlayer() {
        return players[1];
    }

    /** 对手的 UUID；单人模式（自己就是双方）或未入座返回 null。 */
    public UUID opponentOf(UUID player) {
        if (player == null || isSolo()) {
            return null;
        }
        if (player.equals(players[0])) {
            return players[1];
        }
        if (player.equals(players[1])) {
            return players[0];
        }
        return null;
    }

    /** 单人模式的所有者；双人模式返回 null。 */
    public UUID soloOwner() {
        return soloOwner;
    }

    /**
     * 是否单人模式（一个玩家同时执黑白）。单人模式下 {@link #colorOf(UUID)} 返回当前行棋方，
     * 所以同一个玩家会随轮次在黑白之间切换。
     */
    public boolean isSolo() {
        return soloOwner != null;
    }

    /**
     * 该局内色；未入座返回 {@link StoneColor#EMPTY}。
     *
     * <p><b>单人模式</b>：永远返回当前行棋方 {@link #toMove()}——同一个玩家既下黑也下白，
     * 客户端幽灵子/悬浮预览、服务端落子校验都靠这条规则自动对齐。</p>
     */
    public byte colorOf(UUID player) {
        if (player == null) {
            return StoneColor.EMPTY;
        }
        if (isSolo()) {
            return toMove;
        }
        if (player.equals(players[0])) {
            return StoneColor.BLACK;
        }
        if (player.equals(players[1])) {
            return StoneColor.WHITE;
        }
        return StoneColor.EMPTY;
    }

    public byte[] deadMask() {
        return deadMask;
    }

    public boolean[] accepted() {
        return accepted;
    }

    public String result() {
        return result;
    }

    public int passStreak() {
        return passStreak;
    }

    public Set<Long> koHistory() {
        return koHistory;
    }

    /** 待确认的悔棋发起者；null = 没有待确认申请。UI 据此决定显示「同意 / 拒绝」。 */
    public UUID undoRequester() {
        return undoRequester;
    }

    /** 是否还有可退的着手。服务端看快照栈；客户端看同步下来的标记（栈不下发）。 */
    public boolean undoAvailable() {
        return !undoStack.isEmpty() || syncedUndoAvailable;
    }

    /**
     * 手顺的<b>只读副本</b>：每个元素 {@code {x, y, color}}，停一手为 {@code {-1, -1, color}}。
     *
     * <p>返回的是逐条 clone 后的不可修改列表，调用方（SGF 导出）随便读都不会污染对局状态。</p>
     */
    public List<int[]> moves() {
        List<int[]> copy = new ArrayList<>(moves.size());
        for (int[] move : moves) {
            copy.add(move.clone());
        }
        return Collections.unmodifiableList(copy);
    }

    /** 本局的 SGF 是否已落盘（终局自动保存的去重标志）。 */
    public boolean sgfSaved() {
        return sgfSaved;
    }

    /** 标记 SGF 落盘结果；{@link #writeSync} 不下发，客户端无需知道。 */
    public void setSgfSaved(boolean saved) {
        this.sgfSaved = saved;
    }

    public int blackCaptured() {
        return blackCaptured;
    }

    public int whiteCaptured() {
        return whiteCaptured;
    }

    public GoStyle style() {
        return style;
    }

    public void style(GoStyle style) {
        if (style != null) {
            this.style = style;
        }
    }

    /** 数子计时基准；-1 表示当前没有在计时。 */
    public long scoringSinceTick() {
        return scoringSinceTick;
    }

    /** 当前 deadMask 下的数子预览（不改变任何状态）。 */
    public ScoreResult scorePreview() {
        return Scoring.score(board, deadMask);
    }

    // ------------------------------------------------------------------
    // 入座 / 开局
    // ------------------------------------------------------------------

    /**
     * 入座。两人齐 → {@link GamePhase#PLAYING}。
     *
     * <p>允许的阶段：{@code EMPTY} / {@code WAITING}。对局进行中只允许原座位重复入座
     * （返回 {@link SeatResult#ALREADY_SEATED}），不允许换座。</p>
     */
    public SeatResult seat(UUID player, byte color) {
        int seat = seatIndexOf(color);
        if (seat < 0 || player == null) {
            return SeatResult.INVALID_COLOR;
        }
        if (phase == GamePhase.PLAYING || phase == GamePhase.SCORING) {
            return player.equals(players[seat]) ? SeatResult.ALREADY_SEATED : SeatResult.WRONG_PHASE;
        }
        if (phase == GamePhase.FINISHED) {
            return SeatResult.WRONG_PHASE;
        }
        if (players[seat] != null) {
            return players[seat].equals(player) ? SeatResult.ALREADY_SEATED : SeatResult.SEAT_TAKEN;
        }
        int other = 1 - seat;
        if (player.equals(players[other])) {
            return SeatResult.ALREADY_SEATED;
        }
        players[seat] = player;
        if (players[0] != null && players[1] != null) {
            phase = GamePhase.PLAYING;
            toMove = StoneColor.BLACK;
            passStreak = 0;
            accepted[0] = false;
            accepted[1] = false;
            scoringSinceTick = -1L;
        } else {
            phase = GamePhase.WAITING;
        }
        return SeatResult.OK;
    }

    /**
     * 单人模式开局：{@code player} 同时坐黑与白，直接 {@link GamePhase#PLAYING}。
     *
     * <p>只允许在 {@code EMPTY} / {@code WAITING} 阶段调用；已经在 {@code PLAYING} /
     * {@code SCORING} 时返回 {@link SeatResult#ALREADY_SEATED}（重复点按钮不报错），
     * {@code FINISHED} 返回 {@link SeatResult#WRONG_PHASE}（需先 {@code /go reset}）。</p>
     */
    public SeatResult seatSolo(UUID player) {
        if (player == null) {
            return SeatResult.INVALID_COLOR;
        }
        if (phase == GamePhase.PLAYING || phase == GamePhase.SCORING) {
            return SeatResult.ALREADY_SEATED;
        }
        if (phase == GamePhase.FINISHED) {
            return SeatResult.WRONG_PHASE;
        }
        soloOwner = player;
        players[0] = player;
        players[1] = player;
        phase = GamePhase.PLAYING;
        toMove = StoneColor.BLACK;
        passStreak = 0;
        accepted[0] = false;
        accepted[1] = false;
        result = "";
        // 重开一局 = 新棋谱。
        sgfSaved = false;
        scoringSinceTick = -1L;
        return SeatResult.OK;
    }

    // ------------------------------------------------------------------
    // 落子 / 停一手 / 认输
    // ------------------------------------------------------------------

    /**
     * 落子。合法性全部交给 {@link GoRules#tryPlay}（含提子与位置超劫），
     * 成功后记账：提子数、moveCount、lastMove、passStreak、行棋方。
     */
    public PlaceResult place(byte color, int x, int y) {
        if (phase != GamePhase.PLAYING) {
            return PlaceResult.fail(PlaceResult.Status.WRONG_PHASE);
        }
        if (color != StoneColor.BLACK && color != StoneColor.WHITE) {
            return PlaceResult.fail(PlaceResult.Status.NOT_YOUR_TURN);
        }
        if (color != toMove) {
            return PlaceResult.fail(PlaceResult.Status.NOT_YOUR_TURN);
        }
        if (Board.index(x, y) < 0) {
            return PlaceResult.fail(PlaceResult.Status.ILLEGAL_OCCUPIED);
        }

        // 先留一份「落子前」的可回滚快照；只有这次落子真的成功才压栈。
        Snapshot before = snapshot();
        GoRules.MoveResult move = GoRules.tryPlay(board, x, y, color, koHistory);
        if (!move.ok()) {
            return PlaceResult.fail(toStatus(move.result()));
        }
        pushSnapshot(before);

        int capturedCount = move.captured().length;
        if (color == StoneColor.BLACK) {
            blackCaptured += capturedCount;
        } else {
            whiteCaptured += capturedCount;
        }
        moveCount++;
        moves.add(new int[]{x, y, color});
        lastX = x;
        lastY = y;
        passStreak = 0;
        toMove = StoneColor.opponent(color);
        accepted[0] = false;
        accepted[1] = false;
        result = "";
        return PlaceResult.ok(move.captured());
    }

    /**
     * 停一手。连续两次 pass → {@link GamePhase#SCORING}，并用
     * {@link Scoring#floatStones} + {@link Scoring#guessDead} 生成初始 deadMask。
     *
     * @return 该次 pass 是否被接受；第二次 pass（进入数子）也返回 true
     */
    public boolean pass(byte color) {
        if (phase != GamePhase.PLAYING || color != toMove) {
            return false;
        }
        // 停一手也占一手：悔棋要能把它退掉（否则会出现「退到两次 pass 之前」的错位）。
        pushSnapshot(snapshot());
        moves.add(new int[]{-1, -1, color});
        passStreak++;
        moveCount++;
        toMove = StoneColor.opponent(color);
        if (passStreak >= 2) {
            enterScoring();
        }
        return true;
    }

    /** 认输：对方中盘胜，{@link GamePhase#FINISHED}。 */
    public boolean resign(byte color) {
        if (color != StoneColor.BLACK && color != StoneColor.WHITE) {
            return false;
        }
        if (phase != GamePhase.PLAYING && phase != GamePhase.SCORING) {
            return false;
        }
        byte winner = StoneColor.opponent(color);
        result = (winner == StoneColor.BLACK ? "黑" : "白") + "中盘胜（认输）";
        phase = GamePhase.FINISHED;
        // 新的终局 = 一份新的棋谱：允许自动保存再写一次。
        sgfSaved = false;
        scoringSinceTick = -1L;
        return true;
    }

    // ------------------------------------------------------------------
    // 悔棋（快照只存内存，重启后不可悔棋属预期）
    // ------------------------------------------------------------------

    /**
     * 请求悔棋。
     *
     * <p><b>单人模式</b>：直接退一手并返回 {@link UndoResult#OK}，不弹任何确认。</p>
     * <p><b>双人模式</b>：记下发起者并返回 {@link UndoResult#REQUESTED}，
     * 必须等对手 {@link #acceptUndo(UUID)} 才真正退棋。</p>
     */
    public UndoResult requestUndo(UUID requester) {
        if (phase != GamePhase.PLAYING) {
            return UndoResult.WRONG_PHASE;
        }
        if (requester == null) {
            return UndoResult.NOT_PLAYER;
        }
        if (isSolo()) {
            if (!requester.equals(soloOwner)) {
                return UndoResult.NOT_PLAYER;
            }
            if (undoStack.isEmpty()) {
                return UndoResult.NOTHING_TO_UNDO;
            }
            applyUndo();
            return UndoResult.OK;
        }
        if (seatIndexOfPlayer(requester) < 0) {
            return UndoResult.NOT_PLAYER;
        }
        if (undoRequester != null) {
            return UndoResult.PENDING_EXISTS;
        }
        if (undoStack.isEmpty()) {
            return UndoResult.NOTHING_TO_UNDO;
        }
        undoRequester = requester;
        return UndoResult.REQUESTED;
    }

    /**
     * 同意对方的悔棋申请并退一手。
     *
     * <p><b>硬要求</b>：发起者不能自己批准——{@code accepter} 必须是对手，
     * 否则返回 {@link UndoResult#NOT_OPPONENT} 且不改任何状态。</p>
     */
    public UndoResult acceptUndo(UUID accepter) {
        if (phase != GamePhase.PLAYING) {
            return UndoResult.WRONG_PHASE;
        }
        if (accepter == null) {
            return UndoResult.NOT_PLAYER;
        }
        if (isSolo()) {
            // 单人模式没有「对方」：所有申请都当场执行，不存在待确认状态。
            return UndoResult.NOT_PLAYER;
        }
        if (undoRequester == null) {
            return UndoResult.NOTHING_TO_UNDO;
        }
        if (accepter.equals(undoRequester)
                || seatIndexOfPlayer(accepter) == seatIndexOfPlayer(undoRequester)) {
            return UndoResult.NOT_OPPONENT;
        }
        if (seatIndexOfPlayer(accepter) < 0) {
            return UndoResult.NOT_PLAYER;
        }
        if (undoStack.isEmpty()) {
            undoRequester = null;
            return UndoResult.NOTHING_TO_UNDO;
        }
        applyUndo();
        return UndoResult.OK;
    }

    /**
     * 拒绝对方的悔棋申请（清掉 pending）。发起者自己调用、旁观者调用一律忽略；
     * 调用方可用 {@link #undoRequester()} 是否归零判断是否真的拒绝成功。
     */
    public void rejectUndo(UUID rejecter) {
        if (undoRequester == null || rejecter == null || isSolo()) {
            return;
        }
        if (rejecter.equals(undoRequester) || seatIndexOfPlayer(rejecter) < 0) {
            return;
        }
        undoRequester = null;
    }

    // ------------------------------------------------------------------
    // 数子阶段
    // ------------------------------------------------------------------

    /** 切换整串死活；绝对活白名单内的串要求 force 才覆盖。 */
    public ToggleResult toggleDead(int x, int y) {
        return toggleDead(x, y, false);
    }

    /**
     * 切换 {@code (x,y)} 所在<b>整个连通串</b>的死活标记（不递归邻串）。
     *
     * @param force true 时忽略绝对活白名单（玩家 Shift 强制覆盖）
     */
    public ToggleResult toggleDead(int x, int y, boolean force) {
        if (phase != GamePhase.SCORING) {
            return ToggleResult.WRONG_PHASE;
        }
        int idx = Board.index(x, y);
        if (idx < 0 || board.getIndex(idx) == StoneColor.EMPTY) {
            return ToggleResult.NO_STONE;
        }
        int[] chain = board.chainIndices(idx);
        if (chain.length == 0) {
            return ToggleResult.NO_STONE;
        }
        if (!force) {
            for (int point : chain) {
                if (Scoring.isAliveWhitelist(board, point)) {
                    return ToggleResult.REJECTED_ALIVE;
                }
            }
        }
        boolean markDead = deadMask[idx] == 0;
        byte value = (byte) (markDead ? 1 : 0);
        for (int point : chain) {
            deadMask[point] = value;
        }
        accepted[0] = false;
        accepted[1] = false;
        scoringSinceTick = -1L;
        return ToggleResult.TOGGLED;
    }

    /**
     * 确认结算。双方都确认 → 结算并 {@link GamePhase#FINISHED}。
     *
     * <p><b>单人模式</b>：点一次就把左右两边都置为已确认并立即结算——只有一个人，
     * 不能让玩家连点两次。非单人模式保持「各自确认」的语义。</p>
     *
     * @return 该玩家的确认是否被记录（非 SCORING 阶段 / 非本局玩家返回 false）
     */
    public boolean confirm(UUID player) {
        if (phase != GamePhase.SCORING) {
            return false;
        }
        if (isSolo()) {
            // 单人：只有本局的所有者能确认（旁观者不在座位上，不允许替人结算）。
            if (player == null || !player.equals(soloOwner)) {
                return false;
            }
            accepted[0] = true;
            accepted[1] = true;
            settle();
            phase = GamePhase.FINISHED;
            sgfSaved = false;
            scoringSinceTick = -1L;
            return true;
        }
        int seat = seatIndexOfPlayer(player);
        if (seat < 0) {
            return false;
        }
        accepted[seat] = true;
        scoringSinceTick = -1L;
        if (accepted[0] && accepted[1]) {
            settle();
            phase = GamePhase.FINISHED;
            sgfSaved = false;
            scoringSinceTick = -1L;
        }
        return true;
    }

    /** 有争议：accepted 清零，回 {@link GamePhase#PLAYING}（单人模式同样把左右两边一起清零）。 */
    public void dispute() {
        accepted[0] = false;
        accepted[1] = false;
        result = "";
        passStreak = 0;
        undoRequester = null;
        scoringSinceTick = -1L;
        if (phase == GamePhase.SCORING) {
            phase = GamePhase.PLAYING;
        }
    }

    /** 按当前 deadMask 直接结算并 {@link GamePhase#FINISHED}。 */
    public void forceScore() {
        if (phase == GamePhase.FINISHED) {
            return;
        }
        settle();
        phase = GamePhase.FINISHED;
        sgfSaved = false;
        scoringSinceTick = -1L;
    }

    /**
     * 数子阶段的 5 分钟无操作超时。第一次调用只建立计时基准。
     *
     * @param gameTime 世界游戏时间（tick）
     * @return true 表示本次调用触发了强制结算
     */
    public boolean tickScoringTimeout(long gameTime) {
        if (phase != GamePhase.SCORING) {
            scoringSinceTick = -1L;
            return false;
        }
        if (scoringSinceTick < 0L) {
            scoringSinceTick = gameTime;
            return false;
        }
        if (gameTime - scoringSinceTick >= SCORING_TIMEOUT_TICKS) {
            forceScore();
            return true;
        }
        return false;
    }

    /** 按当前 deadMask 结算，写入 {@link #result()}（不改 phase）。 */
    public ScoreResult settle() {
        ScoreResult scored = Scoring.score(board, deadMask);
        result = formatResult(scored);
        return scored;
    }

    // ------------------------------------------------------------------
    // 同步快照（服务端 getUpdateTag / 客户端 loadAdditional 用）
    // ------------------------------------------------------------------

    /**
     * 写<b>同步快照</b>：只包含渲染与交互必需的字段
     * （phase、cells、toMove、moveCount、lastX/lastY、deadMask、accepted、result、style、提子数、
     * 单人模式标记）。
     *
     * <p>刻意<b>不写</b> {@code koHistory} / 玩家 UUID / 计时基准等只对服务端有意义的落盘字段：
     * 同步包越小，失步面越小；客户端根本不需要超劫历史。</p>
     *
     * <p>唯一例外是 {@code soloOwner}：客户端要靠 {@link #isSolo()} 决定
     * {@link #colorOf(UUID)} 的语义（单人模式恒等于行棋方），否则 GUI 的悬浮预览子与
     * 「停一手」按钮永远不可用。它不影响任何服务端校验。</p>
     */
    public void writeSync(CompoundTag tag) {
        tag.putByte("phase", phase.id());
        tag.putByteArray("cells", board.cells().clone());
        tag.putByte("toMove", toMove);
        tag.putInt("moveCount", moveCount);
        tag.putInt("lastX", lastX);
        tag.putInt("lastY", lastY);
        tag.putByteArray("deadMask", deadMask.clone());
        tag.putBoolean("acceptedBlack", accepted[0]);
        tag.putBoolean("acceptedWhite", accepted[1]);
        tag.putString("result", result == null ? "" : result);
        tag.putByte("style", (byte) style.ordinal());
        tag.putInt("blackCaptured", blackCaptured);
        tag.putInt("whiteCaptured", whiteCaptured);
        tag.putBoolean("solo", isSolo());
        if (soloOwner != null) {
            tag.putString("soloOwner", soloOwner.toString());
        }
        // 待确认的悔棋申请必须同步：UI 靠它显示「同意 / 拒绝」。空串 = 没有申请。
        tag.putString("undoRequester", undoRequester == null ? "" : undoRequester.toString());
        // 能否悔棋也必须同步：快照栈不下发，客户端否则永远以为不可悔（按钮全灰）。
        tag.putBoolean("undoAvail", !undoStack.isEmpty());
    }

    /**
     * 读同步快照并就地覆盖本局状态（客户端用）。缺字段一律保持原值，长度不符的数组忽略，
     * 绝不抛异常——收到半截包时宁可视效错一帧，也不能把客户端打崩。
     */
    public void readSync(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        if (tag.contains("cells")) {
            byte[] cells = tag.getByteArray("cells");
            if (cells.length == Board.POINTS) {
                System.arraycopy(cells, 0, board.cells(), 0, Board.POINTS);
            }
        }
        if (tag.contains("phase")) {
            phase = GamePhase.byId(tag.getByte("phase"));
        }
        if (tag.contains("undoAvail")) {
            syncedUndoAvailable = tag.getBoolean("undoAvail");
        }
        if (tag.contains("toMove")) {
            byte next = tag.getByte("toMove");
            if (next == StoneColor.BLACK || next == StoneColor.WHITE) {
                toMove = next;
            }
        }
        if (tag.contains("moveCount")) {
            moveCount = Math.max(0, tag.getInt("moveCount"));
        }
        if (tag.contains("lastX")) {
            lastX = tag.getInt("lastX");
        }
        if (tag.contains("lastY")) {
            lastY = tag.getInt("lastY");
        }
        if (tag.contains("deadMask")) {
            byte[] mask = tag.getByteArray("deadMask");
            if (mask.length == Board.POINTS) {
                System.arraycopy(mask, 0, deadMask, 0, Board.POINTS);
            }
        }
        accepted[0] = tag.getBoolean("acceptedBlack");
        accepted[1] = tag.getBoolean("acceptedWhite");
        result = tag.getString("result");
        if (tag.contains("style")) {
            GoStyle[] styles = GoStyle.values();
            int ordinal = Math.max(0, Math.min(styles.length - 1, tag.getByte("style")));
            style = styles[ordinal];
        }
        if (tag.contains("blackCaptured")) {
            blackCaptured = Math.max(0, tag.getInt("blackCaptured"));
        }
        if (tag.contains("whiteCaptured")) {
            whiteCaptured = Math.max(0, tag.getInt("whiteCaptured"));
        }
        if (tag.contains("solo")) {
            if (tag.getBoolean("solo")) {
                UUID owner = parseUuid(tag.getString("soloOwner"));
                // 半截包（有标记没 UUID）用零 UUID 占位：isSolo() 必须保持 true，
                // 否则客户端会退化成「人人都没颜色」，悬浮预览子与「停一手」全废。
                soloOwner = owner == null ? SOLO_PLACEHOLDER : owner;
                players[0] = soloOwner;
                players[1] = soloOwner;
            } else {
                soloOwner = null;
            }
        }
        // 同步快照不含超劫历史：把当前局面哈希补进去，避免客户端算出的 history 为空。
        koHistory.add(board.positionHash());
        // 待确认的悔棋申请（客户端 UI 用；空串/缺字段 = 没有申请）。
        undoRequester = parseUuid(tag.getString("undoRequester"));
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    /** 整局状态写 NBT（1.21.1 签名带 HolderLookup.Provider）。 */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("board", board.cells().clone());
        tag.putByte("toMove", toMove);
        tag.putInt("moveCount", moveCount);
        tag.putInt("lastX", lastX);
        tag.putInt("lastY", lastY);
        long[] history = new long[koHistory.size()];
        int i = 0;
        for (long hash : koHistory) {
            history[i++] = hash;
        }
        tag.putLongArray("koHistory", history);
        tag.putByte("phase", phase.id());
        if (players[0] != null) {
            tag.putString("blackPlayer", players[0].toString());
        }
        if (players[1] != null) {
            tag.putString("whitePlayer", players[1].toString());
        }
        if (soloOwner != null) {
            tag.putString("soloOwner", soloOwner.toString());
        }
        tag.putByteArray("deadMask", deadMask.clone());
        tag.putBoolean("acceptedBlack", accepted[0]);
        tag.putBoolean("acceptedWhite", accepted[1]);
        tag.putString("result", result == null ? "" : result);
        tag.putInt("passStreak", passStreak);
        tag.putInt("blackCaptured", blackCaptured);
        tag.putInt("whiteCaptured", whiteCaptured);
        tag.putByte("style", (byte) style.ordinal());
        tag.putLong("scoringSinceTick", scoringSinceTick);
        // 手顺（SGF 导出用）：平铺成 {x, y, color} 三元组；停一手 x=y=-1。
        tag.putIntArray("moves", flattenMoves());
        tag.putBoolean("sgfSaved", sgfSaved);
        return tag;
    }

    /** 从 NBT 还原整局状态；缺字段一律回落到空局默认值，不抛异常。 */
    public static GoGame load(CompoundTag tag, HolderLookup.Provider registries) {
        GoGame game = new GoGame();
        if (tag == null) {
            return game;
        }
        if (tag.contains("board")) {
            byte[] cells = tag.getByteArray("board");
            if (cells.length == Board.POINTS) {
                System.arraycopy(cells, 0, game.board.cells(), 0, Board.POINTS);
            }
        }
        game.toMove = tag.contains("toMove") ? tag.getByte("toMove") : StoneColor.BLACK;
        if (game.toMove != StoneColor.BLACK && game.toMove != StoneColor.WHITE) {
            game.toMove = StoneColor.BLACK;
        }
        game.moveCount = Math.max(0, tag.getInt("moveCount"));
        game.lastX = tag.contains("lastX") ? tag.getInt("lastX") : -1;
        game.lastY = tag.contains("lastY") ? tag.getInt("lastY") : -1;
        game.koHistory.clear();
        if (tag.contains("koHistory")) {
            for (long hash : tag.getLongArray("koHistory")) {
                game.koHistory.add(hash);
            }
        }
        game.koHistory.add(game.board.positionHash());
        game.phase = tag.contains("phase") ? GamePhase.byId(tag.getByte("phase")) : GamePhase.EMPTY;
        game.players[0] = parseUuid(tag.getString("blackPlayer"));
        game.players[1] = parseUuid(tag.getString("whitePlayer"));
        game.soloOwner = parseUuid(tag.getString("soloOwner"));
        if (game.soloOwner == null && game.players[0] != null && game.players[0].equals(game.players[1])) {
            // 兼容缺 soloOwner 键的旧存档：黑白同一人 = 单人局。
            game.soloOwner = game.players[0];
        }
        if (tag.contains("deadMask")) {
            byte[] mask = tag.getByteArray("deadMask");
            if (mask.length == Board.POINTS) {
                game.deadMask = mask.clone();
            }
        }
        game.accepted[0] = tag.getBoolean("acceptedBlack");
        game.accepted[1] = tag.getBoolean("acceptedWhite");
        game.result = tag.getString("result");
        game.passStreak = Math.max(0, tag.getInt("passStreak"));
        game.blackCaptured = Math.max(0, tag.getInt("blackCaptured"));
        game.whiteCaptured = Math.max(0, tag.getInt("whiteCaptured"));
        int styleOrdinal = tag.contains("style") ? tag.getByte("style") : 0;
        GoStyle[] styles = GoStyle.values();
        game.style = styles[Math.max(0, Math.min(styles.length - 1, styleOrdinal))];
        game.scoringSinceTick = tag.contains("scoringSinceTick") ? tag.getLong("scoringSinceTick") : -1L;
        // 手顺：旧存档没有 "moves" 键时保持空表（SGF 会导出成「只有根属性」的棋谱，不会崩）。
        if (tag.contains("moves")) {
            int[] flat = tag.getIntArray("moves");
            for (int i = 0; i + 2 < flat.length; i += 3) {
                byte color = (byte) flat[i + 2];
                if (color != StoneColor.BLACK && color != StoneColor.WHITE) {
                    continue;
                }
                if (flat[i] < -1 || flat[i] >= Board.SIZE || flat[i + 1] < -1 || flat[i + 1] >= Board.SIZE) {
                    continue;
                }
                game.moves.add(new int[]{flat[i], flat[i + 1], color});
            }
        }
        game.sgfSaved = tag.getBoolean("sgfSaved");
        return game;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 一手之前的完整可回滚状态（只存内存，不写 NBT）。
     *
     * <p>{@code koHistory} 必须是<b>整份拷贝</b>：悔棋要把那一手记进 history 的哈希一起撤销，
     * 否则重下同一手会被误判「超劫禁手」。</p>
     *
     * <p>{@code movesSize} 是压栈那一刻手顺列表的长度；悔棋回滚时把手顺截断到这个长度，
     * 保证「盘面 ↔ 手顺 ↔ 手数」三者永不脱节（只回滚盘面会留下多余的 SGF 着法）。</p>
     */
    private record Snapshot(byte[] cells, byte toMove, int moveCount, int lastX, int lastY,
                            int blackCaptured, int whiteCaptured, int passStreak, long[] koHistory,
                            int movesSize) {
    }

    /** 当前状态的完整快照（含超劫历史整份拷贝与手顺长度）。 */
    private Snapshot snapshot() {
        long[] history = new long[koHistory.size()];
        int i = 0;
        for (long hash : koHistory) {
            history[i++] = hash;
        }
        return new Snapshot(board.cells().clone(), toMove, moveCount, lastX, lastY,
                blackCaptured, whiteCaptured, passStreak, history, moves.size());
    }

    private void pushSnapshot(Snapshot snapshot) {
        undoStack.addLast(snapshot);
        while (undoStack.size() > MAX_UNDO_HISTORY) {
            undoStack.removeFirst();
        }
    }

    /** 把手顺平铺成 {@code {x, y, color}} 三元组数组（NBT 落盘用）。 */
    private int[] flattenMoves() {
        int[] flat = new int[moves.size() * 3];
        int i = 0;
        for (int[] move : moves) {
            flat[i++] = move[0];
            flat[i++] = move[1];
            flat[i++] = move[2];
        }
        return flat;
    }

    /** 把手顺截断到 {@code size}（悔棋回滚用；size 大于当前长度时什么都不做）。 */
    private void truncateMoves(int size) {
        int target = Math.max(0, Math.min(size, moves.size()));
        while (moves.size() > target) {
            moves.remove(moves.size() - 1);
        }
    }

    /**
     * 真正退一手：盘面 / 行棋方 / 手数 / 最后一手 / 提子数 / pass 连击 / 超劫历史全部回到快照，
     * 并清掉待确认申请与结算确认标记。
     *
     * <p>超劫历史用<b>整份快照覆盖</b>（不是只删一个哈希）：快照是在落子<b>之前</b>压入的，
     * 当时还没有这一手新增的哈希，覆盖回去等于把那一笔记录一并撤销；
     * 若只删「当前局面哈希」，遇到哈希本来就在历史里（重复局面/Set 去重）仍会误判。</p>
     */
    private void applyUndo() {
        Snapshot previous = undoStack.pollLast();
        if (previous == null) {
            return;
        }
        System.arraycopy(previous.cells(), 0, board.cells(), 0, Board.POINTS);
        toMove = previous.toMove();
        moveCount = previous.moveCount();
        lastX = previous.lastX();
        lastY = previous.lastY();
        blackCaptured = previous.blackCaptured();
        whiteCaptured = previous.whiteCaptured();
        passStreak = previous.passStreak();
        // 手顺跟着快照一起退：截断到压栈那一刻的长度（绝不出现「盘面退了、棋谱没退」）。
        truncateMoves(previous.movesSize());
        koHistory.clear();
        for (long hash : previous.koHistory()) {
            koHistory.add(hash);
        }
        undoRequester = null;
        accepted[0] = false;
        accepted[1] = false;
        result = "";
        scoringSinceTick = -1L;
    }

    private void enterScoring() {
        phase = GamePhase.SCORING;
        accepted[0] = false;
        accepted[1] = false;
        result = "";
        scoringSinceTick = -1L;
        deadMask = initialDeadMask();
    }

    /** 初始死活：浮子（确定性）+ 蒙特卡洛终局判定，两者取并集；白名单内的点永远不判死。 */
    private byte[] initialDeadMask() {
        byte[] floating = Scoring.floatStones(board);
        byte[] guessed = Scoring.guessDead(board, SCORING_GUESS_TRIALS, true, Scoring.DEFAULT_SEED);
        byte[] mask = new byte[Board.POINTS];
        for (int i = 0; i < Board.POINTS; i++) {
            if (board.getIndex(i) == StoneColor.EMPTY) {
                continue;
            }
            if (floating[i] != 0 || guessed[i] != 0) {
                mask[i] = 1;
            }
        }
        return mask;
    }

    private static int seatIndexOf(byte color) {
        if (color == StoneColor.BLACK) {
            return 0;
        }
        if (color == StoneColor.WHITE) {
            return 1;
        }
        return -1;
    }

    private int seatIndexOfPlayer(UUID player) {
        if (player == null) {
            return -1;
        }
        if (player.equals(players[0])) {
            return 0;
        }
        if (player.equals(players[1])) {
            return 1;
        }
        return -1;
    }

    private static UUID parseUuid(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static PlaceResult.Status toStatus(GoRules.Result result) {
        return switch (result) {
            case OK -> PlaceResult.Status.OK;
            case ILLEGAL_OCCUPIED -> PlaceResult.Status.ILLEGAL_OCCUPIED;
            case ILLEGAL_SUICIDE -> PlaceResult.Status.ILLEGAL_SUICIDE;
            case ILLEGAL_SUPERKO -> PlaceResult.Status.ILLEGAL_SUPERKO;
        };
    }

    /**
     * 结果文本（PLAN §6.3 格式）：
     * {@code 黑胜 1¾ 子（3.5 目）} / {@code 白胜 2¼ 子（4.5 目）} / {@code 白中盘胜（认输）}。
     */
    private static String formatResult(ScoreResult scored) {
        if ("draw".equals(scored.winner())) {
            return "和棋";
        }
        String side = scored.blackWins() ? "黑" : "白";
        double zi = Math.abs(scored.marginZi());
        double points = Math.abs(scored.marginPoints());
        return side + "胜 " + formatZi(zi) + " 子（" + formatPoints(points) + " 目）";
    }

    /** 子差按四分之一进位成 ¼ / ½ / ¾。 */
    private static String formatZi(double zi) {
        long whole = (long) Math.floor(zi + 1.0E-9D);
        double frac = zi - whole;
        if (frac < 0.125D) {
            return Long.toString(whole);
        }
        if (frac < 0.375D) {
            return whole + "¼";
        }
        if (frac < 0.625D) {
            return whole + "½";
        }
        if (frac < 0.875D) {
            return whole + "¾";
        }
        return Long.toString(whole + 1L);
    }

    /** 目差 = 2 × 子差，只会出现整数或 .5。 */
    private static String formatPoints(double points) {
        if (Math.abs(points - Math.rint(points)) < 1.0E-9D) {
            return Long.toString((long) Math.rint(points));
        }
        return Double.toString(points);
    }
}

package com.dsh.goweiqi.net;

import com.dsh.goweiqi.GoWeiqiMod;
import com.dsh.goweiqi.SgfService;
import com.dsh.goweiqi.block.GoBoardBlock;
import com.dsh.goweiqi.block.GoBoardEntity;
import com.dsh.goweiqi.game.GamePhase;
import com.dsh.goweiqi.game.GoGame;
import com.dsh.goweiqi.game.GoStyle;
import com.dsh.goweiqi.rules.StoneColor;
import com.dsh.goweiqi.sound.ModSounds;

import java.nio.file.Path;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * 网络注册与服务端动作分发（fabric-networking-api-v1）。
 *
 * <p><b>只注册 C2S</b>：状态同步走原版方块实体包（{@code setChanged() + sendBlockUpdated}），
 * 不注册任何 S2C 状态载荷。客户端只发意图，一切校验在服务端。</p>
 */
public final class GoNetworking {

    /** 允许操作棋盘的最大距离（格），防止远距离伪造包。 */
    private static final double MAX_ACTION_DISTANCE_SQ = 8.0D * 8.0D;

    private GoNetworking() {
    }

    /** 在 {@code onInitialize()} 里调用一次。 */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(GoActionPayload.TYPE, GoActionPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GoActionPayload.TYPE, (payload, context) ->
                // 绝不在网络线程改世界状态：切回服务端主线程再处理。
                context.server().execute(() -> handle(context.player(), payload)));
    }

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    private static void handle(ServerPlayer player, GoActionPayload payload) {
        if (player == null || payload == null || payload.pos() == null) {
            return;
        }
        BlockPos pos = payload.pos();
        if (player.blockPosition().distSqr(pos) > MAX_ACTION_DISTANCE_SQ) {
            message(player, Component.translatable("message.mcgo.too_far"));
            return;
        }
        Level level = player.serverLevel();
        GoBoardEntity board = GoBoardBlock.entityOf(level, pos);
        if (board == null) {
            message(player, Component.translatable("message.mcgo.no_board"));
            return;
        }
        GoGame game = board.game();
        // 单人模式是「一个人的局」：棋盘外的玩家不能替他落子/停一手/结算。
        // （单人模式下 colorOf 对谁都返回行棋方，所以这里必须显式按所有人校验。）
        if (game.isSolo() && affectsGame(payload.action())
                && !player.getUUID().equals(game.soloOwner())) {
            message(player, Component.translatable("message.mcgo.not_player"));
            return;
        }
        byte color = game.colorOf(player.getUUID());

        switch (payload.action()) {
            case GoActionPayload.ACTION_SEAT_BLACK ->
                    seat(player, board, game, StoneColor.BLACK);
            case GoActionPayload.ACTION_SEAT_WHITE ->
                    seat(player, board, game, StoneColor.WHITE);
            case GoActionPayload.ACTION_PLACE ->
                    place(player, board, game, color, payload.x(), payload.y());
            case GoActionPayload.ACTION_PASS ->
                    pass(player, board, game, color);
            case GoActionPayload.ACTION_RESIGN ->
                    resign(player, board, game, color);
            case GoActionPayload.ACTION_TOGGLE_DEAD ->
                    toggleDead(player, board, game, payload.x(), payload.y(), payload.force());
            case GoActionPayload.ACTION_CONFIRM ->
                    confirm(player, board, game);
            case GoActionPayload.ACTION_DISPUTE ->
                    dispute(player, board, game);
            case GoActionPayload.ACTION_OPEN -> {
                // 打开界面时的补偿同步：只重发一次原版方块实体包，不动 revision、不弄脏区块存档。
                board.broadcastState();
            }
            case GoActionPayload.ACTION_SOLO -> startSolo(player, board, game);
            case GoActionPayload.ACTION_RESET -> reset(player, board, game);
            case GoActionPayload.ACTION_UNDO -> undo(player, board, game);
            case GoActionPayload.ACTION_UNDO_ACCEPT -> undoAccept(player, board, game);
            case GoActionPayload.ACTION_UNDO_REJECT -> undoReject(player, board, game);
            case GoActionPayload.ACTION_SAVE_SGF -> saveSgf(player, game);
            default -> message(player, Component.translatable("message.mcgo.unknown_action"));
        }
    }

    private static void seat(ServerPlayer player, GoBoardEntity board, GoGame game, byte color) {
        GoGame.SeatResult result = game.seat(player.getUUID(), color);
        switch (result) {
            case OK -> {
                board.markDirtyAndBroadcast();
                message(player, Component.translatable("message.mcgo.seat_ok",
                        colorName(color), phaseName(game.phase())));
            }
            case ALREADY_SEATED -> message(player, Component.translatable("message.mcgo.seat_already"));
            case SEAT_TAKEN -> message(player, Component.translatable("message.mcgo.seat_taken"));
            case WRONG_PHASE -> message(player, Component.translatable("message.mcgo.seat_wrong_phase",
                    phaseName(game.phase())));
            case INVALID_COLOR -> message(player, Component.translatable("message.mcgo.seat_invalid"));
        }
    }

    /** 会改变对局状态的动作（单人模式下只有 {@code soloOwner} 能发）。 */
    private static boolean affectsGame(byte action) {
        return action == GoActionPayload.ACTION_PLACE
                || action == GoActionPayload.ACTION_PASS
                || action == GoActionPayload.ACTION_RESIGN
                || action == GoActionPayload.ACTION_TOGGLE_DEAD
                || action == GoActionPayload.ACTION_CONFIRM
                || action == GoActionPayload.ACTION_DISPUTE
                || action == GoActionPayload.ACTION_RESET
                || action == GoActionPayload.ACTION_UNDO
                || action == GoActionPayload.ACTION_UNDO_ACCEPT
                || action == GoActionPayload.ACTION_UNDO_REJECT;
    }

    /** 单人模式开局：一个玩家同时执黑白（服务端权威，直接进 PLAYING）。 */
    private static void startSolo(ServerPlayer player, GoBoardEntity board, GoGame game) {
        GoGame.SeatResult result = game.seatSolo(player.getUUID());
        switch (result) {
            case OK -> {
                board.markDirtyAndBroadcast();
                message(player, Component.translatable("message.mcgo.solo_ok"));
            }
            case ALREADY_SEATED -> message(player,
                    Component.translatable("message.mcgo.solo_already"));
            case INVALID_COLOR, SEAT_TAKEN -> message(player,
                    Component.translatable("message.mcgo.solo_invalid"));
            case WRONG_PHASE -> message(player,
                    Component.translatable("message.mcgo.solo_wrong_phase", phaseName(game.phase())));
        }
    }

    /**
     * 清盘：与 {@code /go reset} 完全一致（{@code new GoGame()} + 保留旧风格 + {@code board.setGame(...)}），
     * 完成后 {@code markDirtyAndBroadcast()}。若清盘前是单人局，直接以发起者身份开新局。
     *
     * <p>对局进行中拒绝清盘（与「对局中不能拆棋盘」一致）：需要中途终止请用认输或 {@code /go reset}。</p>
     */
    private static void reset(ServerPlayer player, GoBoardEntity board, GoGame game) {
        GamePhase phase = game.phase();
        if (phase == GamePhase.PLAYING || phase == GamePhase.SCORING) {
            message(player, Component.translatable("message.mcgo.clear_busy", phaseName(phase)));
            return;
        }
        boolean wasSolo = game.isSolo();
        GoStyle style = game.style();
        GoGame fresh = new GoGame();
        fresh.style(style);
        boolean soloRestarted = false;
        if (wasSolo) {
            // 单人局清盘后直接开新局，省一次点击（新局 phase 是 EMPTY，seatSolo 必然 OK）。
            soloRestarted = fresh.seatSolo(player.getUUID()) == GoGame.SeatResult.OK;
        }
        board.setGame(fresh);
        message(player, soloRestarted
                ? Component.translatable("message.mcgo.clear_solo_ok")
                : Component.translatable("message.mcgo.clear_ok"));
    }

    /** 悔棋：单人立刻生效；双人发起申请，等对手同意。 */
    private static void undo(ServerPlayer player, GoBoardEntity board, GoGame game) {
        GoGame.UndoResult result = game.requestUndo(player.getUUID());
        switch (result) {
            case OK -> {
                board.markDirtyAndBroadcast();
                message(player, Component.translatable("message.mcgo.undo_ok"));
            }
            case REQUESTED -> {
                board.markDirtyAndBroadcast();
                message(player, Component.translatable("message.mcgo.undo_requested"));
                notifyOpponent(player, game,
                        Component.translatable("message.mcgo.undo_opponent_request"));
            }
            case NOTHING_TO_UNDO -> message(player,
                    Component.translatable("message.mcgo.undo_nothing"));
            case WRONG_PHASE -> message(player,
                    Component.translatable("message.mcgo.undo_wrong_phase", phaseName(game.phase())));
            case PENDING_EXISTS -> message(player,
                    Component.translatable("message.mcgo.undo_pending"));
            case NOT_OPPONENT -> message(player, Component.translatable("message.mcgo.undo_self"));
            case NOT_PLAYER -> message(player, Component.translatable("message.mcgo.not_player"));
        }
    }

    /** 同意对方的悔棋（发起者自己点会被 {@code GoGame} 拒绝）。 */
    private static void undoAccept(ServerPlayer player, GoBoardEntity board, GoGame game) {
        UUID requester = game.undoRequester();
        GoGame.UndoResult result = game.acceptUndo(player.getUUID());
        switch (result) {
            case OK -> {
                board.markDirtyAndBroadcast();
                message(player, Component.translatable("message.mcgo.undo_ok"));
                notifyPlayer(player, requester,
                        Component.translatable("message.mcgo.undo_accepted"));
            }
            case NOT_OPPONENT -> message(player, Component.translatable("message.mcgo.undo_self"));
            case NOTHING_TO_UNDO -> message(player,
                    Component.translatable("message.mcgo.undo_no_pending"));
            case WRONG_PHASE -> message(player,
                    Component.translatable("message.mcgo.undo_wrong_phase", phaseName(game.phase())));
            case NOT_PLAYER -> message(player, Component.translatable("message.mcgo.not_player"));
            case REQUESTED, PENDING_EXISTS -> message(player,
                    Component.translatable("message.mcgo.undo_pending"));
        }
    }

    /** 拒绝对方的悔棋。发起者自己点、旁观者点都不生效。 */
    private static void undoReject(ServerPlayer player, GoBoardEntity board, GoGame game) {
        UUID requester = game.undoRequester();
        if (requester == null) {
            message(player, Component.translatable("message.mcgo.undo_no_pending"));
            return;
        }
        if (requester.equals(player.getUUID())) {
            // 发起者不能自己「拒绝」自己的申请（与「不能自己同意」对称）。
            message(player, Component.translatable("message.mcgo.undo_self"));
            return;
        }
        if (game.colorOf(player.getUUID()) == StoneColor.EMPTY) {
            message(player, Component.translatable("message.mcgo.not_player"));
            return;
        }
        game.rejectUndo(player.getUUID());
        board.markDirtyAndBroadcast();
        message(player, Component.translatable("message.mcgo.undo_rejected_self"));
        notifyPlayer(player, requester, Component.translatable("message.mcgo.undo_rejected"));
    }

    /**
     * 手动导出 SGF 棋谱：服务端写 {@code config/mcgo/sgf/}，把<b>绝对路径</b>回给发起者。
     *
     * <p>写盘失败只回错误消息 + 日志，绝不影响对局状态本身（没人会因为磁盘满了丢掉一盘棋）。</p>
     */
    private static void saveSgf(ServerPlayer player, GoGame game) {
        MinecraftServer server = player.server;
        try {
            Path path = SgfService.save(server, game, false);
            message(player, Component.translatable("message.mcgo.sgf_saved",
                    path.toAbsolutePath().toString()));
        } catch (Exception ex) {
            GoWeiqiMod.LOGGER.warn("[mcgo] 手动保存 SGF 失败", ex);
            message(player, Component.translatable("message.mcgo.sgf_failed"));
        }
    }

    /** 给对手发一条动作栏提示（单人模式没有对手，直接跳过）。 */
    private static void notifyOpponent(ServerPlayer actor, GoGame game, Component text) {
        notifyPlayer(actor, game.opponentOf(actor.getUUID()), text);
    }

    private static void notifyPlayer(ServerPlayer actor, UUID target, Component text) {
        if (actor == null || target == null) {
            return;
        }
        MinecraftServer server = actor.server;
        if (server == null) {
            return;
        }
        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
        if (targetPlayer != null) {
            targetPlayer.displayClientMessage(text, true);
        }
    }

    // ------------------------------------------------------------------
    // 走动播报：附近的围观者能在动作栏看到「谁走了哪里」
    // ------------------------------------------------------------------

    /** 播报半径（格）。只打扰棋盘附近的人，不刷全服聊天。 */
    private static final double BROADCAST_RADIUS = 48.0D;

    private static void broadcastNear(ServerPlayer source, BlockPos pos, Component msg) {
        if (source == null || msg == null) {
            return;
        }
        double r2 = BROADCAST_RADIUS * BROADCAST_RADIUS;
        for (ServerPlayer other : source.serverLevel().players()) {
            if (other.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) <= r2) {
                // 第二个参数 true = 动作栏，不刷聊天记录。
                other.displayClientMessage(msg, true);
            }
        }
    }

    /** 围棋坐标：列 A–T（按惯例跳过 I），行 1–19 由下往上数。 */
    private static String coordName(int x, int y) {
        char col = (char) ('A' + x + (x >= 8 ? 1 : 0));
        return col + String.valueOf(19 - y);
    }

    private static void place(ServerPlayer player, GoBoardEntity board, GoGame game,
                              byte color, int x, int y) {
        if (color == StoneColor.EMPTY) {
            message(player, Component.translatable("message.mcgo.not_player"));
            return;
        }
        GoGame.PlaceResult result = game.place(color, x, y);
        if (!result.ok()) {
            message(player, placeFailure(result.status(), game.phase()));
            return;
        }
        // 落子音在服务端播：playSound(null, ...) 会把声音包发给附近客户端。
        Level level = player.serverLevel();
        BlockPos soundPos = board.getBlockPos();
        level.playSound(null, soundPos, ModSounds.STONE, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (result.captured().length > 0) {
            // 一手只播一次提子音（不按被提子数重复播）。
            level.playSound(null, soundPos, ModSounds.CAPTURE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        board.markDirtyAndBroadcast();
        broadcastNear(player, soundPos, Component.translatable("message.mcgo.broadcast_move",
                colorName(color), coordName(x, y)));
        message(player, Component.translatable("message.mcgo.place_ok",
                x, y, result.captured().length));
    }

    private static void pass(ServerPlayer player, GoBoardEntity board, GoGame game, byte color) {
        if (color == StoneColor.EMPTY) {
            message(player, Component.translatable("message.mcgo.not_player"));
            return;
        }
        if (!game.pass(color)) {
            message(player, Component.translatable("message.mcgo.pass_fail",
                    phaseName(game.phase())));
            return;
        }
        board.markDirtyAndBroadcast();
        broadcastNear(player, board.getBlockPos(),
                Component.translatable("message.mcgo.broadcast_pass", colorName(color)));
        message(player, Component.translatable("message.mcgo.pass_ok", phaseName(game.phase())));
    }

    private static void resign(ServerPlayer player, GoBoardEntity board, GoGame game, byte color) {
        if (color == StoneColor.EMPTY) {
            message(player, Component.translatable("message.mcgo.not_player"));
            return;
        }
        if (!game.resign(color)) {
            message(player, Component.translatable("message.mcgo.resign_fail",
                    phaseName(game.phase())));
            return;
        }
        board.markDirtyAndBroadcast();
        message(player, Component.translatable("message.mcgo.resign_ok", game.result()));
    }

    private static void toggleDead(ServerPlayer player, GoBoardEntity board, GoGame game,
                                  int x, int y, boolean force) {
        GoGame.ToggleResult result = game.toggleDead(x, y, force);
        switch (result) {
            case TOGGLED -> {
                board.markDirtyAndBroadcast();
                message(player, Component.translatable("message.mcgo.toggle_dead_ok", x, y));
            }
            case REJECTED_ALIVE -> message(player,
                    Component.translatable("message.mcgo.alive_rejected"));
            case WRONG_PHASE -> message(player,
                    Component.translatable("message.mcgo.toggle_wrong_phase"));
            case NO_STONE -> message(player,
                    Component.translatable("message.mcgo.toggle_no_stone"));
        }
    }

    private static void confirm(ServerPlayer player, GoBoardEntity board, GoGame game) {
        if (game.colorOf(player.getUUID()) == StoneColor.EMPTY) {
            message(player, Component.translatable("message.mcgo.not_player"));
            return;
        }
        if (!game.confirm(player.getUUID())) {
            message(player, Component.translatable("message.mcgo.confirm_fail",
                    phaseName(game.phase())));
            return;
        }
        board.markDirtyAndBroadcast();
        if (game.phase() == GamePhase.FINISHED) {
            message(player, Component.translatable("message.mcgo.result", game.result()));
        } else {
            message(player, Component.translatable("message.mcgo.confirm_wait"));
        }
    }

    private static void dispute(ServerPlayer player, GoBoardEntity board, GoGame game) {
        if (game.colorOf(player.getUUID()) == StoneColor.EMPTY) {
            message(player, Component.translatable("message.mcgo.not_player"));
            return;
        }
        game.dispute();
        board.markDirtyAndBroadcast();
        message(player, Component.translatable("message.mcgo.dispute_ok"));
    }

    // ------------------------------------------------------------------
    // 文案 / 小工具
    // ------------------------------------------------------------------

    private static void message(ServerPlayer player, Component component) {
        // 动作结果只回给发起者（overlay=true = 动作栏，不刷聊天框）。
        player.displayClientMessage(component, true);
    }

    private static Component placeFailure(GoGame.PlaceResult.Status status, GamePhase phase) {
        return switch (status) {
            case OK -> Component.empty();
            case NOT_YOUR_TURN -> Component.translatable("message.mcgo.place_not_your_turn");
            case WRONG_PHASE -> Component.translatable("message.mcgo.place_wrong_phase",
                    phaseName(phase));
            case ILLEGAL_OCCUPIED -> Component.translatable("message.mcgo.place_occupied");
            case ILLEGAL_SUICIDE -> Component.translatable("message.mcgo.place_suicide");
            case ILLEGAL_SUPERKO -> Component.translatable("message.mcgo.place_superko");
        };
    }

    private static Component colorName(byte color) {
        return switch (color) {
            case StoneColor.BLACK -> Component.translatable("color.mcgo.black");
            case StoneColor.WHITE -> Component.translatable("color.mcgo.white");
            default -> Component.translatable("color.mcgo.empty");
        };
    }

    private static Component phaseName(GamePhase phase) {
        return Component.translatable("phase.mcgo." + phase.name().toLowerCase(java.util.Locale.ROOT));
    }
}

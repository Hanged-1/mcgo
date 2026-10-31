package com.dsh.goweiqi.client;

import com.dsh.goweiqi.block.GoBoardBlock;
import com.dsh.goweiqi.block.GoBoardEntity;
import com.dsh.goweiqi.game.GamePhase;
import com.dsh.goweiqi.game.GoGame;
import com.dsh.goweiqi.game.GoStyle;
import com.dsh.goweiqi.net.GoActionPayload;
import com.dsh.goweiqi.rules.Board;
import com.dsh.goweiqi.rules.ScoreResult;
import com.dsh.goweiqi.rules.StoneColor;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 对弈 GUI（纯客户端）。
 *
 * <p>布局自适应：{@code cell = clamp(min((height-90)/19, 22), 12, 24)}，棋盘居中，
 * 右侧一列按钮 + 状态/数子信息。</p>
 *
 * <p><b>状态来源</b>：界面不缓存任何状态，每帧从本地 {@link GoBoardEntity#game()} 读取
 * （服务端已经用原版方块实体包把状态灌进来了）。所以界面永远和世界一致，不存在两份状态。</p>
 *
 * <p><b>输入</b>：只发 {@link GoActionPayload} 意图（C2S），一切校验在服务端。</p>
 */
public class GoBoardScreen extends Screen {

    // ---- 面板配色（ARGB）----------------------------------------------------
    private static final int PANEL_BG = 0xC014120F;
    private static final int PANEL_EDGE = 0x60E8D8B0;
    private static final int TEXT = 0xFFEDE7DC;
    private static final int TEXT_DIM = 0xFFA8A296;
    private static final int TEXT_ACCENT = 0xFFFFD479;
    private static final int TEXT_GOOD = 0xFF8FE08F;
    private static final int TEXT_BAD = 0xFFE89080;
    private static final int HOVER_RING = 0xC0FFFFFF;

    /** 按钮列宽 / 按钮高 / 行距。 */
    private static final int SIDE_WIDTH = 156;
    private static final int GAP = 8;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_STEP = 24;
    private static final int LINE_H = 10;

    private final BlockPos origin;

    private int cell;
    private int boardPx;
    private int boardX;
    private int boardY;
    private int sideX;
    private int sideY;
    /** 按钮列下方信息文本的起始 Y（由布局算出，避免和「关闭」按钮重叠）。 */
    private int infoY;

    private Button seatBlackButton;
    private Button seatWhiteButton;
    /** 单人模式：一个玩家同时执黑白。只在 EMPTY / WAITING 阶段可见。 */
    private Button soloButton;
    /** 清盘：EMPTY / WAITING / FINISHED 可见。 */
    private Button resetButton;
    private Button passButton;
    /** 悔棋：PLAYING 可见。 */
    private Button undoButton;
    /** 「同意 / 拒绝」对方的悔棋申请：PLAYING 且有非自己的 pending 时可见。 */
    private Button undoAcceptButton;
    private Button undoRejectButton;
    private Button resignButton;
    private Button confirmButton;
    private Button disputeButton;
    /** 保存棋谱（SGF）：PLAYING / SCORING / FINISHED 可见；服务端写盘并回路径。 */
    private Button saveSgfButton;
    private Button closeButton;
    /** 按钮列（按显示顺序）；每帧按当前可见集合重排 y，避免阶段切换后重叠/跑出屏幕。 */
    private final java.util.List<Button> column = new java.util.ArrayList<>();

    /** 主构造：只记原点格，BE 每帧重新解析（不持有可能被替换的旧 BE）。 */
    public GoBoardScreen(BlockPos origin) {
        super(Component.translatable("gui.mcgo.title"));
        this.origin = origin == null ? BlockPos.ZERO : origin.immutable();
    }

    /** 兼容骨架里的旧构造。 */
    public GoBoardScreen(GoBoardEntity board) {
        this(board == null ? BlockPos.ZERO : board.getBlockPos());
    }

    public BlockPos origin() {
        return origin;
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        // cell = clamp(min((height-90)/19, 22), 12, 24)
        int raw = (int) Math.min((this.height - 90) / 19.0D, 22.0D);
        this.cell = Math.max(12, Math.min(24, raw));
        // 盘面贴图覆盖整个 3×3，所以边长 = 3/线距 * cell ≈ 19.08 * cell
        this.boardPx = Math.max(24, Math.round(BoardGeo.BOARD / BoardGeo.SPACING * this.cell));

        int sideWidth = Math.min(SIDE_WIDTH, Math.max(96, this.width - this.boardPx - 2 * GAP));
        int totalW = this.boardPx + GAP + sideWidth;
        this.boardX = Math.max(2, (this.width - totalW) / 2);
        this.boardY = Math.max(2, (this.height - this.boardPx) / 2);
        this.sideX = this.boardX + this.boardPx + GAP;
        this.sideY = this.boardY;

        int buttonW = Math.min(sideWidth, this.width - this.sideX - 2);
        // 所有按钮先放在同一起点，由 layoutColumn() 按「当前可见集合」依次排 y：
        // 不同阶段的按钮数量不同，写死 y 会在按钮增减时重叠或跑出屏幕。
        this.seatBlackButton = addColumnButton(Component.translatable("gui.mcgo.seat_black"),
                buttonW, button -> send(GoActionPayload.seatBlack(origin)));
        this.seatWhiteButton = addColumnButton(Component.translatable("gui.mcgo.seat_white"),
                buttonW, button -> send(GoActionPayload.seatWhite(origin)));
        this.soloButton = addColumnButton(Component.translatable("gui.mcgo.solo"),
                buttonW, button -> send(GoActionPayload.solo(origin)));
        this.resetButton = addColumnButton(Component.translatable("gui.mcgo.clear"),
                buttonW, button -> send(GoActionPayload.reset(origin)));
        this.passButton = addColumnButton(Component.translatable("gui.mcgo.pass"),
                buttonW, button -> send(GoActionPayload.pass(origin)));
        this.undoButton = addColumnButton(Component.translatable("gui.mcgo.undo"),
                buttonW, button -> send(GoActionPayload.undo(origin)));
        this.undoAcceptButton = addColumnButton(Component.translatable("gui.mcgo.undo_accept"),
                buttonW, button -> send(GoActionPayload.undoAccept(origin)));
        this.undoRejectButton = addColumnButton(Component.translatable("gui.mcgo.undo_reject"),
                buttonW, button -> send(GoActionPayload.undoReject(origin)));
        this.resignButton = addColumnButton(Component.translatable("gui.mcgo.resign"),
                buttonW, button -> send(GoActionPayload.resign(origin)));
        this.confirmButton = addColumnButton(Component.translatable("gui.mcgo.confirm"),
                buttonW, button -> send(GoActionPayload.confirm(origin)));
        this.disputeButton = addColumnButton(Component.translatable("gui.mcgo.dispute"),
                buttonW, button -> send(GoActionPayload.dispute(origin)));
        this.saveSgfButton = addColumnButton(Component.translatable("gui.mcgo.save_sgf"),
                buttonW, button -> send(GoActionPayload.saveSgf(origin)));
        this.closeButton = addColumnButton(Component.translatable("gui.mcgo.close"),
                buttonW, button -> onClose());
        layoutColumn();

        // 打开界面时请求一次重发方块实体包：进服/切维度后立刻打开也不会看到空盘。
        send(GoActionPayload.open(origin));
    }

    /** 建一个按钮并登记进按钮列（y 由 {@link #layoutColumn()} 统一排）。 */
    private Button addColumnButton(Component label, int buttonW, Button.OnPress onPress) {
        Button button = addRenderableWidget(Button.builder(label, onPress)
                .bounds(this.sideX, this.sideY, buttonW, BUTTON_H).build());
        this.column.add(button);
        return button;
    }

    /** 只给「当前可见」的按钮依次分配 y，信息区紧跟在最后一个可见按钮下面。 */
    private void layoutColumn() {
        int y = this.sideY;
        for (Button button : this.column) {
            if (button == null || !button.visible) {
                continue;
            }
            button.setY(y);
            y += BUTTON_STEP;
        }
        this.infoY = Math.max(this.sideY + BUTTON_STEP, y + 4);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GoBoardEntity entity = board();
        updateButtons(entity);
        // 背景 + 按钮（按钮列在盘面右侧，不会被下面的盘面覆盖）
        super.render(graphics, mouseX, mouseY, partialTick);
        drawPanel(graphics);
        drawBoard(graphics, entity, mouseX, mouseY);
        drawInfo(graphics, entity);
    }

    /** 半透明底板（先画底板再画盘面，盘面盖在底板上）。 */
    private void drawPanel(GuiGraphics graphics) {
        int pad = 6;
        int x0 = boardX - pad;
        int y0 = boardY - pad;
        int x1 = Math.min(this.width - 1, boardX + boardPx + pad);
        int y1 = Math.min(this.height - 1, boardY + boardPx + pad);
        graphics.fill(x0, y0, x1, y1, PANEL_BG);
        graphics.fill(x0, y0, x1, y0 + 1, PANEL_EDGE);
        graphics.fill(x0, y1 - 1, x1, y1, PANEL_EDGE);
    }

    private void drawBoard(GuiGraphics graphics, GoBoardEntity entity, int mouseX, int mouseY) {
        GoStyle style = entity == null ? GoStyle.KAYA : entity.game().style();
        ResourceLocation boardTexture = BoardTextures.board(style);
        // 11 参 blit：整张 1024² 贴图缩放铺满棋盘区域
        graphics.blit(boardTexture, boardX, boardY, boardPx, boardPx,
                0.0F, 0.0F, BoardTextures.BOARD_SIZE, BoardTextures.BOARD_SIZE,
                BoardTextures.BOARD_SIZE, BoardTextures.BOARD_SIZE);
        if (entity == null) {
            return;
        }
        GoGame game = entity.game();
        byte[] cells = game.cells();
        byte[] deadMask = game.deadMask();
        GamePhase phase = game.phase();
        boolean scoringLike = phase == GamePhase.SCORING || phase == GamePhase.FINISHED;
        ResourceLocation stoneTexture = BoardTextures.stone();
        int stoneSize = Math.max(4, Math.round(this.cell * 0.96F));

        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                int index = Board.index(x, y);
                byte color = cells[index];
                if (color == StoneColor.EMPTY) {
                    continue;
                }
                drawStone(graphics, stoneTexture, screenX(x), screenY(y), stoneSize, color, 1.0F);
                if (scoringLike && deadMask != null && deadMask.length > index && deadMask[index] != 0) {
                    drawDeadMark(graphics, screenX(x), screenY(y), stoneSize);
                }
            }
        }

        // 最后一手红点
        int lastIndex = Board.index(game.lastX(), game.lastY());
        if (lastIndex >= 0 && cells[lastIndex] != StoneColor.EMPTY) {
            int cx = screenX(game.lastX());
            int cy = screenY(game.lastY());
            int r = Math.max(1, stoneSize / 6);
            graphics.fill(cx - r, cy - r, cx + r, cy + r, 0xFFD93A2B);
        }

        // 悬浮：最近交点高亮 + 空点上的半透明预览子
        int[] hovered = intersectionAt(mouseX, mouseY);
        if (hovered != null) {
            int cx = screenX(hovered[0]);
            int cy = screenY(hovered[1]);
            int half = Math.max(2, this.cell / 4);
            graphics.fill(cx - half, cy - half, cx + half, cy + half, HOVER_RING);
            int hoveredIndex = Board.index(hovered[0], hovered[1]);
            byte mine = myColor(game);
            if (phase == GamePhase.PLAYING && mine != StoneColor.EMPTY && mine == game.toMove()
                    && cells[hoveredIndex] == StoneColor.EMPTY) {
                drawStone(graphics, stoneTexture, cx, cy, stoneSize, mine, 0.45F);
            }
        }
    }

    /** 棋子 = 32² 灰度圆盘贴图 + {@code RenderSystem.setShaderColor} 染色，画完立刻复位。 */
    private void drawStone(GuiGraphics graphics, ResourceLocation texture, int centerX, int centerY,
                           int size, byte color, float alpha) {
        float red;
        float green;
        float blue;
        if (color == StoneColor.WHITE) {
            red = 0.98F;
            green = 0.97F;
            blue = 0.93F;
        } else {
            red = 0.13F;
            green = 0.13F;
            blue = 0.15F;
        }
        RenderSystem.setShaderColor(red, green, blue, alpha);
        try {
            int half = size / 2;
            graphics.blit(texture, centerX - half, centerY - half, size, size,
                    0.0F, 0.0F, BoardTextures.STONE_SIZE, BoardTextures.STONE_SIZE,
                    BoardTextures.STONE_SIZE, BoardTextures.STONE_SIZE);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** 死子：红色外框（GUI 里用 fill 画四条边，比贴图 X 更清楚）。 */
    private void drawDeadMark(GuiGraphics graphics, int centerX, int centerY, int size) {
        int half = size / 2;
        int x0 = centerX - half;
        int y0 = centerY - half;
        int x1 = centerX + half;
        int y1 = centerY + half;
        int thickness = Math.max(1, size / 10);
        graphics.fill(x0, y0, x1, y0 + thickness, TEXT_BAD);
        graphics.fill(x0, y1 - thickness, x1, y1, TEXT_BAD);
        graphics.fill(x0, y0, x0 + thickness, y1, TEXT_BAD);
        graphics.fill(x1 - thickness, y0, x1, y1, TEXT_BAD);
    }

    /** 右侧信息：阶段 / 行棋 / 手数 / 提子；数子阶段追加子数、目数、确认状态与结果。 */
    private void drawInfo(GuiGraphics graphics, GoBoardEntity entity) {
        int x = sideX;
        int y = infoTop();
        if (entity == null) {
            graphics.drawString(this.font, Component.translatable("gui.mcgo.sync_wait"), x, y,
                    TEXT_ACCENT, false);
            return;
        }
        GoGame game = entity.game();
        GamePhase phase = game.phase();
        graphics.drawString(this.font, Component.translatable("gui.mcgo.phase", phaseName(phase)),
                x, y, TEXT_ACCENT, false);
        y += LINE_H;
        graphics.drawString(this.font, Component.translatable("gui.mcgo.turn", colorName(game.toMove())),
                x, y, TEXT, false);
        y += LINE_H;
        graphics.drawString(this.font, Component.translatable("gui.mcgo.moves", game.moveCount()),
                x, y, TEXT_DIM, false);
        y += LINE_H;
        graphics.drawString(this.font, Component.translatable("gui.mcgo.captures",
                game.blackCaptured(), game.whiteCaptured()), x, y, TEXT_DIM, false);
        y += LINE_H;

        if (game.isSolo()) {
            graphics.drawString(this.font, Component.translatable("gui.mcgo.solo_mode"),
                    x, y, TEXT_ACCENT, false);
            y += LINE_H;
        }

        // 悔棋申请：自己发起的显示「等待对方确认」，对方发起的显示「对方请求悔棋」。
        UUID undoRequester = game.undoRequester();
        if (undoRequester != null) {
            boolean mine = undoRequester.equals(myUuid());
            graphics.drawString(this.font, Component.translatable(mine
                            ? "gui.mcgo.undo_waiting_info"
                            : "gui.mcgo.undo_pending_info"),
                    x, y, mine ? TEXT_DIM : TEXT_ACCENT, false);
            y += LINE_H;
        }

        if (phase == GamePhase.EMPTY || phase == GamePhase.WAITING) {
            graphics.drawString(this.font, Component.translatable("gui.mcgo.waiting_hint"),
                    x, y + LINE_H / 2, TEXT_DIM, false);
            graphics.drawString(this.font, Component.translatable("gui.mcgo.solo_hint"),
                    x, y + LINE_H + LINE_H / 2, TEXT_DIM, false);
            return;
        }

        ScoreResult score = game.scorePreview();
        boolean[] accepted = game.accepted();
        if (phase == GamePhase.SCORING) {
            y += LINE_H / 2;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.black_stones", score.blackStones()),
                    x, y, TEXT, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.white_stones", score.whiteStones()),
                    x, y, TEXT, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.black_territory", score.blackTerritory()),
                    x, y, TEXT, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.white_territory", score.whiteTerritory()),
                    x, y, TEXT, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.dame", score.dame()),
                    x, y, TEXT_DIM, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.komi"), x, y, TEXT_DIM, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.totals",
                    score.blackTotal(), score.whiteTotal()), x, y, TEXT_ACCENT, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.accepted",
                    colorName(StoneColor.BLACK), acceptedText(accepted[0])), x, y,
                    accepted[0] ? TEXT_GOOD : TEXT_DIM, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.accepted",
                    colorName(StoneColor.WHITE), acceptedText(accepted[1])), x, y,
                    accepted[1] ? TEXT_GOOD : TEXT_DIM, false);
            y += LINE_H + 2;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.scoring_hint"),
                    x, y, TEXT_DIM, false);
            return;
        }

        if (phase == GamePhase.FINISHED) {
            y += LINE_H / 2;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.black_total", score.blackTotal()),
                    x, y, TEXT_DIM, false);
            y += LINE_H;
            graphics.drawString(this.font, Component.translatable("gui.mcgo.white_total", score.whiteTotal()),
                    x, y, TEXT_DIM, false);
            y += LINE_H + 2;
            String result = game.result() == null || game.result().isEmpty() ? "—" : game.result();
            graphics.drawString(this.font, Component.translatable("gui.mcgo.result", result),
                    x, y, TEXT_ACCENT, false);
        }
    }

    /** 按钮列下方留一点空隙再写信息。 */
    private int infoTop() {
        return this.infoY;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 1) {
            // 右键 = 取消 / 退出
            onClose();
            return true;
        }
        if (button != 0) {
            return false;
        }
        GoBoardEntity entity = board();
        if (entity == null) {
            return true;
        }
        int[] point = intersectionAt(mouseX, mouseY);
        if (point == null) {
            return true;
        }
        GamePhase phase = entity.game().phase();
        if (phase == GamePhase.SCORING) {
            // Shift+点击 = force，覆盖「绝对活白名单」的拒绝
            send(GoActionPayload.toggleDead(origin, point[0], point[1], hasShiftDown()));
        } else if (phase == GamePhase.PLAYING) {
            send(GoActionPayload.place(origin, point[0], point[1]));
        }
        // EMPTY / WAITING：入座必须显式点「执黑 / 执白」，避免误点把自己锁进座位。
        return true;
    }

    @Override
    public void onClose() {
        GoBoardRenderer.clearGhost();
        super.onClose();
    }

    @Override
    public void removed() {
        GoBoardRenderer.clearGhost();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 状态 / 坐标换算
    // ------------------------------------------------------------------

    /** 每帧解析本地的方块实体（BE 可能被区块重载替换，绝不缓存）。 */
    private GoBoardEntity board() {
        Minecraft client = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
        Level level = client.level;
        if (level == null) {
            return null;
        }
        return GoBoardBlock.entityOf(level, origin);
    }

    private void updateButtons(GoBoardEntity entity) {
        GamePhase phase = entity == null ? GamePhase.EMPTY : entity.game().phase();
        GoGame game = entity == null ? null : entity.game();
        boolean solo = game != null && game.isSolo();
        boolean canSeat = game != null && (phase == GamePhase.EMPTY || phase == GamePhase.WAITING);
        byte mine = game == null ? StoneColor.EMPTY : myColor(game);
        UUID myId = myUuid();
        UUID undoRequester = game == null ? null : game.undoRequester();
        boolean pendingMine = undoRequester != null && undoRequester.equals(myId);
        // 单人局里只有所有者能操作（服务端同样按 soloOwner 校验）。
        boolean soloOwnerMe = !solo || (myId != null && myId.equals(game.soloOwner()));
        if (seatBlackButton != null) {
            // 单人模式里「执黑 / 执白」没有意义：一个玩家本来就是双方，直接藏掉。
            seatBlackButton.visible = canSeat && !solo;
        }
        if (seatWhiteButton != null) {
            seatWhiteButton.visible = canSeat && !solo;
        }
        if (soloButton != null) {
            soloButton.visible = canSeat && !solo;
        }
        if (resetButton != null) {
            // 清盘：空盘 / 等人 / 已结束可见；对局中与数子中藏起来（服务端也会拒绝）。
            resetButton.visible = phase == GamePhase.EMPTY || phase == GamePhase.WAITING
                    || phase == GamePhase.FINISHED;
        }
        if (passButton != null) {
            passButton.visible = phase == GamePhase.PLAYING;
            passButton.active = game != null && mine != StoneColor.EMPTY && mine == game.toMove();
        }
        if (undoButton != null) {
            undoButton.visible = phase == GamePhase.PLAYING;
            // 已有 pending（自己的或对方的）时让位给「同意 / 拒绝」，避免重复发起。
            undoButton.active = game != null && undoRequester == null && game.undoAvailable()
                    && mine != StoneColor.EMPTY && soloOwnerMe;
        }
        if (undoAcceptButton != null) {
            // 只有「不是自己发起」的 pending 才显示同意 / 拒绝（发起者不能自己批准）。
            undoAcceptButton.visible = phase == GamePhase.PLAYING && undoRequester != null && !pendingMine;
        }
        if (undoRejectButton != null) {
            undoRejectButton.visible = phase == GamePhase.PLAYING && undoRequester != null && !pendingMine;
        }
        if (resignButton != null) {
            resignButton.visible = phase == GamePhase.PLAYING || phase == GamePhase.SCORING;
        }
        if (confirmButton != null) {
            confirmButton.visible = phase == GamePhase.SCORING;
        }
        if (disputeButton != null) {
            disputeButton.visible = phase == GamePhase.SCORING;
        }
        if (saveSgfButton != null) {
            // 保存棋谱：对局中（存档）与终局（留档）都允许，空盘/等人没有可存的东西。
            saveSgfButton.visible = phase == GamePhase.PLAYING || phase == GamePhase.SCORING
                    || phase == GamePhase.FINISHED;
        }
        if (closeButton != null) {
            closeButton.visible = true;
        }
        layoutColumn();
    }

    private UUID myUuid() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return null;
        }
        return this.minecraft.player.getUUID();
    }

    private byte myColor(GoGame game) {
        if (game == null || this.minecraft == null || this.minecraft.player == null) {
            return StoneColor.EMPTY;
        }
        UUID uuid = this.minecraft.player.getUUID();
        return game.colorOf(uuid);
    }

    /** 交点 i 的屏幕 X。 */
    private int screenX(int i) {
        float scale = this.boardPx / BoardGeo.BOARD;
        return boardX + Math.round((BoardGeo.MARGIN + i * BoardGeo.SPACING) * scale);
    }

    /** 交点 j 的屏幕 Y。 */
    private int screenY(int j) {
        float scale = this.boardPx / BoardGeo.BOARD;
        return boardY + Math.round((BoardGeo.MARGIN + j * BoardGeo.SPACING) * scale);
    }

    /**
     * 屏幕坐标 → 最近交点。落在盘面外或换算越界返回 {@code null}。
     *
     * @return {@code {x, y}}（0..18）
     */
    private int[] intersectionAt(double mouseX, double mouseY) {
        if (mouseX < boardX || mouseY < boardY
                || mouseX >= boardX + boardPx || mouseY >= boardY + boardPx) {
            return null;
        }
        float scale = this.boardPx / BoardGeo.BOARD;
        float localX = (float) ((mouseX - boardX) / scale);
        float localY = (float) ((mouseY - boardY) / scale);
        int x = Math.round((localX - BoardGeo.MARGIN) / BoardGeo.SPACING);
        int y = Math.round((localY - BoardGeo.MARGIN) / BoardGeo.SPACING);
        if (Board.index(x, y) < 0) {
            return null;
        }
        return new int[]{x, y};
    }

    private static Component phaseName(GamePhase phase) {
        return Component.translatable("phase.mcgo." + phase.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Component colorName(byte color) {
        return switch (color) {
            case StoneColor.BLACK -> Component.translatable("color.mcgo.black");
            case StoneColor.WHITE -> Component.translatable("color.mcgo.white");
            default -> Component.translatable("color.mcgo.empty");
        };
    }

    private static Component acceptedText(boolean accepted) {
        return accepted
                ? Component.translatable("gui.mcgo.accepted_yes")
                : Component.translatable("gui.mcgo.accepted_no");
    }

    private static void send(GoActionPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(payload);
    }
}

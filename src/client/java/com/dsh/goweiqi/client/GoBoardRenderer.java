package com.dsh.goweiqi.client;

import com.dsh.goweiqi.block.GoBoardEntity;
import com.dsh.goweiqi.game.GamePhase;
import com.dsh.goweiqi.game.GoGame;
import com.dsh.goweiqi.game.GoStyle;
import com.dsh.goweiqi.rules.Board;
import com.dsh.goweiqi.rules.StoneColor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 棋盘 BER（只挂在原点格，PoseStack 原点 = 原点格 min corner）。
 *
 * <p>绘制顺序（PLAN §7）：盘面 → 棋子 → 幽灵子 → 最后一手红点 → 死子红 X。
 * 三类 RenderType 各取一次 buffer：盘面 {@code entityCutoutNoCull}、实体件 {@code entitySolid}、
 * 半透明件 {@code entityTranslucent}。所有几何都按"外表面逆时针"绕序手写，避免被背面剔除吃掉。</p>
 *
 * <p>盘外两侧不再画「提子碗」（玩家反馈像多余的座椅），也不再画提子动画
 * （被提的子直接从盘面消失，由落子/提子音效反馈）。提子数仍在 GUI 里显示，
 * 功能不受影响；只删绘制，不动 {@code blackCaptured/whiteCaptured} 的记账。</p>
 */
public class GoBoardRenderer implements BlockEntityRenderer<GoBoardEntity> {

    /** 黑子顶点色（PLAN §7.2）。 */
    private static final float BLACK_R = 0.085F;
    private static final float BLACK_G = 0.085F;
    private static final float BLACK_B = 0.095F;
    /** 白子顶点色（象牙白）。 */
    private static final float WHITE_R = 0.945F;
    private static final float WHITE_G = 0.935F;
    private static final float WHITE_B = 0.885F;
    /** 最后一手红点 / 死子红 X。 */
    private static final float MARK_R = 0.86F;
    private static final float MARK_G = 0.16F;
    private static final float MARK_B = 0.12F;
    /** 幽灵子透明度（PLAN §7.4）。 */
    private static final float GHOST_ALPHA = 0.45F;

    // ------------------------------------------------------------------
    // 幽灵子（静态：由客户端 tick 根据准星射线设置，BER 每帧读取）
    // ------------------------------------------------------------------

    private static BlockPos ghostOrigin;
    private static int ghostX = -1;
    private static int ghostY = -1;
    private static byte ghostColor = StoneColor.EMPTY;

    /**
     * 设置幽灵子：{@code (x,y)} 落在 {@code origin} 这盘棋上、用 {@code color} 半透明绘制。
     * 参数非法（原点为空 / 越界 / 颜色非法）等价于 {@link #clearGhost()}。
     */
    public static void setGhost(BlockPos origin, int x, int y, byte color) {
        if (origin == null || Board.index(x, y) < 0
                || (color != StoneColor.BLACK && color != StoneColor.WHITE)) {
            clearGhost();
            return;
        }
        ghostOrigin = origin.immutable();
        ghostX = x;
        ghostY = y;
        ghostColor = color;
    }

    /** 清空幽灵子（关闭界面 / 准星离开棋盘 / 断线时调用）。 */
    public static void clearGhost() {
        ghostOrigin = null;
        ghostX = -1;
        ghostY = -1;
        ghostColor = StoneColor.EMPTY;
    }

    /** 当前幽灵子是否落在这块棋盘上（渲染端用）。 */
    private static boolean ghostOn(GoBoardEntity entity) {
        return ghostOrigin != null && ghostColor != StoneColor.EMPTY
                && ghostOrigin.equals(entity.getBlockPos());
    }

    @SuppressWarnings("unused")
    public GoBoardRenderer(BlockEntityRendererProvider.Context context) {
        // 本渲染器不需要 context 里的任何东西（无物品/无字体/无方块模型）。
    }

    @Override
    public void render(GoBoardEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        GoGame game = blockEntity.game();
        GoStyle style = game == null ? GoStyle.KAYA : game.style();
        ResourceLocation boardTexture = BoardTextures.board(style);
        ResourceLocation stoneTexture = BoardTextures.stone();

        PoseStack.Pose pose = poseStack.last();

        // (a) 盘面：3×3 四边形，UV 全图
        drawBoardQuad(pose, bufferSource.getBuffer(RenderType.entityCutoutNoCull(boardTexture)), packedLight);

        VertexConsumer solid = bufferSource.getBuffer(RenderType.entitySolid(stoneTexture));
        byte[] cells = game == null ? null : game.cells();

        // (b) 棋子
        if (cells != null && cells.length >= Board.POINTS) {
            for (int x = 0; x < Board.SIZE; x++) {
                for (int y = 0; y < Board.SIZE; y++) {
                    byte color = cells[x * Board.SIZE + y];
                    if (color == StoneColor.EMPTY) {
                        continue;
                    }
                    drawStone(solid, pose, packedLight,
                            BoardGeo.gridX(x), BoardGeo.gridZ(y), 1.0F, 1.0F, color);
                }
            }
        }

        if (game == null) {
            return;
        }

        // (b2) 幽灵子：准星指向的空交点，半透明（alpha 0.45）画在该交点上
        if (ghostOn(blockEntity) && cells != null && cells[Board.index(ghostX, ghostY)] == StoneColor.EMPTY) {
            VertexConsumer ghostBuffer = bufferSource.getBuffer(RenderType.entityTranslucent(stoneTexture));
            drawStone(ghostBuffer, pose, packedLight,
                    BoardGeo.gridX(ghostX), BoardGeo.gridZ(ghostY), 1.0F, GHOST_ALPHA, ghostColor);
        }

        // (c) 最后一手红点（抬高到棋子顶部之上；交点已空则贴在板面上方）
        int lastX = game.lastX();
        int lastY = game.lastY();
        if (Board.index(lastX, lastY) >= 0) {
            boolean occupied = cells != null && cells[lastX * Board.SIZE + lastY] != StoneColor.EMPTY;
            float topY = occupied
                    ? BoardGeo.STONE_BASE_Y + BoardGeo.STONE_H + BoardGeo.MARK_LIFT
                    : BoardGeo.SURFACE_Y + BoardGeo.MARK_LIFT;
            drawDisc(solid, pose, packedLight,
                    BoardGeo.gridX(lastX), BoardGeo.gridZ(lastY), topY,
                    BoardGeo.LAST_MARK_R, BoardGeo.SEGMENTS,
                    BoardTextures.HIGHLIGHT_U, BoardTextures.HIGHLIGHT_V,
                    MARK_R, MARK_G, MARK_B, 1.0F);
        }

        // (d) 数子阶段的死子标记：红色 X
        byte[] deadMask = game.deadMask();
        GamePhase phase = game.phase();
        boolean scoringLike = phase == GamePhase.SCORING || phase == GamePhase.FINISHED;
        if (scoringLike && deadMask != null && deadMask.length >= Board.POINTS && cells != null) {
            for (int x = 0; x < Board.SIZE; x++) {
                for (int y = 0; y < Board.SIZE; y++) {
                    int idx = x * Board.SIZE + y;
                    if (deadMask[idx] == 0 || cells[idx] == StoneColor.EMPTY) {
                        continue;
                    }
                    drawDeadCross(solid, pose, packedLight, BoardGeo.gridX(x), BoardGeo.gridZ(y));
                }
            }
        }

        // (e) 提子动画已取消：被提的子直接从盘面消失，这里不再渲染 removals()。
        //     GoBoardEntity 也不再入队新的 removal（只保留字段与旧同步/NBT 的兼容读取）。
    }

    /** 3×3 超出单格：不让渲染派发器按单格包围盒剔除。 */
    @Override
    public boolean shouldRenderOffScreen(GoBoardEntity blockEntity) {
        return true;
    }

    // ------------------------------------------------------------------
    // 盘面
    // ------------------------------------------------------------------

    private static void drawBoardQuad(PoseStack.Pose pose, VertexConsumer vc, int light) {
        float y = BoardGeo.SURFACE_Y;
        float e = BoardGeo.BOARD;
        // 绕序：(0,0) → (0,3) → (3,3) → (3,0)，法线 +Y；UV 与贴图一一对应
        vertex(vc, pose, light, 0.0F, y, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
        vertex(vc, pose, light, 0.0F, y, e, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
        vertex(vc, pose, light, e, y, e, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
        vertex(vc, pose, light, e, y, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------
    // 棋子（低模双凸透镜 / 单面穹顶：2 个环带 + 顶部三角扇）
    // ------------------------------------------------------------------

    private static void drawStone(VertexConsumer vc, PoseStack.Pose pose, int light,
                                  float centerX, float centerZ, float scale, float alpha, byte color) {
        float red;
        float green;
        float blue;
        if (color == StoneColor.WHITE) {
            red = WHITE_R;
            green = WHITE_G;
            blue = WHITE_B;
        } else {
            red = BLACK_R;
            green = BLACK_G;
            blue = BLACK_B;
        }
        drawDome(vc, pose, light, centerX, centerZ, scale, alpha, red, green, blue);
    }

    private static void drawDome(VertexConsumer vc, PoseStack.Pose pose, int light,
                                 float centerX, float centerZ, float scale, float alpha,
                                 float red, float green, float blue) {
        float radius = BoardGeo.STONE_R * scale;
        float height = BoardGeo.STONE_H * scale;
        float baseY = BoardGeo.STONE_BASE_Y;
        int segments = BoardGeo.SEGMENTS;

        // 环带：ring0→ring1、ring1→ring2
        for (int ring = 0; ring < 2; ring++) {
            float innerR = BoardGeo.RING_R[ring] * radius;
            float innerY = baseY + BoardGeo.RING_Y[ring] * height;
            float outerR = BoardGeo.RING_R[ring + 1] * radius;
            float outerY = baseY + BoardGeo.RING_Y[ring + 1] * height;
            // UV 径向映射：环半径因子 * STONE_UV_RADIUS 就是贴图上的 uv 半径
            float uvInner = BoardTextures.STONE_UV_RADIUS * BoardGeo.RING_R[ring];
            float uvOuter = BoardTextures.STONE_UV_RADIUS * BoardGeo.RING_R[ring + 1];
            for (int seg = 0; seg < segments; seg++) {
                float a0 = BoardGeo.TAU * seg / segments;
                float a1 = BoardGeo.TAU * (seg + 1) / segments;
                float am = (a0 + a1) * 0.5F;
                float cos0 = (float) Math.cos(a0);
                float sin0 = (float) Math.sin(a0);
                float cos1 = (float) Math.cos(a1);
                float sin1 = (float) Math.sin(a1);
                float[] normal = profileNormal(outerY - innerY, outerR - innerR, am);
                // 绕序：(内环,a0) → (外环,a0) → (外环,a1) → (内环,a1)，法线朝外上
                vertex(vc, pose, light,
                        centerX + cos0 * innerR, innerY, centerZ + sin0 * innerR,
                        0.5F + cos0 * uvInner, 0.5F + sin0 * uvInner,
                        normal[0], normal[1], normal[2], red, green, blue, alpha);
                vertex(vc, pose, light,
                        centerX + cos0 * outerR, outerY, centerZ + sin0 * outerR,
                        0.5F + cos0 * uvOuter, 0.5F + sin0 * uvOuter,
                        normal[0], normal[1], normal[2], red, green, blue, alpha);
                vertex(vc, pose, light,
                        centerX + cos1 * outerR, outerY, centerZ + sin1 * outerR,
                        0.5F + cos1 * uvOuter, 0.5F + sin1 * uvOuter,
                        normal[0], normal[1], normal[2], red, green, blue, alpha);
                vertex(vc, pose, light,
                        centerX + cos1 * innerR, innerY, centerZ + sin1 * innerR,
                        0.5F + cos1 * uvInner, 0.5F + sin1 * uvInner,
                        normal[0], normal[1], normal[2], red, green, blue, alpha);
            }
        }

        // 顶层三角扇：ring2 → 顶心 (r=0, y=H)
        float rimR = BoardGeo.RING_R[2] * radius;
        float rimY = baseY + BoardGeo.RING_Y[2] * height;
        float topY = baseY + height;
        float uvRim = BoardTextures.STONE_UV_RADIUS * BoardGeo.RING_R[2];
        for (int seg = 0; seg < segments; seg++) {
            float a0 = BoardGeo.TAU * seg / segments;
            float a1 = BoardGeo.TAU * (seg + 1) / segments;
            float am = (a0 + a1) * 0.5F;
            float[] normal = profileNormal(topY - rimY, 0.0F - rimR, am);
            // entity 系 RenderType 是 Mode.QUADS：三角扇必须发成"退化四边形"
            // （顶心重复一次 → 第二个三角形 = 顶心→a1→a0），否则四边形拼装会错位。
            vertex(vc, pose, light, centerX, topY, centerZ, 0.5F, 0.5F,
                    normal[0], normal[1], normal[2], red, green, blue, alpha);
            vertex(vc, pose, light, centerX, topY, centerZ, 0.5F, 0.5F,
                    normal[0], normal[1], normal[2], red, green, blue, alpha);
            vertex(vc, pose, light,
                    centerX + (float) Math.cos(a1) * rimR, rimY, centerZ + (float) Math.sin(a1) * rimR,
                    0.5F + (float) Math.cos(a1) * uvRim, 0.5F + (float) Math.sin(a1) * uvRim,
                    normal[0], normal[1], normal[2], red, green, blue, alpha);
            vertex(vc, pose, light,
                    centerX + (float) Math.cos(a0) * rimR, rimY, centerZ + (float) Math.sin(a0) * rimR,
                    0.5F + (float) Math.cos(a0) * uvRim, 0.5F + (float) Math.sin(a0) * uvRim,
                    normal[0], normal[1], normal[2], red, green, blue, alpha);
        }
    }

    /**
     * 剖面切线 (dRadius, dY) 的外法线：在 (径向, Y) 平面里旋转 90°，
     * 再按角度 am 摊到 X/Z 上。返回 {nx, ny, nz}（已归一化）。
     */
    private static float[] profileNormal(float dY, float dRadius, float angle) {
        float radial = dY;
        float up = -dRadius;
        float length = (float) Math.sqrt(radial * radial + up * up);
        if (length < 1.0E-6F) {
            radial = 0.0F;
            up = 1.0F;
            length = 1.0F;
        }
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        return new float[]{radial / length * cos, up / length, radial / length * sin};
    }

    // ------------------------------------------------------------------
    // 红色标记（最后一手圆片 / 死子 X）
    // ------------------------------------------------------------------

    private static void drawDisc(VertexConsumer vc, PoseStack.Pose pose, int light,
                                 float centerX, float centerZ, float y, float radius, int segments,
                                 float u, float v,
                                 float red, float green, float blue, float alpha) {
        for (int seg = 0; seg < segments; seg++) {
            float a0 = BoardGeo.TAU * seg / segments;
            float a1 = BoardGeo.TAU * (seg + 1) / segments;
            // entity 系 RenderType 是 Mode.QUADS：圆心发两次凑成退化四边形，
            // 第二个三角形即 (圆心 → a1 → a0)，法线 +Y。
            vertex(vc, pose, light, centerX, y, centerZ, u, v, 0.0F, 1.0F, 0.0F, red, green, blue, alpha);
            vertex(vc, pose, light, centerX, y, centerZ, u, v, 0.0F, 1.0F, 0.0F, red, green, blue, alpha);
            vertex(vc, pose, light,
                    centerX + (float) Math.cos(a1) * radius, y, centerZ + (float) Math.sin(a1) * radius,
                    u, v, 0.0F, 1.0F, 0.0F, red, green, blue, alpha);
            vertex(vc, pose, light,
                    centerX + (float) Math.cos(a0) * radius, y, centerZ + (float) Math.sin(a0) * radius,
                    u, v, 0.0F, 1.0F, 0.0F, red, green, blue, alpha);
        }
    }

    private static void drawDeadCross(VertexConsumer vc, PoseStack.Pose pose, int light,
                                      float centerX, float centerZ) {
        float y = BoardGeo.STONE_BASE_Y + BoardGeo.STONE_H + BoardGeo.MARK_LIFT;
        drawFlatSlab(vc, pose, light, centerX, centerZ, y, (float) Math.PI * 0.25F);
        drawFlatSlab(vc, pose, light, centerX, centerZ, y, -(float) Math.PI * 0.25F);
    }

    /** 一块贴地的细长四边形（±45° 各一块 → 红 X），法线 +Y。 */
    private static void drawFlatSlab(VertexConsumer vc, PoseStack.Pose pose, int light,
                                     float centerX, float centerZ, float y, float angle) {
        float halfLen = BoardGeo.DEAD_X_HALF_LEN;
        float halfWidth = BoardGeo.DEAD_X_HALF_WIDTH;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float u = BoardTextures.HIGHLIGHT_U;
        float v = BoardTextures.HIGHLIGHT_V;
        // A(-l,-w) → D(-l,+w) → C(+l,+w) → B(+l,-w)，本地 (u=长度, w=宽度)
        emitSlabCorner(vc, pose, light, centerX, centerZ, y, cos, sin, -halfLen, -halfWidth, u, v);
        emitSlabCorner(vc, pose, light, centerX, centerZ, y, cos, sin, -halfLen, halfWidth, u, v);
        emitSlabCorner(vc, pose, light, centerX, centerZ, y, cos, sin, halfLen, halfWidth, u, v);
        emitSlabCorner(vc, pose, light, centerX, centerZ, y, cos, sin, halfLen, -halfWidth, u, v);
    }

    private static void emitSlabCorner(VertexConsumer vc, PoseStack.Pose pose, int light,
                                       float centerX, float centerZ, float y,
                                       float cos, float sin, float along, float across,
                                       float u, float v) {
        float x = centerX + along * cos - across * sin;
        float z = centerZ + along * sin + across * cos;
        vertex(vc, pose, light, x, y, z, u, v, 0.0F, 1.0F, 0.0F, MARK_R, MARK_G, MARK_B, 1.0F);
    }

    // ------------------------------------------------------------------
    // 顶点
    // ------------------------------------------------------------------

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, int light,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz,
                               float red, float green, float blue, float alpha) {
        vc.addVertex(pose, x, y, z)
                .setColor(red, green, blue, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}

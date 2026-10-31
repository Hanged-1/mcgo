package com.dsh.goweiqi.client;

/**
 * 棋盘几何常量表（与 PLAN-mcgo.md §2.4 完全对齐，禁止在任何地方散落魔法数）。
 *
 * <p>坐标约定：BER 的 {@code PoseStack} 原点 = 原点格的 min corner，
 * 板面占据局部坐标 {@code (0,0,0)..(3,0.25,3)}。交点 {@code (i,j)}（i,j = 0..18）
 * 的局部坐标为 {@code (MARGIN + i*SPACING, MARGIN + j*SPACING)}，i 沿 +X（东），j 沿 +Z（南）。</p>
 */
final class BoardGeo {

    /** 台面边长（格）。 */
    static final float BOARD = 3.0F;
    /** 木边宽度（格），贴图里 = 0.085/3*1024 ≈ 29px。 */
    static final float MARGIN = 0.085F;
    /** 19 路线阵总跨度（格）。 */
    static final float GRID_SPAN = 2.83F;
    /** 线距 = 2.83/18 = 0.157222f（贴图里 53.67px）。 */
    static final float SPACING = GRID_SPAN / 18.0F;

    /** 棋子半径 = 0.49 * 线距。 */
    static final float STONE_R = 0.49F * SPACING;
    /** 棋子高度 = 0.45 * 半径。 */
    static final float STONE_H = 0.45F * STONE_R;

    /** 板面高度（略高于方块顶面 0.25，防 z-fighting）。 */
    static final float SURFACE_Y = 0.2502F;
    /** 棋子底面再抬 0.0012，避免棋子边缘与板面共面闪烁。 */
    static final float STONE_BASE_Y = SURFACE_Y + 0.0012F;

    /** 棋子径向分段（12 段 → 每圈 12 个顶点）。 */
    static final int SEGMENTS = 12;
    static final float TAU = (float) (Math.PI * 2.0);

    /**
     * 棋子剖面：3 个环（半径因子、高度因子）+ 顶心，环间四边形带、顶层三角扇。
     * <pre>r=1.00R,y=0 → r=0.92R,y=0.25H → r=0.62R,y=0.72H → r=0,y=H</pre>
     */
    static final float[] RING_R = {1.00F, 0.92F, 0.62F};
    static final float[] RING_Y = {0.00F, 0.25F, 0.72F};

    /** 最后一手红点半径 = 0.18R。 */
    static final float LAST_MARK_R = 0.18F * STONE_R;
    /** 死子红 X：半长 1.15R，线宽 0.12R。 */
    static final float DEAD_X_HALF_LEN = 1.15F * STONE_R;
    static final float DEAD_X_HALF_WIDTH = 0.06F * STONE_R;
    /** 红点 / 红 X / 幽灵子相对棋子顶部的抬升量。 */
    static final float MARK_LIFT = 0.0015F;

    private BoardGeo() {
    }

    /** 交点 i 的局部 X 坐标。 */
    static float gridX(int i) {
        return MARGIN + i * SPACING;
    }

    /** 交点 j 的局部 Z 坐标。 */
    static float gridZ(int j) {
        return MARGIN + j * SPACING;
    }
}

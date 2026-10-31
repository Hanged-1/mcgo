package com.dsh.goweiqi.client;

import com.dsh.goweiqi.GoWeiqiMod;
import com.dsh.goweiqi.game.GoStyle;
import com.mojang.blaze3d.platform.NativeImage;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 程序化贴图（**不引入任何 PNG 资源**）：用 {@link NativeImage} 逐像素写入，
 * 再包成 {@link DynamicTexture} 经 {@code TextureManager#register} 注册固定 id。
 * <ul>
 *   <li>{@code mcgo:board_kaya} / {@code mcgo:board_rosewood}：1024×1024 木纹 + 19 路网格 + 星位 + 外框</li>
 *   <li>{@code mcgo:stone}：32×32 灰度圆盘（黑白靠渲染时顶点色染色）</li>
 * </ul>
 * 生成是<b>幂等</b>的：首次调用生成并注册，之后直接返回缓存的 id；重复调用不会重复注册。
 * 首次调用发生在 BER 渲染里（渲染线程），{@code DynamicTexture#upload()} 因此总是安全的。
 */
public final class BoardTextures {

    public static final int BOARD_SIZE = 1024;
    public static final int STONE_SIZE = 32;

    // ---- 盘面贴图布局（PLAN §5：贴图覆盖整个 3×3 格）--------------------------
    private static final int TEX = BOARD_SIZE;
    /** 木边 = 0.085/3*1024 ≈ 29px。 */
    private static final float MARGIN_RATIO = BoardGeo.MARGIN / BoardGeo.BOARD;
    private static final int MARGIN_PX = Math.round(MARGIN_RATIO * TEX);
    /** 线阵跨度 ≈ 966px。 */
    private static final float GRID_SPAN_PX = TEX - 2.0F * MARGIN_PX;
    /** 线距 = 966/18 ≈ 53.67px。 */
    private static final float LINE_GAP_PX = GRID_SPAN_PX / 18.0F;
    /** 星位索引（0..18）：3 / 9 / 15，9 个星。 */
    private static final int[] STAR_INDEX = {3, 9, 15};
    private static final float STAR_RADIUS_PX = 4.6F;
    /** 线宽：A（榧木）2.8px，C（深紫檀）3.6px。 */
    private static final float LINE_WIDTH_A = 2.8F;
    private static final float LINE_WIDTH_C = 3.6F;
    /** 外围粗框宽度倍数（相对普通线宽）。 */
    private static final float FRAME_SCALE = 2.4F;

    // ---- 配色（任务书固定值）-------------------------------------------------
    private static final int A_WOOD_R = 231;
    private static final int A_WOOD_G = 190;
    private static final int A_WOOD_B = 116;
    private static final int A_LINE_R = 56;
    private static final int A_LINE_G = 41;
    private static final int A_LINE_B = 27;
    private static final int C_WOOD_R = 102;
    private static final int C_WOOD_G = 65;
    private static final int C_WOOD_B = 45;
    private static final int C_LINE_R = 24;
    private static final int C_LINE_G = 17;
    private static final int C_LINE_B = 12;

    /** 木纹噪声固定种子：保证每次生成完全一致（可复现）。 */
    private static final long WOOD_SEED = 0x5EED_1901L;

    // ---- 棋子贴图（32×32 灰度圆盘）------------------------------------------
    /** 圆盘半径占半宽的比例（留一点透明边便于抗锯齿）。 */
    private static final float STONE_RADIUS_PX = STONE_SIZE * 0.5F;
    /** 高光中心到圆心的距离（归一化半径，任务书：半径 0.8 处最亮）。 */
    private static final float HIGHLIGHT_DIST = 0.8F;
    /** 高光方向：左上（-1,-1 归一化）。 */
    private static final float HIGHLIGHT_DIR = -0.70710678F;
    /** 高斯高光半径（归一化）。 */
    private static final float HIGHLIGHT_SIGMA = 0.30F;
    /** 贴图圆盘内部的有效半径比例（用于 UV 映射，避开最外圈透明像素）。 */
    static final float STONE_UV_RADIUS = 0.47F;
    /** 高光中心的 UV（红点 / 红 X / 碗内石子等纯色件采样这里，取到最亮灰度）。 */
    static final float HIGHLIGHT_U = 0.5F + HIGHLIGHT_DIR * HIGHLIGHT_DIST * 0.5F;
    static final float HIGHLIGHT_V = HIGHLIGHT_U;

    private static final Map<GoStyle, ResourceLocation> BOARD_IDS = new EnumMap<>(GoStyle.class);
    private static final Set<GoStyle> BOARD_GENERATED = EnumSet.noneOf(GoStyle.class);
    private static final ResourceLocation STONE_ID =
            ResourceLocation.fromNamespaceAndPath(GoWeiqiMod.MOD_ID, "stone");
    private static boolean stoneGenerated;

    private BoardTextures() {
    }

    /** 该风格的固定贴图 id（不触发生成）。 */
    public static ResourceLocation boardId(GoStyle style) {
        GoStyle key = style == null ? GoStyle.KAYA : style;
        return ResourceLocation.fromNamespaceAndPath(GoWeiqiMod.MOD_ID, key.texturePath());
    }

    /** 盘面贴图 id；首次调用时生成并注册（幂等）。 */
    public static ResourceLocation board(GoStyle style) {
        GoStyle key = style == null ? GoStyle.KAYA : style;
        ResourceLocation id = boardId(key);
        if (BOARD_GENERATED.contains(key)) {
            return id;
        }
        // 先占位再生成：即使生成过程抛异常也不会形成"每次调用都重新注册"的循环。
        BOARD_GENERATED.add(key);
        try {
            generateBoard(key, id);
        } catch (RuntimeException ex) {
            BOARD_GENERATED.remove(key);
            throw ex;
        }
        return id;
    }

    /** 棋子贴图 id；首次调用时生成并注册（幂等）。 */
    public static ResourceLocation stone() {
        if (stoneGenerated) {
            return STONE_ID;
        }
        stoneGenerated = true;
        try {
            generateStone(STONE_ID);
        } catch (RuntimeException ex) {
            stoneGenerated = false;
            throw ex;
        }
        return STONE_ID;
    }

    // ------------------------------------------------------------------
    // 生成 / 注册
    // ------------------------------------------------------------------

    private static void generateBoard(GoStyle style, ResourceLocation id) {
        boolean dark = style == GoStyle.ROSEWOOD;
        NativeImage pixels = new NativeImage(TEX, TEX, true);
        paintWood(pixels, dark);
        paintGrid(pixels, dark);
        // new DynamicTexture(NativeImage) 的 mipmap 由 NativeImage 决定；
        // 写完像素必须 upload()，且只能在渲染线程（BER 首次绘制）调用。
        DynamicTexture texture = new DynamicTexture(pixels);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(id, texture);
        GoWeiqiMod.LOGGER.debug("[mcgo] 程序化生成盘面贴图 {}（{}×{}）", id, TEX, TEX);
    }

    private static void generateStone(ResourceLocation id) {
        NativeImage pixels = new NativeImage(STONE_SIZE, STONE_SIZE, true);
        paintStone(pixels);
        DynamicTexture texture = new DynamicTexture(pixels);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(id, texture);
        GoWeiqiMod.LOGGER.debug("[mcgo] 程序化生成棋子贴图 {}（{}×{}）", id, STONE_SIZE, STONE_SIZE);
    }

    // ------------------------------------------------------------------
    // 木纹
    // ------------------------------------------------------------------

    /**
     * 木色基色 + 1D 正弦条纹（沿 X 的同心纹，相位随行缓慢起伏）+ 固定种子细噪声。
     * 为了不在 1024×1024 的逐像素循环里调用 sin，先把列相关的 sin/cos 打成表。
     */
    private static void paintWood(NativeImage img, boolean dark) {
        int woodR = dark ? C_WOOD_R : A_WOOD_R;
        int woodG = dark ? C_WOOD_G : A_WOOD_G;
        int woodB = dark ? C_WOOD_B : A_WOOD_B;
        // 深色底（C）对比度略提一点，纹路才看得见。
        double grainAmp = dark ? 0.070D : 0.055D;
        double fineAmp = dark ? 0.020D : 0.012D;

        double[] sinGrain = new double[TEX];
        double[] cosGrain = new double[TEX];
        double[] sinFine = new double[TEX];
        double[] cosFine = new double[TEX];
        for (int x = 0; x < TEX; x++) {
            sinGrain[x] = Math.sin(x * 0.055D);
            cosGrain[x] = Math.cos(x * 0.055D);
            sinFine[x] = Math.sin(x * 0.31D);
            cosFine[x] = Math.cos(x * 0.31D);
        }

        Random noise = new Random(WOOD_SEED);
        for (int y = 0; y < TEX; y++) {
            double phase = (Math.sin(y * 0.0045D) * 3.0D + Math.sin(y * 0.0017D) * 5.0D) * 0.35D;
            double sinPhase = Math.sin(phase);
            double cosPhase = Math.cos(phase);
            double finePhase = y * 0.021D;
            double sinFinePhase = Math.sin(finePhase);
            double cosFinePhase = Math.cos(finePhase);
            for (int x = 0; x < TEX; x++) {
                double grain = sinGrain[x] * cosPhase + cosGrain[x] * sinPhase;
                double fine = sinFine[x] * cosFinePhase + cosFine[x] * sinFinePhase;
                double jitter = (noise.nextDouble() - 0.5D) * 0.040D;
                double shade = 1.0D + grain * grainAmp + fine * fineAmp + jitter;
                int r = clampChannel((float) (woodR * shade));
                int g = clampChannel((float) (woodG * shade));
                int b = clampChannel((float) (woodB * shade));
                img.setPixelRGBA(x, y, argb(r, g, b, 255));
            }
        }
    }

    // ------------------------------------------------------------------
    // 网格 / 外框 / 星位
    // ------------------------------------------------------------------

    private static void paintGrid(NativeImage img, boolean dark) {
        int lineR = dark ? C_LINE_R : A_LINE_R;
        int lineG = dark ? C_LINE_G : A_LINE_G;
        int lineB = dark ? C_LINE_B : A_LINE_B;
        float lineW = dark ? LINE_WIDTH_C : LINE_WIDTH_A;

        int first = MARGIN_PX;
        int last = TEX - MARGIN_PX;
        for (int i = 0; i < 19; i++) {
            float px = MARGIN_PX + i * LINE_GAP_PX;
            boolean border = (i == 0 || i == 18);
            float width = border ? lineW * FRAME_SCALE : lineW;
            fillLineVertical(img, px, width, first, last, lineR, lineG, lineB);
            fillLineHorizontal(img, px, width, first, last, lineR, lineG, lineB);
        }
        // 9 个星位
        for (int sx : STAR_INDEX) {
            for (int sy : STAR_INDEX) {
                fillCircle(img,
                        MARGIN_PX + sx * LINE_GAP_PX,
                        MARGIN_PX + sy * LINE_GAP_PX,
                        STAR_RADIUS_PX, lineR, lineG, lineB);
            }
        }
    }

    private static void fillLineVertical(NativeImage img, float centerX, float width,
                                         int fromY, int toY, int r, int g, int b) {
        float half = width * 0.5F;
        int x0 = Math.max(0, (int) Math.floor(centerX - half - 1.0F));
        int x1 = Math.min(TEX - 1, (int) Math.ceil(centerX + half + 1.0F));
        for (int x = x0; x <= x1; x++) {
            float dist = Math.abs((x + 0.5F) - centerX);
            float coverage = Math.min(1.0F, Math.max(0.0F, half + 0.5F - dist));
            if (coverage <= 0.0F) {
                continue;
            }
            for (int y = fromY; y < toY; y++) {
                blendPixel(img, x, y, r, g, b, coverage);
            }
        }
    }

    private static void fillLineHorizontal(NativeImage img, float centerY, float width,
                                           int fromX, int toX, int r, int g, int b) {
        float half = width * 0.5F;
        int y0 = Math.max(0, (int) Math.floor(centerY - half - 1.0F));
        int y1 = Math.min(TEX - 1, (int) Math.ceil(centerY + half + 1.0F));
        for (int y = y0; y <= y1; y++) {
            float dist = Math.abs((y + 0.5F) - centerY);
            float coverage = Math.min(1.0F, Math.max(0.0F, half + 0.5F - dist));
            if (coverage <= 0.0F) {
                continue;
            }
            for (int x = fromX; x < toX; x++) {
                blendPixel(img, x, y, r, g, b, coverage);
            }
        }
    }

    private static void fillCircle(NativeImage img, float centerX, float centerY,
                                   float radius, int r, int g, int b) {
        int x0 = Math.max(0, (int) Math.floor(centerX - radius - 1.0F));
        int x1 = Math.min(TEX - 1, (int) Math.ceil(centerX + radius + 1.0F));
        int y0 = Math.max(0, (int) Math.floor(centerY - radius - 1.0F));
        int y1 = Math.min(TEX - 1, (int) Math.ceil(centerY + radius + 1.0F));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double dx = (x + 0.5D) - centerX;
                double dy = (y + 0.5D) - centerY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                float coverage = (float) Math.min(1.0D, Math.max(0.0D, radius + 0.5D - dist));
                if (coverage <= 0.0F) {
                    continue;
                }
                blendPixel(img, x, y, r, g, b, coverage);
            }
        }
    }

    // ------------------------------------------------------------------
    // 棋子灰度圆盘
    // ------------------------------------------------------------------

    private static void paintStone(NativeImage img) {
        float center = STONE_SIZE * 0.5F;
        float radius = STONE_RADIUS_PX;
        float highlightX = center + HIGHLIGHT_DIR * HIGHLIGHT_DIST * radius;
        float highlightY = center + HIGHLIGHT_DIR * HIGHLIGHT_DIST * radius;
        float sigmaPx = HIGHLIGHT_SIGMA * radius;
        float twoSigmaSq = 2.0F * sigmaPx * sigmaPx;

        for (int y = 0; y < STONE_SIZE; y++) {
            for (int x = 0; x < STONE_SIZE; x++) {
                float dx = (x + 0.5F) - center;
                float dy = (y + 0.5F) - center;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float norm = dist / radius;
                if (norm >= 1.0F) {
                    img.setPixelRGBA(x, y, argb(0, 0, 0, 0));
                    continue;
                }
                // 高光：左上方向的放射性亮点（高斯）
                float hx = (x + 0.5F) - highlightX;
                float hy = (y + 0.5F) - highlightY;
                float highlight = (float) Math.exp(-(hx * hx + hy * hy) / twoSigmaSq);
                // 中心略亮 + 半径方向缓慢变暗（球面感）
                float body = 0.62F + 0.30F * highlight + 0.10F * (1.0F - norm * norm);
                // 边缘压暗（薄边高光之后的一圈暗边）
                float edge = norm > 0.86F ? 1.0F - (norm - 0.86F) / 0.14F * 0.55F : 1.0F;
                int gray = clampChannel(body * edge * 255.0F);
                float alpha = norm > 0.94F ? (1.0F - norm) / 0.06F : 1.0F;
                img.setPixelRGBA(x, y, argb(gray, gray, gray, clampChannel(alpha * 255.0F)));
            }
        }
    }

    // ------------------------------------------------------------------
    // 像素工具
    // ------------------------------------------------------------------

    private static void blendPixel(NativeImage img, int x, int y, int r, int g, int b, float coverage) {
        if (coverage >= 0.999F) {
            img.setPixelRGBA(x, y, argb(r, g, b, 255));
            return;
        }
        int dst = img.getPixelRGBA(x, y);
        int dr = dst & 0xFF;
        int dg = (dst >> 8) & 0xFF;
        int db = (dst >> 16) & 0xFF;
        int da = (dst >> 24) & 0xFF;
        int nr = clampChannel(dr + (r - dr) * coverage);
        int ng = clampChannel(dg + (g - dg) * coverage);
        int nb = clampChannel(db + (b - db) * coverage);
        int na = clampChannel(da + (255 - da) * coverage);
        img.setPixelRGBA(x, y, argb(nr, ng, nb, na));
    }

    /**
     * 打包 {@link NativeImage#setPixelRGBA(int, int, int)} 需要的 int。
     * 1.21.1 的 NativeImage 内存布局是 R,G,B,A（Format.RGBA），
     * 对应 int 就是 {@code A<<24 | B<<16 | G<<8 | R}（低字节 = R）。
     */
    private static int argb(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
    }

    private static int clampChannel(float value) {
        int v = Math.round(value);
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return v;
    }
}

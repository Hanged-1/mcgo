package com.dsh.goweiqi.rules;

import java.util.Random;

/**
 * Zobrist 哈希表：黑白各一套 {@code long[361]}，固定随机种子（必须可复现，测试要稳定）。
 * 纯数据表，不含对局逻辑。
 */
public final class Zobrist {

    /** 固定种子：改动它会让历史棋谱哈希全部失效。 */
    public static final long SEED = 0x5DEECE66DL;

    private static final long[][] TABLE = new long[3][Board.POINTS];

    static {
        Random random = new Random(SEED);
        for (int color = StoneColor.BLACK; color <= StoneColor.WHITE; color++) {
            for (int idx = 0; idx < Board.POINTS; idx++) {
                long value;
                do {
                    value = random.nextLong();
                } while (value == 0L);
                TABLE[color][idx] = value;
            }
        }
    }

    private Zobrist() {
    }

    /** 单点在指定颜色下的 Zobrist 值；EMPTY 恒为 0。 */
    public static long value(int idx, byte color) {
        return color == StoneColor.EMPTY ? 0L : TABLE[color][idx];
    }

    /** 按整盘重新计算哈希（增删子时用 value() 逐步 XOR 会更快，C1 可优化）。 */
    public static long hash(byte[] cells) {
        long hash = 0L;
        for (int idx = 0; idx < Board.POINTS && idx < cells.length; idx++) {
            hash ^= value(idx, cells[idx]);
        }
        return hash;
    }
}

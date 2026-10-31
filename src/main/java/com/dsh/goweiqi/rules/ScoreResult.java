package com.dsh.goweiqi.rules;

/**
 * 中国规则数子结果。
 *
 * <pre>
 * 黑 = 黑子数 + 黑空；白 = 白子数 + 白空；d = 单官数
 * 阈值 = (361 - d) / 2 + 3.75
 * 子差 = 黑 - 阈值          // > 0 黑胜
 * 目差 = 2 * 子差
 * </pre>
 */
public record ScoreResult(
        int blackStones,
        int whiteStones,
        int blackTerritory,
        int whiteTerritory,
        int dame,
        int blackTotal,
        int whiteTotal,
        String winner,
        double marginZi,
        double marginPoints) {

    /** 黑贴 3¾ 子。 */
    public static final double KOMI_ZI = 3.75;

    public boolean blackWins() {
        return "black".equals(winner);
    }
}

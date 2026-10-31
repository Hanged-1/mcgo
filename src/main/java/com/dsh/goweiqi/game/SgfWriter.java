package com.dsh.goweiqi.game;

import com.dsh.goweiqi.rules.Board;
import com.dsh.goweiqi.rules.Scoring;
import com.dsh.goweiqi.rules.StoneColor;

import java.time.LocalDate;
import java.util.List;

/**
 * SGF（FF[4]）序列化器：<b>纯字符串拼装</b>，零第三方依赖，也不依赖 Minecraft 类，
 * 所以既能在服务端落盘用，也能在纯 JUnit 里跑。
 *
 * <p>输出结构（空格仅为示意，实际按下面的行拼）：</p>
 * <pre>
 * (;FF[4]GM[1]CA[UTF-8]AP[mcgo:0.1.0]SZ[19]
 * PB[黑方名]PW[白方名]KM[7.5]RU[Chinese]DT[YYYY-MM-DD]RE[结果]
 * ;B[pd];W[dd]...
 * TB[坐标列表]TW[坐标列表])
 * </pre>
 *
 * <p><b>坐标换算</b>：{@code x} 是列（左→右，等于 {@link Board} 的 i），{@code y} 是行（上→下，等于 j），
 * SGF 点 = {@code 'a'+x} 接 {@code 'a'+y}（19 路正好 a..s）。停一手写成 {@code B[]} / {@code W[]}
 * （FF[4] 的空着手），绝不写成 {@code B[tt]}（那是 FF[3] 的老写法）。</p>
 *
 * <p><b>领地</b>：终局带 deadMask 时，用 {@link Scoring#territory} 算出最终归属，把所有属于黑/白的点
 * 分别写进 {@code TB[]} / {@code TW[]}；死子本身按对方地盘计入。没有 deadMask 就整段省略。</p>
 */
public final class SgfWriter {

    /** AP（应用名:版本），与 build.gradle / fabric.mod.json 的版本保持一致。 */
    public static final String APPLICATION = "mcgo:0.1.0";
    /** RU：中国规则。 */
    public static final String RULES = "Chinese";
    /** KM：黑贴 3¾ 子 = 7.5 目。 */
    public static final String KOMI = "7.5";
    /** RE 在没有结果时的占位（SGF 规范里的 Void）。 */
    public static final String RESULT_VOID = "Void";

    /** 手顺换行的软宽度（SGF 允许在属性之间换行；只在两手之间断，绝不把一手拆开）。 */
    private static final int MOVE_LINE_WIDTH = 96;

    private SgfWriter() {
    }

    /**
     * 生成完整棋谱；日期取本机今天。
     *
     * @param game      对局（只读）
     * @param blackName 黑方显示名（单一模式与 {@code whiteName} 相同也照写）
     * @param whiteName 白方显示名
     */
    public static String toSgf(GoGame game, String blackName, String whiteName) {
        return toSgf(game, blackName, whiteName, LocalDate.now().toString());
    }

    /**
     * 生成完整棋谱。
     *
     * @param date {@code YYYY-MM-DD}（显式传入便于测试与确定性输出）
     */
    public static String toSgf(GoGame game, String blackName, String whiteName, String date) {
        if (game == null) {
            throw new IllegalArgumentException("game == null");
        }
        StringBuilder out = new StringBuilder(1024);
        // 根节点：FF[4] 才有空着手 B[] 与 TB[]/TW[]。
        out.append("(;FF[4]GM[1]CA[UTF-8]AP[").append(APPLICATION)
                .append("]SZ[").append(Board.SIZE).append("]\n");
        out.append("PB[").append(escape(blackName))
                .append("]PW[").append(escape(whiteName))
                .append("]KM[").append(KOMI)
                .append("]RU[").append(RULES)
                .append("]DT[").append(escape(date))
                .append("]RE[").append(escape(resultText(game)))
                .append("]\n");
        appendMoves(out, game);
        appendTerritory(out, game);
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 手顺
    // ------------------------------------------------------------------

    private static void appendMoves(StringBuilder out, GoGame game) {
        List<int[]> moves = game.moves();
        if (moves.isEmpty()) {
            return;
        }
        int lineWidth = 0;
        for (int[] move : moves) {
            String token = moveToken(move);
            if (lineWidth > 0 && lineWidth + token.length() > MOVE_LINE_WIDTH) {
                out.append('\n');
                lineWidth = 0;
            }
            out.append(token);
            lineWidth += token.length();
        }
        out.append('\n');
    }

    /** 一手 → {@code ;B[pd]}；停一手（x/y = -1）→ {@code ;B[]}。 */
    private static String moveToken(int[] move) {
        char side = move[2] == StoneColor.WHITE ? 'W' : 'B';
        return ";" + side + "[" + point(move[0], move[1]) + "]";
    }

    /**
     * 棋盘坐标 → SGF 点。越界（含停一手的 -1）返回空串，于是写成 {@code B[]}。
     */
    public static String point(int x, int y) {
        if (x < 0 || y < 0 || x >= Board.SIZE || y >= Board.SIZE) {
            return "";
        }
        return new String(new char[]{(char) ('a' + x), (char) ('a' + y)});
    }

    // ------------------------------------------------------------------
    // 领地（TB / TW）
    // ------------------------------------------------------------------

    private static void appendTerritory(StringBuilder out, GoGame game) {
        byte[] deadMask = game.deadMask();
        if (!hasDead(deadMask)) {
            // 没有死活标记（对局中 / 认输局）→ 整段省略，只补上收尾括号。
            out.append(")\n");
            return;
        }
        Board board = game.board();
        byte[] territory = Scoring.territory(board, deadMask);
        StringBuilder black = new StringBuilder();
        StringBuilder white = new StringBuilder();
        for (int idx = 0; idx < Board.POINTS; idx++) {
            byte owner = finalOwner(board, deadMask, territory, idx);
            if (owner == StoneColor.BLACK) {
                black.append(point(Board.xOf(idx), Board.yOf(idx)));
            } else if (owner == StoneColor.WHITE) {
                white.append(point(Board.xOf(idx), Board.yOf(idx)));
            }
        }
        if (black.length() > 0) {
            out.append("TB[").append(black).append(']');
        }
        if (white.length() > 0) {
            out.append("TW[").append(white).append(']');
        }
        // 双方都没地盘（纯单官）时只留收尾括号。
        out.append(")\n");
    }

    /**
     * 单点的最终归属（中国规则数子口径）：
     * 空点是 {@link Scoring#territory} 的洪泛结果（单官为 EMPTY）；
     * 活棋归自己的颜色；死子按<b>对方</b>地盘计入（SGF 的 TB/TW 就是「这里的点算谁的」）。
     */
    private static byte finalOwner(Board board, byte[] deadMask, byte[] territory, int idx) {
        byte stone = board.getIndex(idx);
        if (stone == StoneColor.EMPTY) {
            return idx < territory.length ? territory[idx] : StoneColor.EMPTY;
        }
        if (idx < deadMask.length && deadMask[idx] != 0) {
            return StoneColor.opponent(stone);
        }
        return stone;
    }

    private static boolean hasDead(byte[] deadMask) {
        if (deadMask == null) {
            return false;
        }
        for (byte value : deadMask) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    /** 结果文本；空 → {@code Void}。 */
    private static String resultText(GoGame game) {
        String text = game.result();
        if (text == null || text.trim().isEmpty()) {
            return RESULT_VOID;
        }
        return text.trim();
    }

    /**
     * SGF 文本转义：{@code \} → {@code \\}、{@code ]} → {@code \]}；
     * 换行压成空格（属性值里的裸换行会被解析器当软换行，玩家名里不该出现）。
     */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case ']' -> escaped.append("\\]");
                case '\n', '\r' -> escaped.append(' ');
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}

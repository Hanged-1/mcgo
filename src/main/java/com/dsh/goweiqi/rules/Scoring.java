package com.dsh.goweiqi.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 数子：绝对活白名单（真眼 / 气集重合双活 + 两遍蒙特卡洛取交集）、浮子
 * （参照 Sabaki {@code getFloatingStones} 判据）、蒙特卡洛死活、地盘洪泛与中国规则数子。
 * 全部纯 Java，零 MC 依赖；随机源全部走固定种子，结果可复现。
 *
 * <p>中国规则数子：提掉 deadMask 死子后洪泛空区，单色邻接归该方，双色邻接算单官。</p>
 * <pre>
 * 黑 = 黑子数 + 黑空；白 = 白子数 + 白空；d = 单官数
 * 阈值 = (361 - d) / 2 + 3.75        // 黑贴 3¾ 子
 * 子差 = 黑 - 阈值（&gt;0 黑胜）；目差 = 2 × 子差
 * </pre>
 */
public final class Scoring {

    /** guessDead 默认模拟局数。 */
    public static final int DEFAULT_TRIALS = 100;
    /** 默认随机种子（写死，保证可复现）。 */
    public static final long DEFAULT_SEED = 0x9F3A5C71D2B4E6A8L;
    /** 单遍蒙特卡洛的时间上限（毫秒）；超过即提前收敛，避免卡住服务器 tick。 */
    public static final long TIME_LIMIT_MS = 2000L;

    /** 白先那一遍用不同种子，避免与黑先那遍完全同序。 */
    private static final long WHITE_FIRST_SEED_XOR = 0x9E3779B97F4A7C15L;
    /** 单局随机走子的硬上限（防不收敛）。 */
    private static final int MAX_PLAYOUT_MOVES = 2 * Board.POINTS;
    /** 每手最多随机试几个点，试不到合法点就 pass。 */
    private static final int MOVE_ATTEMPTS = 32;
    private static final int BITSET_WORDS = (Board.POINTS + 63) / 64;

    private static final Object CACHE_LOCK = new Object();
    private static byte[] aliveCacheMask;
    private static byte[] aliveCacheCells;
    private static int aliveCacheTrials = -1;

    private Scoring() {
    }

    // ------------------------------------------------------------------
    // 空区分析（洪泛，迭代式）
    // ------------------------------------------------------------------

    /**
     * 洪泛全盘空区：{@code owner[idx]} = 该空区唯一邻接的颜色，双色邻接/无邻接记 EMPTY；
     * {@code size[idx]} = 该空区的点数（区域内所有点同值）。
     */
    private static void analyzeEmptyRegions(Board board, byte[] owner, int[] size) {
        Arrays.fill(owner, StoneColor.EMPTY);
        Arrays.fill(size, 0);
        boolean[] seen = new boolean[Board.POINTS];
        int[] stack = new int[Board.POINTS];
        int[] region = new int[Board.POINTS];
        int[] nb = new int[4];
        for (int start = 0; start < Board.POINTS; start++) {
            if (seen[start] || board.getIndex(start) != StoneColor.EMPTY) {
                continue;
            }
            int sp = 0;
            int rp = 0;
            boolean black = false;
            boolean white = false;
            stack[sp++] = start;
            seen[start] = true;
            while (sp > 0) {
                int cur = stack[--sp];
                region[rp++] = cur;
                int n = Board.neighbors(cur, nb);
                for (int i = 0; i < n; i++) {
                    int p = nb[i];
                    byte c = board.getIndex(p);
                    if (c == StoneColor.BLACK) {
                        black = true;
                    } else if (c == StoneColor.WHITE) {
                        white = true;
                    } else if (!seen[p]) {
                        seen[p] = true;
                        stack[sp++] = p;
                    }
                }
            }
            byte regionOwner;
            if (black && white) {
                regionOwner = StoneColor.EMPTY;
            } else if (black) {
                regionOwner = StoneColor.BLACK;
            } else if (white) {
                regionOwner = StoneColor.WHITE;
            } else {
                regionOwner = StoneColor.EMPTY;
            }
            for (int i = 0; i < rp; i++) {
                owner[region[i]] = regionOwner;
                size[region[i]] = rp;
            }
        }
    }

    /** 把 deadMask 标记的死子（任意非 0 值）从盘上提掉，返回副本。 */
    public static Board removeDead(Board board, byte[] deadMask) {
        Board work = board.copy();
        if (deadMask == null) {
            return work;
        }
        int n = Math.min(Board.POINTS, deadMask.length);
        for (int i = 0; i < n; i++) {
            if (deadMask[i] != 0) {
                work.setIndex(i, StoneColor.EMPTY);
            }
        }
        return work;
    }

    // ------------------------------------------------------------------
    // 地盘 / 数子
    // ------------------------------------------------------------------

    /** 地盘：{@code result[idx]} 为该点归属色，单官/中立/有子的点记 EMPTY。 */
    public static byte[] territory(Board board, byte[] deadMask) {
        byte[] owner = new byte[Board.POINTS];
        int[] size = new int[Board.POINTS];
        analyzeEmptyRegions(removeDead(board, deadMask), owner, size);
        return owner;
    }

    public static byte[] territory(Board board) {
        return territory(board, null);
    }

    /** 中国规则数子（deadMask 可传 null）。 */
    public static ScoreResult score(Board board, byte[] deadMask) {
        Board work = removeDead(board, deadMask);
        byte[] owner = new byte[Board.POINTS];
        int[] size = new int[Board.POINTS];
        analyzeEmptyRegions(work, owner, size);

        int blackStones = 0;
        int whiteStones = 0;
        int blackTerritory = 0;
        int whiteTerritory = 0;
        int dame = 0;
        for (int i = 0; i < Board.POINTS; i++) {
            byte c = work.getIndex(i);
            if (c == StoneColor.BLACK) {
                blackStones++;
            } else if (c == StoneColor.WHITE) {
                whiteStones++;
            } else if (owner[i] == StoneColor.BLACK) {
                blackTerritory++;
            } else if (owner[i] == StoneColor.WHITE) {
                whiteTerritory++;
            } else {
                dame++;
            }
        }

        int blackTotal = blackStones + blackTerritory;
        int whiteTotal = whiteStones + whiteTerritory;
        double threshold = (Board.POINTS - dame) / 2.0 + ScoreResult.KOMI_ZI;
        double marginZi = blackTotal - threshold;
        double marginPoints = 2.0 * marginZi;
        String winner;
        if (marginZi > 0) {
            winner = "black";
        } else if (marginZi < 0) {
            winner = "white";
        } else {
            winner = "draw";
        }
        return new ScoreResult(blackStones, whiteStones, blackTerritory, whiteTerritory, dame,
                blackTotal, whiteTotal, winner, marginZi, marginPoints);
    }

    public static ScoreResult score(Board board) {
        return score(board, null);
    }

    // ------------------------------------------------------------------
    // 浮子（疑似死）
    // ------------------------------------------------------------------

    /**
     * 浮子掩码（疑似死，参照 Sabaki {@code getFloatingStones}）：
     * 棋子连通块的每一口气都落在"仅邻接对方颜色的空区"里（计算空区归属时把本串摘掉，
     * 否则串自身会把该空区染成单官），且该块自己围出的空区 ≤ 1 点（有真眼就不是浮子）。
     */
    public static byte[] floatStones(Board board) {
        byte[] result = new byte[Board.POINTS];

        byte[] baseOwner = new byte[Board.POINTS];
        int[] baseSize = new int[Board.POINTS];
        analyzeEmptyRegions(board, baseOwner, baseSize);

        boolean[] stoneSeen = new boolean[Board.POINTS];
        int[] nb = new int[4];
        for (int start = 0; start < Board.POINTS; start++) {
            byte color = board.getIndex(start);
            if (color == StoneColor.EMPTY || stoneSeen[start]) {
                continue;
            }
            int[] chain = board.chainIndices(start);
            for (int p : chain) {
                stoneSeen[p] = true;
            }
            byte opponent = StoneColor.opponent(color);

            // 条件二：本串自己围出的空区（原盘上纯自己颜色的空区）最大不能超过 1 点。
            int maxOwnRegion = 0;
            for (int p : chain) {
                int n = Board.neighbors(p, nb);
                for (int i = 0; i < n; i++) {
                    int q = nb[i];
                    if (board.getIndex(q) != StoneColor.EMPTY) {
                        continue;
                    }
                    if (baseOwner[q] == color) {
                        maxOwnRegion = Math.max(maxOwnRegion, baseSize[q]);
                    }
                }
            }
            if (maxOwnRegion > 1) {
                continue;
            }

            // 条件一：摘掉本串后，每口气都必须落在仅邻接对方颜色的空区里。
            Board work = board.copy();
            for (int p : chain) {
                work.setIndex(p, StoneColor.EMPTY);
            }
            byte[] workOwner = new byte[Board.POINTS];
            int[] workSize = new int[Board.POINTS];
            analyzeEmptyRegions(work, workOwner, workSize);

            boolean anyLiberty = false;
            boolean allOpponent = true;
            for (int p : chain) {
                int n = Board.neighbors(p, nb);
                for (int i = 0; i < n; i++) {
                    int q = nb[i];
                    if (work.getIndex(q) != StoneColor.EMPTY) {
                        continue;
                    }
                    anyLiberty = true;
                    if (workOwner[q] != opponent) {
                        allOpponent = false;
                    }
                }
            }
            if (anyLiberty && allOpponent) {
                for (int p : chain) {
                    result[p] = 1;
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 绝对活白名单
    // ------------------------------------------------------------------

    /**
     * 绝对活白名单单点查询：两遍蒙特卡洛都判活且未被浮子命中的棋块，
     * 外加两条硬规则（真眼 / 气集完全重合的双活）——后者让双活不会被误判。
     */
    public static boolean isAliveWhitelist(Board board, int idx) {
        if (idx < 0 || idx >= Board.POINTS || board.getIndex(idx) == StoneColor.EMPTY) {
            return false;
        }
        return aliveWhitelist(board)[idx] != 0;
    }

    /** 整盘意义上的绝对活白名单（带缓存；同一盘面的反复查询只算一次）。 */
    public static byte[] aliveWhitelist(Board board) {
        byte[] cells = board.cells().clone();
        synchronized (CACHE_LOCK) {
            if (aliveCacheMask != null && aliveCacheTrials == DEFAULT_TRIALS
                    && Arrays.equals(aliveCacheCells, cells)) {
                return aliveCacheMask.clone();
            }
        }
        byte[] mask = computeAliveWhitelist(board, DEFAULT_TRIALS, DEFAULT_SEED);
        synchronized (CACHE_LOCK) {
            aliveCacheMask = mask.clone();
            aliveCacheCells = cells;
            aliveCacheTrials = DEFAULT_TRIALS;
        }
        return mask;
    }

    private static byte[] computeAliveWhitelist(Board board, int trials, long seed) {
        byte[] protect = absoluteAliveProtect(board);
        byte[] floating = floatStones(board);
        byte[] mcDead = trials > 0 ? runBothPasses(board, trials, seed) : new byte[Board.POINTS];
        byte[] out = new byte[Board.POINTS];
        for (int i = 0; i < Board.POINTS; i++) {
            if (board.getIndex(i) == StoneColor.EMPTY) {
                continue;
            }
            if (protect[i] != 0) {
                out[i] = 1;
            } else if (mcDead[i] == 0 && floating[i] == 0) {
                out[i] = 1;
            }
        }
        return out;
    }

    /**
     * 硬规则白名单（不依赖随机）：
     * <ol>
     *   <li>棋块邻接一块 ≥2 点的纯自己空区 = 有真眼，绝对活；</li>
     *   <li>黑白两串的气集完全重合且 ≥2 气 = 无眼双活，双方都绝对活（seki 不做计分特判，只做保护）。</li>
     * </ol>
     */
    private static byte[] absoluteAliveProtect(Board board) {
        byte[] protect = new byte[Board.POINTS];
        byte[] owner = new byte[Board.POINTS];
        int[] size = new int[Board.POINTS];
        analyzeEmptyRegions(board, owner, size);

        List<int[]> chains = new ArrayList<>();
        List<Byte> colors = new ArrayList<>();
        List<long[]> libertyBits = new ArrayList<>();
        boolean[] seen = new boolean[Board.POINTS];
        for (int start = 0; start < Board.POINTS; start++) {
            byte color = board.getIndex(start);
            if (color == StoneColor.EMPTY || seen[start]) {
                continue;
            }
            int[] chain = board.chainIndices(start);
            for (int p : chain) {
                seen[p] = true;
            }
            long[] bits = new long[BITSET_WORDS];
            for (int p : board.libertyIndices(start)) {
                bits[p >>> 6] |= 1L << (p & 63);
            }
            chains.add(chain);
            colors.add(color);
            libertyBits.add(bits);
        }

        int[] nb = new int[4];
        for (int ci = 0; ci < chains.size(); ci++) {
            byte color = colors.get(ci);
            int maxOwnRegion = 0;
            for (int p : chains.get(ci)) {
                int n = Board.neighbors(p, nb);
                for (int i = 0; i < n; i++) {
                    int q = nb[i];
                    if (board.getIndex(q) == StoneColor.EMPTY && owner[q] == color) {
                        maxOwnRegion = Math.max(maxOwnRegion, size[q]);
                    }
                }
            }
            if (maxOwnRegion >= 2) {
                markChain(protect, chains.get(ci));
            }
        }

        for (int i = 0; i < chains.size(); i++) {
            for (int j = i + 1; j < chains.size(); j++) {
                if (colors.get(i).byteValue() == colors.get(j).byteValue()) {
                    continue;
                }
                if (bitCount(libertyBits.get(i)) < 2) {
                    continue;
                }
                if (Arrays.equals(libertyBits.get(i), libertyBits.get(j))) {
                    markChain(protect, chains.get(i));
                    markChain(protect, chains.get(j));
                }
            }
        }
        return protect;
    }

    private static void markChain(byte[] mask, int[] chain) {
        for (int p : chain) {
            mask[p] = 1;
        }
    }

    private static int bitCount(long[] bits) {
        int count = 0;
        for (long word : bits) {
            count += Long.bitCount(word);
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 蒙特卡洛死活
    // ------------------------------------------------------------------

    public static byte[] guessDead(Board board) {
        return guessDead(board, DEFAULT_TRIALS, true, DEFAULT_SEED);
    }

    public static byte[] guessDead(Board board, int trials) {
        return guessDead(board, trials, true, DEFAULT_SEED);
    }

    /**
     * 蒙特卡洛死活：黑先/白先各跑一遍（各自 trials 局），两遍都判死的点才算死。
     *
     * <p>{@code finished == false} 时只做确定性判定（浮子），不跑模拟——对局进行中算死活没有意义，
     * 数子阶段（双方 pass 后）才传 {@code true}。</p>
     *
     * @param trials   模拟局数，&lt;=0 视为不跑
     * @param finished 是否已到终局（决定跑不跑蒙特卡洛）
     * @param seed     随机种子（写死即可复现）
     * @return 疑似死子掩码（非 0 = 判死；绝对活白名单里的点永远为 0）
     */
    public static byte[] guessDead(Board board, int trials, boolean finished, long seed) {
        byte[] floating = floatStones(board);
        byte[] protect = absoluteAliveProtect(board);
        byte[] mcDead = finished && trials > 0 ? runBothPasses(board, trials, seed) : new byte[Board.POINTS];

        byte[] out = new byte[Board.POINTS];
        for (int i = 0; i < Board.POINTS; i++) {
            if (board.getIndex(i) == StoneColor.EMPTY || protect[i] != 0) {
                continue;
            }
            if (mcDead[i] != 0 || floating[i] != 0) {
                out[i] = 1;
            }
        }
        return out;
    }

    private static byte[] runBothPasses(Board board, int trials, long seed) {
        byte[] blackFirst = runPass(board, trials, seed, StoneColor.BLACK);
        byte[] whiteFirst = runPass(board, trials, seed ^ WHITE_FIRST_SEED_XOR, StoneColor.WHITE);
        byte[] both = new byte[Board.POINTS];
        for (int i = 0; i < Board.POINTS; i++) {
            if (blackFirst[i] != 0 && whiteFirst[i] != 0) {
                both[i] = 1;
            }
        }
        return both;
    }

    private static byte[] runPass(Board board, int trials, long seed, byte firstColor) {
        byte[] initial = board.cells().clone();
        int[] deadCount = new int[Board.POINTS];
        byte[] out = new byte[Board.POINTS];
        if (trials <= 0) {
            return out;
        }
        Random random = new Random(seed);
        Playout playout = new Playout();
        long startNanos = System.nanoTime();
        int done = 0;
        for (int t = 0; t < trials; t++) {
            if ((System.nanoTime() - startNanos) / 1_000_000L > TIME_LIMIT_MS) {
                break;
            }
            playout.run(board, random, firstColor, initial, deadCount);
            done++;
        }
        if (done == 0) {
            return out;
        }
        for (int i = 0; i < Board.POINTS; i++) {
            if (initial[i] != StoneColor.EMPTY && deadCount[i] * 2 > done) {
                out[i] = 1;
            }
        }
        return out;
    }

    /** 一局随机终局：随机走子直到双方连续 pass，再按 Tromp-Taylor 归属统计死活。 */
    private static final class Playout {

        private final byte[] cells = new byte[Board.POINTS];
        private final int[] visit = new int[Board.POINTS];
        private final int[] libMark = new int[Board.POINTS];
        private final int[] stack = new int[Board.POINTS];
        private final int[] region = new int[Board.POINTS];
        private final byte[] owner = new byte[Board.POINTS];
        private final int[] nbPlay = new int[4];
        private final int[] nbLiberty = new int[4];
        private final int[] nbRemove = new int[4];
        private final int[] nbTerritory = new int[4];
        private int stamp;

        void run(Board base, Random random, byte firstColor, byte[] initial, int[] deadCount) {
            System.arraycopy(base.cells(), 0, cells, 0, Board.POINTS);
            byte toMove = firstColor;
            int passes = 0;
            int moves = 0;
            while (passes < 2 && moves < MAX_PLAYOUT_MOVES) {
                if (tryRandomMove(random, toMove)) {
                    passes = 0;
                    moves++;
                } else {
                    passes++;
                }
                toMove = StoneColor.opponent(toMove);
            }
            classifyTerritory();
            for (int i = 0; i < Board.POINTS; i++) {
                byte init = initial[i];
                if (init == StoneColor.EMPTY) {
                    continue;
                }
                byte opponent = StoneColor.opponent(init);
                byte now = cells[i];
                if (now == opponent) {
                    deadCount[i]++;
                } else if (now == StoneColor.EMPTY && owner[i] == opponent) {
                    deadCount[i]++;
                }
            }
        }

        private boolean tryRandomMove(Random random, byte color) {
            for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
                int idx = random.nextInt(Board.POINTS);
                if (cells[idx] != StoneColor.EMPTY) {
                    continue;
                }
                if (play(idx, color)) {
                    return true;
                }
            }
            return false;
        }

        private boolean play(int idx, byte color) {
            cells[idx] = color;
            byte opponent = StoneColor.opponent(color);
            boolean captured = false;
            int n = Board.neighbors(idx, nbPlay);
            for (int i = 0; i < n; i++) {
                int p = nbPlay[i];
                if (cells[p] == opponent && libertyCount(p) == 0) {
                    removeChain(p);
                    captured = true;
                }
            }
            if (!captured && libertyCount(idx) == 0) {
                cells[idx] = StoneColor.EMPTY;
                return false;
            }
            return true;
        }

        private int libertyCount(int idx) {
            byte color = cells[idx];
            if (color == StoneColor.EMPTY) {
                return 0;
            }
            nextStamp();
            int sp = 0;
            int libs = 0;
            stack[sp++] = idx;
            visit[idx] = stamp;
            while (sp > 0) {
                int cur = stack[--sp];
                int n = Board.neighbors(cur, nbLiberty);
                for (int i = 0; i < n; i++) {
                    int p = nbLiberty[i];
                    byte c = cells[p];
                    if (c == StoneColor.EMPTY) {
                        if (libMark[p] != stamp) {
                            libMark[p] = stamp;
                            libs++;
                        }
                    } else if (c == color && visit[p] != stamp) {
                        visit[p] = stamp;
                        stack[sp++] = p;
                    }
                }
            }
            return libs;
        }

        private void removeChain(int idx) {
            byte color = cells[idx];
            if (color == StoneColor.EMPTY) {
                return;
            }
            nextStamp();
            int sp = 0;
            stack[sp++] = idx;
            visit[idx] = stamp;
            while (sp > 0) {
                int cur = stack[--sp];
                cells[cur] = StoneColor.EMPTY;
                int n = Board.neighbors(cur, nbRemove);
                for (int i = 0; i < n; i++) {
                    int p = nbRemove[i];
                    if (cells[p] == color && visit[p] != stamp) {
                        visit[p] = stamp;
                        stack[sp++] = p;
                    }
                }
            }
        }

        /** Tromp-Taylor：空区仅邻接单色则归该方，双色邻接为中立。 */
        private void classifyTerritory() {
            Arrays.fill(owner, StoneColor.EMPTY);
            nextStamp();
            for (int start = 0; start < Board.POINTS; start++) {
                if (cells[start] != StoneColor.EMPTY || visit[start] == stamp) {
                    continue;
                }
                int sp = 0;
                int rp = 0;
                boolean black = false;
                boolean white = false;
                stack[sp++] = start;
                visit[start] = stamp;
                while (sp > 0) {
                    int cur = stack[--sp];
                    region[rp++] = cur;
                    int n = Board.neighbors(cur, nbTerritory);
                    for (int i = 0; i < n; i++) {
                        int p = nbTerritory[i];
                        byte c = cells[p];
                        if (c == StoneColor.BLACK) {
                            black = true;
                        } else if (c == StoneColor.WHITE) {
                            white = true;
                        } else if (visit[p] != stamp) {
                            visit[p] = stamp;
                            stack[sp++] = p;
                        }
                    }
                }
                byte regionOwner;
                if (black && white) {
                    regionOwner = StoneColor.EMPTY;
                } else if (black) {
                    regionOwner = StoneColor.BLACK;
                } else if (white) {
                    regionOwner = StoneColor.WHITE;
                } else {
                    regionOwner = StoneColor.EMPTY;
                }
                for (int i = 0; i < rp; i++) {
                    owner[region[i]] = regionOwner;
                }
            }
        }

        private void nextStamp() {
            stamp++;
            if (stamp <= 0) {
                Arrays.fill(visit, 0);
                Arrays.fill(libMark, 0);
                stamp = 1;
            }
        }
    }
}

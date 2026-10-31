package com.dsh.goweiqi.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 19 路棋盘：{@code byte[361]}，下标 {@code x * 19 + y}（x 东向、y 南向，0..18）。
 * 连通块/气必须用迭代式洪泛（int[] 栈），禁止递归。
 *
 * <p>本类零 MC 依赖，可在纯 JUnit 下运行。</p>
 */
public class Board {

    public static final int SIZE = 19;
    public static final int POINTS = SIZE * SIZE;

    private final byte[] cells;

    public Board() {
        this.cells = new byte[POINTS];
    }

    private Board(byte[] cells) {
        this.cells = cells;
    }

    /** 坐标 → 下标；越界返回 -1。 */
    public static int index(int x, int y) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) {
            return -1;
        }
        return x * SIZE + y;
    }

    public static int xOf(int idx) {
        return idx / SIZE;
    }

    public static int yOf(int idx) {
        return idx % SIZE;
    }

    /**
     * 把 {@code idx} 的四邻写进 {@code out}（容量至少 4），返回邻点个数。
     * 供洪泛与判定复用，避免每步 new 数组。
     */
    public static int neighbors(int idx, int[] out) {
        if (idx < 0 || idx >= POINTS || out == null || out.length < 4) {
            return 0;
        }
        int x = idx / SIZE;
        int y = idx % SIZE;
        int n = 0;
        if (x > 0) {
            out[n++] = idx - SIZE;
        }
        if (x < SIZE - 1) {
            out[n++] = idx + SIZE;
        }
        if (y > 0) {
            out[n++] = idx - 1;
        }
        if (y < SIZE - 1) {
            out[n++] = idx + 1;
        }
        return n;
    }

    public byte[] cells() {
        return cells;
    }

    public byte get(int x, int y) {
        int idx = index(x, y);
        return idx < 0 ? StoneColor.EMPTY : cells[idx];
    }

    public void set(int x, int y, byte color) {
        int idx = index(x, y);
        if (idx >= 0) {
            cells[idx] = color;
        }
    }

    public byte getIndex(int idx) {
        return idx >= 0 && idx < POINTS ? cells[idx] : StoneColor.EMPTY;
    }

    public void setIndex(int idx, byte color) {
        if (idx >= 0 && idx < POINTS) {
            cells[idx] = color;
        }
    }

    public Board copy() {
        return new Board(cells.clone());
    }

    /** 把 {@code other} 的盘面整体拷进本盘（落子合法后提交用）。 */
    public void copyFrom(Board other) {
        if (other == null) {
            return;
        }
        System.arraycopy(other.cells, 0, cells, 0, POINTS);
    }

    public long positionHash() {
        return Zobrist.hash(cells);
    }

    /** 该点所在整串（同色连通块）的全部下标；空点/越界返回空表。迭代式洪泛。 */
    public List<Integer> chain(int idx) {
        int[] indices = chainIndices(idx);
        List<Integer> list = new ArrayList<>(indices.length);
        for (int i : indices) {
            list.add(i);
        }
        return list;
    }

    /** 同 {@link #chain(int)}，但返回裸数组（内部热路径用）。 */
    public int[] chainIndices(int idx) {
        if (idx < 0 || idx >= POINTS) {
            return new int[0];
        }
        byte color = cells[idx];
        if (color == StoneColor.EMPTY) {
            return new int[0];
        }
        boolean[] visited = new boolean[POINTS];
        int[] stack = new int[POINTS];
        int[] chain = new int[POINTS];
        int[] nb = new int[4];
        int sp = 0;
        int cp = 0;
        stack[sp++] = idx;
        visited[idx] = true;
        while (sp > 0) {
            int cur = stack[--sp];
            chain[cp++] = cur;
            int n = neighbors(cur, nb);
            for (int i = 0; i < n; i++) {
                int p = nb[i];
                if (!visited[p] && cells[p] == color) {
                    visited[p] = true;
                    stack[sp++] = p;
                }
            }
        }
        return Arrays.copyOf(chain, cp);
    }

    /** 该点所在串的全部气（空点下标）。迭代式洪泛，禁止递归。 */
    public int[] libertyIndices(int idx) {
        int[] chain = chainIndices(idx);
        if (chain.length == 0) {
            return new int[0];
        }
        boolean[] libMark = new boolean[POINTS];
        int[] libs = new int[POINTS];
        int[] nb = new int[4];
        int lc = 0;
        for (int stone : chain) {
            int n = neighbors(stone, nb);
            for (int i = 0; i < n; i++) {
                int p = nb[i];
                if (cells[p] == StoneColor.EMPTY && !libMark[p]) {
                    libMark[p] = true;
                    libs[lc++] = p;
                }
            }
        }
        return Arrays.copyOf(libs, lc);
    }

    /** 该点所在串的气数。 */
    public int liberties(int idx) {
        return libertyIndices(idx).length;
    }

    /**
     * 提掉 {@code idx} 四邻中无气的敌方整串（{@code color} 为刚落下的颜色）。
     *
     * @return 被提子数；盘面被就地修改
     */
    public int captureAdjacent(int idx, byte color) {
        byte opponent = StoneColor.opponent(color);
        if (opponent == StoneColor.EMPTY || idx < 0 || idx >= POINTS) {
            return 0;
        }
        int[] nb = new int[4];
        int n = neighbors(idx, nb);
        int captured = 0;
        for (int i = 0; i < n; i++) {
            int p = nb[i];
            if (cells[p] != opponent) {
                continue;
            }
            if (liberties(p) > 0) {
                continue;
            }
            for (int q : chainIndices(p)) {
                cells[q] = StoneColor.EMPTY;
                captured++;
            }
        }
        return captured;
    }

    /** 在 (x,y) 落下 color 后，己方串是否仍有气（自杀判定用，不改动本盘）。 */
    public boolean hasLibertyAfterMove(int x, int y, byte color) {
        int idx = index(x, y);
        if (idx < 0 || color == StoneColor.EMPTY) {
            return false;
        }
        Board work = copy();
        work.setIndex(idx, color);
        work.captureAdjacent(idx, color);
        return work.liberties(idx) > 0;
    }

    /** 盘上某色棋子数（数子用，不计死活）。 */
    public int countStones(byte color) {
        int count = 0;
        for (byte c : cells) {
            if (c == color) {
                count++;
            }
        }
        return count;
    }
}

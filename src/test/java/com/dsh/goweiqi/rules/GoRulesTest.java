package com.dsh.goweiqi.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * 落子规则 JUnit 4 测试（零 MC 依赖，可离线跑）。
 *
 * <p>棋盘坐标：{@code idx = x * 19 + y}，x 东向、y 南向。</p>
 */
public class GoRulesTest {

    private static final byte B = StoneColor.BLACK;
    private static final byte W = StoneColor.WHITE;
    private static final byte E = StoneColor.EMPTY;

    private static int at(int x, int y) {
        return Board.index(x, y);
    }

    private static void black(Board b, int x, int y) {
        b.set(x, y, B);
    }

    private static void white(Board b, int x, int y) {
        b.set(x, y, W);
    }

    /** 维护历史局面哈希，模拟一次完整对局的 superko 判定。 */
    private static final class History {
        private final Set<Long> hashes = new HashSet<>();

        History(Board b) {
            hashes.add(b.positionHash());
        }

        GoRules.MoveResult play(Board b, int x, int y, byte color) {
            return GoRules.tryPlay(b, x, y, color, 0L, hashes);
        }
    }

    /**
     * 在 (bx,by) 处摆一个标准劫形（4×3）。{@code whiteKo=true} 表示内点有白子（黑可提），
     * 否则外点有黑子（白可提）。
     *
     * <pre>
     * . B W .        B = 黑, W = 白, 内点 = (bx+1,by+1), 外点 = (bx+2,by+1)
     * B W . W
     * . B W .
     * </pre>
     */
    private static void placeKo(Board b, int bx, int by, boolean whiteKo) {
        black(b, bx + 1, by);
        black(b, bx, by + 1);
        black(b, bx + 1, by + 2);
        white(b, bx + 2, by);
        white(b, bx + 3, by + 1);
        white(b, bx + 2, by + 2);
        if (whiteKo) {
            white(b, bx + 1, by + 1);
        } else {
            black(b, bx + 2, by + 1);
        }
    }

    // ------------------------------------------------------------------
    // 棋盘表示 / 连通块 / 气
    // ------------------------------------------------------------------

    @Test
    public void boardIndexIsXTimes19PlusY() {
        assertEquals(0, at(0, 0));
        assertEquals(1, at(0, 1));
        assertEquals(19, at(1, 0));
        assertEquals(Board.POINTS - 1, at(18, 18));
        assertEquals(-1, Board.index(-1, 0));
        assertEquals(-1, Board.index(0, 19));
        assertEquals(7, Board.xOf(at(7, 12)));
        assertEquals(12, Board.yOf(at(7, 12)));
    }

    @Test
    public void hashIsDeterministicAndReproducible() {
        Board a = new Board();
        black(a, 3, 3);
        white(a, 4, 4);
        Board b = new Board();
        black(b, 3, 3);
        white(b, 4, 4);
        assertEquals(a.positionHash(), b.positionHash());
        assertEquals(a.positionHash(), a.copy().positionHash());

        Board c = new Board();
        black(c, 3, 3);
        black(c, 4, 4);
        assertNotEquals(a.positionHash(), c.positionHash());
        // 固定种子：同一点同色的 Zobrist 值每次都一样
        assertEquals(Zobrist.value(at(3, 3), B), Zobrist.value(at(3, 3), B));
        assertNotEquals(0L, Zobrist.value(at(3, 3), B));
    }

    @Test
    public void chainAndLibertiesUseIterativeFlood() {
        Board b = new Board();
        black(b, 5, 5);
        black(b, 6, 5);
        List<Integer> chain = b.chain(at(5, 5));
        assertEquals(2, chain.size());
        assertTrue(chain.contains(at(5, 5)));
        assertTrue(chain.contains(at(6, 5)));
        // 两子横连：上 2 + 下 2 + 左 1 + 右 1 = 6 气
        assertEquals(6, b.liberties(at(6, 5)));
        assertEquals(0, b.chain(at(9, 9)).size());
    }

    @Test
    public void cornerAndEdgeLibertyCounts() {
        Board corner = new Board();
        black(corner, 0, 0);
        assertEquals("角上一子 2 气", 2, corner.liberties(at(0, 0)));

        Board cornerBlocked = new Board();
        black(cornerBlocked, 0, 0);
        white(cornerBlocked, 1, 0);
        assertEquals("角上一子被堵一侧剩 1 气", 1, cornerBlocked.liberties(at(0, 0)));

        Board edge = new Board();
        black(edge, 0, 5);
        assertEquals("边上一子 3 气", 3, edge.liberties(at(0, 5)));

        Board middle = new Board();
        black(middle, 9, 9);
        assertEquals("中腹一子 4 气", 4, middle.liberties(at(9, 9)));

        Board edgeTwo = new Board();
        black(edgeTwo, 0, 5);
        white(edgeTwo, 0, 4);
        assertEquals("边上一子被堵一侧剩 2 气", 2, edgeTwo.liberties(at(0, 5)));
    }

    // ------------------------------------------------------------------
    // 提子
    // ------------------------------------------------------------------

    @Test
    public void singleStoneCapture() {
        Board b = new Board();
        white(b, 5, 5);
        black(b, 4, 5);
        black(b, 6, 5);
        black(b, 5, 4);
        History h = new History(b);

        GoRules.MoveResult r = h.play(b, 5, 6, B);
        assertTrue("提子应合法", r.ok());
        assertEquals(1, r.captured().length);
        assertEquals(at(5, 5), r.captured()[0]);
        assertEquals(E, b.get(5, 5));
        assertEquals(B, b.get(5, 6));
    }

    @Test
    public void multiStoneChainCapture() {
        Board b = new Board();
        white(b, 5, 5);
        white(b, 5, 6);
        black(b, 4, 5);
        black(b, 6, 5);
        black(b, 5, 4);
        black(b, 4, 6);
        black(b, 6, 6);
        History h = new History(b);

        assertEquals(1, b.liberties(at(5, 5)));
        GoRules.MoveResult r = h.play(b, 5, 7, B);
        assertTrue("提两子应合法", r.ok());
        assertEquals(2, r.captured().length);
        Set<Integer> captured = new HashSet<>();
        for (int p : r.captured()) {
            captured.add(p);
        }
        assertTrue(captured.contains(at(5, 5)));
        assertTrue(captured.contains(at(5, 6)));
        assertEquals(E, b.get(5, 5));
        assertEquals(E, b.get(5, 6));
        assertEquals(B, b.get(5, 7));
        // 提子后自己也得了气（内点空出来）
        assertTrue(b.liberties(at(5, 7)) > 0);
    }

    @Test
    public void captureGivesOwnChainLiberty() {
        // 劫形：黑提后自己的子只剩内点 1 气，但依然合法（不是自杀）
        Board b = new Board();
        placeKo(b, 1, 1, true);
        History h = new History(b);

        assertTrue("提子后己方得气", b.hasLibertyAfterMove(3, 2, B));
        GoRules.MoveResult r = h.play(b, 3, 2, B);
        assertTrue(r.ok());
        assertEquals(1, r.captured().length);
        assertEquals(1, b.liberties(at(3, 2)));
    }

    // ------------------------------------------------------------------
    // 禁手
    // ------------------------------------------------------------------

    @Test
    public void suicideIsRejected() {
        Board b = new Board();
        white(b, 4, 5);
        white(b, 6, 5);
        white(b, 5, 4);
        white(b, 5, 6);
        History h = new History(b);
        long before = b.positionHash();

        GoRules.MoveResult r = h.play(b, 5, 5, B);
        assertEquals(GoRules.Result.ILLEGAL_SUICIDE, r.result());
        assertEquals("失败时盘面不许被改动", E, b.get(5, 5));
        assertEquals(before, b.positionHash());
    }

    @Test
    public void occupiedPointIsRejected() {
        Board b = new Board();
        black(b, 5, 5);
        History h = new History(b);
        assertEquals(GoRules.Result.ILLEGAL_OCCUPIED, h.play(b, 5, 5, W).result());
        assertEquals(GoRules.Result.ILLEGAL_OCCUPIED, h.play(b, 19, 0, W).result());
        assertEquals(B, b.get(5, 5));
    }

    @Test
    public void koImmediateRecaptureIsRejected() {
        Board b = new Board();
        placeKo(b, 1, 1, true);
        History h = new History(b);

        GoRules.MoveResult takeKo = h.play(b, 3, 2, B);
        assertTrue(takeKo.ok());
        assertEquals(E, b.get(2, 2));

        GoRules.MoveResult recapture = h.play(b, 2, 2, W);
        assertEquals("原地回提必须被超劫拒绝", GoRules.Result.ILLEGAL_SUPERKO, recapture.result());
        // 盘面保持黑提之后的局面
        assertEquals(B, b.get(3, 2));
        assertEquals(E, b.get(2, 2));
    }

    @Test
    public void tripleKoCycleTriggersSuperko() {
        // 三个独立的劫：ko1=A, ko2=B, ko3=A。六手后盘面回到初始局面，第 6 手必须被拒。
        Board b = new Board();
        placeKo(b, 1, 1, true);
        placeKo(b, 1, 7, false);
        placeKo(b, 1, 13, true);
        History h = new History(b);

        int[][] moves = {{3, 2}, {2, 8}, {3, 14}, {2, 2}, {3, 8}, {2, 14}};
        byte[] colors = {B, W, B, W, B, W};
        for (int i = 0; i < 5; i++) {
            GoRules.MoveResult r = h.play(b, moves[i][0], moves[i][1], colors[i]);
            assertTrue("第 " + (i + 1) + " 手应合法，实际 " + r.result(), r.ok());
        }
        GoRules.MoveResult sixth = h.play(b, moves[5][0], moves[5][1], colors[5]);
        assertEquals("三劫循环第六手回到初始局面，应判位置超劫",
                GoRules.Result.ILLEGAL_SUPERKO, sixth.result());
    }

    @Test
    public void koRecaptureAfterUnrelatedMoveIsLegal() {
        // 超劫只看整盘哈希：黑白各脱先一手后回提，盘面没重复，所以合法
        Board b = new Board();
        placeKo(b, 1, 1, true);
        History h = new History(b);
        assertTrue(h.play(b, 3, 2, B).ok());
        assertTrue("白脱先一手", h.play(b, 12, 12, W).ok());
        assertTrue("黑脱先一手", h.play(b, 13, 13, B).ok());
        GoRules.MoveResult recapture = h.play(b, 2, 2, W);
        assertTrue("脱先后回提合法，实际 " + recapture.result(), recapture.ok());
        assertEquals(W, b.get(2, 2));
        assertEquals(E, b.get(3, 2));
    }
}

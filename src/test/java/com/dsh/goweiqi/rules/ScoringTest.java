package com.dsh.goweiqi.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * 数子 / 浮子 / 蒙特卡洛死活 JUnit 4 测试（零 MC 依赖，可离线跑）。
 *
 * <p>黑贴 3¾ 子：{@code 阈值 = (361 - d) / 2 + 3.75}，{@code 子差 = 黑 - 阈值}，
 * {@code 目差 = 2 × 子差}。</p>
 */
public class ScoringTest {

    private static final byte B = StoneColor.BLACK;
    private static final byte W = StoneColor.WHITE;
    private static final byte E = StoneColor.EMPTY;

    private static int at(int x, int y) {
        return Board.index(x, y);
    }

    /** 19 路左右分割：黑第 8 列、白第 10 列，第 9 列全是单官。黑地 8×19=152，白地 152。 */
    private static Board splitBoard() {
        Board b = new Board();
        for (int y = 0; y < Board.SIZE; y++) {
            b.set(8, y, B);
            b.set(10, y, W);
        }
        return b;
    }

    /** 全盘填满、无单官的人造终局：前 blackCount 点为黑，其余为白。 */
    private static Board filledBoard(int blackCount) {
        Board b = new Board();
        for (int i = 0; i < Board.POINTS; i++) {
            b.setIndex(i, i < blackCount ? B : W);
        }
        return b;
    }

    /** 白环包住 3..7 的内部，内部放一颗黑孤子；右侧另有黑白两列，保证外侧不是"对方地盘"。 */
    private static Board ringBoard() {
        Board b = new Board();
        for (int x = 2; x <= 8; x++) {
            for (int y = 2; y <= 8; y++) {
                boolean border = x == 2 || x == 8 || y == 2 || y == 8;
                if (border) {
                    b.set(x, y, W);
                }
            }
        }
        b.set(5, 5, B); // 白地盘里的黑孤子：疑似死
        for (int y = 0; y < Board.SIZE; y++) {
            b.set(11, y, W);
            b.set(13, y, B);
        }
        return b;
    }

    /** 无眼双活：黑白各一单子，气集完全重合（都是 (1,1)、(2,2)）。 */
    private static Board noEyeSekiBoard() {
        Board b = new Board();
        b.set(2, 1, B);
        b.set(0, 2, B);
        b.set(1, 3, B);
        b.set(1, 2, W);
        b.set(3, 1, W);
        b.set(2, 0, W);
        return b;
    }

    /** 有眼双活：黑白各一只有 2 点真眼的棋块，且通过 (7,3) 共享一口气。 */
    private static Board eyeSekiBoard() {
        Board b = new Board();
        // 黑环 (2..5, 2..4)，眼位 (3,3)(4,3)
        for (int x = 2; x <= 5; x++) {
            b.set(x, 2, B);
            b.set(x, 4, B);
        }
        b.set(2, 3, B);
        b.set(5, 3, B);
        b.set(6, 3, B); // 伸出的一子，与 (7,3) 相邻
        // 白环 (6..9, 5..7)，眼位 (7,6)(8,6)
        for (int x = 6; x <= 9; x++) {
            b.set(x, 5, W);
            b.set(x, 7, W);
        }
        b.set(6, 6, W);
        b.set(9, 6, W);
        b.set(6, 4, W);
        b.set(7, 4, W);
        return b;
    }

    private static Set<Integer> libertySet(Board b, int idx) {
        Set<Integer> set = new HashSet<>();
        for (int p : b.libertyIndices(idx)) {
            set.add(p);
        }
        return set;
    }

    // ------------------------------------------------------------------
    // 数子
    // ------------------------------------------------------------------

    @Test
    public void splitBoardCountsTerritoryAndDame() {
        Board b = splitBoard();
        ScoreResult r = Scoring.score(b, null);

        assertEquals(19, r.blackStones());
        assertEquals(19, r.whiteStones());
        assertEquals(152, r.blackTerritory());
        assertEquals(152, r.whiteTerritory());
        assertEquals(19, r.dame());
        assertEquals(171, r.blackTotal());
        assertEquals(171, r.whiteTotal());
        assertEquals("白胜", "white", r.winner());
        assertEquals(-3.75, r.marginZi(), 1e-9);
        assertEquals(-7.5, r.marginPoints(), 1e-9);
        assertFalse(r.blackWins());
    }

    @Test
    public void dameIsNotCountedForEitherSide() {
        Board b = splitBoard();
        byte[] terr = Scoring.territory(b, null);
        assertEquals("双色邻接的空区是单官", E, terr[at(9, 5)]);
        assertEquals(B, terr[at(5, 5)]);
        assertEquals(W, terr[at(12, 5)]);
        assertEquals("有子的点不是地", E, terr[at(8, 5)]);
    }

    @Test
    public void komiBoundaryAt184And185() {
        // 单官为 0：184 子白胜、185 子黑胜
        ScoreResult r184 = Scoring.score(filledBoard(184), null);
        assertEquals(0, r184.dame());
        assertEquals(184, r184.blackTotal());
        assertEquals(177, r184.whiteTotal());
        assertEquals("white", r184.winner());
        assertEquals(-0.25, r184.marginZi(), 1e-9);
        assertEquals(-0.5, r184.marginPoints(), 1e-9);

        ScoreResult r185 = Scoring.score(filledBoard(185), null);
        assertEquals(0, r185.dame());
        assertEquals(185, r185.blackTotal());
        assertEquals(176, r185.whiteTotal());
        assertEquals("black", r185.winner());
        assertEquals(0.75, r185.marginZi(), 1e-9);
        assertEquals(1.5, r185.marginPoints(), 1e-9);
    }

    @Test
    public void deadMaskRemovesStonesBeforeScoring() {
        Board b = splitBoard();
        b.set(4, 10, W); // 黑空里的白子
        // 未提死子时：黑空左侧整区被白子染成单官，黑地归零
        ScoreResult alive = Scoring.score(b, null);
        assertEquals(20, alive.whiteStones());
        assertEquals(0, alive.blackTerritory());
        assertEquals(170, alive.dame());
        assertEquals(19, alive.blackTotal());

        byte[] dead = new byte[Board.POINTS];
        dead[at(4, 10)] = 1;
        ScoreResult scored = Scoring.score(b, dead);
        assertEquals(19, scored.whiteStones());
        assertEquals(152, scored.blackTerritory());
        assertEquals(19, scored.dame());
        assertEquals(-3.75, scored.marginZi(), 1e-9);
    }

    // ------------------------------------------------------------------
    // 浮子
    // ------------------------------------------------------------------

    @Test
    public void floatStonesFindsLoneStoneInOpponentArea() {
        Board b = ringBoard();
        byte[] floating = Scoring.floatStones(b);

        assertNotEquals("白地盘里的黑孤子应判浮子", 0, floating[at(5, 5)]);
        assertEquals("有自己地盘的棋块不是浮子", 0, floating[at(13, 9)]);
        assertEquals("有自己地盘的棋块不是浮子", 0, floating[at(11, 9)]);
    }

    // ------------------------------------------------------------------
    // 双活不被判死
    // ------------------------------------------------------------------

    @Test
    public void noEyeSekiWithIdenticalLibertiesIsAlive() {
        Board b = noEyeSekiBoard();
        assertEquals(libertySet(b, at(2, 1)), libertySet(b, at(1, 2)));
        assertEquals(2, libertySet(b, at(2, 1)).size());

        byte[] floating = Scoring.floatStones(b);
        assertEquals(0, floating[at(2, 1)]);
        assertEquals(0, floating[at(1, 2)]);

        assertTrue("气集重合的双活必须进绝对活白名单", Scoring.isAliveWhitelist(b, at(2, 1)));
        assertTrue("气集重合的双活必须进绝对活白名单", Scoring.isAliveWhitelist(b, at(1, 2)));

        byte[] dead = Scoring.guessDead(b, 16, true, 20250808L);
        assertEquals("无眼双活不许判死", 0, dead[at(2, 1)]);
        assertEquals("无眼双活不许判死", 0, dead[at(1, 2)]);
    }

    @Test
    public void sekiWithEyesIsAlive() {
        Board b = eyeSekiBoard();
        byte[] floating = Scoring.floatStones(b);
        assertEquals(0, floating[at(2, 2)]);
        assertEquals(0, floating[at(6, 5)]);

        assertTrue(Scoring.isAliveWhitelist(b, at(2, 2)));
        assertTrue(Scoring.isAliveWhitelist(b, at(6, 5)));

        byte[] dead = Scoring.guessDead(b, 16, true, 20250808L);
        assertEquals("有眼双活不许判死", 0, dead[at(2, 2)]);
        assertEquals("有眼双活不许判死", 0, dead[at(6, 5)]);
        assertEquals("有眼双活不许判死", 0, dead[at(6, 3)]);
    }

    @Test
    public void guessDeadIsDeterministicForSameSeed() {
        Board b = ringBoard();
        byte[] first = Scoring.guessDead(b, 12, true, 424242L);
        byte[] second = Scoring.guessDead(b, 12, true, 424242L);
        assertEquals(first.length, second.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i]);
        }
        assertNotEquals("白地盘里的黑孤子会被蒙特卡洛/浮子判死", 0, first[at(5, 5)]);
        assertEquals("黑自己有地盘的棋块不会被判死", 0, first[at(13, 9)]);
    }
}

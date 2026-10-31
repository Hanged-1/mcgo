package com.dsh.goweiqi.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 落子合法性判定（纯 Java，零 MC 依赖）。
 *
 * <p>规则：中国规则 19 路，禁自杀，打劫采用<b>位置超劫</b>（positional superko）——
 * 只比较整盘 Zobrist 哈希是否在历史集合中，普通单劫/三劫循环都是它的特例。</p>
 */
public final class GoRules {

    /** 判定结果。 */
    public enum Result {
        OK,
        ILLEGAL_OCCUPIED,
        ILLEGAL_SUICIDE,
        ILLEGAL_SUPERKO
    }

    /** tryPlay 的返回值：状态 + 本手提掉的下标（OK 时非 null）。 */
    public record MoveResult(Result result, int[] captured) {

        public boolean ok() {
            return result == Result.OK;
        }

        public static MoveResult success(int[] captured) {
            return new MoveResult(Result.OK, captured == null ? new int[0] : captured);
        }

        public static MoveResult fail(Result result) {
            return new MoveResult(result, new int[0]);
        }
    }

    private GoRules() {
    }

    /**
     * 顺序：非空 → 落子 → 提掉相邻无气敌块 → 己方无气则自杀禁手 → 新哈希已在 history 则超劫禁手。
     *
     * <p>成功时把落子后的盘面提交回 {@code board}，并把新局面哈希记入 {@code history}
     * （Set 去重，调用方重复 add 无副作用）。失败时 {@code board} 保持不变。</p>
     *
     * @param newHash 落子后的整盘哈希；传 {@code 0L} 时由本方法自己算（测试/业务都方便）
     * @param history 历史局面哈希集合，可为 null（此时不做超劫判定）
     */
    public static MoveResult tryPlay(Board board, int x, int y, byte color, long newHash, Set<Long> history) {
        int idx = Board.index(x, y);
        if (idx < 0 || color != StoneColor.BLACK && color != StoneColor.WHITE) {
            return MoveResult.fail(Result.ILLEGAL_OCCUPIED);
        }
        if (board.getIndex(idx) != StoneColor.EMPTY) {
            return MoveResult.fail(Result.ILLEGAL_OCCUPIED);
        }

        // 在工作副本上推演，只有全部判定通过才提交，保证失败时盘面原子不变。
        Board work = board.copy();
        work.setIndex(idx, color);

        byte opponent = StoneColor.opponent(color);
        int[] nb = new int[4];
        int neighborCount = Board.neighbors(idx, nb);
        List<Integer> capturedList = new ArrayList<>(4);
        for (int i = 0; i < neighborCount; i++) {
            int p = nb[i];
            if (work.getIndex(p) != opponent) {
                continue;
            }
            if (work.liberties(p) > 0) {
                continue;
            }
            for (int q : work.chainIndices(p)) {
                work.setIndex(q, StoneColor.EMPTY);
                capturedList.add(q);
            }
        }

        if (work.liberties(idx) == 0) {
            return MoveResult.fail(Result.ILLEGAL_SUICIDE);
        }

        long hash = newHash != 0L ? newHash : work.positionHash();
        if (history != null && history.contains(hash)) {
            return MoveResult.fail(Result.ILLEGAL_SUPERKO);
        }

        board.copyFrom(work);
        if (history != null) {
            history.add(hash);
        }

        int[] captured = new int[capturedList.size()];
        for (int i = 0; i < captured.length; i++) {
            captured[i] = capturedList.get(i);
        }
        return MoveResult.success(captured);
    }

    /** 便捷重载：哈希由本方法计算。 */
    public static MoveResult tryPlay(Board board, int x, int y, byte color, Set<Long> history) {
        return tryPlay(board, x, y, color, 0L, history);
    }

    /** 便捷重载：不判超劫（只判占位/自杀，供随机对局/回放用）。 */
    public static MoveResult tryPlay(Board board, int x, int y, byte color) {
        return tryPlay(board, x, y, color, 0L, null);
    }
}

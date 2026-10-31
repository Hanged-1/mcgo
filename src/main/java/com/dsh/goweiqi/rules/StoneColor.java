package com.dsh.goweiqi.rules;

/**
 * 棋子颜色：用 byte 常量而不是 enum，便于 {@code byte[361]} 棋盘与网络载荷直传。
 */
public final class StoneColor {

    public static final byte EMPTY = 0;
    public static final byte BLACK = 1;
    public static final byte WHITE = 2;

    private StoneColor() {
    }

    /** 对手颜色；EMPTY 传入时返回 EMPTY。 */
    public static byte opponent(byte color) {
        if (color == BLACK) {
            return WHITE;
        }
        if (color == WHITE) {
            return BLACK;
        }
        return EMPTY;
    }

    public static String name(byte color) {
        return switch (color) {
            case BLACK -> "black";
            case WHITE -> "white";
            default -> "empty";
        };
    }
}

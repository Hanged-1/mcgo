package com.dsh.goweiqi.game;

/**
 * 棋盘配色：A = 榧木浅黄 / 象牙白·墨黑；C = 深紫檀 / 牙白·玄铁。
 * id 用于贴图名 {@code mcgo:board_<id>}。
 */
public enum GoStyle {

    KAYA("kaya"),
    ROSEWOOD("rosewood");

    private final String id;

    GoStyle(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 程序化生成贴图的固定注册名后缀：board_kaya / board_rosewood。 */
    public String texturePath() {
        return "board_" + id;
    }

    public static GoStyle byId(String id) {
        for (GoStyle style : values()) {
            if (style.id.equalsIgnoreCase(id)) {
                return style;
            }
        }
        return KAYA;
    }

    /**
     * 命令/配置里的短代号：{@code a} = {@link #KAYA}，{@code c} = {@link #ROSEWOOD}，
     * 同时也接受 {@code kaya} / {@code rosewood} / 枚举名。
     *
     * @return 无法识别时返回 {@code null}（命令据此报错，不静默回落）
     */
    public static GoStyle byToken(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "a", "kaya" -> KAYA;
            case "c", "rosewood" -> ROSEWOOD;
            default -> null;
        };
    }
}

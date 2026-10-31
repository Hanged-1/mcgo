package com.dsh.goweiqi.game;

/**
 * 对局阶段。byte id 用于网络载荷（GoStatePayload.phase）。
 */
public enum GamePhase {

    EMPTY((byte) 0),
    WAITING((byte) 1),
    PLAYING((byte) 2),
    SCORING((byte) 3),
    FINISHED((byte) 4);

    private final byte id;

    GamePhase(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }

    public static GamePhase byId(byte id) {
        for (GamePhase phase : values()) {
            if (phase.id == id) {
                return phase;
            }
        }
        return EMPTY;
    }
}

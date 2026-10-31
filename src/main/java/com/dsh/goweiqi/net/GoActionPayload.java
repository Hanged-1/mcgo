package com.dsh.goweiqi.net;

import com.dsh.goweiqi.GoWeiqiMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：客户端意图。服务端全量校验，客户端只发「我想干什么」。
 *
 * <p><b>没有任何 S2C 状态包</b>：状态同步完全走原版方块实体包
 * （服务端 {@code setChanged() + sendBlockUpdated} → {@link net.minecraft.world.level.block.entity.BlockEntity#getUpdateTag}），
 * 客户端 BE 在 {@code loadAdditional} 里还原成同一个 {@code GoGame}。少一套状态镜像就少一类失步 bug。</p>
 *
 * @param pos    棋盘<b>原点格</b>坐标（9 格里任意一格由服务端换算出原点）
 * @param action {@link #ACTION_SEAT_BLACK} … {@link #ACTION_SOLO}
 * @param x      棋盘 x（0..18；该动作不需要时为 -1）
 * @param y      棋盘 y（0..18；该动作不需要时为 -1）
 * @param force  仅 {@link #ACTION_TOGGLE_DEAD} 用：跳过「绝对活白名单」的强制覆盖
 */
public record GoActionPayload(BlockPos pos, byte action, int x, int y, boolean force)
        implements CustomPacketPayload {

    /** 入座执黑。 */
    public static final byte ACTION_SEAT_BLACK = 0;
    /** 入座执白。 */
    public static final byte ACTION_SEAT_WHITE = 1;
    /** 落子，用 x/y。 */
    public static final byte ACTION_PLACE = 2;
    /** 停一手。 */
    public static final byte ACTION_PASS = 3;
    /** 认输。 */
    public static final byte ACTION_RESIGN = 4;
    /** 切换整串死活，用 x/y（+ force）。 */
    public static final byte ACTION_TOGGLE_DEAD = 5;
    /** 确认数子结果。 */
    public static final byte ACTION_CONFIRM = 6;
    /** 对数子结果有异议。 */
    public static final byte ACTION_DISPUTE = 7;
    /** 打开界面：请求服务端重发一次方块实体包（首次同步/丢失补偿）。 */
    public static final byte ACTION_OPEN = 8;
    /** 单人模式：自己同时执黑白，直接开局。 */
    public static final byte ACTION_SOLO = 9;
    /** 清盘：重置该棋盘的对局（等价 {@code /go reset}；单人局清盘后自动开新局）。 */
    public static final byte ACTION_RESET = 10;
    /** 悔棋：单人立刻生效；双人变成「待对方确认」。 */
    public static final byte ACTION_UNDO = 11;
    /** 同意对方的悔棋申请（只有对方能点）。 */
    public static final byte ACTION_UNDO_ACCEPT = 12;
    /** 拒绝对方的悔棋申请。 */
    public static final byte ACTION_UNDO_REJECT = 13;
    /** 手动把当前对局导出成 SGF（服务端写盘，回绝对路径给发起者）。 */
    public static final byte ACTION_SAVE_SGF = 14;

    public static final CustomPacketPayload.Type<GoActionPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(GoWeiqiMod.MOD_ID, "go_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GoActionPayload> CODEC = StreamCodec.of(
            (RegistryFriendlyByteBuf buf, GoActionPayload payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeByte(payload.action());
                buf.writeVarInt(payload.x());
                buf.writeVarInt(payload.y());
                buf.writeBoolean(payload.force());
            },
            (RegistryFriendlyByteBuf buf) -> new GoActionPayload(
                    buf.readBlockPos(), buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ------------------------------------------------------------------
    // 工厂（客户端只通过这些构造，避免到处写魔数）
    // ------------------------------------------------------------------

    public static GoActionPayload seatBlack(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_SEAT_BLACK, -1, -1, false);
    }

    public static GoActionPayload seatWhite(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_SEAT_WHITE, -1, -1, false);
    }

    public static GoActionPayload place(BlockPos pos, int x, int y) {
        return new GoActionPayload(pos, ACTION_PLACE, x, y, false);
    }

    public static GoActionPayload pass(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_PASS, -1, -1, false);
    }

    public static GoActionPayload resign(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_RESIGN, -1, -1, false);
    }

    public static GoActionPayload toggleDead(BlockPos pos, int x, int y, boolean force) {
        return new GoActionPayload(pos, ACTION_TOGGLE_DEAD, x, y, force);
    }

    public static GoActionPayload confirm(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_CONFIRM, -1, -1, false);
    }

    public static GoActionPayload dispute(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_DISPUTE, -1, -1, false);
    }

    public static GoActionPayload open(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_OPEN, -1, -1, false);
    }

    /** 单人模式开局（一个玩家同时执黑白）。 */
    public static GoActionPayload solo(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_SOLO, -1, -1, false);
    }

    /** 清盘（重置对局）。 */
    public static GoActionPayload reset(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_RESET, -1, -1, false);
    }

    /** 悔棋（单人立刻生效 / 双人发起申请）。 */
    public static GoActionPayload undo(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_UNDO, -1, -1, false);
    }

    /** 同意悔棋。 */
    public static GoActionPayload undoAccept(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_UNDO_ACCEPT, -1, -1, false);
    }

    /** 拒绝悔棋。 */
    public static GoActionPayload undoReject(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_UNDO_REJECT, -1, -1, false);
    }

    /** 手动导出 SGF 棋谱（服务端写 config/mcgo/sgf/，回绝对路径）。 */
    public static GoActionPayload saveSgf(BlockPos pos) {
        return new GoActionPayload(pos, ACTION_SAVE_SGF, -1, -1, false);
    }
}

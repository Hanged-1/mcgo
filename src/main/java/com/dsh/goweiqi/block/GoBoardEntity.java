package com.dsh.goweiqi.block;

import com.dsh.goweiqi.GoWeiqiMod;
import com.dsh.goweiqi.SgfService;
import com.dsh.goweiqi.game.GamePhase;
import com.dsh.goweiqi.game.GoGame;
import com.dsh.goweiqi.game.GoStyle;
import com.dsh.goweiqi.rules.Board;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 棋盘方块实体：只挂在原点格（X==0 && Z==0），持有整局状态。
 *
 * <p>1.21.1 的存取签名固定为 {@code saveAdditional/loadAdditional(CompoundTag, HolderLookup.Provider)}。
 * <b>全量落盘</b>走 {@code saveAdditional}，<b>网络同步</b>走 {@link #getUpdateTag(HolderLookup.Provider)}
 * （只含同步必需字段），两者靠 {@code TAG_SYNC} 标记区分。</p>
 *
 * <p>任何状态变更都必须经过 {@link #markDirtyAndBroadcast()}：{@code revision++} + {@code setChanged()}
 * + {@code sendBlockUpdated(UPDATE_CLIENTS)}，由原版把 {@code getUpdateTag()} 发给跟踪该区块的客户端。</p>
 */
public class GoBoardEntity extends BlockEntity {

    /** 提子动画时长（毫秒），同时给出等价的 tick 数供渲染用。 */
    public static final long CAPTURE_ANIM_MS = 300L;
    /** 300ms @ 20tps。 */
    public static final int CAPTURE_ANIM_TICKS = 6;

    /**
     * 一次提子动画。{@code startTick} 是 {@link Level#getGameTime()} 的 tick 值，
     * 渲染端用 {@code (gameTime + partialTick - startTick) / CAPTURE_ANIM_TICKS} 求进度。
     */
    public record Removal(int x, int y, byte color, long startTick) {
    }

    /** 同步快照标记键：{@code getUpdateTag} 只写同步字段，{@code loadAdditional} 据此分支。 */
    private static final String TAG_SYNC = "sync";

    private GoGame game = new GoGame();
    private int revision = 0;
    /** 提子动画队列（时间由 world gameTime 推算，不入 NBT）。 */
    private final List<Removal> removals = new ArrayList<>();
    /**
     * 瞬态（不入 NBT）：本次终局自动导出 SGF 已经失败过。
     *
     * <p>{@link #markDirtyAndBroadcast()} 会被频繁调用，写盘失败时若不做标记，
     * 每次状态变更都会重试并刷一条 warn。失败即放弃，交给玩家手动「保存棋谱」兜底。</p>
     */
    private boolean sgfAutoSaveFailed;

    public GoBoardEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.GO_BOARD_ENTITY, pos, state);
    }

    // ------------------------------------------------------------------
    // 状态访问
    // ------------------------------------------------------------------

    public GoGame game() {
        return game;
    }

    /** 换一整局（/go reset、反序列化用）；会 bump revision。 */
    public void setGame(GoGame game) {
        this.game = game == null ? new GoGame() : game;
        this.removals.clear();
        this.sgfAutoSaveFailed = false;
        markDirtyAndBroadcast();
    }

    public GoStyle style() {
        return game.style();
    }

    public void style(GoStyle style) {
        game.style(style);
        markDirtyAndBroadcast();
    }

    public int revision() {
        return revision;
    }

    /**
     * 提子动画队列（只读视图）。本版已取消提子动画，队列恒为空；
     * 保留只为旧同步包/旧存档的兼容读取（那段数据仍能被解析，不会炸）。
     */
    public List<Removal> removals() {
        return Collections.unmodifiableList(removals);
    }

    /**
     * 已停用：取消提子动画后不再入队（被提的子直接从盘面消失）。
     *
     * <p>方法签名保留给旧调用点与兼容读取；{@code removals()} / {@link #removalProgress} 与
     * NBT/同步里的那段数据仍可解析。</p>
     */
    public void addRemoval(int x, int y, byte color, long startTick) {
        // no-op：不做提子动画。
    }

    /** 已停用：一次落子提多个子也不再生效（保留签名兼容）。 */
    public void addRemovals(int[] indices, byte color, long startTick) {
        // no-op：不做提子动画。
    }

    public void clearRemovals() {
        removals.clear();
    }

    /** 提子动画进度 0..1（渲染端每帧调用）。 */
    public float removalProgress(Removal removal, float partialTick) {
        if (removal == null) {
            return 1.0F;
        }
        long now = level == null ? removal.startTick() : level.getGameTime();
        float elapsed = (now + partialTick) - removal.startTick();
        return Mth.clamp(elapsed / CAPTURE_ANIM_TICKS, 0.0F, 1.0F);
    }

    // ------------------------------------------------------------------
    // 变更 / tick
    // ------------------------------------------------------------------

    /**
     * 状态变更统一入口：{@code revision++} + {@code setChanged()} + 向正在跟踪该区块的客户端
     * 重发原版方块实体包（{@link #getUpdateTag} 的内容）。
     *
     * <p><b>这是本 mod 唯一的服务端→客户端状态通道</b>：不另造 S2C 状态包，
     * 因此不存在「状态镜像失步」这一类 bug。</p>
     */
    public void markDirtyAndBroadcast() {
        this.revision++;
        setChanged();
        // 先同步状态、再做可能很慢的磁盘 I/O：写盘再慢也不会让客户端晚收到这一帧。
        broadcastState();
        autoSaveSgfIfNeeded();
    }

    /**
     * 终局自动导出 SGF（只在服务端生效）。
     *
     * <p>触发条件：{@code phase == FINISHED && !game.sgfSaved()}。成功 → {@code game.setSgfSaved(true)}；
     * 失败 → 记 {@link #sgfAutoSaveFailed} + 一条 warn，<b>绝不让异常冒出去</b>
     * （写盘失败不能卡 tick，更不能让客户端收不到最新的方块实体包）。</p>
     *
     * <p>防重复三重：{@code sgfSaved}（跨重启，进 NBT）、{@code sgfAutoSaveFailed}（本次会话失败后不再重试）、
     * 以及本方法只在状态变更时跑（不是每 tick）。</p>
     */
    private void autoSaveSgfIfNeeded() {
        if (level == null || level.isClientSide || sgfAutoSaveFailed) {
            return;
        }
        GoGame current = this.game;
        if (current == null || current.phase() != GamePhase.FINISHED || current.sgfSaved()) {
            return;
        }
        try {
            SgfService.save(level.getServer(), current, true);
            current.setSgfSaved(true);
        } catch (Exception ex) {
            sgfAutoSaveFailed = true;
            GoWeiqiMod.LOGGER.warn("[mcgo] 终局 SGF 自动保存失败（对局不受影响，可手动保存）", ex);
        }
    }

    /**
     * 只重发一次方块实体包（不 bump revision、不弄脏存档）。
     * 用于玩家打开界面时补偿同步，以及 {@link #markDirtyAndBroadcast()}。
     */
    public void broadcastState() {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    /** {@link #markDirtyAndBroadcast()} 的旧名字，保留给已有调用点。 */
    public void markChanged() {
        markDirtyAndBroadcast();
    }

    /** 双端可跑：清理超过 {@link #CAPTURE_ANIM_TICKS} 的提子动画项。 */
    public static void tick(Level level, BlockPos pos, BlockState state, GoBoardEntity entity) {
        if (entity == null) {
            return;
        }
        // 提子动画的推进由 world gameTime 推算（removalProgress），tick 只负责剔除过期项。
        if (!entity.removals.isEmpty()) {
            entity.removals.removeIf(removal -> entity.removalProgress(removal, 0.0F) >= 1.0F);
        }
        // 服务端：数子阶段 5 分钟无操作 → 强制结算（客户端不算，只按状态渲染）。
        if (!level.isClientSide && entity.game.phase() == GamePhase.SCORING) {
            if (entity.game.tickScoringTimeout(level.getGameTime())) {
                entity.markDirtyAndBroadcast();
            }
        }
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("revision", revision);
        // 兼容旧键：style 同时平铺一份，整局数据在 "game" 下。
        tag.putByte("style", (byte) game.style().ordinal());
        tag.put("game", game.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.getBoolean(TAG_SYNC)) {
            // 客户端：从原版方块实体包还原即时状态，不碰落盘格式。
            revision = tag.getInt("revision");
            game.readSync(tag);
            removals.clear();
            long now = level == null ? 0L : level.getGameTime();
            int[] flat = tag.getIntArray("removals");
            for (int i = 0; i + 2 < flat.length; i += 3) {
                addRemoval(flat[i], flat[i + 1], (byte) flat[i + 2], now);
            }
            return;
        }
        revision = tag.getInt("revision");
        if (tag.contains("game")) {
            game = GoGame.load(tag.getCompound("game"), registries);
        } else if (tag.contains("style")) {
            GoStyle[] styles = GoStyle.values();
            int ordinal = Math.max(0, Math.min(styles.length - 1, tag.getByte("style")));
            game = new GoGame();
            game.style(styles[ordinal]);
        }
        removals.clear();
    }

    /**
     * 同步包内容：只放渲染/交互必需的字段（见 {@link GoGame#writeSync}）+ 最近一次提子的坐标，
     * 让客户端能接住提子动画。**不放** {@code koHistory} 等落盘字段。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_SYNC, true);
        tag.putInt("revision", revision);
        game.writeSync(tag);
        if (!removals.isEmpty()) {
            int[] flat = new int[removals.size() * 3];
            int i = 0;
            for (Removal removal : removals) {
                flat[i++] = removal.x();
                flat[i++] = removal.y();
                flat[i++] = removal.color();
            }
            tag.putIntArray("removals", flat);
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

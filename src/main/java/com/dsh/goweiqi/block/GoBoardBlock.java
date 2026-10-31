package com.dsh.goweiqi.block;

import com.dsh.goweiqi.game.GamePhase;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 3×3 棋盘台面的单格方块。X/Z 表示它在这 9 格里的偏移（按世界轴对齐，不做 FACING），
 * 形状 0.25 格高（低于 0.6 自动跨步，可直接走上去）。BE 只挂在原点格。
 *
 * <p>整盘拆除：任意一格被拆 → 其余 8 格一起消失 + 掉 1 个棋盘物品；
 * 对局进行中（{@code PLAYING}/{@code SCORING}）拒绝拆除。真正的“取消拆除”由
 * {@code PlayerBlockBreakEvents.BEFORE} 承担（{@code playerWillDestroy} 无法阻止后续 removeBlock）。</p>
 */
public class GoBoardBlock extends Block implements EntityBlock {

    /** 台面边长（格）。 */
    public static final int BOARD_SIZE = 3;

    public static final IntegerProperty X = IntegerProperty.create("x", 0, BOARD_SIZE - 1);
    public static final IntegerProperty Z = IntegerProperty.create("z", 0, BOARD_SIZE - 1);

    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 4.0D, 16.0D);

    public static final MapCodec<GoBoardBlock> CODEC = simpleCodec(GoBoardBlock::new);

    public GoBoardBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(X, Z);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // 只有原点角（X==0 && Z==0）持有 BE；其余 8 格返回 null，全部数据从原点取。
        if (state.getValue(X) == 0 && state.getValue(Z) == 0) {
            return new GoBoardEntity(pos, state);
        }
        return null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> blockEntityType) {
        return (tickLevel, pos, tickState, blockEntity) ->
                GoBoardEntity.tick(tickLevel, pos, tickState, (GoBoardEntity) blockEntity);
    }

    // ------------------------------------------------------------------
    // 原点 / BE 定位
    // ------------------------------------------------------------------

    /** 原点格坐标：{@code pos.offset(-X, 0, -Z)}。 */
    public static BlockPos originOf(BlockState state, BlockPos pos) {
        return pos.offset(-state.getValue(X), 0, -state.getValue(Z));
    }

    /**
     * 给定棋盘上任意一格，返回原点格的 BE（不是棋盘 / 没有 BE 时返回 null）。
     * 命令、网络、渲染都从原点取数据。
     */
    public static GoBoardEntity entityOf(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof GoBoardBlock)) {
            return null;
        }
        BlockPos origin = originOf(state, pos);
        return level.getBlockEntity(origin) instanceof GoBoardEntity entity ? entity : null;
    }

    /** 该格所在棋盘是否正在对弈（PLAYING / SCORING）。 */
    public static boolean isGameRunning(Level level, BlockPos pos) {
        GoBoardEntity entity = entityOf(level, pos);
        if (entity == null) {
            return false;
        }
        GamePhase phase = entity.game().phase();
        return phase == GamePhase.PLAYING || phase == GamePhase.SCORING;
    }

    // ------------------------------------------------------------------
    // 交互 / 拆除
    // ------------------------------------------------------------------

    /**
     * 空手右击棋盘：**双端都返回 {@link InteractionResult#SUCCESS}**。
     *
     * <p>界面本身在客户端侧由 {@code UseBlockCallback} 打开（主源码集不能出现 client 类）。
     * 这里必须返回 SUCCESS 而不是 PASS，否则 1.21.1 的客户端交互链不会走到 Fabric 的
     * {@code UseBlockCallback}（方块交互提前判定为"未处理"），右击就开不了界面。</p>
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        return InteractionResult.SUCCESS;
    }

    /**
     * 拆除整盘。任意一格被拆 → 其余 8 格一起移除，并在原点掉 1 个棋盘物品。
     *
     * <p>对局进行中只提示不拆除；注意本方法<b>不能</b>取消后续的 removeBlock，
     * 真正拦截靠 {@code PlayerBlockBreakEvents.BEFORE}（见 {@code GoWeiqiMod}）。</p>
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            if (isGameRunning(level, pos)) {
                player.displayClientMessage(Component.translatable("message.mcgo.board_busy"), true);
                return state;
            }
            BlockPos origin = originOf(state, pos);
            for (int dx = 0; dx < BOARD_SIZE; dx++) {
                for (int dz = 0; dz < BOARD_SIZE; dz++) {
                    BlockPos other = origin.offset(dx, 0, dz);
                    if (other.equals(pos)) {
                        continue;
                    }
                    if (level.getBlockState(other).getBlock() == this) {
                        level.removeBlock(other, false);
                    }
                }
            }
            if (!player.isCreative()) {
                Block.popResource(level, origin, new ItemStack(ModBlocks.GO_BOARD_ITEM));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}

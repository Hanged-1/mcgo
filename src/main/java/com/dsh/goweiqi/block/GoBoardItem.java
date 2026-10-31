package com.dsh.goweiqi.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 棋盘方块物品：一次铺满 3×3，而不是只放单格。
 *
 * <p>服务端（客户端做同样的预演）校验 3×3 全部格可替换且在世界范围内，
 * 通过后一次性放置 9 格（原点格带 BE），失败则提示且不消耗物品。</p>
 */
public class GoBoardItem extends BlockItem {

    public GoBoardItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        Player player = context.getPlayer();

        if (!canPlaceBoard(level, origin)) {
            if (!level.isClientSide && player != null) {
                player.displayClientMessage(Component.translatable("message.mcgo.no_space"), true);
            }
            return InteractionResult.FAIL;
        }

        for (int dx = 0; dx < GoBoardBlock.BOARD_SIZE; dx++) {
            for (int dz = 0; dz < GoBoardBlock.BOARD_SIZE; dz++) {
                BlockPos target = origin.offset(dx, 0, dz);
                BlockState state = ModBlocks.GO_BOARD.defaultBlockState()
                        .setValue(GoBoardBlock.X, dx)
                        .setValue(GoBoardBlock.Z, dz);
                level.setBlock(target, state, Block.UPDATE_ALL);
            }
        }

        if (!level.isClientSide) {
            ItemStack stack = context.getItemInHand();
            if (player == null || !player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            level.playSound(null, origin, ModBlocks.GO_BOARD.defaultBlockState().getSoundType().getPlaceSound(),
                    SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 3×3 是否全部可替换（同维度天然成立：都在同一个 {@link Level} 里）。 */
    private static boolean canPlaceBoard(Level level, BlockPos origin) {
        for (int dx = 0; dx < GoBoardBlock.BOARD_SIZE; dx++) {
            for (int dz = 0; dz < GoBoardBlock.BOARD_SIZE; dz++) {
                BlockPos target = origin.offset(dx, 0, dz);
                if (!level.isInWorldBounds(target)) {
                    return false;
                }
                if (!level.getBlockState(target).canBeReplaced()) {
                    return false;
                }
            }
        }
        return true;
    }
}

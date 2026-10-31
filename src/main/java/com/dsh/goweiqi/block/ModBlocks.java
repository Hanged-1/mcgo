package com.dsh.goweiqi.block;

import com.dsh.goweiqi.GoWeiqiMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/**
 * Block / BlockEntityType / BlockItem 的注册与 ResourceKey。
 * 静态字段在类加载时完成注册；{@link #register()} 只需触发类加载。
 */
public final class ModBlocks {

    // 1.21.1 的 ResourceLocation(String,String) 构造器是 private，必须用工厂方法。
    public static final ResourceLocation BOARD_ID =
            ResourceLocation.fromNamespaceAndPath(GoWeiqiMod.MOD_ID, "board");

    public static final ResourceKey<Block> BOARD_BLOCK_KEY =
            ResourceKey.create(Registries.BLOCK, BOARD_ID);
    public static final ResourceKey<BlockEntityType<?>> BOARD_BE_KEY =
            ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, BOARD_ID);
    public static final ResourceKey<Item> BOARD_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, BOARD_ID);

    /** 3×3 台面：抗爆 1200（strength 第二参数），可被走上去（0.25 格高），活塞推不动。 */
    public static final Block GO_BOARD = Registry.register(BuiltInRegistries.BLOCK, BOARD_BLOCK_KEY,
            new GoBoardBlock(BlockBehaviour.Properties.of()
                    .strength(2.0F, 1200.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));

    public static final Item GO_BOARD_ITEM = Registry.register(BuiltInRegistries.ITEM, BOARD_ITEM_KEY,
            new GoBoardItem(GO_BOARD, new Item.Properties()));

    public static final BlockEntityType<GoBoardEntity> GO_BOARD_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, BOARD_ID,
            BlockEntityType.Builder.of(GoBoardEntity::new, GO_BOARD).build());

    private ModBlocks() {
    }

    /** 由 {@link GoWeiqiMod#onInitialize()} 调用；只为显式触发本类的静态初始化。 */
    public static void register() {
        GoWeiqiMod.LOGGER.debug("[mcgo] 方块/物品/方块实体已注册：{}", BOARD_ID);
    }
}

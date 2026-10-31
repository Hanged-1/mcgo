package com.dsh.goweiqi.client;

import com.dsh.goweiqi.block.GoBoardBlock;
import com.dsh.goweiqi.block.GoBoardEntity;
import com.dsh.goweiqi.block.ModBlocks;
import com.dsh.goweiqi.game.GamePhase;
import com.dsh.goweiqi.game.GoGame;
import com.dsh.goweiqi.net.GoActionPayload;
import com.dsh.goweiqi.rules.Board;
import com.dsh.goweiqi.rules.StoneColor;

import java.util.UUID;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 客户端入口：只允许引用客户端专用类（src/client/java，由 splitEnvironmentSourceSets 隔离）。
 * 主入口 {@code GoWeiqiMod#onInitialize()} 绝不能引用本类或本包下任何类。
 *
 * <p>承担四件事：BER 注册、右击交互的<b>两条落子通道</b>、每 tick 维护世界内幽灵子、断线清理。</p>
 *
 * <p>两条通道的分工（见 PLAN §4）：</p>
 * <ul>
 *   <li><b>空手右击</b> → 打开 19 路 GUI（主通道：点得准，有悬浮高亮和按钮）</li>
 *   <li><b>蹲下 + 空手右击</b> → 直接在世界里落子（沉浸通道：不打断视角，靠幽灵子预览）</li>
 * </ul>
 * <p>客户端只发意图，落子是否合法一律由服务端裁决。</p>
 */
public class GoWeiqiClient implements ClientModInitializer {

    /** 幽灵子 / 世界内落子的拾取距离（与服务端动作距离守卫对齐）。 */
    private static final double GHOST_PICK_DISTANCE = 6.0D;

    @Override
    public void onInitializeClient() {
        // BER 只挂在原点格（服务端未从世界读取过）——按 BlockEntityType 注册即可。
        BlockEntityRendererRegistry.register(ModBlocks.GO_BOARD_ENTITY, GoBoardRenderer::new);
        // 空手右击棋盘 → 开 GUI 或世界内落子（方块侧 useWithoutItem 已返回 SUCCESS，否则这里不会触发）
        UseBlockCallback.EVENT.register(GoWeiqiClient::onUseBlock);
        ClientTickEvents.END_CLIENT_TICK.register(GoWeiqiClient::onEndClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> GoBoardRenderer.clearGhost());
    }

    // ------------------------------------------------------------------
    // 右击：两条通道
    // ------------------------------------------------------------------

    /**
     * 只在<b>客户端</b>处理并把交互结果置为 SUCCESS（短路后续的手部摆动 / 物品使用），
     * 服务端返回 PASS，交给方块自身的 {@code useWithoutItem}（返回 SUCCESS，保持双端一致）。
     *
     * <p>主手必须为空——拿着东西右击不抢原版用途（放置方块等）。</p>
     */
    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
                                                BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND || player == null || level == null || hitResult == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            return InteractionResult.PASS;
        }
        // 主手必须为空：拿着东西右击不抢原版用途（放置方块等）。
        if (!player.getItemInHand(hand).isEmpty()) {
            return InteractionResult.PASS;
        }
        BlockPos hitPos = hitResult.getBlockPos();
        BlockState state = level.getBlockState(hitPos);
        if (!(state.getBlock() instanceof GoBoardBlock)) {
            return InteractionResult.PASS;
        }
        BlockPos origin = GoBoardBlock.originOf(state, hitPos);

        // 通道二：蹲下 + 空手 = 直接在世界里落子。
        if (player.isShiftKeyDown()) {
            int[] point = pickPoint(origin, hitResult);
            if (point == null) {
                // 点在木边或盘外：什么都不做，也别顺手把界面弹出来。
                return InteractionResult.FAIL;
            }
            ClientPlayNetworking.send(GoActionPayload.place(origin, point[0], point[1]));
            return InteractionResult.SUCCESS;
        }

        // 通道一：空手 = 打开 19 路 GUI。
        Minecraft.getInstance().setScreen(new GoBoardScreen(origin));
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 坐标换算（GUI 与射线共用同一套：台面是平的，命中点比例直接映射到交叉点）
    // ------------------------------------------------------------------

    /**
     * 把一次右键命中换算成最近的交叉点。
     *
     * <p>只认台面朝上的那一面（侧面点出来的交点没有意义）；盘外木边返回 {@code null}。</p>
     *
     * @return {@code {x, y}}（0..18），非法时为 {@code null}
     */
    private static int[] pickPoint(BlockPos origin, BlockHitResult hit) {
        if (hit.getDirection() != Direction.UP) {
            return null;
        }
        Vec3 location = hit.getLocation();
        int x = Math.round((float) (location.x - origin.getX() - BoardGeo.MARGIN) / BoardGeo.SPACING);
        int y = Math.round((float) (location.z - origin.getZ() - BoardGeo.MARGIN) / BoardGeo.SPACING);
        if (Board.index(x, y) < 0) {
            return null;
        }
        return new int[]{x, y};
    }

    // ------------------------------------------------------------------
    // 幽灵子（每 tick 按准星更新；界面打开时清空）
    // ------------------------------------------------------------------

    private static void onEndClientTick(Minecraft client) {
        if (client == null || client.screen != null || client.level == null || client.player == null) {
            GoBoardRenderer.clearGhost();
            return;
        }
        updateGhost(client);
    }

    private static void updateGhost(Minecraft client) {
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            GoBoardRenderer.clearGhost();
            return;
        }
        ClientLevel level = client.level;
        BlockPos hitPos = blockHit.getBlockPos();
        BlockState state = level.getBlockState(hitPos);
        if (!(state.getBlock() instanceof GoBoardBlock)) {
            GoBoardRenderer.clearGhost();
            return;
        }
        if (client.player.getEyePosition().distanceToSqr(blockHit.getLocation()) > GHOST_PICK_DISTANCE * GHOST_PICK_DISTANCE) {
            GoBoardRenderer.clearGhost();
            return;
        }
        BlockPos origin = GoBoardBlock.originOf(state, hitPos);
        int[] point = pickPoint(origin, blockHit);
        if (point == null) {
            GoBoardRenderer.clearGhost();
            return;
        }
        GoBoardEntity entity = GoBoardBlock.entityOf(level, hitPos);
        if (entity == null) {
            GoBoardRenderer.clearGhost();
            return;
        }
        GoGame game = entity.game();
        // 只在对局进行中、且轮到本地玩家时显示预览子。
        if (game.phase() != GamePhase.PLAYING) {
            GoBoardRenderer.clearGhost();
            return;
        }
        UUID uuid = client.player.getUUID();
        byte color = game.colorOf(uuid);
        if (color == StoneColor.EMPTY || color != game.toMove()) {
            GoBoardRenderer.clearGhost();
            return;
        }
        int index = Board.index(point[0], point[1]);
        if (index < 0 || game.cells()[index] != StoneColor.EMPTY) {
            GoBoardRenderer.clearGhost();
            return;
        }
        GoBoardRenderer.setGhost(origin, point[0], point[1], color);
    }
}

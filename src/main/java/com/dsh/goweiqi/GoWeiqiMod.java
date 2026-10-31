package com.dsh.goweiqi;

import com.dsh.goweiqi.block.GoBoardBlock;
import com.dsh.goweiqi.block.GoBoardEntity;
import com.dsh.goweiqi.block.ModBlocks;
import com.dsh.goweiqi.game.GamePhase;
import com.dsh.goweiqi.game.GoGame;
import com.dsh.goweiqi.game.GoStyle;
import com.dsh.goweiqi.net.GoNetworking;
import com.dsh.goweiqi.rules.Board;
import com.dsh.goweiqi.rules.ScoreResult;
import com.dsh.goweiqi.rules.StoneColor;
import com.dsh.goweiqi.sound.ModSounds;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主入口（双端）：只做注册调度，不碰任何客户端类。
 * 客户端专用逻辑见 {@code com.dsh.goweiqi.client.GoWeiqiClient}（src/client/java）。
 */
public class GoWeiqiMod implements ModInitializer {

    public static final String MOD_ID = "mcgo";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 命令找棋盘时视线射线的最大距离（格）。 */
    private static final double BOARD_PICK_DISTANCE = 6.0D;

    @Override
    public void onInitialize() {
        // 触发 ModSounds 类加载：静态字段初始化时把两个 SoundEvent 注册进 BuiltInRegistries。
        ModSounds.register();
        ModBlocks.register();
        // 放进创造模式物品栏「功能方块」：测试时直接拿，不用打 /give。
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(entries -> entries.accept(ModBlocks.GO_BOARD_ITEM));
        GoNetworking.register();
        registerCommands();
        registerEvents();
        LOGGER.info("[mcgo] 已加载（规则引擎 + 世界层 + /go 调试命令）");
    }

    // ------------------------------------------------------------------
    // 事件：对局进行中禁止拆除棋盘
    // ------------------------------------------------------------------

    private static void registerEvents() {
        // playerWillDestroy 无法取消后续的 removeBlock，真正的拦截必须放在这里（生存/创造都生效）。
        PlayerBlockBreakEvents.BEFORE.register((Level level, Player player, BlockPos pos, BlockState state,
                                                BlockEntity blockEntity) -> {
            if (level.isClientSide) {
                return true;
            }
            if (!(state.getBlock() instanceof GoBoardBlock)) {
                return true;
            }
            GoBoardEntity entity = GoBoardBlock.entityOf(level, pos);
            if (entity == null) {
                return true;
            }
            GamePhase phase = entity.game().phase();
            if (phase == GamePhase.PLAYING || phase == GamePhase.SCORING) {
                player.displayClientMessage(Component.translatable("message.mcgo.board_busy"), true);
                return false;
            }
            return true;
        });
    }

    // ------------------------------------------------------------------
    // 调试命令：/go place|pass|resign|reset|style|info
    // ------------------------------------------------------------------

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("go")
                        .then(Commands.literal("place")
                                .then(Commands.argument("x", IntegerArgumentType.integer(0, Board.SIZE - 1))
                                        .then(Commands.argument("y", IntegerArgumentType.integer(0, Board.SIZE - 1))
                                                .then(Commands.argument("color", StringArgumentType.word())
                                                        .executes(GoWeiqiMod::commandPlace)))))
                        .then(Commands.literal("pass")
                                .then(Commands.argument("color", StringArgumentType.word())
                                        .executes(GoWeiqiMod::commandPass)))
                        .then(Commands.literal("resign")
                                .then(Commands.argument("color", StringArgumentType.word())
                                        .executes(GoWeiqiMod::commandResign)))
                        .then(Commands.literal("seat")
                                .then(Commands.argument("color", StringArgumentType.word())
                                        .executes(GoWeiqiMod::commandSeat)))
                        .then(Commands.literal("force").executes(GoWeiqiMod::commandForce))
                        .then(Commands.literal("reset").executes(GoWeiqiMod::commandReset))
                        .then(Commands.literal("style")
                                .then(Commands.argument("style", StringArgumentType.word())
                                        .executes(GoWeiqiMod::commandStyle)))
                        .then(Commands.literal("info").executes(GoWeiqiMod::commandInfo))));
    }

    private static int commandPlace(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_player"));
            return 0;
        }
        GoBoardEntity board = findBoard(player);
        if (board == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_board"));
            return 0;
        }
        byte color = parseColor(StringArgumentType.getString(context, "color"));
        if (color == StoneColor.EMPTY) {
            source.sendFailure(Component.translatable("command.mcgo.bad_color"));
            return 0;
        }
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        GoGame game = board.game();
        GoGame.PlaceResult result = game.place(color, x, y);
        if (!result.ok()) {
            source.sendFailure(Component.translatable("command.mcgo.place_failed", result.status().name()));
            return 0;
        }
        board.markDirtyAndBroadcast();
        source.sendSuccess(() -> Component.translatable("command.mcgo.place_ok",
                colorName(color), x, y, result.captured().length), false);
        return 1;
    }

    private static int commandPass(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_player"));
            return 0;
        }
        GoBoardEntity board = findBoard(player);
        if (board == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_board"));
            return 0;
        }
        byte color = parseColor(StringArgumentType.getString(context, "color"));
        if (color == StoneColor.EMPTY) {
            source.sendFailure(Component.translatable("command.mcgo.bad_color"));
            return 0;
        }
        GoGame game = board.game();
        if (!game.pass(color)) {
            source.sendFailure(Component.translatable("command.mcgo.pass_failed",
                    game.phase().name(), colorName(game.toMove())));
            return 0;
        }
        board.markDirtyAndBroadcast();
        source.sendSuccess(() -> Component.translatable("command.mcgo.pass_ok",
                colorName(color), game.phase().name()), false);
        return 1;
    }

    private static int commandResign(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_player"));
            return 0;
        }
        GoBoardEntity board = findBoard(player);
        if (board == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_board"));
            return 0;
        }
        byte color = parseColor(StringArgumentType.getString(context, "color"));
        if (color == StoneColor.EMPTY) {
            source.sendFailure(Component.translatable("command.mcgo.bad_color"));
            return 0;
        }
        GoGame game = board.game();
        if (!game.resign(color)) {
            source.sendFailure(Component.translatable("command.mcgo.resign_failed", game.phase().name()));
            return 0;
        }
        board.markDirtyAndBroadcast();
        source.sendSuccess(() -> Component.translatable("command.mcgo.resign_ok", colorName(color), game.result()), false);
        return 1;
    }

    private static int commandSeat(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_player"));
            return 0;
        }
        GoBoardEntity board = findBoard(player);
        if (board == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_board"));
            return 0;
        }
        byte color = parseColor(StringArgumentType.getString(context, "color"));
        if (color == StoneColor.EMPTY) {
            source.sendFailure(Component.translatable("command.mcgo.bad_color"));
            return 0;
        }
        GoGame game = board.game();
        GoGame.SeatResult result = game.seat(player.getUUID(), color);
        if (result != GoGame.SeatResult.OK) {
            source.sendFailure(Component.translatable("command.mcgo.seat_failed", result.name()));
            return 0;
        }
        board.markDirtyAndBroadcast();
        source.sendSuccess(() -> Component.translatable("command.mcgo.seat_ok",
                colorName(color), game.phase().name()), false);
        return 1;
    }

    private static int commandForce(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_player"));
            return 0;
        }
        GoBoardEntity board = findBoard(player);
        if (board == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_board"));
            return 0;
        }
        GoGame game = board.game();
        if (game.phase() != GamePhase.SCORING) {
            source.sendFailure(Component.translatable("command.mcgo.force_failed", game.phase().name()));
            return 0;
        }
        game.forceScore();
        board.markDirtyAndBroadcast();
        source.sendSuccess(() -> Component.translatable("command.mcgo.force_ok", game.result()), false);
        return 1;
    }

    private static int commandReset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_player"));
            return 0;
        }
        GoBoardEntity board = findBoard(player);
        if (board == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_board"));
            return 0;
        }
        GoGame fresh = new GoGame();
        fresh.style(board.game().style());
        board.setGame(fresh);
        source.sendSuccess(() -> Component.translatable("command.mcgo.reset_ok"), false);
        return 1;
    }

    private static int commandStyle(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_player"));
            return 0;
        }
        GoBoardEntity board = findBoard(player);
        if (board == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_board"));
            return 0;
        }
        String token = StringArgumentType.getString(context, "style");
        GoStyle style = GoStyle.byToken(token);
        if (style == null) {
            source.sendFailure(Component.translatable("command.mcgo.style_unknown", token));
            return 0;
        }
        board.style(style);
        source.sendSuccess(() -> Component.translatable("command.mcgo.style_ok", style.id()), false);
        return 1;
    }

    private static int commandInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_player"));
            return 0;
        }
        GoBoardEntity board = findBoard(player);
        if (board == null) {
            source.sendFailure(Component.translatable("command.mcgo.no_board"));
            return 0;
        }
        GoGame game = board.game();
        ScoreResult preview = game.scorePreview();
        String resultText = game.result().isEmpty() ? "-" : game.result();
        source.sendSuccess(() -> Component.translatable("command.mcgo.info",
                game.phase().name(), colorName(game.toMove()), game.moveCount(),
                game.blackCaptured(), game.whiteCaptured(), resultText), false);
        source.sendSuccess(() -> Component.translatable("command.mcgo.info_extra",
                board.revision(), preview.blackTotal(), preview.whiteTotal(), preview.dame(),
                game.style().id()), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 找棋盘 / 小工具
    // ------------------------------------------------------------------

    /**
     * 找“玩家正在看的那个棋盘”：先按视线射线取方块，再回落到脚下 3×3×2 范围内。
     * 任意一格都会被换算出原点格再取 BE。
     */
    private static GoBoardEntity findBoard(ServerPlayer player) {
        HitResult hit = player.pick(BOARD_PICK_DISTANCE, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit) {
            GoBoardEntity entity = GoBoardBlock.entityOf(player.serverLevel(), blockHit.getBlockPos());
            if (entity != null) {
                return entity;
            }
        }
        BlockPos base = player.blockPosition();
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    GoBoardEntity entity = GoBoardBlock.entityOf(player.serverLevel(), base.offset(dx, dy, dz));
                    if (entity != null) {
                        return entity;
                    }
                }
            }
        }
        return null;
    }

    private static byte parseColor(String token) {
        if (token == null) {
            return StoneColor.EMPTY;
        }
        return switch (token.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "b", "black", "黑" -> StoneColor.BLACK;
            case "w", "white", "白" -> StoneColor.WHITE;
            default -> StoneColor.EMPTY;
        };
    }

    private static Component colorName(byte color) {
        return switch (color) {
            case StoneColor.BLACK -> Component.translatable("color.mcgo.black");
            case StoneColor.WHITE -> Component.translatable("color.mcgo.white");
            default -> Component.translatable("color.mcgo.empty");
        };
    }
}

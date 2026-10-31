package com.dsh.goweiqi;

import com.dsh.goweiqi.game.GoGame;
import com.dsh.goweiqi.game.SgfWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * SGF 落盘服务（<b>服务端权威</b>，主源码集）。
 *
 * <p>目录：{@code config/mcgo/sgf}（缺失自动创建）；
 * 文件名：{@code yyyyMMdd-HHmmss-<黑>vs<白>.sgf}，玩家名做安全化，重名自动加 {@code -2} / {@code -3}…
 * 写入一律 UTF-8。</p>
 *
 * <p>本类只做「拼 + 写」，不做任何对局校验：调用方（{@code GoNetworking} 手动保存 /
 * {@code GoBoardEntity} 终局自动保存）自己决定什么时候调。</p>
 */
public final class SgfService {

    /** 配置目录下的 mod 子目录。 */
    public static final String CONFIG_DIR_NAME = "mcgo";
    /** 配置目录下的棋谱子目录。 */
    public static final String SGF_DIR_NAME = "sgf";

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 文件名里玩家名的最大长度（防止超长文件名踩 Windows 路径上限）。 */
    private static final int MAX_NAME_IN_FILENAME = 24;
    /** 同秒重名的最大尝试次数（-2 … -99）。 */
    private static final int MAX_DUPLICATE_TRIES = 99;
    /** 未入座的座位显示名。 */
    private static final String UNSEATED_NAME = "未入座";
    /** UUID 兜底名的长度。 */
    private static final int UUID_PREFIX_LEN = 8;

    private SgfService() {
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 棋谱目录（不保证已存在）。 */
    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(CONFIG_DIR_NAME)
                .resolve(SGF_DIR_NAME);
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    /**
     * 把整局写成一份 SGF。
     *
     * <p>写盘失败一律抛 {@link IOException}（含目录建不出来、文件被占、磁盘满），
     * 由调用方决定「回错误消息」还是「静默降级」——本类绝不吞异常，也绝不碰对局状态。</p>
     *
     * @param server 服务端（用来把 UUID 解析成在线玩家名；可传 null，退化成 UUID 前缀）
     * @param game   对局
     * @param auto   true = 终局自动保存（只用于日志/语义区分，不影响内容）
     * @return 实际写入的文件（绝对路径）
     */
    public static Path save(MinecraftServer server, GoGame game, boolean auto) throws IOException {
        if (game == null) {
            throw new IOException("game == null");
        }
        LocalDateTime now = LocalDateTime.now();
        Path dir = directory();
        Files.createDirectories(dir);

        String blackName = playerName(server, game.blackPlayer());
        String whiteName = playerName(server, game.whitePlayer());
        String sgf = SgfWriter.toSgf(game, blackName, whiteName,
                now.toLocalDate().toString());
        String base = now.format(FILE_STAMP)
                + "-" + safeFileName(blackName)
                + "vs" + safeFileName(whiteName);

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_DUPLICATE_TRIES; attempt++) {
            String fileName = attempt == 1 ? base + ".sgf" : base + "-" + attempt + ".sgf";
            Path target = dir.resolve(fileName);
            try {
                Files.writeString(target, sgf, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return target.toAbsolutePath();
            } catch (FileAlreadyExistsException ex) {
                // 同一秒内的同名对局 → 试 -2、-3…（CREATE_NEW 保证不会覆盖别人的棋谱）
                last = ex;
            }
        }
        throw last != null ? last : new IOException("无法创建棋谱文件：" + base);
    }

    // ------------------------------------------------------------------
    // 名字
    // ------------------------------------------------------------------

    /**
     * 玩家显示名：在线 → 游戏档案名；离线/未入座 → UUID 前 8 位。
     *
     * @return 永不为 null（未入座是 {@code 未入座}）
     */
    public static String playerName(MinecraftServer server, UUID uuid) {
        if (uuid == null) {
            return UNSEATED_NAME;
        }
        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                String name = player.getGameProfile().getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        }
        String text = uuid.toString();
        return text.substring(0, Math.min(UUID_PREFIX_LEN, text.length()));
    }

    /**
     * 文件名安全化：去掉 {@code \ / : * ? " < > |} 与控制字符，空白统一变 {@code _}，
     * 再截到 {@link #MAX_NAME_IN_FILENAME}；全空的退化成 {@code unknown}。
     */
    public static String safeFileName(String name) {
        if (name == null) {
            return "unknown";
        }
        StringBuilder safe = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '\\', '/', ':', '*', '?', '"', '<', '>', '|' -> {
                    // 非法文件名字符：直接丢掉
                }
                default -> {
                    if (Character.isISOControl(c)) {
                        continue;
                    }
                    safe.append(Character.isWhitespace(c) ? '_' : c);
                }
            }
        }
        String result = safe.toString();
        if (result.length() > MAX_NAME_IN_FILENAME) {
            result = result.substring(0, MAX_NAME_IN_FILENAME);
        }
        return result.isEmpty() ? "unknown" : result;
    }
}

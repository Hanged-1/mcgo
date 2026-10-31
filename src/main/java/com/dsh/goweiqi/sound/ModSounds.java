package com.dsh.goweiqi.sound;

import com.dsh.goweiqi.GoWeiqiMod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * 音效注册（双端）。
 *
 * <p>音效文件与事件声明在 {@code assets/mcgo/sounds.json} 与 {@code assets/mcgo/sounds/*.ogg}，
 * 本类只负责把 {@link SoundEvent} 注册进原版 {@code BuiltInRegistries.SOUND_EVENT}。
 * 由 {@link GoWeiqiMod#onInitialize()} 调用 {@link #register()} 触发类加载（静态字段初始化即注册）。</p>
 *
 * <p>1.21.1 注意：{@link ResourceLocation} 只能走 {@code fromNamespaceAndPath}（构造器本线不可用），
 * 且 {@code setId} 是 1.21.2+ 才要求的，本线不要写。</p>
 */
public final class ModSounds {

    /** 落子音。 */
    public static final SoundEvent STONE = register("stone");
    /** 提子音（一手只播一次，不按被提子数重复）。 */
    public static final SoundEvent CAPTURE = register("capture");

    private ModSounds() {
    }

    private static SoundEvent register(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(GoWeiqiMod.MOD_ID, path);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** 触发类加载（静态字段初始化时完成注册）。 */
    public static void register() {
        // 无实例状态：调用本方法只为确保上面的 register(...) 已执行。
    }
}

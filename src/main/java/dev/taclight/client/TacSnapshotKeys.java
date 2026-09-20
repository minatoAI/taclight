package dev.taclight.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.taclight.TacLightMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * F9 一键调试快照(2026-09-19 最小闭环):与 {@code !snap} / {@code /taclight snap}
 * 调同一 {@link DebugSnapshotter#saveSnapshot} 入口。
 *
 * <p>自含订阅(仿 {@link ClientEvents} 的 ModBus/按键双订阅写法):注册键位 + 消费按键,
 * 不动 ClientEvents。</p>
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT)
public final class TacSnapshotKeys {
    /** F9:一键调试快照(与 L/K/M/N/B 键位互不冲突;原版 F9 无默认绑定)。 */
    public static final KeyMapping SNAPSHOT = new KeyMapping(
            "key.taclight.snapshot",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "key.categories.taclight");

    private TacSnapshotKeys() {}

    @Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(SNAPSHOT);
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        while (SNAPSHOT.consumeClick()) {
            Path dir = DebugSnapshotter.saveSnapshot("key/F9");
            TacLightMod.LOGGER.info("[TacLight] SNAP key/F9 -> {}",
                    dir == null ? "FAILED" : dir.toString());
        }
    }
}

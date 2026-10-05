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

    // 2026-09-26 task-32 v2:这里的嵌套 MOD 总线订阅类已移除 —— SNAPSHOT 的注册统一搬到顶层
    // KeyBindingsModBus(与五个 TacLight 键一起,并打印 "keybind register:" 可观测行)。
    // 原因:复验实测"注册处理器没有产生任何可观测副作用",不再把关键逻辑挂在未验证会被触发的事件上。

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        while (SNAPSHOT.consumeClick()) {
            Path dir = DebugSnapshotter.saveSnapshot("key/F9");
            TacLightMod.LOGGER.info("[TacLight] SNAP key/F9 -> {}",
                    dir == null ? "FAILED" : dir.toString());
            // S4a depth 自举已关(2026-10-06 凌晨 A/B 定案):16:10:47(体素)与
            // 16:11:03(depth fresh,moved=0)同姿态差异证明 depth 跑通了,
            // 但 frozen 形态不适合跟手电移动——每张 F9 强烘骚扰测试,
            // 改回纯快照,depth 只在专门诊断轮手动布防(含中继的测试包)。
        }
    }
}

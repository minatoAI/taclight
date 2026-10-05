package dev.taclight.client;

import dev.taclight.TacLightMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 按键注册器(2026-09-26 task-32 v2)。**顶层类** —— 原来这份逻辑放在
 * {@code ClientEvents.ModBus} / {@code TacSnapshotKeys.ModBus} 两个**嵌套**类里。
 *
 * <p><b>为什么搬到这里</b>:task-34 复验实测 —— 把 {@code key_key.taclight.flashlight_toggle} 写成
 * {@code key.keyboard.u} 后重启,{@code !key list} 仍报代码默认 J,而
 * {@code applySavedKeybindingsOnce()} 里的日志("跑过"的判据)**一次都没出现**;
 * 字节码里确实有那个常量与调用 ⇒ **不是构建没带上**,而是 **该处理器没有产生任何可观测副作用**
 * (其唯一调用点就在嵌套订阅类的 {@code onRegisterKeys} 里)。
 * 机制(= Forge 是否把 MOD 总线事件投递给嵌套的 {@code bus=MOD} 类)**无法从测试侧判定**,
 * 因此修法不押在它上面:① 把注册搬到**顶层**类(本类);② 另加"首客户端 tick 兜底"应用存档值
 * (见 {@code ClientEvents.onClientTick}),两条路径互补。</p>
 *
 * <p><b>可观测行</b>:注册成功即打印 {@code keybind register: registered=N names=...} ——
 * 这是"注册处理器确实跑过"的唯一判据。教训:**"代码写好了" ≠ "代码被执行了"**。</p>
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KeyBindingsModBus {
    private KeyBindingsModBus() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        KeyMapping[] keys = {
                KeyBindings.FLASHLIGHT_TOGGLE,
                KeyBindings.GUNLIGHT_TOGGLE
        };
        for (KeyMapping k : keys) {
            event.register(k);
        }
        TacLightMod.LOGGER.info("[TacLight] keybind register: registered={} names={}",
                keys.length, describe(keys));
    }

    /** 把注册到的键压成一行(诊断用;不含默认键值,避免与"当前绑定"混淆)。 */
    static String describe(KeyMapping[] keys) {
        StringBuilder sb = new StringBuilder();
        for (KeyMapping k : keys) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k.getName());
        }
        return sb.toString();
    }
}

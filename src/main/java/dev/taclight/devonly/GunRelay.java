package dev.taclight.devonly;

import dev.taclight.client.GunControl;

/**
 * <b>中继侧的枪灯操作</b>({@code !gun} 专用)—— <b>纯逻辑、零 Minecraft 依赖</b>。
 *
 * <p><b>2026-09-29 R12c(N1 收尾)</b>:本类的两个方法原本在**发布侧**的 {@code dev.taclight.client.GunControl} 里
 * ({@code parseRelayArg} 与 {@code applyAuto}),但它们**只被 dev-only 的中继 {@code DebugCommandRelay} 调用**
 * ⇒ 在发布件里是**死方法**(审计 `VERDICT §4 N1` 点名 {@code parseRelayArg} 一个,查同类时发现 {@code applyAuto} 同病)。
 * 现整体搬到 {@code dev/taclight/devonly/**}(随发布件剔除)⇒ **发布件的 {@code GunControl.class} 不再含这两个符号**。</p>
 *
 * <p>⚠️ <b>为什么不做进 {@code DebugCommandRelay}</b>:那个类 import 了 Minecraft 类 ⇒ 纯 JVM 契约
 * ({@code GunControlContract}) 一加载它就 {@code NoClassDefFoundError}。原方法之所以放在 {@code GunControl},
 * 正是因为那是"无 MC 依赖、契约可测"的纯逻辑 —— 搬到这里**同时**满足"离开发布件"与"契约仍可测"。</p>
 *
 * <p>状态机本身仍由 {@code GunControl.toggleGunManual()}(M 键,发布侧真在用)与 {@code GunControl.Action} 定义,
 * 本类只是它在中继侧的**入口**。</p>
 */
public final class GunRelay {

    private GunRelay() {}

    /**
     * {@code !gun} 参数决议:未知参=回显不乱动(与 {@code !rec} 无参只回显同规)。
     * 空串/null = 翻转(兼容旧行为)。
     */
    public static GunControl.Action parse(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        switch (a) {
            case "": return GunControl.Action.TOGGLE;
            case "on": return GunControl.Action.ON;
            case "off": return GunControl.Action.OFF;
            case "auto":
            case "probe": return GunControl.Action.AUTO;
            case "status":
            case "state": return GunControl.Action.STATUS;
            default: return GunControl.Action.STATUS;
        }
    }

    /** {@code !gun auto}:清手动旗恢复探针跟随。返回清旗后手动态(恒 false,供回显断言)。 */
    public static boolean applyAuto() {
        dev.taclight.client.ClientLightState.clearGunManual();
        return dev.taclight.client.ClientLightState.gunManual();
    }
}

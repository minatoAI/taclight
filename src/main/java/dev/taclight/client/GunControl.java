package dev.taclight.client;

/**
 * 枪灯手动控制语义(纯逻辑,无 Minecraft 依赖,契约可测)。
 * M 键与 !gun 共用同一状态机:翻转/显式 on-off 均为手动覆写(探针不再覆盖);
 * !gun auto 清手动旗,恢复 tick 探针跟随(主手有灯枪即亮、空手即灭)。
 *
 * <p><b>2026-09-29 R12c(N1 收尾)</b>:中继侧的两个入口({@code parseRelayArg} 与 {@code applyAuto})
 * 原本在这里 —— 类在发布件里、方法却只被 dev-only 的 {@code DebugCommandRelay} 调用 ⇒ 发布件里的**死方法**
 * (审计 {@code VERDICT §4 N1} 点名 {@code parseRelayArg};查同类时 {@code applyAuto} 同病)。
 * 现已整体搬到 {@code dev.taclight.devonly.GunRelay}(随发布件剔除)。
 * 本类只保留**发布侧真在用**的入口({@link #toggleGunManual()},由 {@code ClientEvents} 调)
 * 与 {@link Action} 枚举(状态机语义的一部分,{@code GunRelay} 与契约都引用它)。</p>
 */
public final class GunControl {
    public enum Action { TOGGLE, ON, OFF, AUTO, STATUS }

    /** M 键/翻转:手动覆写取反。返回翻转后状态(供回显与服务端同步)。 */
    public static boolean toggleGunManual() {
        boolean next = !ClientLightState.gunLightOn();
        ClientLightState.setGunLightManual(next);
        return next;
    }

    /** 枪灯 Iris 区块光强度(纯函数,2026-10-06):装上即亮是 bug,必须跟随开关。
     * <p>本地玩家读本机有效值({@code gunLightEffective}=开关×持枪门);
     * 远端玩家读同步真源({@code PlayerLightAccess.gunLight},服务端写的已是有效值),
     * 不能读本机状态否则观察者看不到对端灯态。未装件恒返 0。 */
    public static int gunBlockLightEmission(boolean hasOurLight, boolean isLocal, boolean localEffective, boolean remoteSynced) {
        if (!hasOurLight) return 0;
        boolean on = isLocal ? localEffective : remoteSynced;
        // 2026-10-06:值不再硬编码 —— 与手电共用 LightTuneOverride.heldLevel()
        // (默认 10 的单一真源 = DEFAULT_HELD_LEVEL;/taclight tune held 可覆盖)。
        return on ? dev.taclight.channel.LightTuneOverride.heldLevel() : 0;
    }

    private GunControl() {}
}

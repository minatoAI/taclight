package dev.taclight.client;

/**
 * 枪灯手动控制语义(纯逻辑,无 Minecraft 依赖,契约可测)。
 * M 键与 !gun 共用同一状态机:翻转/显式 on-off 均为手动覆写(探针不再覆盖);
 * !gun auto 清手动旗,恢复 tick 探针跟随(主手有灯枪即亮、空手即灭)。
 */
public final class GunControl {
    public enum Action { TOGGLE, ON, OFF, AUTO, STATUS }

    /** 中继参数决议:未知参=回显不乱动(与 !rec 无参只回显同规)。 */
    public static Action parseRelayArg(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        switch (a) {
            case "": return Action.TOGGLE;
            case "on": return Action.ON;
            case "off": return Action.OFF;
            case "auto":
            case "probe": return Action.AUTO;
            case "status":
            case "state": return Action.STATUS;
            default: return Action.STATUS;
        }
    }

    /** M 键/翻转:手动覆写取反。返回翻转后状态(供回显与服务端同步)。 */
    public static boolean toggleGunManual() {
        boolean next = !ClientLightState.gunLightOn();
        ClientLightState.setGunLightManual(next);
        return next;
    }

    /** !gun auto:清手动旗恢复探针跟随。返回清旗后手动态(恒 false,供回显断言)。 */
    public static boolean applyAuto() {
        ClientLightState.clearGunManual();
        return ClientLightState.gunManual();
    }

    private GunControl() {}
}

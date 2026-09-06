package dev.taclight.client;

/**
 * 枪灯手动控制契约(2026-09-07 用户需求:枪灯要有游戏内开关 + 空手不亮可解释).
 * M 键与 !gun 共用同一状态机;!gun auto 清手动旗恢复探针跟随。
 */
public class GunControlContract {
    public static void main(String[] args) {
        // 复位,免受运行顺序污染
        ClientLightState.clearGunManual();
        ClientLightState.setGunLight(false);
        // 参数决议
        check(GunControl.parseRelayArg("") == GunControl.Action.TOGGLE, "裸 !gun=翻转(兼容旧行为)");
        check(GunControl.parseRelayArg("on") == GunControl.Action.ON, "on");
        check(GunControl.parseRelayArg(" ON ") == GunControl.Action.ON, "大小写+空格容忍");
        check(GunControl.parseRelayArg("off") == GunControl.Action.OFF, "off");
        check(GunControl.parseRelayArg("auto") == GunControl.Action.AUTO, "auto=回探针");
        check(GunControl.parseRelayArg("probe") == GunControl.Action.AUTO, "probe=auto 别名");
        check(GunControl.parseRelayArg("status") == GunControl.Action.STATUS, "status=回显");
        check(GunControl.parseRelayArg("xyz") == GunControl.Action.STATUS, "未知参=回显不乱动");
        // M 键翻转语义:置手动旗
        check(GunControl.toggleGunManual(), "翻转 false->true");
        check(ClientLightState.gunManual(), "翻转后手动旗置位");
        ClientLightState.setGunLight(false);
        check(ClientLightState.gunLightOn(), "手动期探针写 false 不覆盖");
        // auto 清旗恢复探针
        check(!GunControl.applyAuto(), "auto 后手动旗恒 false");
        ClientLightState.setGunLight(false);
        check(!ClientLightState.gunLightOn(), "auto 后探针写 false 生效");
        ClientLightState.setGunLight(true);
        check(ClientLightState.gunLightOn(), "auto 后探针写 true 生效(空手切枪即亮/灭)");
        // 复位,免污染后续契约/运行
        ClientLightState.setGunLight(false);
        ClientLightState.clearGunManual();
        System.out.println("GunControlContract: ALL PASS (13 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

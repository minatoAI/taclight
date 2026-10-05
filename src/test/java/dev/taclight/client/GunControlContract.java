package dev.taclight.client;

import dev.taclight.devonly.GunRelay;

/**
 * 枪灯手动控制契约(2026-09-07 用户需求:枪灯要有游戏内开关 + 空手不亮可解释).
 * M 键与 !gun 共用同一状态机;!gun auto 清手动旗恢复探针跟随。
 *
 * <p><b>2026-09-29 R12c(N1 收尾)</b>:中继侧两个入口(参数决议 / auto 清旗)已从发布侧
 * {@code GunControl} 搬到 {@code dev.taclight.devonly.GunRelay} ⇒ 本契约改为对 {@code GunRelay} 断言
 * (它同为"零 MC 依赖"的纯逻辑,所以纯 JVM 可测这一点没变)。
 * ⚠️ 「发布件里 {@code GunControl.class} 不含这两个方法符号」由
 * {@code InteropPackagingContract} 的**构件层**闸门钉(那里比字节码 ⇒ 不受注释/文档文字影响),
 * 本契约**不做**源码文本级负断言(会与 javadoc 里提到的名字打架 —— {@code AGENTS §五} M1 的镜像教训)。</p>
 */
public class GunControlContract {
    private static int checks;

    public static void main(String[] args) {
        // 复位,免受运行顺序污染
        ClientLightState.clearGunManual();
        ClientLightState.setGunLight(false);
        // 参数决议(dev-only 纯逻辑:GunRelay)
        check(GunRelay.parse("") == GunControl.Action.TOGGLE, "裸 !gun=翻转(兼容旧行为)");
        check(GunRelay.parse("on") == GunControl.Action.ON, "on");
        check(GunRelay.parse(" ON ") == GunControl.Action.ON, "大小写+空格容忍");
        check(GunRelay.parse("off") == GunControl.Action.OFF, "off");
        check(GunRelay.parse("auto") == GunControl.Action.AUTO, "auto=回探针");
        check(GunRelay.parse("probe") == GunControl.Action.AUTO, "probe=auto 别名");
        check(GunRelay.parse("status") == GunControl.Action.STATUS, "status=回显");
        check(GunRelay.parse("xyz") == GunControl.Action.STATUS, "未知参=回显不乱动");
        // M 键翻转语义:置手动旗
        check(GunControl.toggleGunManual(), "翻转 false->true");
        check(ClientLightState.gunManual(), "翻转后手动旗置位");
        ClientLightState.setGunLight(false);
        check(ClientLightState.gunLightOn(), "手动期探针写 false 不覆盖");
        // auto 清旗恢复探针
        check(!GunRelay.applyAuto(), "auto 后手动旗恒 false");
        ClientLightState.setGunLight(false);
        check(!ClientLightState.gunLightOn(), "auto 后探针写 false 生效");
        ClientLightState.setGunLight(true);
        check(ClientLightState.gunLightOn(), "auto 后探针写 true 生效(空手切枪即亮/灭)");
        // 持枪门(2026-09-07 用户实测:持枪开灯后切其他物品灯还亮=bug):
        // 有效灯 = 手动偏好 × 主手探针,切走即灭、切回即复(偏好保留)。
        ClientLightState.clearGunManual();
        ClientLightState.setGunProbe(true);
        ClientLightState.setGunLight(true);
        check(ClientLightState.gunLightEffective(), "auto+持灯枪=有效亮");
        ClientLightState.setGunProbe(false);
        ClientLightState.setGunLight(false);
        check(!ClientLightState.gunLightEffective(), "auto+空手=有效灭");
        ClientLightState.setGunLightManual(true);
        ClientLightState.setGunProbe(true);
        check(ClientLightState.gunLightEffective(), "手动开+持灯枪=有效亮");
        ClientLightState.setGunProbe(false);
        check(!ClientLightState.gunLightEffective(), "手动开+切走=有效灭(本次回归)");
        check(ClientLightState.gunLightOn(), "切走后手动偏好保留");
        ClientLightState.setGunProbe(true);
        check(ClientLightState.gunLightEffective(), "切回持枪=恢复亮");
        // Iris 区块光门控(2026-10-06 用户实测:装上即亮不受开关=bug):未装件恒 0;本地读有效值;远端读同步真源
        check(GunControl.gunBlockLightEmission(false, true, true, false) == 0, "未装件=0(与开关无关)");
        check(GunControl.gunBlockLightEmission(true, true, false, false) == 0, "本地装件但关灯=0(本次回归)");
        check(GunControl.gunBlockLightEmission(true, true, true, false) == 10, "本地装件+开灯=10");
        check(GunControl.gunBlockLightEmission(true, false, false, false) == 0, "远端同步灭=0(不读本机)");
        check(GunControl.gunBlockLightEmission(true, false, true, true) == 10, "远端同步亮=10(本机开关不干扰)");
        check(GunControl.gunBlockLightEmission(true, false, true, false) == 0, "远端以同步为准");
        // 复位,免污染后续契约/运行
        ClientLightState.setGunProbe(false);
        ClientLightState.setGunLight(false);
        ClientLightState.clearGunManual();
        System.out.println("GunControlContract: ALL PASS (" + checks + " checks)");
    }

    /** 计数改为**动态**(R13-6 的同族清理:硬编码 checks 数会与静态调用点数漂移)。 */
    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

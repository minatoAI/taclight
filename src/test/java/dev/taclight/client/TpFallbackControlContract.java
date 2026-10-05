package dev.taclight.client;

/**
 * TpFallbackControl 契约(2026-09-02 屏外枪灯连续性 A/B 旋钮):
 * 默认 blend(新连续性行为);可切 hard 复现旧二元回退供对照;空参回显。
 * 束轴读数调试模式(col/row)随 MuzzlePoseMathContract 钉死,此处只管回退模式。
 */
public class TpFallbackControlContract {
    public static void main(String[] args) {
        check(TpFallbackControl.blended(), "默认 blend(连续性行为)");
        check(TpFallbackControl.configureFallback("hard").contains("hard") && !TpFallbackControl.blended(),
                "切 hard=旧二元回退(A/B 对照)");
        check(TpFallbackControl.configureFallback("blend").contains("blend") && TpFallbackControl.blended(),
                "切回 blend");
        check(TpFallbackControl.configureFallback("bogus").contains("无法解析") && TpFallbackControl.blended(),
                "坏参数报错且不改变现状");
        check(TpFallbackControl.configureFallback("").contains("blend"), "空参回显当前模式");
        System.out.println("TpFallbackControlContract: ALL PASS (5 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

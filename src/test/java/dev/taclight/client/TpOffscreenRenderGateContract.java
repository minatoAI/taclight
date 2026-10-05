package dev.taclight.client;

/**
 * TpOffscreenRenderGate 契约(2026-09-03 v3 屏外不剔除渲染):
 * 强制渲染门禁 = 仅当"远程玩家 + 枪灯开 + 观察距离内 + 非观察者本人"时才对
 * EntityRenderDispatcher.shouldRender 返回 true(绕过视锥剔除)。
 * 其余一律放行(返回原值,零行为变化)。
 */
public class TpOffscreenRenderGateContract {
    public static void main(String[] args) {
        // 红:以下调用在 Gate 实现落地前全部抛 NoSuchMethodError/AssertionError
        check(TpOffscreenRenderGate.keepFor(false, true, 10.0, 48.0),
                "远程+枪灯+距离内=强制渲染");
        check(!TpOffscreenRenderGate.keepFor(true, true, 10.0, 48.0),
                "观察者本人=不强制(本地第一人称链不受影响)");
        check(!TpOffscreenRenderGate.keepFor(false, false, 10.0, 48.0),
                "枪灯关=不强制(普通玩家零开销)");
        check(!TpOffscreenRenderGate.keepFor(false, true, 60.0, 48.0),
                "超观察距离=不强制(与灯收集链同门禁)");
        check(TpOffscreenRenderGate.keepFor(false, true, 48.0, 48.0),
                "恰在距离边界(含)=强制(边界语义钉死)");
        check(!TpOffscreenRenderGate.keepFor(false, true, Double.NaN, 48.0),
                "距离 NaN=不强制(坏输入不毒化渲染帧)");
        check(!TpOffscreenRenderGate.keepFor(false, true, -1.0, 48.0),
                "负距离=不强制(坏输入不毒化渲染帧)");
        check(!TpOffscreenRenderGate.enabled() || TpOffscreenRenderGate.keepFor(false, true, 1.0, 48.0),
                "总开关 on 时门禁生效(默认 on)");
        TpOffscreenRenderGate.setEnabled(false);
        check(!TpOffscreenRenderGate.keepFor(false, true, 1.0, 48.0),
                "总开关 off=一键回退(全部放行,零行为变化)");
        TpOffscreenRenderGate.setEnabled(true);
        check(TpOffscreenRenderGate.keepFor(false, true, 1.0, 48.0),
                "总开关恢复 on 后门禁重生效");
        System.out.println("TpOffscreenRenderGateContract: ALL PASS (10 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

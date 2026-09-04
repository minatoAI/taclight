package dev.taclight.channel;

import dev.taclight.channel.ClientSpotlightUploader.LightParams;

/**
 * !lv 档位覆盖层契约(2026-09-03 真实感调参):
 * 默认关闭(零行为变化)/档位映射 intensity=6·2^-d/越界拒绝/关闭恢复/幂等。
 */
public class LightLevelOverrideContract {
    public static void main(String[] args) {
        // 默认:无覆盖 = 原值直通
        LightLevelOverride.configure("off");
        check(LightLevelOverride.intensityFor(6.0f) == 6.0f, "默认关闭:intensity 直通");

        check(LightLevelOverride.configure("0").contains("off"), "lv 0 = 关闭");
        check(LightLevelOverride.intensityFor(6.0f) == 6.0f, "关闭后直通");

        String s2 = LightLevelOverride.configure("2");
        check(s2.contains("1.5"), "lv 2 → 6·2^-2=1.5: " + s2);
        check(Math.abs(LightLevelOverride.intensityFor(6.0f) - 1.5f) < 1e-6, "覆盖生效");

        LightLevelOverride.configure("3");
        check(Math.abs(LightLevelOverride.intensityFor(6.0f) - 0.75f) < 1e-6, "lv 3 → 0.75");

        String bad = LightLevelOverride.configure("9");
        check(bad.startsWith("range"), "越界拒绝: " + bad);
        check(Math.abs(LightLevelOverride.intensityFor(6.0f) - 0.75f) < 1e-6, "越界不污染当前档");

        String bad2 = LightLevelOverride.configure("abc");
        check(bad2.startsWith("bad arg"), "非法输入拒绝: " + bad2);

        String st = LightLevelOverride.configure("status");
        check(st.contains("3"), "status 回显当前档: " + st);

        // radius 自耦合:档位只换 intensity,√(I/6) 半径经 buildSpotBeam 自动跟
        LightParams p = new LightParams(18.0f, 96.0f,
                LightLevelOverride.intensityFor(6.0f), 0.848f, 0.951f, 0.35f);
        SpotlightData dim = ClientSpotlightUploader.buildSpotBeam(
                0, 0, 0, 0, 0, -1, p, 1.0f);
        check(Math.abs(dim.radius() - 18.0f * (float) Math.sqrt(0.75 / 6.0)) < 0.01,
                "lv3 半径自耦合 √(0.75/6)×18 ≈ 6.36: " + dim.radius());

        LightLevelOverride.configure("off");
        check(LightLevelOverride.intensityFor(6.0f) == 6.0f, "收尾关闭(零残留)");

        System.out.println("LightLevelOverrideContract: ALL PASS (10 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

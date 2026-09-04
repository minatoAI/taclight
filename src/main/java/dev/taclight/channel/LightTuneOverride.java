package dev.taclight.channel;

/**
 * 手电三旋钮覆盖层(2026-09-04,用户体感调参"手动调出符合感受的效果"):
 * {@code !bright} 绝对亮度 / {@code !dist} 绝对照距 / {@code !atten} 衰减系数 K。
 * <p>Forge CLIENT config 热改 toml 不回读(瞬时读仍是旧值,见 {@link LightLevelOverride}),
 * 故三路均为纯内存覆盖,重启实例 = 覆盖清零 = 回 config 默认。
 * <p>默认全关 = {@link #brightnessFor}/{@link #radiusFor} 直通、{@link #attenK} 返回 0
 * (GLSL 侧 cone.z ≤ 0 回退编译期 {@code TACLIGHT_ATTEN_K} 默认)。
 * 衰减 K 经 SSBO cone.z 逐灯透传(保留槽,此前恒 0)——半径 r 仍走原通道,零布局变化。
 * <p>范围:bright 0.5..30(同 INTENSITY define 域)/dist 4..96(同 RADIUS 域)/
 * atten 0.2..20(0.5r 处约 44%..2% 亮度,5.0=当前主包标定)。
 */
public final class LightTuneOverride {
    private static volatile float brightValue = 6.0f;
    private static volatile boolean brightActive;
    private static volatile float distValue = 36.0f;
    private static volatile boolean distActive;
    private static volatile float attenValue;
    private static volatile boolean attenActive;

    private LightTuneOverride() {}

    /** relay 入口:返回状态串(供日志)。 */
    public static String configureBright(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return brightActive ? ("bright=" + brightValue) : "off(默认 intensity=6.0)";
        }
        if (arg.equals("off")) {
            brightActive = false;
            return "off(默认 intensity=6.0)";
        }
        try {
            float v = Float.parseFloat(arg);
            if (v < 0.5f || v > 30.0f) return "range 0.5..30, got " + arg;
            brightValue = v;
            brightActive = true;
            return "bright=" + v;
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 0.5..30/off/status)";
        }
    }

    /** relay 入口:返回状态串(供日志)。 */
    public static String configureDist(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return distActive ? ("dist=" + distValue) : "off(默认 radius=36.0)";
        }
        if (arg.equals("off")) {
            distActive = false;
            return "off(默认 radius=36.0)";
        }
        try {
            float v = Float.parseFloat(arg);
            if (v < 4.0f || v > 96.0f) return "range 4..96, got " + arg;
            distValue = v;
            distActive = true;
            return "dist=" + v;
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 4..96/off/status)";
        }
    }

    /** relay 入口:返回状态串(供日志)。 */
    public static String configureAtten(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return attenActive ? ("attenK=" + attenValue) : "off(GLSL 默认 K=5.0)";
        }
        if (arg.equals("off")) {
            attenActive = false;
            attenValue = 0.0f;
            return "off(GLSL 默认 K=5.0)";
        }
        try {
            float v = Float.parseFloat(arg);
            if (v < 0.2f || v > 20.0f) return "range 0.2..20, got " + arg;
            attenValue = v;
            attenActive = true;
            return "attenK=" + v;
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 0.2..20/off/status)";
        }
    }

    /** buildSpotBeam 调用:有覆盖 → 绝对亮度替换(与 !lv 档位互斥,后写者胜由调用序决定)。 */
    public static float brightnessFor(float base) {
        if (!brightActive) return base;
        return brightValue;
    }

    /** buildSpotBeam 调用:有覆盖 → 绝对半径替换( bypass √亮度耦合)。 */
    public static float radiusFor(float coupled, float radiusMax) {
        if (!distActive) return coupled;
        return Math.min(distValue, radiusMax);
    }

    /** buildSpotBeam 调用:有覆盖 → 逐灯 K;0 = cone.z 留 0,GLSL 回退编译期默认。 */
    public static float attenK() {
        if (!attenActive) return 0.0f;
        return attenValue;
    }
}

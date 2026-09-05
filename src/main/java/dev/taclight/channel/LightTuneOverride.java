package dev.taclight.channel;

/**
 * 手电五旋钮覆盖层(2026-09-04 三旋钮用户体感调参,2026-09-05 加 !knee/!beam):
 * {@code !bright} 绝对亮度 / {@code !dist} 绝对照距 / {@code !atten} 衰减系数 K /
 * {@code !knee} 近场软肩 G(经 SSBO cone.w 逐灯透传) /
 * {@code !beam} 体积光束密度(经 SSBO vlParams.y 直接换值,GLSL 零改动)。
 * <p>Forge CLIENT config 热改 toml 不回读(瞬时读仍是旧值,见 {@link LightLevelOverride}),
 * 故四路均为纯内存覆盖,重启实例 = 覆盖清零 = 回 config 默认。
 * <p>默认全关 = {@link #brightnessFor}/{@link #radiusFor} 直通、{@link #attenK}/
 * {@link #kneeG} 返回 0(GLSL 侧 cone.z/w ≤ 0 回退编译期默认 = 今日行为,零变化)。
 * 衰减 K 经 SSBO cone.z、软肩 G 经 cone.w 逐灯透传(保留槽,此前恒 0)——
 * 半径 r 仍走原通道,零布局变化。
 * <p>范围:bright 0.5..30(同 INTENSITY define 域)/dist 4..96(同 RADIUS 域)/
 * atten 0.2..20(0.5r 处约 44%..2% 亮度,5.0=当前主包标定)/
 * knee 0.2..8(近场压暗强度,2.0=当前主包标定;0.2≈趋平/压缩最弱,越大近场压得越狠)/
 * beam 0..1(体积密度 = 丁达尔效果强度,0=完全关光束做开关对比,off=回 config 默认 0.05;
 * 与 atten/knee 的 0 哨兵语义不同——密度是消费值本身,显式 0 就是关,不回退)。
 */
public final class LightTuneOverride {
    private static volatile float brightValue = 6.0f;
    private static volatile boolean brightActive;
    private static volatile float distValue = 36.0f;
    private static volatile boolean distActive;
    private static volatile float attenValue;
    private static volatile boolean attenActive;
    private static volatile float kneeValue;
    private static volatile boolean kneeActive;
    private static volatile float beamValue;
    private static volatile boolean beamActive;
    private static volatile boolean beamOnlyActive;

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

    /** relay 入口:返回状态串(供日志)。 */
    public static String configureKnee(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return kneeActive ? ("kneeG=" + kneeValue) : "off(GLSL 默认 G=2.0)";
        }
        if (arg.equals("off")) {
            kneeActive = false;
            kneeValue = 0.0f;
            return "off(GLSL 默认 G=2.0)";
        }
        try {
            float v = Float.parseFloat(arg);
            if (v < 0.2f || v > 8.0f) return "range 0.2..8, got " + arg;
            kneeValue = v;
            kneeActive = true;
            return "kneeG=" + v;
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 0.2..8/off/status)";
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

    /** relay 入口:返回状态串(供日志)。 */
    public static String configureBeam(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return beamActive ? ("beam=" + beamValue) : "off(config 默认 density=0.05)";
        }
        if (arg.equals("off")) {
            beamActive = false;
            beamValue = 0.0f;
            return "off(config 默认 density=0.05)";
        }
        try {
            float v = Float.parseFloat(arg);
            if (v < 0.0f || v > 1.0f) return "range 0..1, got " + arg;
            beamValue = v;
            beamActive = true;
            return "beam=" + v;
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 0..1/off/status)";
        }
    }

    /** buildSpotBeam 调用:有覆盖 → 逐灯 K;0 = cone.z 留 0,GLSL 回退编译期默认。 */
    public static float attenK() {
        if (!attenActive) return 0.0f;
        return attenValue;
    }

    /** buildSpotBeam 调用:有覆盖 → 逐灯软肩 G;0 = cone.w 留 0,GLSL 恒等直通(今日行为)。 */
    public static float kneeG() {
        if (!kneeActive) return 0.0f;
        return kneeValue;
    }

    /** buildSpotBeam 调用:有覆盖 → 逐灯体积密度替换(0=完全关光束;off 回 config 默认)。 */
    public static float beamDensityOr(float configDensity) {
        if (!beamActive) return configDensity;
        return beamValue;
    }

    /** relay 入口:返回状态串(供日志)。 */
    public static String configureBeamonly(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return beamOnlyActive ? "beamonly=on(表面照明已关,只留体积束)" : "beamonly=off";
        }
        if (arg.equals("on")) {
            beamOnlyActive = true;
            return "beamonly=on(表面照明已关,只留体积束)";
        }
        if (arg.equals("off")) {
            beamOnlyActive = false;
            return "beamonly=off";
        }
        return "bad arg " + arg + " (want on/off/status)";
    }

    /** onFrame 调用:true → 头部 flags 置 FLAG_BEAM_ONLY(GLSL 跳过 M1 表面照明)。 */
    public static boolean beamOnly() {
        return beamOnlyActive;
    }
}

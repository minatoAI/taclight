package dev.taclight.channel;

/**
 * 真实感调参档位(2026-09-03,用户体感"光晕太亮照不清"→ 零重启体感对照)。
 * <p>Forge CLIENT config 在游戏内热改 toml 不回读(瞬时读 ConfigValue 还是旧值),
 * 重启实例才能换亮度——体感扫参太贵。故加纯内存覆盖层:uploader 的 LightParams.load()
 * 在取值后经过本层(有覆盖 → intensity 按档位替换)。重启实例 = 覆盖清零 = 回 0 档。
 * <p>档位语义:lv 档 d(0~6) → intensity = 6·2^(-d)。d=0 即当前(6.0);
 * d=3 → 0.75。radius 经 buildSpotBeam 的 √(I/6) 自耦合,档位只改亮度,照距自动跟。
 */
public final class LightLevelOverride {
    private static volatile float levelDb = 0.0f;
    private static volatile boolean active;

    private LightLevelOverride() {}

    /** relay 入口:返回状态串(供日志)。 */
    public static String configure(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return active ? ("level=" + levelDb + " intensity=" + intensityFor(6.0f))
                    : "off(默认 intensity=6.0)";
        }
        if (arg.equals("off") || arg.equals("0")) {
            active = false;
            levelDb = 0.0f;
            return "off(默认 intensity=6.0)";
        }
        try {
            float d = Float.parseFloat(arg);
            if (d < 0.0f || d > 6.0f) return "range 0..6, got " + arg;
            levelDb = d;
            active = true;
            return "level=" + d + " intensity=" + intensityFor(6.0f);
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 0..6/off/status)";
        }
    }

    /** uploader 在 LightParams.load() 取值后调用:有覆盖 → 替换 intensity。 */
    public static float intensityFor(float base) {
        if (!active) return base;
        return (float) (6.0f * Math.pow(2.0, -levelDb));
    }
}

package dev.taclight.channel;

/**
 * 手电八旋钮覆盖层(2026-09-04 三旋钮,2026-09-05 加 !knee/!beam/!beamonly/!scat/!beamcap/!cone):
 * {@code !bright} 绝对亮度 / {@code !dist} 绝对照距 / {@code !atten} 衰减系数 K /
 * {@code !knee} 近场软肩 G(经 SSBO cone.w 逐灯透传;2026-09-05 用户实测"光路内反光
 * 刺眼"定案默认开:{@link #DEFAULT_KNEE_G}=2.0,off=回默认——近场 HDR 值不再直通 bloom) /
 * {@code !beam} 体积光束密度(经 SSBO vlParams.y 直接换值,GLSL 零改动) /
 * {@code !scat} 体积光轴向底亮份额 f(经 SSBO vlParams.x 直接换值,GLSL 零改动;
 * 侧面相位 phase=NORM·(f+(1−f)·sin²θ):0=纯侧面丁达尔,off=回编译期默认 0.04——
 * 2026-09-05 用户定案:体积光只为侧面视角服务,正对/沿轴调低防与表面光叠加刺眼) /
 * {@code !beamcap} 体积光重叠软上限倍率 m(经 SSBO vlParams.z 透传,GLSL cap=2.0×m:
 * 低于半帽点恒等=单灯观感零变化,多灯重叠亮度指数肩部渐近 cap 不许无限叠加——
 * 2026-09-05 用户需求:两灯同照刺眼;0.25=压得最狠,8≈基本不限,off=回 m=1) /
 * {@code !cone} 锥角收窄(2026-09-05 用户定案"接近平行光"):外锥半角(度),内锥=外×0.5,
 * Java 侧直改 cosOuter/cosInner,SSBO/GLSL 零改动;0 哨兵=直通 config 默认 外8/内4。 /
 * {@code !occl} 遮挡距离表(2026-09-06 方案二,默认开):GLSL composite 预建逐灯均向
 * D 表(colortex8),composite1 体积光逐采样灯侧 DDA 降为查表;off=回逐采样 DDA(A/B 对照)。
 * <p>Forge CLIENT config 热改 toml 不回读(瞬时读仍是旧值,见 {@link LightLevelOverride}),
 * 故各路均为纯内存覆盖,重启实例 = 覆盖清零 = 回 config 默认。
 * <p>默认:bright/dist 直通、attenK 回 0(GLSL 回退编译期默认)、kneeG 回
 * {@link #DEFAULT_KNEE_G}(默认即开)、coneDeg 回 0(直通 config 8/4)。
 * 衰减 K 经 SSBO cone.z、软肩 G 经 cone.w 逐灯透传(保留槽)——半径 r 仍走原通道,零布局变化。
 * <p>范围:bright 0.5..30(同 INTENSITY 域)/dist 4..96(同 RADIUS 域)/
 * atten 0.2..20(0.5r 处约 44%..2% 亮度,20.0=当前主包标定,2026-09-06 扫参冻结,旧 5.0 作古)/
 * knee 0.2..8(近场压暗强度,2.0=当前主包标定;0.2≈趋平/压缩最弱,越大近场压得越狠)/
 * beam 0..1(体积密度 = 丁达尔效果强度,0=完全关光束做开关对比,off=回 config 默认 0.05;
 * 与 atten/knee 的 0 哨兵语义不同——密度是消费值本身,显式 0 就是关,不回退)/
 * cone 2..45(度,外半角;off=回 config 默认 8/4)。
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
    private static volatile float scatValue;
    private static volatile boolean scatActive;
    private static volatile float beamCapValue;
    private static volatile boolean beamCapActive;
    private static volatile float coneValue;
    private static volatile boolean coneActive;

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
            return attenActive ? ("attenK=" + attenValue) : "off(GLSL 默认 K=20.0)";
        }
        if (arg.equals("off")) {
            attenActive = false;
            attenValue = 0.0f;
            return "off(GLSL 默认 K=20.0)";
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
            return kneeActive ? ("kneeG=" + kneeValue) : "off(默认 G=2.0,默认即开)";
        }
        if (arg.equals("off")) {
            kneeActive = false;
            kneeValue = 0.0f;
            return "off(默认 G=2.0,默认即开)";
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
            return beamActive ? ("beam=" + beamValue) : "off(config 默认 density=0.25)";
        }
        if (arg.equals("off")) {
            beamActive = false;
            beamValue = 0.0f;
            return "off(config 默认 density=0.25)";
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

    /** 表面照明近场软肩默认 G(2026-09-05 用户实测"站在光路内反光刺眼"定案默认开,
     *  与编译期标定 TACLIGHT_KNEE_GAIN 同值;!knee off 回此默认——off≠恒等)。 */
    public static final float DEFAULT_KNEE_G = 2.0f;

    /** buildSpotBeam 调用:有覆盖 → 逐灯软肩 G;默认回 {@link #DEFAULT_KNEE_G}(默认即开)。 */
    public static float kneeG() {
        if (!kneeActive) return DEFAULT_KNEE_G;
        return kneeValue;
    }

    /** buildSpotBeam 调用:有覆盖 → 逐灯体积密度替换(0=完全关光束;off 回 config 默认)。 */
    public static float beamDensityOr(float configDensity) {
        if (!beamActive) return configDensity;
        return beamValue;
    }

    /** relay 入口:返回状态串(供日志)。 */
    public static String configureScat(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return scatActive ? ("scat=" + scatValue) : "off(GLSL 默认 floor 0.04)";
        }
        if (arg.equals("off")) {
            scatActive = false;
            scatValue = 0.0f;
            return "off(GLSL 默认 floor 0.04)";
        }
        try {
            float v = Float.parseFloat(arg);
            if (v < 0.0f || v > 0.9f) return "range 0..0.9, got " + arg;
            scatValue = v;
            scatActive = true;
            return "scat=" + v;
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 0..0.9/off/status)";
        }
    }

    /** buildSpotBeam 调用:有覆盖 → 逐灯轴向底亮份额 f 换值(GLSL phase=NORM·(f+(1−f)·sin²θ),
     *  0=纯侧面丁达尔;off 回编译期默认 0.06。与 !beam 同族:0 是合法消费值,不走 0 哨兵)。 */
    public static float scatOr(float configSideFloor) {
        if (!scatActive) return configSideFloor;
        return scatValue;
    }

    /** buildSpotBeam 调用:scat 覆盖是否激活(决定是否需要重排 SSBO 灯数据)。 */
    public static boolean scatActive() {
        return scatActive;
    }

    /** relay 入口:返回状态串(供日志)。m 是 GLSL 软上限 cap=2.0×m 的倍率:
     *  越小重叠眩光压得越狠,越大越接近无上限;off=回 m=1(GLSL 默认)。 */
    public static String configureBeamcap(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return beamCapActive ? ("beamcap=" + beamCapValue + "x") : "off(软上限 cap=2.0 线性)";
        }
        if (arg.equals("off")) {
            beamCapActive = false;
            beamCapValue = 0.0f;
            return "off(软上限 cap=2.0 线性)";
        }
        try {
            float v = Float.parseFloat(arg);
            if (v < 0.25f || v > 8.0f) return "range 0.25..8, got " + arg;
            beamCapValue = v;
            beamCapActive = true;
            return "beamcap=" + v + "x";
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 0.25..8/off/status)";
        }
    }

    /** buildSpotBeam 调用:有覆盖 → vlParams.z 软上限倍率(GLSL cap=TACLIGHT_BEAM_CAP×m);
     *  0 = 未激活(槽位保持 spotBeam 写入的默认 1.0,零行为变化)。 */
    public static float beamCapM() {
        if (!beamCapActive) return 0.0f;
        return beamCapValue;
    }

    /** relay 入口:返回状态串(供日志)。锥角收窄(2026-09-05 用户定案"接近平行光"):
     *  外锥半角(度),内锥=外×0.5;旧默认 32/18 在 30m 外光斑半径 ≈18.7m(远距离
     *  范围过大),新默认 8/4 在 30m 外 ≈4.2m。off=直通 config 默认 外8/内4。 */
    public static String configureCone(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return coneActive ? ("cone=" + coneValue + "(inner=" + (coneValue * 0.5f) + ")")
                              : "off(默认 config 外8/内4)";
        }
        if (arg.equals("off")) {
            coneActive = false;
            coneValue = 0.0f;
            return "off(默认 config 外8/内4)";
        }
        try {
            float v = Float.parseFloat(arg);
            if (v < 2.0f || v > 45.0f) return "range 2..45, got " + arg;
            coneValue = v;
            coneActive = true;
            return "cone=" + v + "(inner=" + (v * 0.5f) + ")";
        } catch (NumberFormatException e) {
            return "bad arg " + arg + " (want 2..45/off/status)";
        }
    }

    /** buildSpotBeam 调用:>0 = 外锥半角覆盖(内锥=外×0.5);0 = 直通 config 默认。 */
    public static float coneDeg() {
        if (!coneActive) return 0.0f;
        return coneValue;
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

    /** !occl 遮挡距离表(2026-09-06 方案二,用户批准的性能立项):默认开——
     *  GLSL composite 每帧预建逐灯均向"最远无遮挡距离"表(colortex8,512×256 equirect),
     *  composite1 体积光的逐采样灯侧 DDA(光池内 @4K +20.6ms,占开灯开销 2/3)降为
     *  每采样一次查表。off = 回逐采样 DDA(逐格精确但慢,A/B 对照用)。
     *  Java 仅在体素栅格有效时置位 FLAG_OCCL_TABLE(栅格无效=旧行为回退可见)。 */
    private static volatile boolean occlActive = true;

    /** relay 入口:返回状态串(供日志)。on/off/status,重启回默认(on)。 */
    public static String configureOccl(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return occlActive ? "occl=on(遮挡距离表:查表代替体积光逐采样 DDA)"
                              : "occl=off(逐采样 DDA)";
        }
        if (arg.equals("on")) {
            occlActive = true;
            return "occl=on(遮挡距离表:查表代替体积光逐采样 DDA)";
        }
        if (arg.equals("off")) {
            occlActive = false;
            return "occl=off(逐采样 DDA)";
        }
        return "bad arg " + arg + " (want on/off/status)";
    }

    /** onFrame 调用:true → 头部 flags 置 FLAG_OCCL_TABLE(GLSL composite1 查表)。 */
    public static boolean occlTable() {
        return occlActive;
    }

    /** !tm 体积光时间复用(2026-09-06 用户批准立项,默认开):composite1 步数 64→32 +
     *  IGN 抖动逐帧旋转(帧间去相关)+ 上一帧历史(colortex9)重投影混合,权重 =
     *  0.75 × 逐灯置信度(Java LightMotionConf 按灯位姿帧间差分,经 SSBO vlParams.w
     *  透传;首帧/灯开关/瞬移 = 0 = 全新鲜,防拖影)。静态场景有效步数
     *  32/(1−0.75)=128,raymarch 每帧步数减半;off = 回 64 步全新鲜(逐位旧行为)。 */
    private static volatile boolean temporalActive = true;

    /** relay 入口:返回状态串(供日志)。on/off/status,重启回默认(on)。 */
    public static String configureTemporal(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            return temporalActive ? "tm=on(体积光时间复用:32步+历史混合,有效≈128步)"
                                  : "tm=off(64 步全新鲜)";
        }
        if (arg.equals("on")) {
            temporalActive = true;
            return "tm=on(体积光时间复用:32步+历史混合,有效≈128步)";
        }
        if (arg.equals("off")) {
            temporalActive = false;
            return "tm=off(64 步全新鲜)";
        }
        return "bad arg " + arg + " (want on/off/status)";
    }

    /** onFrame 调用:true → 头部 flags 置 FLAG_TEMPORAL(GLSL composite1 时间复用)。 */
    public static boolean temporal() {
        return temporalActive;
    }
}

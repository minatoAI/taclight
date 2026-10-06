package dev.taclight.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** 聚光灯客户端配置(仅视觉。全部为施工图默认值,游戏内 config/taclight-client.toml 可调)。 */
public final class TacLightConfig {
    /** 半径硬上限(与 defineInRange 同源;上传侧 √亮度耦合的钳制值经 LightParams 注入)。 */
    public static final double RADIUS_MAX = 96.0;

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<Double> RADIUS;
    public static final ForgeConfigSpec.ConfigValue<Double> INTENSITY;
    public static final ForgeConfigSpec.ConfigValue<Double> CONE_OUTER_DEG;
    public static final ForgeConfigSpec.ConfigValue<Double> CONE_INNER_DEG;
    public static final ForgeConfigSpec.ConfigValue<Double> BEAM_DENSITY;
    // /taclight tune 持久化键(2026-09-19 八旋钮晋升正式命令):atten/knee/scat/beamcap
    // 此前只有内存覆盖层(LightTuneOverride),无 config 键,重启即丢。新增键默认值 =
    // 各路 off 时的当前有效值(atten 20.0 = GLSL TACLIGHT_ATTEN_K 扫参冻结值,见
    // InlineCoreContract;scat 0.04 = SpotlightData.BEAM_SIDE_FLOOR;beamcap 1.0 =
    // 槽位默认;voxel 默认开),范围与覆盖层同源。tune 写入 + 开机回填覆盖层。
    public static final ForgeConfigSpec.ConfigValue<Double> ATTEN_K;
    public static final ForgeConfigSpec.ConfigValue<Double> KNEE_GAIN;
    public static final ForgeConfigSpec.ConfigValue<Double> SCAT_FLOOR;
    public static final ForgeConfigSpec.ConfigValue<Double> BEAM_CAP_M;
    public static final ForgeConfigSpec.ConfigValue<Boolean> VOXEL_ENABLED;
    /** 手持光照值(Iris/Oculus 的 heldBlockLightValue):0..15 的**整数**键,持久化走 Sink.setInt/getInt。 */
    public static final ForgeConfigSpec.ConfigValue<Integer> HELD_LIGHT_LEVEL;
    public static final ForgeConfigSpec.ConfigValue<Double> GUN_MULTIPLIER;
    public static final ForgeConfigSpec.ConfigValue<Double> REMOTE_LIGHT_MAX_DIST;
    public static final ForgeConfigSpec.ConfigValue<Integer> REMOTE_LIGHT_MAX_COUNT;
    public static final ForgeConfigSpec.ConfigValue<Boolean> SELF_LIGHT_ENABLED;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("TacLight spotlight tuning (client-side visuals)").push("spotlight");
        // radius 语义 = 基准半径(参考亮度 6.0 下);实际半径由上传侧按 √亮度 缩放(doc06 §8.7)
        // F3(2026-08-30)能量重标定:56 是为室外远照调的档,反平方在室内尺度
        // 无衰减感(atten(8m)≈0.94 → 多链叠加推平顶,R1);18 档下 atten(8m)≈0.58、
        // atten(3m)≈0.92,光斑恢复衰减层次。室外远照场景调大 radius 即可(√ 亮度耦合不变)。
        RADIUS = builder.comment("reference radius (blocks) @ intensity 6.0; effective radius scales as sqrt(intensity/6.0)").defineInRange("radius", 36.0, 4.0, RADIUS_MAX);
        INTENSITY = builder.comment("light intensity").defineInRange("intensity", 6.0, 0.5, 30.0);
        // 锥角(2026-09-05 用户定案"接近平行光"):旧 32/18 在 30m 外光斑半径 ≈18.7m,
        // 远距离范围过大;8/4 在 30m 外 ≈4.2m、20m 外 ≈2.8m。运行时微调用中继 !cone。
        // 2026-09-19 tune 持久化:/tune cone 域 2..45 直写 outer=cone/inner=cone×0.5,
        // 故下限放宽到 outer 2.0/inner 1.0(旧 5.0/2.0 会把 cone 2..5 的合法调参拒之门外;
        // 只放宽不收紧,存量 toml 全兼容)。
        CONE_OUTER_DEG = builder.comment("outer half-angle in degrees (8 = near-parallel beam)").defineInRange("coneOuterDeg", 8.0, 2.0, 60.0);
        CONE_INNER_DEG = builder.comment("inner half-angle in degrees (= full-brightness core)").defineInRange("coneInnerDeg", 4.0, 1.0, 55.0);
        BEAM_DENSITY = builder.comment("volumetric beam density (0 = off)").defineInRange("beamDensity", 0.25, 0.0, 1.0);
        ATTEN_K = builder.comment("distance falloff K (tune atten; 20.0 = GLSL TACLIGHT_ATTEN_K frozen 2026-09-06)").defineInRange("attenK", 20.0, 0.2, 20.0);
        KNEE_GAIN = builder.comment("near-field soft-knee G (tune knee; 2.0 = default-on)").defineInRange("kneeGain", 2.0, 0.2, 8.0);
        SCAT_FLOOR = builder.comment("volumetric axial floor share f (tune scat; 0 = pure side-view)").defineInRange("scatFloor", 0.04, 0.0, 0.9);
        BEAM_CAP_M = builder.comment("volumetric overlap soft-cap multiplier m (tune beamcap; cap=2.0*m)").defineInRange("beamCapM", 1.0, 0.25, 8.0);
        VOXEL_ENABLED = builder.comment("voxel DDA occlusion grid (tune voxel; false = fallback SSO)").define("voxelEnabled", true);
        // held(2026-10-06 用户需求"能不能调那个类似火把的亮度值"):Iris/Oculus 的 heldBlockLightValue
        // —— 手持光照值,方块光等级量纲 0..15,光影包用它算玩家周围氛围光(Complementary 的
        // Dynamic Handheld Lighting 选项,默认 Normal=开)。10 = 2026-09-04 用户体感定案
        // (15 像火把太强、5 基本看不见)。**与锥形主光无关**(那条走 SSBO)。
        // 注意:本键是整数(defineInRange 的 int 重载)⇒ Sink 必须走 setInt/getInt。
        HELD_LIGHT_LEVEL = builder.comment("held block light value 0-15 (player ambient light, shader heldBlockLightValue; 10 = tuned default)").defineInRange("heldLightLevel", 10, 0, 15);
        GUN_MULTIPLIER = builder.comment("gun-mounted light intensity multiplier").defineInRange("gunMultiplier", 1.1, 0.1, 3.0);
        // M5 多人:远程玩家灯的收集护栏(旁观视角与多人调试方案.md §4.3)
        REMOTE_LIGHT_MAX_DIST = builder.comment("max distance (blocks) to render other players' lights").defineInRange("remoteLightMaxDist", 48.0, 8.0, 128.0);
        REMOTE_LIGHT_MAX_COUNT = builder.comment("max number of remote lights (nearest kept; SSBO hard cap 8)").defineInRange("remoteLightMaxCount", 8, 1, 8);
        // 自身灯总闸(2026-09-03 用户需求:枪灯测试时自身手电/枪灯干扰观察):
        // false = 本客户端不上传自身两盏灯(远程灯照常),单变量看远程枪灯效果。
        // 默认 true(零行为变化);中继 !selflight 可运行时翻转,供测试对照。
        SELF_LIGHT_ENABLED = builder.comment("upload own handheld+gun lights (false = observe remote lights only)").define("selfLightEnabled", true);
        builder.pop();
        SPEC = builder.build();
    }

    public static float cosDeg(double deg) { return (float) Math.cos(Math.toRadians(deg)); }

    private TacLightConfig() {}
}

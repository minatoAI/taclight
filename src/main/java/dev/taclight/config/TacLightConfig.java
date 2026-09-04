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
        CONE_OUTER_DEG = builder.comment("outer half-angle in degrees").defineInRange("coneOuterDeg", 32.0, 5.0, 60.0);
        CONE_INNER_DEG = builder.comment("inner half-angle in degrees").defineInRange("coneInnerDeg", 18.0, 2.0, 55.0);
        BEAM_DENSITY = builder.comment("volumetric beam density (0 = off)").defineInRange("beamDensity", 0.05, 0.0, 1.0);
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

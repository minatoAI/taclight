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

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("TacLight spotlight tuning (client-side visuals)").push("spotlight");
        // radius 语义 = 基准半径(参考亮度 6.0 下);实际半径由上传侧按 √亮度 缩放(doc06 §8.7)
        RADIUS = builder.comment("reference radius (blocks) @ intensity 6.0; effective radius scales as sqrt(intensity/6.0)").defineInRange("radius", 56.0, 4.0, RADIUS_MAX);
        INTENSITY = builder.comment("light intensity").defineInRange("intensity", 6.0, 0.5, 30.0);
        CONE_OUTER_DEG = builder.comment("outer half-angle in degrees").defineInRange("coneOuterDeg", 32.0, 5.0, 60.0);
        CONE_INNER_DEG = builder.comment("inner half-angle in degrees").defineInRange("coneInnerDeg", 18.0, 2.0, 55.0);
        BEAM_DENSITY = builder.comment("volumetric beam density (0 = off)").defineInRange("beamDensity", 0.05, 0.0, 1.0);
        GUN_MULTIPLIER = builder.comment("gun-mounted light intensity multiplier").defineInRange("gunMultiplier", 1.1, 0.1, 3.0);
        builder.pop();
        SPEC = builder.build();
    }

    public static float cosDeg(double deg) { return (float) Math.cos(Math.toRadians(deg)); }

    private TacLightConfig() {}
}

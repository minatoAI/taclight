package dev.taclight.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** 聚光灯客户端配置(仅视觉。全部为施工图默认值,游戏内 config/taclight-client.toml 可调)。 */
public final class TacLightConfig {
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
        RADIUS = builder.comment("cone radius (blocks)").defineInRange("radius", 24.0, 4.0, 64.0);
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

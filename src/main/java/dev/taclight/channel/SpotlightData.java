package dev.taclight.channel;

/** 单个聚光灯数据(与 GLSL TacLightSpot 逐字节对应,std430)。 */
public record SpotlightData(
        float posX, float posY, float posZ, float radius,
        float red, float green, float blue, float intensity,
        float dirX, float dirY, float dirZ, float type,
        float cosOuter, float cosInner, float coneReservedZ, float coneReservedW,
        float anisotropy, float density, float beam, float vlReservedW,
        float cookieR, float cookieG, float cookieB, float cookieA) {

    public static SpotlightData spot(float px, float py, float pz, float radius,
                                     float r, float g, float b, float intensity,
                                     float dx, float dy, float dz,
                                     float cosOuter, float cosInner) {
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1e-6f) { dx /= len; dy /= len; dz /= len; }
        return new SpotlightData(px, py, pz, radius, r, g, b, intensity,
                dx, dy, dz, 1.0f, cosOuter, cosInner, 0f, 0f,
                0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);
    }

    /** 带体积束参数的聚光灯(第 5 个 vec4:anisotropy/density/beam/reserved)。
     *  anisotropy=0.55:M3 体积光 HG 相位的各向异性系数——前向散射强,
     *  光束沿照射方向最亮(手电束感);0 = 各向同性雾球(无方向感)。 */
    public static final float BEAM_ANISOTROPY = 0.55f;

    public static SpotlightData spotBeam(float px, float py, float pz, float radius,
                                         float r, float g, float b, float intensity,
                                         float dx, float dy, float dz,
                                         float cosOuter, float cosInner,
                                         float density, float beam) {
        SpotlightData base = spot(px, py, pz, radius, r, g, b, intensity, dx, dy, dz, cosOuter, cosInner);
        return new SpotlightData(base.posX(), base.posY(), base.posZ(), base.radius(),
                base.red(), base.green(), base.blue(), base.intensity(),
                base.dirX(), base.dirY(), base.dirZ(), base.type(),
                base.cosOuter(), base.cosInner(), 0f, 0f,
                BEAM_ANISOTROPY, density, beam, 0f, -1f, 0f, 1f, 0f);
    }

    /** 空数据:光强 0(等同于关闭)。 */
    public static SpotlightData off() {
        return new SpotlightData(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** F2(2026-08-30):自体胶囊 —— cookie 槽语义扩展(SSBO 布局 96B 不变)。
     *  cookie = (灯位→胶囊中心偏移.xyz, 胶囊半径);胶囊竖直半高由 GLSL 常量
     *  TACLIGHT_SELF_CAP_HALF 定。半径 >0 启用 GLSL 侧豁免:SSO 步进点落在
     *  胶囊内不视为遮挡(灯锚在身体上,身体半影全弥散,无硬阴影边)。
     *  默认 cookie w=0(未启用),旧灯数据两侧行为一致。 */
    public SpotlightData withSelfCapsule(float capOffX, float capOffY, float capOffZ, float capRadius) {
        return new SpotlightData(posX, posY, posZ, radius,
                red, green, blue, intensity,
                dirX, dirY, dirZ, type,
                cosOuter, cosInner, coneReservedZ, coneReservedW,
                anisotropy, density, beam, vlReservedW,
                capOffX, capOffY, capOffZ, capRadius);
    }
}

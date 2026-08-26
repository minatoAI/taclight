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

    /** 带体积束参数的聚光灯(第 5 个 vec4:anisotropy/density/beam/reserved)。 */
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
                0f, density, beam, 0f, -1f, 0f, 1f, 0f);
    }

    /** 空数据:光强 0(等同于关闭)。 */
    public static SpotlightData off() {
        return new SpotlightData(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}

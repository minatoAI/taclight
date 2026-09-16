package dev.taclight.channel;

/** 单个聚光灯数据(与 GLSL TacLightSpot 逐字节对应,std430)。 */
public record SpotlightData(
        float posX, float posY, float posZ, float radius,
        float red, float green, float blue, float intensity,
        float dirX, float dirY, float dirZ, float type,
        float cosOuter, float cosInner, float coneReservedZ, float coneReservedW,
        float sideFloor, float density, float beam, float vlReservedW,
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

    /** 带体积束参数的聚光灯(第 5 个 vec4:sideFloor/density/beam/reserved)。
     *  sideFloor=0.04(2026-09-05 侧面相位定案+实测定标):GLSL phase = NORM·(f + (1−f)·sin²θ),
     *  f = 轴向底亮份额——正侧 90° 视角最亮(丁达尔效应服务旁观者),正对/沿轴视角
     *  只剩 f 份额,不再与表面照明叠加刺眼;0 = 纯侧面(正对光源零体积光)。 */
    public static final float BEAM_SIDE_FLOOR = 0.04f;

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
                BEAM_SIDE_FLOOR, density, beam, 0f, -1f, 0f, 1f, 0f);
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
                sideFloor, density, beam, vlReservedW,
                capOffX, capOffY, capOffZ, capRadius);
    }

    /** 时间复用置信度写入口(2026-09-06 !tm 立项):vlParams.w 槽位(原恒 0 保留),
     *  GLSL composite1 混合权重 = 0.75 × 本值;首帧/灯开关/瞬移 → 0 = 全新鲜。 */
    public SpotlightData withVlReservedW(float w) {
        return new SpotlightData(posX, posY, posZ, radius,
                red, green, blue, intensity,
                dirX, dirY, dirZ, type,
                cosOuter, cosInner, coneReservedZ, coneReservedW,
                sideFloor, density, beam, w,
                cookieR, cookieG, cookieB, cookieA);
    }
}

#version 430 core
/*
 * composite1 · M3 体积光束(doc06 §2.8):
 *   沿像素视线 raymarch,每个采样点:实心几何深度遮挡(depthtex1,视图空间
 *   线性距离比较——热修 13 同款,设备深度域必漏检)→ 锥判定(与表面照明
 *   同一 smoothstep 软边)→ D6 衰减 × HG 相位(各向异性,Java 侧 g=0.55)
 *   × 密度(vlParams.y,配置 beamDensity)累加各灯散射;
 *   贴灯豁免(TACLIGHT_SSO_SELF_FREE,与 SSO 同值):自身体/枪身不切光束。
 *   输出 colortex4;final 中 additive 合成,bloom 从 scene+beam 提取。
 * 性能:全屏 24 步 + IGN 抖动(bloom/颗粒融合残条带);步数/增益旋钮见下。
 */
/* DRAWBUFFERS:4 */
#include "/lib/taclight_common.glsl"

// depthtex1(实心几何深度)声明来自 lib/taclight_common.glsl;F1 起本 pass
// 不再采样 depthtex0(march 终点与遮挡统一实心语义)。

in vec2 texcoord;
layout(location = 0) out vec4 taclightVL;

#define TACLIGHT_VL_STEPS 32   // F5(2026-08-30):24→32,条纹更细
// 色调管线 v2:colortex0/合成改线性域后,光束在 final 中直接线性相加(旧域等效
// 贡献 ≈ b^2.2,新域 = b 本身);1.4→0.32 为同观感重校(核心亮度以 B0 截图对齐)。
#define TACLIGHT_BEAM_GAIN 0.5
#define TACLIGHT_VL_MAX_DIST 96.0   // 天空像素的 march 终点(= radiusMax)

void main() {
    vec3 vl = vec3(0.0);
    if (lightCount > 0u) {
        // F1(2026-08-30):march 终点用 depthtex1(实心几何)而非 depthtex0。
        // 雨/玻璃等半透写 depthtex0 且逐帧移动,会让光束终点逐帧抖动;
        // 体积光束与深度遮挡(下方循环)统一以实心表面为终点语义。
        float depth = texture(depthtex1, texcoord).r;
        // 视线方向:z 取 0.5(中程点)反投影构造——天空像素 depth=1.0 反投影
        // 落在远平面,坐标巨大且有精度风险;中程点方向与远场方向一致且数值安全
        vec4 farPt = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, 0.5, 1.0);
        vec3 rd = normalize(farPt.xyz / farPt.w);
        float maxDist = depth < 1.0 ? length(taclight_depth_to_view(texcoord, depth)) : TACLIGHT_VL_MAX_DIST;

        // 灯数据预取(view 空间),步进内层只做数学
        uint nL = min(lightCount, 8u);
        vec3 lv[8];
        vec3 dv[8];
        for (uint i = 0u; i < nL; i++) {
            lv[i] = taclight_scene_to_view(taclight_world_to_scene(lights[i].posRadius.xyz));
            dv[i] = normalize(mat3(gbufferModelView) * normalize(lights[i].dirType.xyz));
        }

        float jitter = taclight_ign(gl_FragCoord.xy);
        for (int s = 0; s < TACLIGHT_VL_STEPS; s++) {
            float t = (float(s) + 0.5 + (jitter - 0.5) * 0.35)
                      / float(TACLIGHT_VL_STEPS) * maxDist + 0.05;
            vec3 sp = rd * t;
            // 深度遮挡:步进点在实心几何之后 → 该点无直射光
            vec2 suv = taclight_view_to_uv(sp);
            float sceneDist = 1e9;
            if (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0)))) {
                sceneDist = -taclight_depth_to_view(suv, texture(depthtex1, suv).r).z;
            }
            for (uint i = 0u; i < nL; i++) {
                TacLightSpot L = lights[i];
                if (L.dirType.w < 0.5) continue;
                vec3 toL = lv[i] - sp;
                float d = length(toL);
                float radius = L.posRadius.w;
                if (d > radius || d < 1e-3) continue;
                // 遮挡(贴灯豁免:紧贴光源的自身体,阴影半影物理上全弥散)
                if (t > sceneDist + 0.10 && distance(sp, lv[i]) >= TACLIGHT_SSO_SELF_FREE) continue;
                float cosAng = dot(-toL / d, dv[i]);
                float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
                if (spot <= 0.001) continue;
                // HG 相位:视线方向 × 光传播方向(灯→采样点),g=vlParams.x
                float ph = taclight_hg(dot(rd, -toL / d), L.vlParams.x);
                // F4(2026-08-30):近场正则化。灯锚在玩家头侧(前 0.35m 起),
                // march 采样点可距灯 <0.1m,反平方在此发散 → 近场亮核白爆;
                // 体素采样不早于 0.75m(≈灯锚到枪口/手电前沿的尺度),
                // 与 taclight_soft_knee 的近场压缩互补。
                vl += L.colorIntensity.rgb * L.colorIntensity.a
                      * (spot * taclight_attenuation(max(d, 0.75), radius, L.cone.z) * ph) * L.vlParams.y;
            }
        }
        vl *= TACLIGHT_BEAM_GAIN / float(TACLIGHT_VL_STEPS);
    }
    taclightVL = vec4(vl, 1.0);
}

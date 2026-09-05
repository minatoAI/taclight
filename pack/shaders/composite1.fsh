#version 430 core
/*
 * composite1 · M3 体积光束(doc06 §2.8):
 *   沿像素视线 raymarch,每个采样点:实心几何深度遮挡(depthtex1,视图空间
 *   线性距离比较——热修 13 同款,设备深度域必漏检)→ 锥判定(与表面照明
 *   同一 smoothstep 软边)→ **灯侧体素遮挡**(2026-09-05 用户实测"墙后灯束
 *   穿墙糊到观察侧墙面"立项:深度门只切视线已过实心表面的采样,切不住
 *   "采样点在空气里、但灯→采样点光路穿墙"的锥体越墙部分;与表面照明同一
 *   taclight_vox_transmit DDA/同一栅格,栅格无效回退可见)→ D6 衰减 ×
 *   侧面相位(sin²θ,vlParams.x 轴向底亮,Java 侧 BEAM_SIDE_FLOOR=0.04:
 *   侧面服务型丁达尔,正面压暗)× 密度(vlParams.y,配置 beamDensity)累加;
 *   贴灯豁免(TACLIGHT_SSO_SELF_FREE,与 SSO 同值):自身体/枪身不切光束。
 *   输出 colortex4;final 中 additive 合成,bloom 从 scene+beam 提取。
 * 性能:全屏 64 步 + IGN 抖动(bloom/颗粒融合残条带);步数/增益旋钮见下;
 *   灯侧 DDA 只对"锥内且权重可见"的采样执行(权重 <1e-4 直接跳过)。
 */
/* DRAWBUFFERS:4 */
#include "/lib/taclight_common.glsl"

// depthtex1(实心几何深度)声明来自 lib/taclight_common.glsl;F1 起本 pass
// 不再采样 depthtex0(march 终点与遮挡统一实心语义)。

in vec2 texcoord;
layout(location = 0) out vec4 taclightVL;

#define TACLIGHT_VL_STEPS 64   // 2026-09-05:32→64,细锥采样翻倍(32 步对远背景视线步长
                               // 1~3m 会把近场细锥整段跨过=侧视不可见三因之一,证据
                               // evidence/2026-09-05-beam-visibility-diagnosis/)
// 色调管线 v2:colortex0/合成改线性域后,光束在 final 中直接线性相加(旧域等效
// 贡献 ≈ b^2.2,新域 = b 本身);1.4→0.32 为同观感重校(核心亮度以 B0 截图对齐)。
#define TACLIGHT_BEAM_GAIN 1.0   // 全局体积亮度标量(2026-09-05 由 0.5 翻倍);侧面轮廓
                                 // 亮度由下方 BEAM_NORM 决定,重叠眩光由 BEAM_CAP 软上限兜底
// 侧面相位归一(2026-09-05 侧面相位定案+实测定标):phase = NORM·(f + (1−f)·sin²θ),
// 正侧 90° 相位 = NORM = 0.4(旧 HG g=0.55 侧视 0.0373 的 ~10.7 倍,空中光束项实测
// +~32/255,旧法 +7.7——用户"侧面轮廓再亮一点"定标);正对/沿轴只剩 f·NORM
// (f=vlParams.x 轴向底亮,Java 侧 BEAM_SIDE_FLOOR=0.04,!scat 透传)≈ 0.016/采样,
// 仍低于旧 HG 后向瓣 0.029,正面不与表面照明叠加刺眼。
#define TACLIGHT_BEAM_NORM 0.4
// 软上限默认帽(线性域),vlParams.z 倍率 m 相乘(!beamcap 旋钮透传):低于半帽点恒等
// =单灯观感零变化;多灯重叠亮度指数肩部渐近 cap——不许无限叠加刺眼(2026-09-05 用户需求)。
#define TACLIGHT_BEAM_CAP 2.0
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
            // 采样点世界坐标(灯侧体素 DDA 用;视图→世界全矩阵逆,含 bob,铁律 3)
            vec3 spWorld = taclight_view_to_world(sp);
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
                // 侧面相位(2026-09-05 用户定案):体积光只为侧面视角服务(丁达尔效应,
                // 强化旁观者)。sin²θ 剖面:θ=视线×光传播,正侧 90° 最亮;正对光源/沿轴
                // (0°/180°)只剩 vlParams.x 底亮份额 f → 正面不再与表面照明叠加刺眼。
                float cosT = dot(rd, -toL / d);
                float ph = TACLIGHT_BEAM_NORM * (L.vlParams.x + (1.0 - L.vlParams.x) * (1.0 - cosT * cosT));
                float atten = taclight_attenuation(max(d, 0.75), radius, L.cone.z);
                // 便宜门:远场/擦边采样权重低于显示噪声时直接跳过(不付 DDA 成本)
                if (spot * atten * ph < 1e-4) continue;
                // 灯侧体素遮挡(2026-09-05 用户实测"墙后灯束穿墙糊到观察侧墙面"立项):
                // 上面的深度门只切"视线已过实心表面"的采样,切不住"采样点在空气里、
                // 但灯→采样点光路穿墙"的锥体越墙部分(墙后灯的锥体从洞口/墙沿探进
                // 观察侧,空气采样照样过锥判定 → 漏光)。与表面照明同一 DDA/同一栅格;
                // 返回 -1(栅格无效或任一端出格)回退可见=旧行为,覆盖不足不假遮挡。
                float visVox = taclight_vox_transmit(L.posRadius.xyz, spWorld);
                if (visVox < 0.0) visVox = 1.0;
                if (visVox <= 0.003) continue;
                // F4(2026-08-30):近场正则化。灯锚在玩家头侧(前 0.35m 起),
                // march 采样点可距灯 <0.1m,反平方在此发散 → 近场亮核白爆;
                // 体素采样不早于 0.75m(≈灯锚到枪口/手电前沿的尺度),
                // 与 taclight_soft_knee 的近场压缩互补。
                vl += L.colorIntensity.rgb * L.colorIntensity.a
                      * (spot * atten * ph * visVox) * L.vlParams.y;
            }
        }
        vl *= TACLIGHT_BEAM_GAIN / float(TACLIGHT_VL_STEPS);
        // 多灯重叠软上限:分量级指数肩部——分量 ≤半帽点恒等(单灯观感零变化),
        // >半帽点渐近 cap;倍率 m 来自 vlParams.z(全局旋钮,所有灯同值,取槽 0)。
        // 坑105:GLSL 关系运算符不支持 vec3 与 float 混用(C1020 静默禁包),
        // 必须用 min+exp 分量级写法,不能用 vl<=sh 三元。
        float cap = TACLIGHT_BEAM_CAP * lights[0].vlParams.z;
        float sh = cap * 0.5;
        vec3 over = max(vl - vec3(sh), vec3(0.0));
        vl = min(vl, vec3(sh) + (cap - sh) * (1.0 - exp(-over / (cap - sh))));
    }
    taclightVL = vec4(vl, 1.0);
}

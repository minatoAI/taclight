#version 430 core
/*
 * composite1 · M3 体积光束(doc06 §2.8):
 *   沿像素视线 raymarch,每个采样点:实心几何深度遮挡(depthtex1,视图空间
 *   线性距离比较——热修 13 同款,设备深度域必漏检)→ 锥判定(与表面照明
 *   同一 smoothstep 软边)→ **灯侧体素遮挡**(2026-09-05 立项;2026-09-06
 *   方案二查表:前 4 灯查 colortex8 均向 D 表(双线性 4tap,条纹修复),
 *   其余/回退走逐采样 DDA)→ D6 衰减 × 侧面相位(sin²θ,侧面服务型丁达尔)
 *   × 密度累加。输出 colortex4;final 中 additive 合成,bloom 从 scene 提取(F6)。
 *
 * 时间复用(2026-09-06 用户批准立项,!tm 默认开):
 *   步数 64→32 + IGN 抖动逐帧旋转(黄金比例共轭,帧间去相关),再与上一帧
 *   历史(colortex9,clear=false)重投影混合:权重 = 0.75 × 逐灯置信度
 *   (vlParams.w,Java LightMotionConf 按灯位姿帧间差分;灯动/瞬移/开关 → 0
 *   = 全新鲜,防拖影)。有效性门:历史 a>0 且非 NaN + 重投影 uv 出界拒用 +
 *   march 终点距离差 <2m(实心表面/天空 96m 上限同源,disocclusion 拒用)。
 *   静态场景有效步数 = 32/(1−0.75) = 128;tm off = 64 步全新鲜(逐位旧行为)。
 *   半分辨率路线已被本包史否决(Oculus 1.8.0 buffer 全屏假设不成立,
 *   composite2.fsh 头注释 v0.10.0 热修),故为全分辨率减步。
 *   MRT:colortex4(rgb=光束 a=逐像素置信度)+ colortex9(rgb=光束 a=终点距离/256)。
 * 性能:tm 开时 raymarch 每帧步数减半;灯侧遮挡查表(方案二)不变。
 */
/* DRAWBUFFERS:49 */
#include "/lib/taclight_common.glsl"

// depthtex1(实心几何深度)声明来自 lib/taclight_common.glsl;F1 起本 pass
// 不再采样 depthtex0(march 终点与遮挡统一实心语义)。

in vec2 texcoord;
layout(location = 0) out vec4 taclightVL;      // -> colortex4(rgb=光束 a=置信度)
layout(location = 1) out vec4 taclightHistOut; // -> colortex9(rgb=光束 a=终点距离/256)

uniform sampler2D colortex9;   // 上一帧历史(clear=false;本 pass 读写,同 colortex7 曝光历史机制)
uniform int frameCounter;      // 抖动逐帧旋转相位(Iris 标准 uniform)

uniform float viewWidth;
uniform float viewHeight;

#define TACLIGHT_VL_STEPS 64   // tm off 路径步数(2026-09-06 定案:查表化后 64 可负担)
#define TACLIGHT_VL_STEPS_TM 32  // tm 开路径步数:时间累积补回有效步数(32/(1−0.75)=128)
// 时间复用参数(2026-09-06 立项定稿;TemporalReuseContract 同源钉死):
#define TACLIGHT_TM_WEIGHT 0.75             // 历史基础权重(×逐灯置信度;0.75≈3 帧衰减尾)
#define TACLIGHT_TM_DEPTH_TOL 2.0           // 终点距离一致性容差(格;视差余量 vs disocclusion 拖影)
#define TACLIGHT_TM_HISTORY_DIST_SCALE 256.0  // 历史 alpha 距离归一(0..256m,unorm16 ≈4mm 精度)
// 色调管线 v2:colortex0/合成改线性域后,光束在 final 中直接线性相加;1.4→0.32 为同观感重校。
#define TACLIGHT_BEAM_GAIN 1.0   // 全局体积亮度标定(2026-09-05 由 0.5 翻倍)
// 侧面相位归一(2026-09-05 侧面相位定案+实测定标):phase = NORM·(f + (1−f)·sin²θ)。
#define TACLIGHT_BEAM_NORM 0.4
// 软上限默认帽(线性域),vlParams.z 倍率 m 相乘(!beamcap 旋钮透传)。
#define TACLIGHT_BEAM_CAP 2.0
#define TACLIGHT_VL_MAX_DIST 96.0   // 天空像素的 march 终点(= radiusMax)

void main() {
    bool tmOn = (flags & TACLIGHT_FLAG_TEMPORAL) != 0u;
    int vlSteps = tmOn ? TACLIGHT_VL_STEPS_TM : TACLIGHT_VL_STEPS;
    // 方案二(2026-09-06):遮挡距离表可用性 = 头部 bit4 且视口 ≥ 表区(854×480 的
    // B 端 512×256 放得下;更小视口回退逐采样 DDA)。Java 侧仅在体素栅格有效时置位。
    bool occlTableOn = (flags & TACLIGHT_FLAG_OCCL_TABLE) != 0u
            && viewWidth >= TACLIGHT_OCCL_TABLE_SIZE_X
            && viewHeight >= TACLIGHT_OCCL_TABLE_SIZE_Y;

    // F1(2026-08-30):march 终点用 depthtex1(实心几何)而非 depthtex0(雨/玻璃
    // 逐帧移动会抖)。终点同时是时间复用的一致性校验量与历史归一存储值。
    float depth = texture(depthtex1, texcoord).r;
    // 视线方向:z 取 0.5(中程点)反投影构造——天空像素 depth=1.0 反投影
    // 落在远平面,坐标巨大且有精度风险;中程点方向与远场方向一致且数值安全
    vec4 farPt = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, 0.5, 1.0);
    vec3 rd = normalize(farPt.xyz / farPt.w);
    vec3 endView;
    float maxDist;
    if (depth < 1.0) {
        endView = taclight_depth_to_view(texcoord, depth);
        maxDist = length(endView);
    } else {
        endView = rd * TACLIGHT_VL_MAX_DIST;
        maxDist = TACLIGHT_VL_MAX_DIST;
    }

    vec3 vl = vec3(0.0);
    bool contributed = false;
    float lightConf = 1.0;
    if (lightCount > 0u) {
        // 灯数据预取(view 空间),步进内层只做数学
        uint nL = min(lightCount, 8u);
        vec3 lv[8];
        vec3 dv[8];
        for (uint i = 0u; i < nL; i++) {
            lv[i] = taclight_scene_to_view(taclight_world_to_scene(lights[i].posRadius.xyz));
            dv[i] = normalize(mat3(gbufferModelView) * normalize(lights[i].dirType.xyz));
        }

        float jitter = taclight_ign(gl_FragCoord.xy);
        if (tmOn) jitter = fract(jitter + 0.6180340f * float(frameCounter));
        for (int s = 0; s < vlSteps; s++) {
            float t = (float(s) + 0.5 + (jitter - 0.5) * 0.35)
                      / float(vlSteps) * maxDist + 0.05;
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
                // 强化旁观者)。正侧 90° 最亮;正对光源/沿轴只剩 vlParams.x 底亮份额 f。
                float cosT = dot(rd, -toL / d);
                float ph = TACLIGHT_BEAM_NORM * (L.vlParams.x + (1.0 - L.vlParams.x) * (1.0 - cosT * cosT));
                float atten = taclight_attenuation(max(d, 0.75), radius, L.cone.z);
                // 便宜门:远场/擦边采样权重低于显示噪声时直接跳过(不付查表/DDA 成本)
                if (spot * atten * ph < 1e-4) continue;
                // 灯侧体素遮挡:表模式(前 4 灯,双线性查表)→ 逐采样 DDA 回退
                // (-1 = 栅格无效回退可见,覆盖不足不假遮挡)。
                float visVox;
                if (occlTableOn && i < 4u) {
                    visVox = taclight_occl_table_vis(i, spWorld - L.posRadius.xyz);
                } else {
                    visVox = taclight_vox_transmit(L.posRadius.xyz, spWorld);
                    if (visVox < 0.0) visVox = 1.0;
                }
                if (visVox <= 0.003) continue;
                // F4(2026-08-30):近场正则化——体素采样不早于 0.75m,与 knee 互补。
                vl += L.colorIntensity.rgb * L.colorIntensity.a
                      * (spot * atten * ph * visVox) * L.vlParams.y;
                contributed = true;
                lightConf = min(lightConf, L.vlParams.w);
            }
        }
        vl *= TACLIGHT_BEAM_GAIN / float(vlSteps);
        // 多灯重叠软上限:分量级指数肩部——分量 ≤半帽点恒等(单灯观感零变化),
        // >半帽点渐近 cap;倍率 m 来自 vlParams.z(全局旋钮,所有灯同值,取槽 0)。
        // 坑105:GLSL 关系运算符不支持 vec3 与 float 混用(C1020 静默禁包),
        // 必须用 min+exp 分量级写法,不能用 vl<=sh 三元。
        float cap = TACLIGHT_BEAM_CAP * lights[0].vlParams.z;
        float sh = cap * 0.5;
        vec3 over = max(vl - vec3(sh), vec3(0.0));
        vl = min(vl, vec3(sh) + (cap - sh) * (1.0 - exp(-over / (cap - sh))));
    }
    // 逐像素置信度:贡献灯的最小值;无贡献像素 = 0(灯关瞬间光束即灭,不靠历史尾巴)
    float conf = contributed ? lightConf : 0.0;

    // ---- 时间复用混合(静态场景有效步数 32/(1−0.75)=128;拖影由置信度+有效性门兜底)----
    vec3 beam = vl;
    if (tmOn && conf > 0.0) {
        vec4 hist = texture(colortex9, texcoord);
        bool ok = hist.a > 1e-6 && hist.a == hist.a;
        vec2 uvPrev = ok ? taclight_reproject_prev_uv(endView) : vec2(-1.0);
        ok = ok && uvPrev.x > 0.0 && uvPrev.x < 1.0 && uvPrev.y > 0.0 && uvPrev.y < 1.0;
        if (ok) {
            ok = abs(hist.a * TACLIGHT_TM_HISTORY_DIST_SCALE - maxDist) < TACLIGHT_TM_DEPTH_TOL;
        }
        float wHist = ok ? TACLIGHT_TM_WEIGHT * conf : 0.0;
        beam = mix(vl, hist.rgb, wHist);
    }
    taclightVL = vec4(beam, conf);
    taclightHistOut = vec4(beam, maxDist / TACLIGHT_TM_HISTORY_DIST_SCALE);
}

#version 430 core
/*
 * composite · M1 锥光照明(doc06 §4 M1 / §2.7):
 *   radiance = Σ灯: albedo·NdL(diffuse) + GGX(spec) × spot(软边) × atten(D6) × vis(SSO)
 *   color    = 原版基线(colortex0,环境光/太阳光兜底) + knee(radiance) × GAIN
 * M1 为 additive 注入("技术正确"版);M2 转 ACES/自适应曝光后再评估全权渲染。
 *
 * G-Buffer 输入(布局契约见 lib/taclight_gbuffer.glsl 头注释):
 *   colortex1: xy=八面体法线 zw=lmcoord(RGBA16)
 *   colortex2: rgb=原始 albedo(无光图) a=LabPBR smoothness(RGBA16)
 *   colortex5: r=F0(介电) g=金属标志(阶段二新增,terrain/entities/hand 写)
 *
 * 语法注意(实机教训 2026-08-27):430 core 下禁用 varying/gl_FragData,
 * 必须 in/out + 显式 layout(location) 输出;顶点侧保持 330 compatibility。
 */
/* DRAWBUFFERS:0 */
/*
 * Buffer formats (Iris comment constants, pipeline-wide, M2/M3 HDR chain):
 * colortex0 RGBA16  scene + surface lighting (HDR-ready for bloom/ACES)
 * colortex1 RGBA16  G-Buffer normal+lm;composite2 起复用为 bloom 一级(照明后已消费)
 * colortex2 RGBA16  G-Buffer albedo+smooth;composite3 起复用为 bloom 二级(同上)
 * colortex3 RGBA32F G-Buffer viewpos + occlusion factor
 * colortex4 RGBA16  composite1 volumetric beam
 * colortex5 RGBA8   G-Buffer 材质(阶段二:F0/金属;M2 旧 bloom 半分辨率 buffer 已退役)
 * colortex7 RGBA16  adaptive exposure history (cleared = never)
const int colortex0Format = RGBA16;
const int colortex1Format = RGBA16;
const int colortex2Format = RGBA16;
const int colortex3Format = RGBA32F;
const int colortex4Format = RGBA16;
const int colortex5Format = RGBA8;
const int colortex7Format = RGBA16;
const bool colortex7Clear = false;
*/
#include "/lib/taclight_common.glsl"
#include "/lib/taclight_debug.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex5;   // G-Buffer 材质(阶段二)
// depthtex1 声明来自 lib/taclight_common.glsl(F1 起表面查找用它,不再采样 depthtex0)

in vec2 texcoord;
layout(location = 0) out vec4 taclightCompositeOut;

// 门探针已闭环(2026-08-27:照明系统实际在工作,场景/入射角误导判读)。
// 沉淀结论:平射时地面 ndl≈0.1 属物理正确;草丛背面片元由双面法线修复(gbuffers 侧)。
#define TACLIGHT_DBG_GATE_PROBE 0

// 法线审计盒(左下 25%×25%):显示 G-Buffer 解码属性法线 n*0.5+0.5。
// 逐面变色 = 健康;大面积单一色 = 属性法线损坏。2026-08-29 实测已验收(逐面变色),
// M1 闭环关闭;排障时可临时回 1。值本体在 lib/taclight_debug.glsl(DBG_STRIP == 1 启用)。

// 轮廓检测已随导数法线方案一并移除(热修 11):设备深度差随距离二次缩小,
// 固定阈值在中远距离必然漏检,混合四边形的垃圾法线就是黑边根因。

void main() {
    // 色调管线 v2(2026-08-30 消融实验结论):colortex0 自此为**线性**。
    // 旧版在 gamma 域把 M1 辐射加进原版画面,final 再整体 pow(2.2) 线性化——
    // 加法发生在错误的域:叠加项的感知贡献随底亮度非线性(暗底压扁/亮底放大),
    // 且彩色光斑经往返 gamma 后色相偏移。现在入口一次线性化,M1 与原版基线
    // 在同一线性域相加;final 不再做 pow(2.2)(契约:colortex0=线性,见 gbuffer 头)。
    vec3 color = pow(max(texture(colortex0, texcoord).rgb, 0.0), vec3(2.2));
    // F1(2026-08-30):表面查找用 depthtex1(实心几何)而非 depthtex0。
    // 原版雨/玻璃/水等半透写 depthtex0 但不进 depthtex1;雨丝逐帧移动会
    // 让"被照表面"逐帧跳变(雨天亮纹闪烁的主源之一),且雨/玻璃自身不该
    // 作为被照表面——光透过它们照亮背后的实体表面。SSO/M3 早已用 depthtex1,
    // 此处统一"表面 = 实心表面"语义。
    float depth = texture(depthtex1, texcoord).r;

#if TACLIGHT_DBG_STRIP == 3
    // ---- DBG 3:SSO 屏蔽掩码全屏成像(白=光可达,黑=被遮挡;多灯取最大)----
    if (depth < 1.0 && lightCount > 0u) {
        vec3 fv3 = taclight_depth_to_view(texcoord, depth);
        float vis3 = 0.0;
        for (uint i = 0u; i < lightCount && i < 8u; i++) {
            TacLightSpot L3 = lights[i];
            vec3 lv3 = taclight_scene_to_view(taclight_world_to_scene(L3.posRadius.xyz));
            float d3 = distance(fv3, lv3);
            if (d3 > L3.posRadius.w || L3.posRadius.w < 1e-3) continue;
            vis3 = max(vis3, taclight_sso(fv3, lv3, L3));
        }
        taclightCompositeOut = vec4(vec3(vis3), 1.0);
        return;
    }
    taclightCompositeOut = vec4(0.0, 0.0, 0.0, 1.0);
    return;
#endif

    if (depth < 1.0 && lightCount > 0u) {
        vec3 fragView = taclight_depth_to_view(texcoord, depth);

        if ((flags & TACLIGHT_FLAG_DEBUG) != 0u) {
            // ---- K 键绿锥诊断通道(几何/半径/软边,忽略材质)----
            vec3 glow = vec3(0.0);
            for (uint i = 0u; i < lightCount && i < 8u; i++) {
                glow += taclight_debug_green_cone(lights[i], fragView) * vec3(0.15, 1.0, 0.30);
            }
            color += glow * 1.8;
        } else {
            // ---- M1 真实表面照明 ----
            vec4 g1 = texture(colortex1, texcoord);
            vec4 g2 = texture(colortex2, texcoord);
            vec3 albedo = pow(g2.rgb, vec3(2.2));   // 线性域照明:albedo 一并解码
            // 法线(M1 热修 9b,2026-08-27):视图空间位置经 gbuffers 以 varying 写入
            // colortex3(与 ftransform 同源、透视正确插值 → 面内线性,导数=精确面法线;
            // 前两版失败原因:①顶点属性法线在 Embeddium 地形路径方向错误;②深度反投影
            // 位置有系统误差,面内法线被扰 + 轮廓处爆冲成"发光描边")。
            // 深度不连续(轮廓)检测:深度梯度超阈 → 回退属性法线(实体正确;地形轮廓
            // 边缘稍暗,可接受)。方向仍做构造校正。
            vec3 vPos = texture(colortex3, texcoord).xyz;
            // 法线回归属性路径(M1 热修 11,2026-08-27):导数法线在混合四边形
            // (近物+远地同处一个 2×2)输出两表面切向混合的垃圾 → ndl=0 黑边,
            // 且设备深度差随距离二次缩小,阈值无法可靠检出(8m 外 0.0037<0.004)。
            // 历史结论"Embeddium 地形属性法线不可信"出自 ndl 符号错误时代的门探针,
            // 判读已被污染;属性法线逐像素精确、零边缘垃圾,回归为主路径。
            vec3 n = taclight_decode_normal(g1.xy);
            if (dot(n, n) < 1e-4) {
                n = -normalize(vPos);              // 未写像素兜底:朝相机
            } else if (dot(n, vPos) > 0.0) {
                n = -n;                            // 构造性朝向:可见面必朝相机
            }
            vec3 v = -normalize(fragView);
            // 阶段二材质解码:LabPBR smoothness(旧占位 0.3 由 gbuffers 回落值兼容)
            // 粗糙度映射换为 LabPBR 标准的 (1-s)²(此前 1-s 线性);下限 0.20 是
            // 能量护栏——(1-s)² 下近镜面 GGX 分布项 D 峰值 ∝ 1/a⁴ 量级发散,
            // 0.20 + SPEC_DAMP 0.35 把同轴镜心压在 knee 平台以内;真镜面 glint
            // 的形状(非能量)留给 ACES 滚降呈现。
            float smoothness = g2.a;
            float roughness = clamp((1.0 - smoothness) * (1.0 - smoothness), 0.20, 1.0);
            vec4 g5 = texture(colortex5, texcoord);
            float metal = g5.g;
            // 金属:标准允许的简化(230-255 全按 255)→ F0 = albedo(彩色菲涅尔),
            // diffuse 清零(金属无体散射)。
            vec3 f0 = mix(vec3(g5.r), albedo, metal);

            vec3 radiance = vec3(0.0);
            for (uint i = 0u; i < lightCount && i < 8u; i++) {
                TacLightSpot L = lights[i];
                if (L.dirType.w < 0.5) continue;          // 预留:类型过滤
                vec3 lightView = taclight_scene_to_view(taclight_world_to_scene(L.posRadius.xyz));
                vec3 toFrag = fragView - lightView;
                float dist = length(toFrag);
                float radius = L.posRadius.w;
                if (dist > radius || radius < 1e-3) continue;   // 廉价门:半径窗口
                vec3 lf = toFrag / max(dist, 1e-4);       // 灯→片元(锥判定轴,与绿锥同式)
                float cosAng = dot(lf, normalize(mat3(gbufferModelView) * normalize(L.dirType.xyz)));
                float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
                if (spot <= 0.001) continue;              // 廉价门:锥外
                // M1 根因热修(2026-08-27):l 此前直接沿用灯→片元方向,同轴光下
                // dot(n,l) 恒负 → ndl 门拒绝全部像素(五轮"无白光"的真正根因);
                // 光照约定必须是 表面→灯。
                vec3 l = -lf;
                float ndl = max(dot(n, l), 0.0);
                if (ndl <= 0.0) continue;                 // 廉价门:背面
                // vis 按灯-相机几何分流(M1 热修 12):第一人称灯锚眼睛 = 同轴光,
                // "可见即无遮挡"是几何事实,SSO 步进只会沿轮廓假消光(黑边②实锤);
                // 第三人称灯在角色眼、与相机分离,存在真遮挡(实测树干透光),走 SSO。
                float vis;
                if (dot(lightView, lightView) < 0.25) {
                    vis = 1.0;
                } else {
                    vis = taclight_sso(fragView, lightView, L);
                    if (vis <= 0.003) continue;
                }

                vec3 lc = L.colorIntensity.rgb * L.colorIntensity.a;
                float atten = taclight_attenuation(dist, radius);
                vec3 diffuse = albedo * (ndl * (1.0 - metal));
                vec3 spec = taclight_ggx(n, v, l, roughness, f0) * (ndl * TACLIGHT_SPEC_DAMP);
                radiance += (diffuse + spec) * lc * (spot * atten * vis);
            }

            // NaN 品红警报:任何一盏灯的路径产生 NaN 会毒化整个辐射和
            if (radiance.x != radiance.x || radiance.y != radiance.y || radiance.z != radiance.z) {
                color = vec3(1.0, 0.0, 1.0);
            } else {
                color += taclight_soft_knee3(radiance) * TACLIGHT_LIGHT_GAIN;
            }

#if TACLIGHT_DBG_STRIP == 1
            // ---- 左下角法线审计盒:decode(colortex1) 视图法线可视化 ----
            if (texcoord.x < 0.25 && texcoord.y < 0.25) {
                taclightCompositeOut = vec4(n * 0.5 + 0.5, 1.0);
                return;
            }
#endif

#if TACLIGHT_DBG_GATE_PROBE
            // ---- 左下象限:灯0 门探针(与主循环相同的门,逐门显色)----
            if (texcoord.x < 0.5 && texcoord.y < 0.5) {
                TacLightSpot L = lights[0];
                vec3 lightView0 = taclight_scene_to_view(taclight_world_to_scene(L.posRadius.xyz));
                vec3 toFrag = fragView - lightView0;
                float dist0 = length(toFrag);
                float radius0 = L.posRadius.w;
                vec3 gate;
                if (L.dirType.w < 0.5) {
                    gate = vec3(1.0, 0.0, 0.0);                       // 红 = type 门
                } else if (dist0 > radius0 || radius0 < 1e-3) {
                    gate = vec3(0.0, 1.0, 0.0);                       // 绿 = 半径门
                } else {
                    vec3 lf0 = toFrag / max(dist0, 1e-4);
                    float cos0 = dot(lf0, normalize(mat3(gbufferModelView) * normalize(L.dirType.xyz)));
                    float spot0 = smoothstep(L.cone.x, L.cone.y, cos0);
                    float ndl0 = max(dot(n, -lf0), 0.0);   // 表面→灯(同主循环符号热修)
                    if (spot0 <= 0.001) {
                        gate = vec3(0.0, 0.0, 1.0);                   // 蓝 = 锥门
                    } else if (ndl0 <= 0.0) {
                        gate = vec3(1.0, 1.0, 0.0);                   // 黄 = 背面门
                    } else {
                        float vis0 = taclight_sso(fragView, lightView0, L);
                        if (vis0 <= 0.003) {
                            gate = vec3(0.0, 1.0, 1.0);               // 青 = SSO 门
                        } else {
                            float atten0 = taclight_attenuation(dist0, radius0);
                            gate = vec3(spot0, atten0, ndl0 * vis0);  // 全过 = 幅值编码
                        }
                    }
                }
                color = gate;
            }
#endif
        }
    }

    taclightCompositeOut = vec4(color, 1.0);
}

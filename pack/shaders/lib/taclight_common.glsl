// ============================================================================
// TacLight Shaders · SSBO 契约镜像(lib/taclight_common.glsl)v0.9.0
// 唯一真源(Single Source):taclight/src/main/java/dev/taclight/channel/SpotlightBufferLayout.java
// 守护:SpotlightBufferLayoutContract + UploaderSemanticContract(纯 JVM 契约测试)
//
// 【坐标语义 —— doc06 §2.5 铁律 3,v0.9.0 起生效】
//   posRadius.xyz / dirType.xyz 均为 **world** 坐标系;
//   scene-relative 转换只允许发生在消费侧,且必须经过下方
//   taclight_world_to_scene -> taclight_scene_to_view 两级封装。
//   历史教训:直接把 world 乘进 composite 的 gbufferModelView(R-only,
//   无平移)= 灯被摆到几百格外(v0.8.4 事故)。本文件其余代码禁止再出现
//   内联的坐标换算。
//
// 仅可被 #version 430 及以上的程序 include(composite* 等);gbuffers 用不到它。
// ============================================================================

#ifndef TACLIGHT_COMMON_INCLUDED
#define TACLIGHT_COMMON_INCLUDED

// 八面体法线解码(composite 消费端;编码在 gbuffers 侧)。
// 注意:Iris 的 #include 指令取整行作路径,行尾严禁跟注释(实机事故 2026-08-27)。
#include "/lib/taclight_gbuffer.glsl"
#include "/lib/taclight_style.glsl"

// ---- 头部 flags 位(与 SpotlightBufferLayout.java 逐位一致)----
#define TACLIGHT_FLAG_HAS_DATA     1u   // bit0
#define TACLIGHT_FLAG_DEBUG        2u   // bit1 K 键绿锥调试(doc06 §2.10)
#define TACLIGHT_FLAG_TIMING_PROBE 8u   // bit3 reserved 回读探针(DEBUG 构建才置位)

// ---- 每灯 96B · 6×vec4(std430,与 Java writeLight 写序一致)----
struct TacLightSpot {
    vec4 posRadius;       // xyz = **world** 坐标;w = 半径(格)
    vec4 colorIntensity;  // rgb = 线性色;a = 强度
    vec4 dirType;         // xyz = 归一化 world 方向(灯→目标);w = 类型(1=spot)
    vec4 cone;            // x = cos 外锥半角;y = cos 内锥半角;z/w 保留
    vec4 vlParams;        // x 各向异性 g,y 密度,z 光束强度(M3 体积光用)
    vec4 cookie;          // GLSL→Java 回写诊断槽位(探针阶段启用)
};

// 注意(铁律 2):不要在 shaders.properties 里声明 bufferObject.7 ——
// 那会让 Iris 自建同名缓冲覆盖模组绑定。此缓冲由模组创建并每帧更新,包只读。
layout(std430, binding = 7) buffer TacLightSSBO {
    uint  lightCount;     // 头偏移 0
    float vlIntensity;    // 头偏移 4
    uint  flags;          // 头偏移 8
    uint  reserved;       // 头偏移 12(时序探针回写字)
    TacLightSpot lights[];
};

// ---- 各阶段矩阵约定(doc06 §2.2 表)----
uniform mat4 gbufferModelView;         // composite/final:纯旋转(R-only)
uniform mat4 gbufferProjection;        // view→clip(composite 中有效)
uniform mat4 gbufferProjectionInverse; // clip→view(深度重建用)
uniform vec3 cameraPosition;

// ----------------------------------------------------------------------------
// 项目仅有的一对纵深入口(doc06 §2.2 规则 6):
// 全部光照代码只允许调用这两个函数做坐标换算,禁止内联重复公式。
// ----------------------------------------------------------------------------

/** world → 场景相对(world − cameraPosition)。v0.9.0 起上传侧为 world,先走这一步。 */
vec3 taclight_world_to_scene(vec3 worldPos) {
    return worldPos - cameraPosition;
}

/** 场景相对 → 视图空间。composite/final 阶段 gbufferModelView 是 R-only,只能乘 |mat3|。 */
vec3 taclight_scene_to_view(vec3 scenePos) {
    return mat3(gbufferModelView) * scenePos;
}

/** 视图空间 → 屏幕 uv(供遮挡步进等屏幕空间运算取参考 uv)。 */
vec2 taclight_view_to_uv(vec3 viewPos) {
    vec4 clip = gbufferProjection * vec4(viewPos, 1.0);
    return (clip.xy / clip.w) * 0.5 + 0.5;
}

/** 深度缓冲反投影:uv + 设备深度 → 视图空间表面位置(composite 光照的地基)。 */
vec3 taclight_depth_to_view(vec2 uv, float depth) {
    vec4 ndc  = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = gbufferProjectionInverse * ndc;
    return view.xyz / view.w;
}

// ----------------------------------------------------------------------------
// 距离衰减(doc06 §2.3 定义 + §5 D6 已批:平滑反平方,k 由半径标定)
//   atten(0)=1,atten(radius)=0,中段长尾自然;分母 1+k·d² 无奇点。
// v0.9.0 M0 热修:绿锥预览即用本函数(替换旧 (1-d/r) 线性淡出——线性在
// 半半径处只剩 50% 亮度,视觉半径"提前死亡",即实测"照明距离不足"主因)。
// M1 表面照明直接复用本函数,不再另写。
// ----------------------------------------------------------------------------
#define TACLIGHT_ATTEN_K 2.0   // 标定常数:越小尾越长;2.0 = 0.8r 处约 16% 亮度
float taclight_attenuation(float dist, float radius) {
    float k = TACLIGHT_ATTEN_K / max(radius * radius, 1e-4);
    float tail = 1.0 / (1.0 + k * radius * radius);   // = 1/(1+K)
    float e = 1.0 / (1.0 + k * dist * dist) - tail;
    return max(e, 0.0) / (1.0 - tail);
}

// ----------------------------------------------------------------------------
// 近场软肩压缩(v0.9.0 M0 热修,实机反馈:近场过曝糊死、看不清被照物体)
//   f(x) = x / (1 + G·x) —— 斜率设计:
//   · x 小(远场尾部)时 f ≈ x,几乎不衰减(远处保持可见);
//   · x 大(近场)被压向上限 1/G(近场显著压暗,不再削顶);
//   · G = TACLIGHT_KNEE_GAIN 是用户可调的"亮度曲线"旋钮:越大近场压得越狠、
//     近远对比越大;调小则整体趋平(退化为旧行为 G→0)。
// 效果标定(K=2, r=56):0.25r 处 0.83→0.31,0.5r 处 0.50→0.25,
// 0.8r 处 0.158→0.120(×合成增益 1.8 = 0.216 仍清晰可见);近远比 5.3:1 → 2.6:1。
// 参考本项目 v0.8.2 软膝(taclight_knee = e/(1+e))的行为语义,公式重写;
// M1 表面高光压缩直接复用本函数,完整 ACES 仍在 M2。
// ----------------------------------------------------------------------------
#define TACLIGHT_KNEE_GAIN 2.0
float taclight_soft_knee(float x) {
    return x / (1.0 + TACLIGHT_KNEE_GAIN * x);
}

/** knee 的逐通道版本(表面照明 radiance 是 vec3)。 */
vec3 taclight_soft_knee3(vec3 x) {
    return x / (1.0 + TACLIGHT_KNEE_GAIN * x);
}

// ----------------------------------------------------------------------------
// 多源感知肩部(v0.10.1,08-30 深夜 interfere 判定:双灯同点线性叠加过亮,用户复核)
//   理论定位:渲染方程 L_o = Σ_i f_r·L_i·(n·ω_i) 对多光源是线性叠加(光的超叠加原理),
//   radiance 的求和保持不动;但人眼亮度感知是压缩的(Stevens 幂律,指数 ~0.33),
//   "N 灯同点应只比单灯略亮"属于感知/显示域 —— 在 filmic 管线里由肩部曲线承担
//   (与色调映射 shoulder 同构;此处放在 M1 输出级,锚点 = 单灯名义核心辐射 T,
//   即曝光理论"把名义曝光锚在肩部起点"的做法)。
//   f(x) = x                    (x ≤ T:逐像素恒等 —— 单灯外观零改变)
//        = T + H·tanh((x-T)/H)  (x > T:C1 平滑收敛,H = q·T)
//   性质:N 灯同点 ≤ T+H = (1+q)·T 恒有界(对未知超和簇也鲁棒);等强度双灯
//   (2T)→ ≈(1+q)·T;q=0.15 → ≤1.22×;非重叠双灯互不影响(逐像素压缩,
//   非按灯数归一,远场/第二灯独立光斑不受压)。
// ----------------------------------------------------------------------------
#define TACLIGHT_SHOULDER_T 0.55
#define TACLIGHT_SHOULDER_Q 0.15
vec3 taclight_shoulder3(vec3 x, float t, float head) {
    vec3 e = max(x - vec3(t), vec3(0.0));
    return min(x, vec3(t)) + head * vec3(tanh(e.x / head), tanh(e.y / head), tanh(e.z / head));
}

// ----------------------------------------------------------------------------
// M1 · 表面照明数学(全部公开标准公式,自写实现)
// ----------------------------------------------------------------------------

/** GGX 镜面(Torrance-Sparrow:GGX 分布 × Smith 遮蔽 × Schlick 菲涅尔)。枪身反光来源。
 *  阶段二:f0 升为 vec3 —— 金属的 F0 = albedo(彩色),介电为常量灰。 */
vec3 taclight_ggx(vec3 n, vec3 v, vec3 l, float roughness, vec3 f0) {
    vec3 h = normalize(v + l);
    float ndh = max(dot(n, h), 0.0);
    float ndv = max(dot(n, v), 1e-3);
    float ndl = max(dot(n, l), 0.0);
    float a = roughness * roughness;
    float a2 = a * a;
    float d = a2 / (3.14159265 * pow(ndh * ndh * (a2 - 1.0) + 1.0, 2.0));
    float k = a * 0.5;
    float g = (ndv / (ndv * (1.0 - k) + k)) * (ndl / (ndl * (1.0 - k) + k));
    vec3 f = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    return d * g * f;
}

// ----------------------------------------------------------------------------
// M1 · 屏幕空间遮挡(doc06 §2.7:普通 Iris 包的可行上限,写死接受)
// 沿片元→灯的视图空间射线步进 24 步(F5,2026-08-30:16→24,台阶更细),
// 投影回屏幕与 depthtex1(实心几何深度,不含半透明)比较;被挡则按遮挡者
// 材质系数消光。已知局限:视锥外的遮挡者不投影(墙后物体不挡光)。
// IGN 抖动把台阶软化成噪点(风格层颗粒进一步融合)。
// 遮挡系数 = colortex3.a(gbuffers 写,分类表 pack/shaders/block.properties):
//   1.0 实心 / 0.6 树叶 / 0.25 软植被(草/花/作物)——镂空植被按全挡处理会把
//   满草场景的地面消成死黑、只剩草叶亮(实机实锤),半透折中。
// ----------------------------------------------------------------------------
uniform sampler2D depthtex1;
uniform sampler2D colortex3;   // rgb=视图空间位置(gbuffers varying 直写) a=遮挡系数

#define TACLIGHT_SSO_SELF_FREE 0.6   // 贴灯豁免半径:自身体/枪身贴着灯,其阴影半影
                                     // 物理上全弥散,按"可见即照亮"豁免(消脚下暗环)

// F2(2026-08-30):自体胶囊豁免。灯锚在玩家身体上(眼位+固定偏移),身体
// 对"从身体内发出的光"不是硬遮挡物——半影覆盖全身,不存在锐利阴影边。
// 只豁免贴灯球不够:第三人称下腿部距灯 >0.6m,身体仍把锥的下半切出硬边
// (0830 截图"左右不对称"根因 R2)。Java 侧每灯在 cookie 槽写
// (灯→胶囊中心偏移.xyz, 胶囊半径);胶囊竖直半高为常量。w<=0 = 未启用。
#define TACLIGHT_SELF_CAP_HALF 1.05

float taclight_sso(vec3 fragView, vec3 lightView, TacLightSpot L) {
    const int STEPS = 24;   // F5:16→24
    vec3 rayVec = lightView - fragView;
    float dither = taclight_ign(gl_FragCoord.xy);
    float blocked = 0.0;
    // 自体胶囊(视图空间):中心 = 灯位 + mat3 旋转后的偏移;轴 = 世界竖直
    // 经 mat3 旋转。每灯一次预计算,步进内只做点积/距离。
    bool capOn = L.cookie.w > 0.0;
    vec3 capC = lightView + mat3(gbufferModelView) * L.cookie.xyz;
    vec3 capAxis = mat3(gbufferModelView) * vec3(0.0, 1.0, 0.0);
    for (int i = 1; i <= STEPS; i++) {
        float t = (float(i) - 0.5 + dither * 0.9) / float(STEPS);
        vec3 sp = fragView + rayVec * t;
        if (distance(sp, lightView) < TACLIGHT_SSO_SELF_FREE) continue;   // 贴灯豁免
        if (capOn) {
            vec3 rel = sp - capC;
            float h = clamp(dot(rel, capAxis), -TACLIGHT_SELF_CAP_HALF, TACLIGHT_SELF_CAP_HALF);
            if (distance(sp, capC + capAxis * h) < L.cookie.w) continue;  // 自体胶囊豁免
        }
        vec2 suv = taclight_view_to_uv(sp);
        if (any(lessThan(suv, vec2(0.0))) || any(greaterThan(suv, vec2(1.0)))) continue;
        // 热修 13:比较必须在视图空间线性距离上做。设备深度 ≈ near/距离,
        // 同样世界距离差的设备深度值随距离二次缩小——设备深度域比较的 bias
        // 在 5m 外比半格厚真遮挡的信号还大,必漏检,与热修 11 删除轮廓
        // 检测是同款陷阱。
        vec3 occView = taclight_depth_to_view(suv, texture(depthtex1, suv).r);
        float sceneDist = -occView.z;
        float rayDist = -sp.z;
        float biasW = 0.06 + 0.04 * t;   // 世界尺度防自表面 acne;远小于任何真遮挡物
        if (rayDist > sceneDist + biasW) {
            // F2 扩展(2026-08-30):遮挡者本体落在自体胶囊内 → 不计。
            // 场景:第三人称相机下,"灯照亮的远墙"与"玩家身体"在屏幕上重叠,
            // 世界空间射线并未穿体,但深度测试拿身体的深度当遮挡者 → 整面
            // 墙的光斑被自己的身体假消光。按遮挡者的世界位置(非采样点的
            // 屏幕投影)做胶囊判定;occView 与 sceneDist 同源,零额外反投影。
            bool selfOcc = false;
            if (capOn) {
                vec3 orel = occView - capC;
                float oh = clamp(dot(orel, capAxis), -TACLIGHT_SELF_CAP_HALF, TACLIGHT_SELF_CAP_HALF);
                selfOcc = distance(occView, capC + capAxis * oh) < L.cookie.w;
            }
            if (!selfOcc) blocked += texture(colortex3, suv).a;   // 遮挡者材质系数(植被半透)
        }
    }
    // 陡消光(F5 软化 2026-08-30:2.4→1.2)——16→24 步后单步遮挡占比变小,
    // 旧的 2.4 斜率让细遮挡物(树干)从"消到不可见"退化为"半消光闪烁带";
    // 1.2 保持"全挡趋灭、边缘缓降",把 IGN 噪闪幅度压一半,代价是极细
    // 遮挡物透光略增(与树叶半透折中同一方向)。
    float occ = blocked / float(STEPS);
    return pow(max(1.0 - occ * 1.2, 0.0), 2.0);
}

/** M1 表面照明总增益(与 knee 配合;实测反馈驱动调参)。2026-08-29 实测过曝,2.0→1.0。 */
#define TACLIGHT_LIGHT_GAIN 2.2

/** F3(2026-08-30):spec 项能量钳制。GGX 分布项(d)在低 roughness 下峰值可到
 *  10+,× intensity 6 → 镜面尖峰独占 ~2.0 辐射,与 diffuse/bloom/体积多链叠加
 *  推出饱和平台(R1 高频推手)。diffuse 有 albedo 纹理作视觉载体,spec 是无
 *  载体的窄峰——压幅不压形:0.35 保留高光形状,削去能量尖峰。 */
#define TACLIGHT_SPEC_DAMP 0.35

// ----------------------------------------------------------------------------
// M0 · K 键调试绿锥(doc06 §2.4 锥判定公式 × §2.10 诊断方式):
// 忽略材质,锥内输出绿色,用于肉眼验证"数据通道 + 锥几何 + 半径"三件事。
// 返回 [0,1] 的锥内系数(内锥全亮、外锥归零、半径外为零)。
// ----------------------------------------------------------------------------
float taclight_debug_green_cone(TacLightSpot L, vec3 fragView) {
    if ((flags & TACLIGHT_FLAG_DEBUG) == 0u) return 0.0;
    if ((flags & TACLIGHT_FLAG_HAS_DATA) == 0u) return 0.0;

    vec3 lightView = taclight_scene_to_view(taclight_world_to_scene(L.posRadius.xyz));
    vec3 toFrag = fragView - lightView;
    float dist = length(toFrag);
    float radius = L.posRadius.w;
    if (dist > radius || radius < 1e-3) return 0.0;

    // 方向只用旋转部分(gbufferModelView 的平移在 composite 阶段无效)
    vec3 dirView = normalize(mat3(gbufferModelView) * normalize(L.dirType.xyz));
    float cosAng = dot(toFrag / max(dist, 1e-4), dirView);
    // 内锥(cosY)全亮,外锥(cosX)全灭:spot = smoothstep(cosOut, cosIn, cosAng)
    float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
    // 软肩压缩:近场压暗、远场几乎不动(实机调优 2026-08-27,详见上方注释)
    return taclight_soft_knee(spot * taclight_attenuation(dist, radius));
}

#endif // TACLIGHT_COMMON_INCLUDED

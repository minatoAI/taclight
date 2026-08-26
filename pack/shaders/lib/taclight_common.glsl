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

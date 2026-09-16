// ============================================================================
// TacLight Shaders · G-Buffer 编解码(lib/taclight_gbuffer.glsl)
// 版本无关(可被 #version 120 的 gbuffers 与 #version 430 的 composite 共用)。
//
// 法线编码:八面体映射(octahedral mapping,公开数学)—— vec3 单位法线 → vec2 [0,1]。
// colortex1 布局(M1 冻结):
//   xy = 八面体编码法线   z = lmcoord.x   w = lmcoord.y
// colortex2 布局(阶段二起 a = LabPBR smoothness,原为 0.3 占位):
//   rgb = 原始 albedo(不含光图)a = LabPBR perceptual smoothness(粗糙度 = (1-a)²)
// colortex3 布局(M1 热修 9b):
//   rgb = 视图空间位置(gbuffers vsh 以 gl_ModelViewMatrix*gl_Vertex 写入,RGBA32F)
//   —— composite 对它求屏幕空间导数得面法线(本栈 Embeddium 地形顶点法线方向不可信)
// colortex5 布局(阶段二新增,仅 terrain/entities/hand 三个 gbuffers 写):
//   r = F0(介电线性值,0-0.898)  g = 金属标志(1.0 = LabPBR G≥230)
//   b = smoothness 副本(供 final DBG7 审计用——final 读时 colortex2 已被 bloom 复用)
//   a = 1.0(已写标志;无 _s 数据的像素回落默认值,见 taclight_decode_specular)
// 两端实现必须逐字一致;改动 = 契约变更,需同步此处与所有 gbuffers。
//
// 色调管线 v2(2026-08-30):colortex0 契约改为**线性域**(composite 入口对原版
// 基线做 pow(2.2) 一次线性化,M1 辐射在同域相加;final 不再整体 pow(2.2))。
// colortex1/2 的 G-Buffer 侧写入仍是原始值(线性化只在 composite 消费点做)。
// ============================================================================

#ifndef TACLIGHT_GBUFFER_INCLUDED
#define TACLIGHT_GBUFFER_INCLUDED

vec2 taclight_encode_normal(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.z >= 0.0
        ? n.xy
        : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}

vec3 taclight_decode_normal(vec2 e) {
    e = e * 2.0 - 1.0;
    vec3 n = vec3(e.xy, 1.0 - abs(e.x) - abs(e.y));
    float t = max(-n.z, 0.0);
    n.x += n.x >= 0.0 ? -t : t;
    n.y += n.y >= 0.0 ? -t : t;
    return normalize(n);
}

// ----------------------------------------------------------------------------
// 阶段二 · LabPBR 1.3 specular(_s)贴图解码(shaderlabs 标准,仅承诺 R+G 两通道
// —— 该标准自身定义"正确读取 smoothness(R)与 F0(G)"即为 LabPBR-ready):
//   R = perceptual smoothness(线性 0-1;composite 侧粗糙度 = (1-s)²)
//   G = F0:0-229 线性映射 F0 0-0.898;230-255 = 金属。金属按标准明文允许的
//       简化处理:整个 230-255 段一律按 255 对待(F0 = albedo,由 composite
//       侧用 colortex2.rgb 上色),预定义金属 N/K 表留待阶段三。
//   B/A(porosity/SSS/emission)本栈暂不消费。
// spec 全零 = 该纹理无 _s 数据(Iris 对缺失自定义纹理绑定为黑)→ 回落旧默认
// (粗糙度 0.7 / F0 0.04),保证原版材质观感与阶段一完全一致。
// 返回 vec4(smoothness, F0介电, metal, 0)。
// ----------------------------------------------------------------------------
#define TACLIGHT_SMOOTHNESS_FALLBACK 0.1633   // = 1 - sqrt(0.7):经 (1-s)² 逆推出的
                                              // 占位值,还原阶段一默认粗糙度 0.7
#define TACLIGHT_F0_FALLBACK        0.04      // 介电塑料默认 F0

vec4 taclight_decode_specular(vec4 spec) {
    if (spec.r == 0.0 && spec.g == 0.0) {
        return vec4(TACLIGHT_SMOOTHNESS_FALLBACK, TACLIGHT_F0_FALLBACK, 0.0, 0.0);
    }
    float metal = spec.g >= 230.0 / 255.0 ? 1.0 : 0.0;
    return vec4(spec.r, spec.g, metal, 0.0);
}

#endif // TACLIGHT_GBUFFER_INCLUDED

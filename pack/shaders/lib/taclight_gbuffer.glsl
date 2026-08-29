// ============================================================================
// TacLight Shaders · G-Buffer 编解码(lib/taclight_gbuffer.glsl)
// 版本无关(可被 #version 120 的 gbuffers 与 #version 430 的 composite 共用)。
//
// 法线编码:八面体映射(octahedral mapping,公开数学)—— vec3 单位法线 → vec2 [0,1]。
// colortex1 布局(M1 冻结):
//   xy = 八面体编码法线   z = lmcoord.x   w = lmcoord.y
// colortex2 布局:
//   rgb = 原始 albedo(不含光图)a = smoothness(粗糙度 = 1-a)
// colortex3 布局(M1 热修 9b):
//   rgb = 视图空间位置(gbuffers vsh 以 gl_ModelViewMatrix*gl_Vertex 写入,RGBA32F)
//   —— composite 对它求屏幕空间导数得面法线(本栈 Embeddium 地形顶点法线方向不可信)
// 两端实现必须逐字一致;改动 = 契约变更,需同步此处与所有 gbuffers。
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

#endif // TACLIGHT_GBUFFER_INCLUDED

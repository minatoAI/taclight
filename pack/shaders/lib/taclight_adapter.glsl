// ============================================================================
// TacLight Shaders · 本包 G-Buffer 适配层(lib/taclight_adapter.glsl)
// ============================================================================
// 【interop 分层】照明核心(taclight_core.glsl)与"本包数据编码"之间的唯一隔层。
//   跨光影包移植时:替换本文件即可接入,核心代码零改动。
//   本文件声明本包私有的 G-Buffer 布局知识与量纲标定 —— 均为 taclight 包所
//   特有,其他包有自己的编码与 tonemap 链,必须在适配层重写(接口见 core 的
//   TACLIGHT_OCCLUSION_AT 注入点注释)。
//
// 【被 include 的位置约束】必须先于 taclight_core.glsl 被 include
//   (common 聚合头的 include 顺序即契约,ShaderCoreContract 钉死):
//   TACLIGHT_OCCLUSION_AT 宏要在 core 的 SSO 函数展开前可见。
// ============================================================================

#ifndef TACLIGHT_ADAPTER_INCLUDED
#define TACLIGHT_ADAPTER_INCLUDED

// ---- colortex3 布局(M1 热修 9b,本包私有契约;布局说明见 taclight_gbuffer.glsl)----
//   rgb = 视图空间位置(gbuffers vsh 以 gl_ModelViewMatrix*gl_Vertex 写入,RGBA32F)
//   a   = 遮挡者材质消光系数(分类表 pack/shaders/block.properties):
//         1.0 实心 / 0.6 树叶 / 0.25 软植被(草/花/作物)——镂空植被按全挡
//         处理会把满草场景的地面消成死黑、只剩草叶亮(实机实锤),半透折中。
uniform sampler2D colortex3;

// SSO 遮挡系数注入点的本包实现(core 默认回退 1.0 = 保守全挡)。
#define TACLIGHT_OCCLUSION_AT(uv) (texture(colortex3, uv).a)

// ---- 亮度量纲标定(对本包"线性域 colortex0 + AgX tonemap"链路联合标定)----
// interop 分层后自 core 移入适配层:移植到其他包(不同 tonemap/合成域)时
// 这里的数值必须重新标定,否则要么全黑要么炸。
// M1 表面照明总增益(与 knee 配合;实测反馈驱动调参)。2026-08-29 实测过曝,2.0→1.0。
#define TACLIGHT_LIGHT_GAIN 2.2

// ----------------------------------------------------------------------------
// 方案二 · 遮挡距离表(2026-09-06,本包私有缓冲布局知识,故在适配层):
//   colortex8 = 逐灯均向"最远无遮挡距离"D(dir),composite pass 每帧预建
//   (composite.fsh MRT location=1 写入),composite1 每采样查表(代替灯侧 DDA 走格)。
//   参数化 = equirect 全球:texel (tx,ty) → lon=(tx+0.5)/512·2π−π, lat=(ty+0.5)/256·π−π/2
//   → dir=(cosLat·cosLon, sinLat, cosLat·sinLon);消费侧 atan/asin 逆变换取同一 texel
//   (NEAREST texelFetch,无跨接缝滤波问题;往返恒等由 OcclTableContract 钉死)。
//   存储:每 texel RGBA = 灯 0..3 的 D(世界格单位 ÷ 128 归一;RGBA16 ≈ 2-4cm 量化,
//   远小于 FUZZ 0.35);区域固定左上 512×256(854×480 的 B 端也放得下),表区外
//   像素写哨兵 1.0(=128m,消费侧视为可见)。灯 4..7 不入表(消费侧回退逐采样 DDA)。
//   angular 分辨率 ≈0.7°/texel:影子边缘定位误差 ≈ d·tan(0.7°)(10m 处 ≈12cm),
//   被 FUZZ 0.35 半影带覆盖——契约 OcclTableContract 用"角点歧义带"性质钉死。
// ----------------------------------------------------------------------------
uniform sampler2D colortex8;
#define TACLIGHT_OCCL_TABLE_SIZE_X 512.0
#define TACLIGHT_OCCL_TABLE_SIZE_Y 256.0
#define TACLIGHT_OCCL_DIST_SCALE 128.0
#define TACLIGHT_OCCL_TABLE_AT(rel) taclight_occl_table_row(rel)

/** 消费侧:relWorld(world 域灯→采样向量)→ 该方向 texel 的 4 灯 D 行(归一值)。 */
vec4 taclight_occl_table_row(vec3 relWorld) {
    vec3 d = relWorld / max(length(relWorld), 1e-4);
    float u = atan(d.z, d.x) / 6.2831853 + 0.5;
    float v = asin(clamp(d.y, -1.0, 1.0)) / 3.14159265 + 0.5;
    ivec2 texel = ivec2(clamp(u, 0.0, 0.9999) * TACLIGHT_OCCL_TABLE_SIZE_X,
                        clamp(v, 0.0, 0.9999) * TACLIGHT_OCCL_TABLE_SIZE_Y);
    return texelFetch(colortex8, texel, 0);
}

/** 构建侧:表内归一坐标 (0..1)² → 世界方向(与上行严格互逆,契约钉死)。 */
vec3 taclight_occl_table_dir(vec2 uv01) {
    float lon = uv01.x * 6.2831853 - 3.14159265;
    float lat = uv01.y * 3.14159265 - 1.5707963;
    float cl = cos(lat);
    return vec3(cl * cos(lon), sin(lat), cl * sin(lon));
}

#endif // TACLIGHT_ADAPTER_INCLUDED

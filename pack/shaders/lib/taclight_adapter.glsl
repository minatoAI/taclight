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

#endif // TACLIGHT_ADAPTER_INCLUDED

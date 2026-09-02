// ============================================================================
// TacLight Shaders · 聚合头(lib/taclight_common.glsl)
// ============================================================================
// 【interop 分层(v1.0)】本文件自 v1.0 起是**聚合头**——本体代码按依赖边界
//   拆分为三层,本包 composite 家族仍只 include 本文件,接口零变化:
//
//     adapter  本包 G-Buffer 私有编码 + 量纲标定(移植时整文件替换)
//     core     零依赖照明核心(SSBO/坐标换算/照明数学/SSO/DDA/灯循环)
//     math     公开数学(IGN/HG),core 与 style 共用
//     gbuffer  本包 G-Buffer 编解码(八面体法线/LabPBR)
//     style    本包风格链(ACES/AgX/split-tone/vignette)
//
//   include 顺序即契约:**adapter 必须先于 core**(TACLIGHT_OCCLUSION_AT 宏
//   要在 core 的 SSO 展开前可见),由 ShaderCoreContract 钉死。
//
// 【SSBO 唯一真源(Single Source)】
//   taclight/src/main/java/dev/taclight/channel/SpotlightBufferLayout.java
//   守护:SpotlightBufferLayoutContract + UploaderSemanticContract(纯 JVM 契约测试)
//
// 【坐标语义 —— doc06 §2.5 铁律 3,v0.9.0 起生效】
//   posRadius.xyz / dirType.xyz 均为 **world** 坐标系;
//   scene-relative 转换只允许发生在消费侧,且必须经过 core 的
//   taclight_world_to_scene -> taclight_scene_to_view 两级封装。
//   历史教训:直接把 world 乘进 composite 的 gbufferModelView(R-only,
//   无平移)= 灯被摆到几百格外(v0.8.4 事故)。消费代码禁止内联坐标换算。
//
// 仅可被 #version 430 及以上的程序 include(composite* 等);gbuffers 用不到它。
// ============================================================================

#ifndef TACLIGHT_COMMON_INCLUDED
#define TACLIGHT_COMMON_INCLUDED

#include "/lib/taclight_adapter.glsl"
#include "/lib/taclight_core.glsl"
#include "/lib/taclight_gbuffer.glsl"
#include "/lib/taclight_style.glsl"

#endif // TACLIGHT_COMMON_INCLUDED

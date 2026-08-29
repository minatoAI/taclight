#version 120

/*
 * gbuffers_weather · 雨雪粒子(F1,2026-08-30 重写)。
 *
 * 根因(0830 缺陷报告 R3):雨像素曾整块覆写 colortex1/2/3 —— Iris 的辅助
 * 附件无混合(gbuffers_* 直写),雨丝逐帧移动 → 背后实心几何的 G-Buffer
 * 属性被雨的自带 varying 反复覆盖,表面照明读到错位法线/遮挡系数 =
 * 移动时"亮纹忽明忽暗"。且原版 1.20.1 的雨 RenderType 写深度
 * (COLOR_DEPTH_WRITE),本文件旧注释"雨不写深度"前提不成立。
 *
 * 修复:雨只写 colortex0(颜色,带混合);colortex1/2/3 保持背后实心
 * 几何的属性不被污染。M1 表面查找同步改用 depthtex1(实心深度,雨不进),
 * 雨丝不再作为"被照表面"产生亮纹;光穿过雨幕照亮的仍是背后的实体表面。
 * 低 alpha 的雨丝边缘丢弃,减少对背后光斑颜色的半透污染。
 */
/* DRAWBUFFERS:0 */
#include "/lib/taclight_gbuffer.glsl"

uniform sampler2D gtexture;
uniform sampler2D lightmap;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 tnormal;
varying vec3 vposView;

void main() {
    vec4 albedo = texture2D(gtexture, texcoord) * glcolor;
    if (albedo.a < 0.1) discard;
    gl_FragData[0] = vec4(albedo.rgb * texture2D(lightmap, lmcoord).rgb, albedo.a);
}

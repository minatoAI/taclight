#version 120

/*
 * gbuffers_water · M1 G-Buffer 写出(半透明层;混合状态作用于全部目标,
 * 法线/albedo 在水面上被轻微软化——M1 接受,M3 折射评估时再议)。
 */
/* DRAWBUFFERS:0123 */
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
    vec3 nrm = normalize(tnormal);
    if (!gl_FrontFacing) nrm = -nrm;
    gl_FragData[0] = vec4(albedo.rgb * texture2D(lightmap, lmcoord).rgb, albedo.a);
    gl_FragData[1] = vec4(taclight_encode_normal(nrm), lmcoord);
    gl_FragData[2] = vec4(albedo.rgb, 0.3);
    gl_FragData[3] = vec4(vposView, 1.0); // 视图空间位置(32F)
}

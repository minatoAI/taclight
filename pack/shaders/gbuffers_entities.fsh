#version 120

/*
 * gbuffers_entities · M1 G-Buffer 写出。
 * entityColor(受击红闪/充能蓝闪)同时进 albedo——锥光下也要保留原版反馈。
 */
/* DRAWBUFFERS:0123 */
#include "/lib/taclight_gbuffer.glsl"

uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform vec4 entityColor;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 tnormal;
varying vec3 vposView;

void main() {
    vec4 albedo = texture2D(gtexture, texcoord) * glcolor;
    albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);
    // 双面法线:实体模型也有双面部件(披风/裙摆等)
    vec3 nrm = normalize(tnormal);
    if (!gl_FrontFacing) nrm = -nrm;
    gl_FragData[0] = vec4(albedo.rgb * texture2D(lightmap, lmcoord).rgb, albedo.a);
    gl_FragData[1] = vec4(taclight_encode_normal(nrm), lmcoord);
    gl_FragData[2] = vec4(albedo.rgb, 0.3);
    gl_FragData[3] = vec4(vposView, 1.0); // 视图空间位置(32F)
}

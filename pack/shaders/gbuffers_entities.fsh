#version 120

/*
 * gbuffers_entities · M1 G-Buffer 写出。
 * entityColor(受击红闪/充能蓝闪)同时进 albedo——锥光下也要保留原版反馈。
 * 阶段二:TACZ 枪模(实体路径)的 LabPBR _s 贴图在此解码 → colortex2.a/colortex5。
 */
/* DRAWBUFFERS:01235 */
#include "/lib/taclight_gbuffer.glsl"

uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform sampler2D specular;
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
    vec4 mat = taclight_decode_specular(texture2D(specular, texcoord));
    gl_FragData[0] = vec4(albedo.rgb * texture2D(lightmap, lmcoord).rgb, albedo.a);
    gl_FragData[1] = vec4(taclight_encode_normal(nrm), lmcoord);
    gl_FragData[2] = vec4(albedo.rgb, mat.r);
    gl_FragData[3] = vec4(vposView, 1.0); // 视图空间位置(32F)
    gl_FragData[4] = vec4(mat.g, mat.b, mat.r, 1.0);   // colortex5:F0/金属/smoothness副本(DBG7 用,final 读时 colortex2 已是 bloom)
}

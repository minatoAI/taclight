#version 120

/*
 * gbuffers_hand · M1 G-Buffer 写出。
 * 枪身 GGX 高光的法线与 albedo 都来自这里;hand 深度已含于 depthtex0/1。
 * 阶段二:第一人称枪模的 LabPBR _s 贴图在此解码 → colortex2.a/colortex5。
 */
/* DRAWBUFFERS:01235 */
#include "/lib/taclight_gbuffer.glsl"

uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform sampler2D specular;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 tnormal;
varying vec3 vposView;

void main() {
    vec4 albedo = texture2D(gtexture, texcoord) * glcolor;
    vec3 nrm = normalize(tnormal);
    if (!gl_FrontFacing) nrm = -nrm;
    vec4 mat = taclight_decode_specular(texture2D(specular, texcoord));
    gl_FragData[0] = vec4(albedo.rgb * texture2D(lightmap, lmcoord).rgb, albedo.a);
    gl_FragData[1] = vec4(taclight_encode_normal(nrm), lmcoord);
    gl_FragData[2] = vec4(albedo.rgb, mat.r);
    gl_FragData[3] = vec4(vposView, 1.0); // 视图空间位置(32F)
    gl_FragData[4] = vec4(mat.g, mat.b, mat.r, 1.0);   // colortex5:F0/金属/smoothness副本(DBG7 用,final 读时 colortex2 已是 bloom)
}

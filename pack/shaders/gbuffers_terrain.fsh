#version 120

/*
 * gbuffers_terrain · M1 G-Buffer 写出(布局契约见 lib/taclight_gbuffer.glsl):
 *   0 = 原版光图颜色(基线兜底) 1 = 八面体法线 + lmcoord
 *   2 = 原始 albedo + LabPBR smoothness  3 = 视图空间位置 + 遮挡系数
 *   5 = 材质(F0 介电值 / 金属标志)——阶段二起由 LabPBR _s 贴图解码,
 *       无 _s 数据回落旧默认(粗糙度 0.7 / F0 0.04,观感与阶段一一致)。
 * 遮挡系数(colortex3.a,SSO/体积光消费):实心=1.0 树叶=0.6 软植被=0.25,
 * 分类表 = 同目录 block.properties(Iris 官方机制,mc_Entity.x 匹配)。
 * doc06 §2.7 "smoothness 差异化"由 LabPBR 解码落地(取代按方块 ID 方案)。
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
varying float vblockId;

void main() {
    vec4 albedo = texture2D(gtexture, texcoord) * glcolor;
    if (albedo.a < 0.1) discard; // cutout 层必需;discard 必须先于任何写出
    // 双面法线(M1 实机调优):草/树叶交叉面片双面渲染,背面片元翻转法线才能正确受光
    vec3 nrm = normalize(tnormal);
    if (!gl_FrontFacing) nrm = -nrm;
    // 植被遮挡系数(M1 收尾):软植被 0.25 / 树叶 0.6 / 其余实心 1.0
    float occl = 1.0;
    if (vblockId > 2000.5 && vblockId < 2001.5) {
        occl = 0.25;
    } else if (vblockId > 2001.5 && vblockId < 2002.5) {
        occl = 0.6;
    }
    vec4 mat = taclight_decode_specular(texture2D(specular, texcoord));
    gl_FragData[0] = vec4(albedo.rgb * texture2D(lightmap, lmcoord).rgb, albedo.a);
    gl_FragData[1] = vec4(taclight_encode_normal(nrm), lmcoord);
    gl_FragData[2] = vec4(albedo.rgb, mat.r);
    gl_FragData[3] = vec4(vposView, occl);
    gl_FragData[4] = vec4(mat.g, mat.b, mat.r, 1.0);   // colortex5:F0/金属/smoothness副本(DBG7 用,final 读时 colortex2 已是 bloom)
}

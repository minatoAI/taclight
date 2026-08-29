#version 120

/*
 * gbuffers_terrain · M1 G-Buffer 写出(布局契约见 lib/taclight_gbuffer.glsl):
 *   0 = 原版光图颜色(基线兜底) 1 = 八面体法线 + lmcoord
 *   2 = 原始 albedo + smoothness  3 = 视图空间位置 + 遮挡系数
 * 遮挡系数(colortex3.a,SSO/体积光消费):实心=1.0 树叶=0.6 软植被=0.25,
 * 分类表 = 同目录 block.properties(Iris 官方机制,mc_Entity.x 匹配)。
 * M1 计划后续:smoothness 按方块 ID 差异化(金属/石头),见 doc06 §2.7。
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
    gl_FragData[0] = vec4(albedo.rgb * texture2D(lightmap, lmcoord).rgb, albedo.a);
    gl_FragData[1] = vec4(taclight_encode_normal(nrm), lmcoord);
    gl_FragData[2] = vec4(albedo.rgb, 0.3);
    gl_FragData[3] = vec4(vposView, occl);
}

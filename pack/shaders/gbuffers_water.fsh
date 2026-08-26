#version 120

/*
 * gbuffers_water · 半透明层(水/玻璃/冰等)。
 * 混合由渲染层固定状态决定,着色器只需正确输出 alpha。M1 接入法线+折射与否是范围决策(doc06 §3.3)。
 */
uniform sampler2D gtexture;
uniform sampler2D lightmap;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    vec4 color = texture2D(gtexture, texcoord) * glcolor;
    color.rgb *= texture2D(lightmap, lmcoord).rgb;
    gl_FragData[0] = color;
}

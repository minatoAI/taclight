#version 120

/*
 * gbuffers_terrain · 地形方块(含 cutout:树叶/草)。
 * M1 计划:此处追加写 colortex1(法线)/colortex2(albedo+粗糙度),
 * 并保留本文件的光图项作为"无光照兜底"分量。
 */
uniform sampler2D gtexture;
uniform sampler2D lightmap;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    vec4 color = texture2D(gtexture, texcoord) * glcolor;
    if (color.a < 0.1) discard; // cutout 层必需
    color.rgb *= texture2D(lightmap, lmcoord).rgb;
    gl_FragData[0] = color;
}

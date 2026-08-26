#version 120

/*
 * gbuffers_textured · 有贴图无光照材质基类。
 * (gbuffers_clouds 等未显式提供的程序会沿 Iris 回退链落到这里或 textured_lit。)
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

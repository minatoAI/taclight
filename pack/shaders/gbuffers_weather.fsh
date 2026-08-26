#version 120

/*
 * gbuffers_weather · 雨/雪。UV 滚动由模型矩阵内置,直接采样即可。
 * M3 体积光(vlParams 密度)将与雨雪强度联动评估,但不在 weather 着色器内做。
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

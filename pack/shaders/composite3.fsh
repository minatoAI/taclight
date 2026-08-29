#version 430 core
/*
 * composite3 · M2 风格层:bloom 第二级(colortex1 → colortex2,均全屏 1:1)。
 * 13tap 圆环大半径模糊(中心 + 内外两环),产出大半径辉光;
 * final 中与一级(轻模糊)叠加 = 最简两级 bloom(doc06 §8.3)。
 * 槽位复用注记:colortex2(G-Buffer smoothness)在 composite 照明后已消费完,
 * 此处覆盖写,下一帧 gbuffers 重新写入(v0.10.0 热修:不再占用新增 buffer)。
 */
/* DRAWBUFFERS:2 */
#include "/lib/taclight_common.glsl"

uniform sampler2D colortex1;
uniform float viewWidth;
uniform float viewHeight;

in vec2 texcoord;
layout(location = 0) out vec4 taclightBloom2;

void main() {
    vec2 px = 1.0 / vec2(viewWidth, viewHeight);
    const float R1 = 3.0;   // 内环半径(px)
    const float R2 = 7.0;   // 外环半径(px)
    vec3 s = texture(colortex1, texcoord).rgb * 3.0;
    for (int i = 0; i < 6; i++) {
        float a = 6.2831853 * (float(i) + 0.5) / 6.0;
        vec2 d = vec2(cos(a), sin(a));
        s += texture(colortex1, texcoord + d * R1 * px).rgb * 2.0;
        s += texture(colortex1, texcoord + d * R2 * px).rgb;
    }
    taclightBloom2 = vec4(s / 21.0, 1.0);
}

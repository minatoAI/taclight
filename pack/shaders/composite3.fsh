#version 430 core
/*
 * composite3 · M2 风格层:bloom 第二级(colortex1 → colortex2,均全屏 1:1)。
 * 13tap 圆环大半径模糊(中心 + 内外两环),产出大半径辉光;
 * final 中与一级(轻模糊)叠加 = 最简两级 bloom(doc06 §8.3)。
 * 槽位复用注记:colortex2(G-Buffer smoothness)在 composite 照明后已消费完,
 * 此处覆盖写,下一帧 gbuffers 重新写入(v0.10.0 热修:不再占用新增 buffer)。
 *
 * 坑37 时域平滑(2026-08-30 深夜,远程移动光闪烁定位):
 *   移动光斑扫过 bloom 软阈值带时,提取权重随位置变化被 smoothstep 非线性放大
 *   (F6 同族,成员=表面光斑;F6 只修了 beam 不进 bloom)。治理 = 大半径辉光
 *   (视觉主导项)输出对上一帧做 EMA:辉光是低频量,α=0.6 时空迹 ~2 帧、
 *   单帧跳变衰减 40%,移动忽亮忽暗消失。历史存 colortex6(全包唯一未占用槽,
 *   Iris 对 4-7 号缓冲默认不逐帧清除,与 colortex7 曝光历史同机制)。
 */
/* DRAWBUFFERS:26 */
#include "/lib/taclight_common.glsl"

uniform sampler2D colortex1;
uniform sampler2D colortex6;   // 上一帧 bloom2 历史(clear=false 语义,见文件头坑37)
uniform float viewWidth;
uniform float viewHeight;

in vec2 texcoord;
layout(location = 0) out vec4 taclightBloom2;   // -> colortex2
layout(location = 1) out vec4 taclightBloom2H;  // -> colortex6(历史镜像)

#define TACLIGHT_BLOOM_SMOOTH 0.6   // 每帧新值权重;越小越平滑、拖迹越长

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
    vec3 b2 = s / 21.0;
    vec3 smoothed = mix(texture(colortex6, texcoord).rgb, b2, TACLIGHT_BLOOM_SMOOTH);
    taclightBloom2 = vec4(smoothed, 1.0);
    taclightBloom2H = vec4(smoothed, 1.0);
}

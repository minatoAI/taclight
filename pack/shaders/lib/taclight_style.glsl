// ============================================================================
// TacLight Shaders · M2 风格层数学(lib/taclight_style.glsl)
// 版本无关(#version 120 的 final 与 #version 430 的 composite 家族均可 include;
// 纯函数、无 uniform、无内建状态依赖)。doc06 §2.9:风格层 = 原创性核心资产。
// 只使用公开数学公式与 ACES 官方色度常量(0 行第三方 shader 代码)。
// 【interop 分层】IGN/HG(公开数学、core 复用)已移至 taclight_math.glsl,
// 此处经 include 保持对 final 的传递可见(胶片颗粒用 ign)。
// ============================================================================

#ifndef TACLIGHT_STYLE_INCLUDED
#define TACLIGHT_STYLE_INCLUDED

#include "/lib/taclight_math.glsl"


// M2 · ACES 色调映射(Hill 拟合;输入/输出均为线性值,矩阵 = ACES 官方
// 输入/输出色度变换常量)。高光滚降防"平白一片",暗部 S 曲线收黑位。
vec3 taclight_aces(vec3 x) {
    mat3 ain  = mat3(0.59719, 0.07600, 0.02840,
                     0.35458, 0.90834, 0.13383,
                     0.04823, 0.01566, 0.83777);
    mat3 aout = mat3( 1.60475, -0.10208, -0.00327,
                     -0.53108,  1.10813, -0.07276,
                     -0.07367, -0.00605,  1.07602);
    vec3 v = ain * x;
    vec3 a = v * (v + 0.0245786) - 0.000090537;
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return clamp(aout * (a / b), 0.0, 1.0);
}

// M2 · AgX 色调映射(AgX minimals 公开数学;输入线性,输出线性显示值,调用方做 gamma 编码)。
// 与 ACES 的实测差异(2026-08-30 消融):ACES 高光滚降偏暖且中间调反差硬
// (夜景局部对比 0.298 vs 参考包 0.092);AgX 高光去饱和走向白、色相偏移小。
// 前向矩阵列和 = 1(中性轴保持,数值验证过);逆矩阵由前向矩阵数值求逆
// (GLSL120 无 inverse());对比多项式 = 公开发布的最简 S 型近似。
// EV 窗口旋钮:发布默认 ±12.47 面向场景参照 HDR;我们的输入是显示参照的
// 原版画面(线性域 ~0.001-2),窗口收窄到 ±6 才能把中间调放对(实机校准)。
#define TACLIGHT_AGX_MIN_EV -6.0
#define TACLIGHT_AGX_MAX_EV  6.0

vec3 taclight_agx_contrast(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4
         - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
}

vec3 taclight_agx(vec3 v) {
    mat3 m = mat3(0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                  0.0783847559184412, 0.878468636469772, 0.0784335999504165,
                  0.0791323665908833, 0.0791661274605437, 0.879142971512145);
    v = clamp(log2(max(v, vec3(1e-6))), vec3(TACLIGHT_AGX_MIN_EV), vec3(TACLIGHT_AGX_MAX_EV));
    v = (m * v - TACLIGHT_AGX_MIN_EV) / (TACLIGHT_AGX_MAX_EV - TACLIGHT_AGX_MIN_EV);
    return taclight_agx_contrast(v);
}

vec3 taclight_agx_eotf(vec3 v) {
    mat3 mi = mat3( 1.19687011936545, -0.09796426368363, -0.09890963868184,
                   -0.05289645903295,  1.15190062767580, -0.09896648525240,
                   -0.05297124238905, -0.09804595609125,  1.15106836000501);
    v = mi * (v * (TACLIGHT_AGX_MAX_EV - TACLIGHT_AGX_MIN_EV) + TACLIGHT_AGX_MIN_EV);
    return exp2(v);
}

// M2 · split-tone(D7 夜景基调):阴影推冷、高光推暖(幅度温和,灯色本身
// 已由 SSBO 暖白 3500-4000K 提供,这里只做环境氛围分离)。
vec3 taclight_split_tone(vec3 c, float lum) {
    vec3 cool = c * vec3(0.94, 0.98, 1.08);
    vec3 warm = c * vec3(1.04, 1.00, 0.94);
    return mix(cool, warm, smoothstep(0.10, 0.55, lum));
}

// M2 · 暗角:中心 1.0,四角压到 strength;smoothstep 区间 [0.45,1.35](归一半径)。
float taclight_vignette(vec2 uv, float strength) {
    float d = length(uv - 0.5) * 1.4142;
    return 1.0 - smoothstep(0.45, 1.35, d) * (1.0 - strength);
}

#endif // TACLIGHT_STYLE_INCLUDED

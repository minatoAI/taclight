// ============================================================================
// TacLight Shaders · M2 风格层数学(lib/taclight_style.glsl)
// 版本无关(#version 120 的 final 与 #version 430 的 composite 家族均可 include;
// 纯函数、无 uniform、无内建状态依赖)。doc06 §2.9:风格层 = 原创性核心资产。
// 只使用公开数学公式与 ACES 官方色度常量(0 行第三方 shader 代码)。
// ============================================================================

#ifndef TACLIGHT_STYLE_INCLUDED
#define TACLIGHT_STYLE_INCLUDED

// Interleaved Gradient Noise(Jimenez 2014 公开公式):时序稳定的空间抖动源,
// 用于 SSO/体积光步进抖动与胶片颗粒。
float taclight_ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// M3 · HG 相位函数(Henyey-Greenstein,公开数学):体积散射角分布。
// cosTheta = 视线方向 · 光传播方向;g>0 前向散射强(手电束感),g=0 各向同性。
float taclight_hg(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.566371 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

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

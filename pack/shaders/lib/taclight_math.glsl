// ============================================================================
// TacLight Shaders · 零依赖数学层(lib/taclight_math.glsl)
// 仅公开数学公式,无 uniform、无内建状态、无包私有编码 —— 照明核心
// (taclight_core.glsl)与风格层(taclight_style.glsl)共同的下层。
// 版本无关(#version 120 的 final 与 #version 430 的 composite 家族均可 include)。
// ============================================================================

#ifndef TACLIGHT_MATH_INCLUDED
#define TACLIGHT_MATH_INCLUDED

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

#endif // TACLIGHT_MATH_INCLUDED

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

// 体积光角分布不再用 HG 相位(2026-09-05 侧面相位定案,见 composite1.fsh):
// sin²θ 侧面剖面内联在消费点——正侧 90° 最亮(丁达尔),正对/沿轴只剩底亮份额。

#endif // TACLIGHT_MATH_INCLUDED

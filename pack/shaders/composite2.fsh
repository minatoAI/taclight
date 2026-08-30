#version 430 core
/*
 * composite2 · M2 风格层(doc06 §2.9 / §8.3 审批):
 *   bloom 第一级:scene(colortex0)+ 光束(colortex4)之和做亮部软阈值提取,
 *   每像素 4tap 邻域平均(预模糊),全屏 1:1 写 colortex1
 *   (G-Buffer 槽位复用:法线在 composite 照明后已消费完;v0.10.0 热修——
 *   初版曾用"内容半分辨率块写 + LINEAR 上采样"写新增 buffer colortex5,
 *   实机出现亮部图缩放错位叠加(右上角复制画面),该技巧对 buffer 尺寸
 *   全屏的假设在 Oculus 1.8.0 上不成立,整体退役)。
 *   自适应曝光:8×3 稀疏网格全屏平均亮度 → 目标亮度反比 → 帧率无关
 *   眼适应(时间常数 ~0.3s)→ colortex7(clear=false,跨帧 history)。
 */
/* DRAWBUFFERS:17 */
#include "/lib/taclight_common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex4;
uniform sampler2D colortex7;   // 曝光 history(clear=false,读上一帧写本帧)
uniform float viewWidth;
uniform float viewHeight;
uniform float frameTime;

in vec2 texcoord;
layout(location = 0) out vec4 taclightBloom1;   // -> colortex1
layout(location = 1) out vec4 taclightExposure; // -> colortex7

// 色调管线 v2(2026-08-30 消融):阈值进线性域。旧 0.55 是 gamma 域亮度——
// 白天整个天空(0.7-0.9)都进 bloom → 画面泛白雾;玻璃/亮天被 bloom 打成死白块。
// 线性域 1.0 软阈 ±0.4 只提取真正的高光(光斑核心/太阳直射),天空不再起雾。
#define TACLIGHT_BLOOM_TH      1.0   // 亮部提取阈值(线性域,软边 ±0.4)
#define TACLIGHT_EXPOSURE_LOCK 1     // 1=固定曝光(A/B 截图防亮度漂移,值见下) 0=自适应眼适应
#define TACLIGHT_EXPOSURE_LOCK_VALUE 1.0  // 锁定时的曝光值
#define TACLIGHT_EXPOSURE_TARGET 0.12 // 目标全屏平均亮度(夜景基调,偏暗)
#define TACLIGHT_EXPOSURE_MIN  0.5
#define TACLIGHT_EXPOSURE_MAX  3.0
#define TACLIGHT_ADAPT_RATE    3.5    // 眼适应速率(1/s)

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 sceneAt(vec2 uv) {
    // F6(2026-08-30 移动闪烁定位):bloom 提取只取 scene(colortex0),
    // 体积束(colortex4)不进 bloom。实测三连对照:beam 层移动中平滑单调
    // (DBG4 连拍),scene 层平滑(关 beam 连拍),合成后锯齿(B1)——
    // 同轴视角下中央 luma 恰落在软阈值(TACLIGHT_BLOOM_TH±0.15)中段,
    // beam 的平滑变化被 smoothstep 非线性放大成提取权重跳变 = 移动忽亮忽暗。
    // beam 自身即为辉光观感(final 中直加),无需二次 bloom。
    return texture(colortex0, uv).rgb;
}

void main() {
    // ---- bloom 第一级:全屏 1:1,4tap 邻域平均 + 软阈值 ----
    vec2 o = 1.0 / vec2(viewWidth, viewHeight);
    vec3 s = (sceneAt(texcoord + vec2( o.x,  o.y))
            + sceneAt(texcoord + vec2(-o.x,  o.y))
            + sceneAt(texcoord + vec2( o.x, -o.y))
            + sceneAt(texcoord + vec2(-o.x, -o.y))) * 0.25;
    float w = smoothstep(TACLIGHT_BLOOM_TH - 0.4, TACLIGHT_BLOOM_TH + 0.4, dot(s, LUMA));
    taclightBloom1 = vec4(s * w, 1.0);

    // ---- 全屏平均亮度(锁定模式写入 .g 供诊断;自适应模式用其反比做曝光)----
    float avg = 0.0;
    for (int y = 0; y < 3; y++) {
        for (int x = 0; x < 8; x++) {
            avg += dot(sceneAt(vec2(float(x) + 0.5, float(y) + 0.5) / vec2(8.0, 3.0)), LUMA);
        }
    }
    avg /= 24.0;
    // ---- 曝光:锁定(调试 A/B)或稀疏网格平均亮度 + 跨帧 history ----
#if TACLIGHT_EXPOSURE_LOCK
    taclightExposure = vec4(TACLIGHT_EXPOSURE_LOCK_VALUE, avg, 0.0, 1.0);
#else
    float expT = clamp(TACLIGHT_EXPOSURE_TARGET / max(avg, 1e-4),
                       TACLIGHT_EXPOSURE_MIN, TACLIGHT_EXPOSURE_MAX);
    float prev = texture(colortex7, texcoord).r;
    if (prev < 1e-4) prev = expT;   // history 冷启动(首帧)
    float k = 1.0 - exp(-max(frameTime, 1e-3) * TACLIGHT_ADAPT_RATE);
    taclightExposure = vec4(mix(prev, expT, k), avg, 0.0, 1.0);
#endif
}

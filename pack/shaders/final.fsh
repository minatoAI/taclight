#version 120

/*
 * final · M2 风格层合成(doc06 §2.9,保持 120 与现有 vsh 配套):
 *   scene + 体积光 + 两级 bloom → 自适应曝光(colortex7 history)→
 *   ACES(Hill 拟合)→ gamma 回投 → split-tone(夜景:阴影冷/高光暖)→
 *   暗角 → 胶片颗粒(IGN,暗部偏重)。
 * 全部风格旋钮在下方 #define 区(单一入口);数学在 lib/taclight_style.glsl。
 * 调试视图:TACLIGHT_DBG_STRIP(值定义在 lib/taclight_debug.glsl,与 composite 共享)
 *   在 main 入口早退直出对应缓冲(2/4/5/6);模式 1/3 在 composite 侧。
 */
#include "/lib/taclight_style.glsl"
#include "/lib/taclight_debug.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;   // DBG 2:rgb=视图空间位置 a=遮挡系数(实心1/树叶0.6/软植被0.25)
uniform sampler2D colortex4;
uniform sampler2D colortex5;   // DBG 7:G-Buffer 材质(r=F0 g=金属 b=smoothness 副本)
uniform sampler2D colortex7;
uniform sampler2D depthtex0;   // DBG 6:线性深度
#if TACLIGHT_DBG_STRIP == 6
uniform mat4 gbufferProjectionInverse;
uniform float far;
#endif

varying vec2 texcoord;

#define TACLIGHT_BLOOM1_GAIN 0.35   // 一级辉光强度(colortex1,轻模糊)
#define TACLIGHT_BLOOM2_GAIN 0.55   // 二级辉光强度(colortex2,大半径)
#define TACLIGHT_SPLITTONE   1.0    // 0=关,1=默认(内部幅度温和)
#define TACLIGHT_VIGNETTE    0.78   // 四角亮度(1=无暗角)
#define TACLIGHT_GRAIN       0.035  // 胶片颗粒幅度

void main() {
#if TACLIGHT_DBG_STRIP >= 2
    // ---- 调试视图早退:直出目标缓冲(不经风格链,数值即内容)----
#if TACLIGHT_DBG_STRIP == 2
    // 遮挡系数热图(灰度 γ0.45 拉开层级):白=1.0 实心 / 0.6 树叶 / 0.25 软植被 / 黑=未写(天空)
    float occ = texture2D(colortex3, texcoord).a;
    gl_FragData[0] = vec4(vec3(pow(clamp(occ, 0.0, 1.0), 0.45)), 1.0);
    return;
#elif TACLIGHT_DBG_STRIP == 4
    // 体积光单独(colortex4 原值直读)
    gl_FragData[0] = vec4(clamp(texture2D(colortex4, texcoord).rgb, 0.0, 1.0), 1.0);
    return;
#elif TACLIGHT_DBG_STRIP == 5
    // bloom 链单独:两级增益后之和(= final 里实际被加进画面的 bloom 量)
    vec3 bloom5 = TACLIGHT_BLOOM1_GAIN * texture2D(colortex1, texcoord).rgb
                + TACLIGHT_BLOOM2_GAIN * texture2D(colortex2, texcoord).rgb;
    gl_FragData[0] = vec4(clamp(bloom5, 0.0, 1.0), 1.0);
    return;
#elif TACLIGHT_DBG_STRIP == 7
    // ---- 材质解码审计(阶段二;必须在 final 早退——composite 侧写会被 beam/bloom
    //      二次叠加污染,07:50 实机教训)。数据源只用 colortex5(gbuffers 之后无人写):
    //      R=smoothness(γ0.45) G=F0×2.5(介电值域小,×2.5 可判读) B=金属(蓝) ----
    vec4 mt7 = texture2D(colortex5, texcoord);
    if (mt7.a > 0.001) {
        gl_FragData[0] = vec4(pow(mt7.b, 0.45), pow(min(mt7.r * 2.5, 1.0), 0.45), mt7.g, 1.0);
    } else {
        gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);   // 天空/未写
    }
    return;
#elif TACLIGHT_DBG_STRIP == 6
    // 视图空间线性深度(γ0.35 压近场;黑=天空)
    float d6 = texture2D(depthtex0, texcoord).r;
    if (d6 >= 1.0) { gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec4 vp6 = gbufferProjectionInverse * vec4(vec3(texcoord, d6) * 2.0 - 1.0, 1.0);
    float view6 = length(vp6.xyz / max(abs(vp6.w), 1e-5));
    gl_FragData[0] = vec4(vec3(pow(clamp(view6 / far, 0.0, 1.0), 0.35)), 1.0);
    return;
#endif
#endif

    vec3 color = texture2D(colortex0, texcoord).rgb
               + texture2D(colortex4, texcoord).rgb
               + TACLIGHT_BLOOM1_GAIN * texture2D(colortex1, texcoord).rgb
               + TACLIGHT_BLOOM2_GAIN * texture2D(colortex2, texcoord).rgb;

    float exposure = texture2D(colortex7, vec2(0.5)).r;
    if (exposure < 1e-4) exposure = 1.0;   // history 冷启动兜底

    // gamma 空间(原版基线)→ 线性 → 曝光 → ACES → 显示 gamma
    vec3 lin = pow(max(color, 0.0), vec3(2.2)) * exposure;
    vec3 outc = pow(taclight_aces(lin), vec3(1.0 / 2.2));

    float lum = dot(outc, vec3(0.2126, 0.7152, 0.0722));
    outc = mix(outc, taclight_split_tone(outc, lum), TACLIGHT_SPLITTONE);
    outc *= taclight_vignette(texcoord, TACLIGHT_VIGNETTE);
    outc += (taclight_ign(gl_FragCoord.xy) - 0.5) * TACLIGHT_GRAIN * (1.0 - lum * 0.6);

    gl_FragData[0] = vec4(clamp(outc, 0.0, 1.0), 1.0);
}

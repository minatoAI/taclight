#version 120

/*
 * final · M2 风格层合成(doc06 §2.9,保持 120 与现有 vsh 配套):
 *   scene+体积光+bloom(全部线性域,v2)→ 自适应曝光(colortex7 history)→
 *   AgX(公开 minimals 数学;ACES 可用 TACLIGHT_TONEMAP=1 切回)→ gamma →
 *   后置饱和 → split-tone(夜景:阴影冷/高光暖,幅度 0.35)→ 暗角 → 颗粒。
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

#define TACLIGHT_TONEMAP     0      // 0=AgX(默认,2026-08-30) 1=ACES(旧,保留作 A/B)
#define TACLIGHT_TONE_GAIN   3.0    // 色调映射前标定增益(显示参照输入 → EV 窗口置中)
#define TACLIGHT_SATURATION  1.05   // 后置饱和(色调映射会压饱和,映射后补偿)
#define TACLIGHT_BLOOM1_GAIN 0.18   // 一级辉光强度(colortex1,轻模糊;线性域)
#define TACLIGHT_BLOOM2_GAIN 0.30   // 二级辉光强度(colortex2,大半径;线性域)
#define TACLIGHT_SPLITTONE   0.35   // 0=关,1=默认;消融显示 1.0 抬饱和+蓝移(0.29/0.08),压到 0.35
#define TACLIGHT_VIGNETTE    0.85   // 四角亮度(1=无暗角)
#define TACLIGHT_GRAIN       0.025  // 胶片颗粒幅度

void main() {
#if TACLIGHT_DBG_STRIP >= 2
    // ---- 调试视图早退:直出目标缓冲(不经风格链,数值即内容)----
#if TACLIGHT_DBG_STRIP == 2
    // 遮挡系数热图(灰度 γ0.45 拉开层级):白=1.0 实心 / 0.6 树叶 / 0.25 软植被 / 黑=未写(天空)
    float occ = texture2D(colortex3, texcoord).a;
    gl_FragData[0] = vec4(vec3(pow(clamp(occ, 0.0, 1.0), 0.45)), 1.0);
    return;
#elif TACLIGHT_DBG_STRIP == 4
    // 体积光单独(colortex4 原值直读)。色调管线 v2 起光束是线性域小值,直读近乎
    // 全黑,量测工具会被 TACZ 弹药 HUD 抢走最亮区——显示增益 ×6 + γ0.45
    // (仅 DBG 视图,不入真实管线;倍数只影响诊断显示,不改几何)。
    vec3 b4 = texture2D(colortex4, texcoord).rgb;
    gl_FragData[0] = vec4(pow(clamp(b4 * 6.0, 0.0, 1.0), vec3(0.45)), 1.0);
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
#elif TACLIGHT_DBG_STRIP == 8
    // 表面光单独(colortex0 直读,M1 照明结果,不含光束/bloom)。与 DBG4 同款显示增益
    // ——W3 同轴判定的"表面光斑"腿:两腿都在风格链之前的原始缓冲域,质心不随
    // 色调映射/后处理旋钮摆动(坑26)。
    vec3 s8 = texture2D(colortex0, texcoord).rgb;
    gl_FragData[0] = vec4(pow(clamp(s8 * 6.0, 0.0, 1.0), vec3(0.45)), 1.0);
    return;
#endif
#endif

    // 色调管线 v2(2026-08-30 消融实验):colortex0/beam/bloom 已全部是线性域
    // (composite 入口统一线性化),此处不再做 pow(2.2)。顺序对齐参考实现特征:
    // 曝光 → 色调映射(AgX,高光去饱和走向白、色相稳定)→ gamma → 后置饱和 →
    // split-tone(幅度 0.35)→ 暗角 → 颗粒。
    vec3 color = texture2D(colortex0, texcoord).rgb
               + texture2D(colortex4, texcoord).rgb
               + TACLIGHT_BLOOM1_GAIN * texture2D(colortex1, texcoord).rgb
               + TACLIGHT_BLOOM2_GAIN * texture2D(colortex2, texcoord).rgb;

    float exposure = texture2D(colortex7, vec2(0.5)).r;
    if (exposure < 1e-4) exposure = 1.0;   // history 冷启动兜底

    vec3 lin = max(color, 0.0) * (exposure * TACLIGHT_TONE_GAIN);
#if TACLIGHT_TONEMAP == 1
    vec3 outc = pow(taclight_aces(lin), vec3(1.0 / 2.2));
#else
    vec3 outc = pow(taclight_agx_eotf(taclight_agx(lin)), vec3(1.0 / 2.2));
#endif

    float lum = dot(outc, vec3(0.2126, 0.7152, 0.0722));
    outc = mix(outc, vec3(lum), 1.0 - TACLIGHT_SATURATION);
    outc = mix(outc, taclight_split_tone(outc, lum), TACLIGHT_SPLITTONE);
    outc *= taclight_vignette(texcoord, TACLIGHT_VIGNETTE);
    outc += (taclight_ign(gl_FragCoord.xy) - 0.5) * TACLIGHT_GRAIN * (1.0 - lum * 0.6);

    gl_FragData[0] = vec4(clamp(outc, 0.0, 1.0), 1.0);
}

#version 430 core
/*
 * composite · M0 骨架版:
 *  - 读 G-Buffer(depthtex0)重建视图空间位置(经 taclight_depth_to_view);
 *  - FLAG_DEBUG(K 键)时叠加调试绿锥(taclight_debug_green_cone);
 *  - 其余情况像素级直通 = 与无光影的原版画面一致(M0 验收条件 a/b)。
 * 真实表面照明(diffuse/GGX/SSO)属 M1;体积光 raymarch 属 composite1/M3。
 *
 * 语法注意(实机教训 2026-08-27):430 core 下禁用 varying/gl_FragData,
 * 必须 in/out + 显式 layout(location) 输出;顶点侧保持 330 compatibility
 * (gl_MultiTexCoord0/ftransform 合法)—— 该混搭组合是路线 P 实测可用的。
 */
/* DRAWBUFFERS:0 */
#include "/lib/taclight_common.glsl"

uniform sampler2D colortex0;
uniform sampler2D depthtex0;

in vec2 texcoord;
layout(location = 0) out vec4 taclightCompositeOut;

void main() {
    vec3 color = texture(colortex0, texcoord).rgb;
    float depth = texture(depthtex0, texcoord).r;

    // M0 范围:只有绿锥诊断通道。真实光照 M1 在此处接入(需要 normals/albedo G-Buffer)。
    // 增益 1.8:配合软肩压缩(knee)补偿远场,近场不削顶(实机调优 2026-08-27)。
    if (depth < 1.0 && lightCount > 0u) {
        vec3 fragView = taclight_depth_to_view(texcoord, depth);
        vec3 glow = vec3(0.0);
        for (uint i = 0u; i < lightCount && i < 8u; i++) {
            glow += taclight_debug_green_cone(lights[i], fragView) * vec3(0.15, 1.0, 0.30);
        }
        color += glow * 1.8;
    }

    taclightCompositeOut = vec4(color, 1.0);
}

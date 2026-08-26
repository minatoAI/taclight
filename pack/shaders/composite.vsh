#version 330 compatibility

/*
 * composite 顶点阶段:保持 330 compatibility(legacy 内建合法),
 * 与 430 core 的片段阶段混搭 —— 路线 P 在本机验证过的组合。
 * 片段侧禁用 varying/gl_FragData 的教训见 composite.fsh 头注释。
 */

varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}

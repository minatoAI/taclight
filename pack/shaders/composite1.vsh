#version 330 compatibility

/*
 * composite1 顶点阶段:与 composite 同款(330 compatibility + ftransform,
 * 片段侧 430 core —— 路线 P 在本机验证过的组合,教训见 composite.fsh 头注释)。
 */

varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}

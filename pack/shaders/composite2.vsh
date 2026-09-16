#version 330 compatibility

/*
 * composite2 顶点阶段:与 composite 同款(330 compatibility + ftransform)。
 */

varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}

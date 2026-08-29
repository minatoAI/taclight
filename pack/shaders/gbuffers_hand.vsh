#version 120

// gbuffers_hand · M1:补充视图空间法线(枪身 GGX 高光的法线来源)
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 tnormal;
varying vec3 vposView; // 视图空间位置(与 ftransform 同源;composite 导数求面法线)

void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord  = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor  = gl_Color;
    tnormal  = gl_NormalMatrix * gl_Normal;
    vposView = (gl_ModelViewMatrix * gl_Vertex).xyz;
}

#version 120

// gbuffers_terrain · M1:补充视图空间法线(gl_NormalMatrix 为 120 内建)
// M1 收尾:mc_Entity 透传给 fsh 做植被分类(遮挡系数,colortex3.a;分类表 block.properties)
attribute vec4 mc_Entity;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 tnormal;
varying vec3 vposView; // 视图空间位置(与 ftransform 同源;composite 导数求面法线)
varying float vblockId;

void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord  = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor  = gl_Color;
    tnormal  = gl_NormalMatrix * gl_Normal;
    vposView = (gl_ModelViewMatrix * gl_Vertex).xyz;
    vblockId = mc_Entity.x;
}

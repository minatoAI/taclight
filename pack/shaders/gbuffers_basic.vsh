#version 120

// gbuffers_basic · M1:选中方块框/线条也进 G-Buffer(无贴图,albedo=顶点色)
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 tnormal;
varying vec3 vposView; // 视图空间位置(与 ftransform 同源;composite 导数求面法线)

void main() {
    gl_Position = ftransform();
    lmcoord  = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor  = gl_Color;
    tnormal  = gl_NormalMatrix * gl_Normal;
    vposView = (gl_ModelViewMatrix * gl_Vertex).xyz;
}

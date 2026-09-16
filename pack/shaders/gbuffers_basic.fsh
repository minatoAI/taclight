#version 120

/*
 * gbuffers_basic · M1 G-Buffer 写出(选中方块框/拴绳等无贴图线条)。
 * albedo = 顶点色;同样参与锥光(线条被手电照亮的边缘感)。
 */
/* DRAWBUFFERS:0123 */
#include "/lib/taclight_gbuffer.glsl"

varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 tnormal;
varying vec3 vposView;

void main() {
    gl_FragData[0] = glcolor;
    gl_FragData[1] = vec4(taclight_encode_normal(normalize(tnormal)), lmcoord);
    gl_FragData[2] = vec4(glcolor.rgb, 0.3);
    gl_FragData[3] = vec4(vposView, 1.0);
}

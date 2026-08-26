#version 120

/*
 * gbuffers_skybasic · 天空穹底色(渐变/地平线雾色,顶点色已含全部信息)。
 * 直通即可;不做星空判定(星体走 skytextured)。M2 风格层将在 final 阶段统一调色。
 */
varying vec4 glcolor;

void main() {
    gl_FragData[0] = vec4(glcolor.rgb, 1.0);
}

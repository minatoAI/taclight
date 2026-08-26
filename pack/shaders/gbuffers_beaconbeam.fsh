#version 120

/*
 * gbuffers_beaconbeam · 信标光柱(自发光、不吃光图)。
 * 塔科夫夜景目标下它是难得的"环境发光参照物",保留原样。
 */
uniform sampler2D gtexture;

varying vec2 texcoord;
varying vec4 glcolor;

void main() {
    vec4 color = texture2D(gtexture, texcoord) * glcolor;
    gl_FragData[0] = color;
}

#version 120

/*
 * gbuffers_hand · 第一人称手持物。
 * M1 关键位:手持 TaCZ 枪械的高光(GGX)发生在 composite 阶段,
 * 但 hand 深度必须可区分(hand depth),Iris 默认已写入 depthtex0。
 */
uniform sampler2D gtexture;
uniform sampler2D lightmap;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    vec4 color = texture2D(gtexture, texcoord) * glcolor;
    color.rgb *= texture2D(lightmap, lmcoord).rgb;
    gl_FragData[0] = color;
}

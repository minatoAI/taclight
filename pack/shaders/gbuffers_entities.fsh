#version 120

/*
 * gbuffers_entities · 实体(含玩家/怪/生物)。
 * entityColor 是受击红闪/爬行者充能蓝闪的混合量,必须保留否则失去原版反馈。
 */
uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform vec4 entityColor;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    vec4 color = texture2D(gtexture, texcoord) * glcolor;
    color.rgb = mix(color.rgb, entityColor.rgb, entityColor.a);
    color.rgb *= texture2D(lightmap, lmcoord).rgb;
    gl_FragData[0] = color;
}

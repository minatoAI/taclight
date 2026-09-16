#version 120

/* gbuffers_skytextured · 太阳/月亮/星星/末地天空(无光图直通)。 */
uniform sampler2D gtexture;

varying vec2 texcoord;
varying vec4 glcolor;

void main() {
    vec4 color = texture2D(gtexture, texcoord) * glcolor;
    gl_FragData[0] = color;
}

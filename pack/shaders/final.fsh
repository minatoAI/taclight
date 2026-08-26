#version 120

/*
 * final · M0:直通输出。
 * 后续里程碑接入点:M2 风格层(ACES/自适应曝光/颗粒/暗角/最简 bloom)、FXAA。
 */
uniform sampler2D colortex0;

varying vec2 texcoord;

void main() {
    vec3 color = texture(colortex0, texcoord).rgb;
    gl_FragData[0] = vec4(color, 1.0);
}

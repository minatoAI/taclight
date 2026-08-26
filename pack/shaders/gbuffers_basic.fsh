#version 120

/*
 * gbuffers_basic · 无贴图几何(选中方块框、拴绳、区块边界线等)。
 * 直通顶点色,不吃光照 = 原版观感。M1 在此基础上按需扩展。
 */
varying vec4 glcolor;

void main() {
    gl_FragData[0] = glcolor;
}

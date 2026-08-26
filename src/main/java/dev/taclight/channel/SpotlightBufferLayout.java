package dev.taclight.channel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * TacLight SSBO std430 契约唯一真源(与 shader_patches/taclight_lights.glsl 对齐)。
 * 头:16 字节(uint lightCount, float vlIntensity, uint flags, uint reserved)
 * 灯:6 x vec4 = 96 字节。N 灯总长 16 + 96N。
 *
 * <p>坐标语义(v0.9.0 起,doc06 §2.5 铁律 3):<b>posRadius.xyz = world 坐标</b>,
 * dirType.xyz = world 方向;scene-relative 转换一律由光影包消费侧执行
 * (pack/shaders/lib/taclight_common.glsl)。GLSL 侧镜像见
 * pack/shaders/lib/taclight_common.glsl(路线 S 主契约)。</p>
 */
public final class SpotlightBufferLayout {
    public static final int HEADER_BYTES = 16;
    public static final int LIGHT_STRIDE_BYTES = 96;
    public static final int BINDING = 7;

    public static final int OFF_LIGHT_COUNT = 0;
    public static final int OFF_VL_INTENSITY = 4;
    public static final int OFF_FLAGS = 8;
    public static final int OFF_RESERVED = 12;

    public static final int OFF_POS_RADIUS = 0;
    public static final int OFF_COLOR_INTENSITY = 16;
    public static final int OFF_DIR_TYPE = 32;
    public static final int OFF_CONE = 48;
    public static final int OFF_VL_PARAMS = 64;
    public static final int OFF_COOKIE = 80;

    public static final int FLAG_HAS_DATA = 1;
    /** bit1: 霓虹调试模式(K 键)——GLSL 用纯绿锥形光渲染,肉眼分辨通道。 */
    public static final int FLAG_DEBUG = 1 << 1;
    /** bit3: 时序探针(G0 风格)——GLSL 在表面 pass 用 atomicOr 写回 reserved。 */
    public static final int FLAG_TIMING_PROBE = 1 << 3;

    private SpotlightBufferLayout() {}

    public static int bufferSize(int lightCount) {
        if (lightCount < 0) throw new IllegalArgumentException("count<0");
        return HEADER_BYTES + lightCount * LIGHT_STRIDE_BYTES;
    }

    public static int lightOffset(int index) {
        if (index < 0) throw new IllegalArgumentException("index<0");
        return HEADER_BYTES + index * LIGHT_STRIDE_BYTES;
    }

    public static ByteBuffer newBuffer(int lightCount) {
        return ByteBuffer.allocateDirect(bufferSize(lightCount)).order(ByteOrder.nativeOrder());
    }

    public static void writeHeader(ByteBuffer buf, int lightCount, float vlIntensity, int flags) {
        buf.putInt(OFF_LIGHT_COUNT, lightCount);
        buf.putFloat(OFF_VL_INTENSITY, vlIntensity);
        buf.putInt(OFF_FLAGS, flags);
        buf.putInt(OFF_RESERVED, 0);
    }

    public static void writeLight(ByteBuffer buf, int index, SpotlightData l) {
        int base = lightOffset(index);
        buf.putFloat(base + OFF_POS_RADIUS, l.posX());
        buf.putFloat(base + OFF_POS_RADIUS + 4, l.posY());
        buf.putFloat(base + OFF_POS_RADIUS + 8, l.posZ());
        buf.putFloat(base + OFF_POS_RADIUS + 12, l.radius());
        buf.putFloat(base + OFF_COLOR_INTENSITY, l.red());
        buf.putFloat(base + OFF_COLOR_INTENSITY + 4, l.green());
        buf.putFloat(base + OFF_COLOR_INTENSITY + 8, l.blue());
        buf.putFloat(base + OFF_COLOR_INTENSITY + 12, l.intensity());
        buf.putFloat(base + OFF_DIR_TYPE, l.dirX());
        buf.putFloat(base + OFF_DIR_TYPE + 4, l.dirY());
        buf.putFloat(base + OFF_DIR_TYPE + 8, l.dirZ());
        buf.putFloat(base + OFF_DIR_TYPE + 12, l.type());
        buf.putFloat(base + OFF_CONE, l.cosOuter());
        buf.putFloat(base + OFF_CONE + 4, l.cosInner());
        buf.putFloat(base + OFF_CONE + 8, l.coneReservedZ());
        buf.putFloat(base + OFF_CONE + 12, l.coneReservedW());
        buf.putFloat(base + OFF_VL_PARAMS, l.anisotropy());
        buf.putFloat(base + OFF_VL_PARAMS + 4, l.density());
        buf.putFloat(base + OFF_VL_PARAMS + 8, l.beam());
        buf.putFloat(base + OFF_VL_PARAMS + 12, l.vlReservedW());
        buf.putFloat(base + OFF_COOKIE, l.cookieR());
        buf.putFloat(base + OFF_COOKIE + 4, l.cookieG());
        buf.putFloat(base + OFF_COOKIE + 8, l.cookieB());
        buf.putFloat(base + OFF_COOKIE + 12, l.cookieA());
    }

    /** 读回单灯(契约测试用,不参与渲染路径)。 */
    public static SpotlightData readLight(ByteBuffer buf, int index) {
        int base = lightOffset(index);
        java.util.function.IntFunction<Float> f = (int o) -> buf.getFloat(base + o);
        return new SpotlightData(
                f.apply(OFF_POS_RADIUS), f.apply(OFF_POS_RADIUS + 4), f.apply(OFF_POS_RADIUS + 8), f.apply(OFF_POS_RADIUS + 12),
                f.apply(OFF_COLOR_INTENSITY), f.apply(OFF_COLOR_INTENSITY + 4), f.apply(OFF_COLOR_INTENSITY + 8), f.apply(OFF_COLOR_INTENSITY + 12),
                f.apply(OFF_DIR_TYPE), f.apply(OFF_DIR_TYPE + 4), f.apply(OFF_DIR_TYPE + 8), f.apply(OFF_DIR_TYPE + 12),
                f.apply(OFF_CONE), f.apply(OFF_CONE + 4), f.apply(OFF_CONE + 8), f.apply(OFF_CONE + 12),
                f.apply(OFF_VL_PARAMS), f.apply(OFF_VL_PARAMS + 4), f.apply(OFF_VL_PARAMS + 8), f.apply(OFF_VL_PARAMS + 12),
                f.apply(OFF_COOKIE), f.apply(OFF_COOKIE + 4), f.apply(OFF_COOKIE + 8), f.apply(OFF_COOKIE + 12));
    }
}

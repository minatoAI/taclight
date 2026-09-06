package dev.taclight.channel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * TacLight SSBO std430 契约唯一真源(与 pack/shaders/lib/taclight_common.glsl 对齐)。
 * 头:16 字节(uint lightCount, float vlIntensity, uint flags, uint reserved)
 * 灯:8 x vec4 = 96 字节,v0.12 起定长 [8](原不定长数组;为尾段体素栅格让位)。
 * 体素栅格尾段(2026-09-01 深夜④ DDA 遮挡):voxOrigin(784) voxMeta(800)
 * voxData(816 起,2bit/体素)。总长固定 = 816 + 131072×4 = 525,104 B。
 *
 * <p>坐标语义(v0.9.0 起,doc06 §2.5 铁律 3):<b>posRadius.xyz = world 坐标</b>,
 * dirType.xyz = world 方向;scene-relative 转换一律由光影包消费侧执行
 * (pack/shaders/lib/taclight_common.glsl)。GLSL 侧镜像见
 * pack/shaders/lib/taclight_common.glsl(路线 S 主契约)。</p>
 */
public final class SpotlightBufferLayout {
    public static final int HEADER_BYTES = 16;
    public static final int LIGHT_STRIDE_BYTES = 96;
    public static final int MAX_LIGHTS = 8;
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

    // ---- 体素遮挡栅格尾段(v0.12;GLSL 侧 voxData 为不定长末成员,读界内即可)----
    /** voxOrigin:xyz=栅格角点 world 坐标(方块格对齐),w&gt;0=有效/w≤0=无效(GLSL 回退 SSO)。 */
    public static final int OFF_VOX_ORIGIN = HEADER_BYTES + MAX_LIGHTS * LIGHT_STRIDE_BYTES; // 784
    /** voxMeta:xyz=各轴格数,w 保留。 */
    public static final int OFF_VOX_META = OFF_VOX_ORIGIN + 16;   // 800
    /** 2bit 打包数据起点。 */
    public static final int OFF_VOX_DATA = OFF_VOX_META + 16;     // 816
    /** 单轴最大格数(与 VoxelField.MAX_DIM 同值;不引用以防包间循环无谓耦合,契约钉等值)。 */
    public static final int VOX_MAX_DIM = 128;
    /** 128³ × 2bit / 32bit。 */
    public static final int VOX_MAX_UINTS = VOX_MAX_DIM * VOX_MAX_DIM * VOX_MAX_DIM / 16; // 131072
    /** SSBO 总长(定长)。 */
    private static final int FIXED_BYTES = OFF_VOX_DATA + VOX_MAX_UINTS * 4;

    public static final int FLAG_HAS_DATA = 1;
    /** bit1: 霓虹调试模式(K 键)——GLSL 用纯绿锥形光渲染,肉眼分辨通道。 */
    public static final int FLAG_DEBUG = 1 << 1;
    /** bit2: 只看光束(!beamonly)——GLSL 跳过 M1 表面照明,composite1 体积束照常(2026-09-05)。 */
    public static final int FLAG_BEAM_ONLY = 1 << 2;
    /** bit3: 时序探针(G0 风格)——GLSL 在表面 pass 用 atomicOr 写回 reserved。 */
    public static final int FLAG_TIMING_PROBE = 1 << 3;
    /** bit4: 遮挡距离表(!occl,2026-09-06 方案二)——GLSL composite 每帧预建均向
     *  D 表(colortex8)、composite1 查表代替体积光逐采样灯侧 DDA;仅体素栅格有效时置位。 */
    public static final int FLAG_OCCL_TABLE = 1 << 4;

    private SpotlightBufferLayout() {}

    /** SSBO 总字节数(v0.12 起定长,与灯数无关——尾段体素栅格恒占位)。 */
    public static int bufferSize() {
        return FIXED_BYTES;
    }

    public static int lightOffset(int index) {
        if (index < 0 || index >= MAX_LIGHTS) throw new IllegalArgumentException("index out of [0,8): " + index);
        return HEADER_BYTES + index * LIGHT_STRIDE_BYTES;
    }

    /** 头 + 8 灯区(体素尾段由 {@link #writeVoxHeader}/数据区单独写)。 */
    public static ByteBuffer newBuffer(int lightCount) {
        return ByteBuffer.allocateDirect(OFF_VOX_DATA).order(ByteOrder.nativeOrder());
    }

    public static void writeHeader(ByteBuffer buf, int lightCount, float vlIntensity, int flags) {
        buf.putInt(OFF_LIGHT_COUNT, lightCount);
        buf.putFloat(OFF_VL_INTENSITY, vlIntensity);
        buf.putInt(OFF_FLAGS, flags);
        buf.putInt(OFF_RESERVED, 0);
    }

    /** 体素栅格头:角点 world 坐标 + 各轴格数 + 有效位(w=1)。 */
    public static void writeVoxHeader(ByteBuffer buf, float ox, float oy, float oz, int dx, int dy, int dz) {
        buf.putFloat(OFF_VOX_ORIGIN, ox);
        buf.putFloat(OFF_VOX_ORIGIN + 4, oy);
        buf.putFloat(OFF_VOX_ORIGIN + 8, oz);
        buf.putFloat(OFF_VOX_ORIGIN + 12, 1.0f);   // w>0 = 有效
        buf.putInt(OFF_VOX_META, dx);
        buf.putInt(OFF_VOX_META + 4, dy);
        buf.putInt(OFF_VOX_META + 8, dz);
        buf.putInt(OFF_VOX_META + 12, 0);
    }

    /** 体素栅格无效位(GLSL 检查 voxOrigin.w≤0 回退 SSO)。 */
    public static void writeVoxInvalid(ByteBuffer buf) {
        buf.putFloat(OFF_VOX_ORIGIN + 12, -1.0f);
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
        buf.putFloat(base + OFF_VL_PARAMS, l.sideFloor());
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

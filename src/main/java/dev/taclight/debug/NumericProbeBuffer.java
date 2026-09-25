package dev.taclight.debug;

import dev.taclight.channel.LightTuneOverride;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;

/**
 * 帧内数值探针(P2)的 Java 侧:布防请求 / 回读结果。调试中继 {@code !numprobe} 是唯一入口。
 *
 * <p><b>目的</b>:把光照**项**的数值(vis/atten/spot/ndl…)读回来,而不是只看像素色 ——
 * 于是"被遮挡处 vis 必须为 0"这类断言从"看图像"变成"读数字" = 渲染单元测试。</p>
 *
 * <p><b>为什么是一个独立的小缓冲(binding=8)而不是塞进 binding=7</b>:binding=7 的
 * {@code voxData[]} 是运行时长度数组,在它之后加字段会挪动其偏移 ⇒ 要改
 * {@link dev.taclight.channel.SpotlightBufferLayout} 真源;独立缓冲零侵入。</p>
 *
 * <p><b>为什么不用 uniform 传请求</b>:注入的内联文本"零 uniform 行"是硬约束
 * ({@code InlineCoreContract};宿主 composite 已声明 Iris 标准附件,重复声明 = C1038)。
 * 所以请求与结果都放在这个缓冲里。</p>
 *
 * <p><b>访问闸门</b>:GLSL 只在 binding=7 头部 {@code flags} 的 bit6
 * ({@code FLAG_NUM_PROBE})置位时才碰 binding=8。本类布防时经
 * {@link LightTuneOverride#setNumProbe(boolean)} 打开该闸门,撤销时关闭 ——
 * 未布防时 GLSL 因 {@code &&} 短路**完全不访问**这个缓冲 ⇒ 生产包里 binding=8
 * 不需要绑定、也没有未定义读取。</p>
 *
 * <p><b>代数(generation)防陈旧</b>:每次布防自增并把代数写进请求;GLSL 命中时把代数
 * 回显到 {@code probeMeta.z}。回读时两者不一致 ⇒ 结果是上一帧的陈旧值(或根本没命中),
 * 判据会显示 {@code STALE/NO-HIT}。</p>
 */
public final class NumericProbeBuffer {

    /** 与 core 的 {@code layout(std430, binding = 8)} 一致。 */
    public static final int BINDING = 8;
    /** 5 × 16B:probeReq / probeTerms / probeExtra / probeFrag / probeMeta。 */
    public static final int BYTES = 5 * 16;

    private static final ByteBuffer SCRATCH = BufferUtils.createByteBuffer(BYTES);
    private static int ssbo;
    private static int generation;
    private static boolean installed;
    private static String note = "not-installed";

    private NumericProbeBuffer() {
    }

    /** 幂等安装:建缓冲 + 清零 + 绑到 binding=8。 */
    public static String install() {
        if (installed) {
            return note;
        }
        try {
            ssbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, BYTES, GL15.GL_DYNAMIC_DRAW);
            zeroScratch();
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, SCRATCH);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING, ssbo);
            installed = true;
            note = "installed ssbo=" + ssbo + " binding=" + BINDING + " bytes=" + BYTES;
        } catch (Throwable t) {
            note = "install failed: " + t;
        }
        return note;
    }

    /**
     * 布防:写请求(x,y,代数,灯序号)+ 清零结果 + 打开 flags 闸门。
     *
     * @param lightIndex 只观测该序号的灯;-1 = 任意(最后写入者胜,用于先确认通道是否通)。
     */
    public static String arm(int x, int y, int lightIndex) {
        String inst = install();
        if (!installed) {
            return "arm aborted: " + inst;
        }
        try {
            generation++;
            zeroScratch();
            SCRATCH.putInt(0, x);
            SCRATCH.putInt(4, y);
            SCRATCH.putInt(8, generation);
            SCRATCH.putInt(12, lightIndex);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, SCRATCH);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING, ssbo);
            LightTuneOverride.setNumProbe(true);
            return "armed px=(" + x + "," + y + ") light=" + (lightIndex < 0 ? "any" : Integer.toString(lightIndex))
                    + " gen=" + generation;
        } catch (Throwable t) {
            return "arm failed: " + t;
        }
    }

    /** 撤销:关闸门(此后 GLSL 不再访问 binding=8)+ 清零缓冲。 */
    public static String disarm() {
        LightTuneOverride.setNumProbe(false);
        if (!installed) {
            return "disarmed (never installed)";
        }
        try {
            zeroScratch();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, SCRATCH);
            return "disarmed gen=" + generation;
        } catch (Throwable t) {
            return "disarm failed: " + t;
        }
    }

    /** 回读并格式化为一行(含 FRESH / STALE-NO-HIT 判定,防"读到上一帧还以为是本次")。 */
    public static String read() {
        if (!installed) {
            return "read failed: not installed (arm first)";
        }
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, SCRATCH);
            int rx = SCRATCH.getInt(0);
            int ry = SCRATCH.getInt(4);
            int rgen = SCRATCH.getInt(8);
            int rlight = SCRATCH.getInt(12);
            float vis = SCRATCH.getFloat(16);
            float atten = SCRATCH.getFloat(20);
            float spot = SCRATCH.getFloat(24);
            float ndl = SCRATCH.getFloat(28);
            float dist = SCRATCH.getFloat(32);
            float type = SCRATCH.getFloat(36);
            float cosAng = SCRATCH.getFloat(40);
            float radius = SCRATCH.getFloat(44);
            float fx = SCRATCH.getFloat(48);
            float fy = SCRATCH.getFloat(52);
            float fz = SCRATCH.getFloat(56);
            int hits = SCRATCH.getInt(64);
            int hitLight = SCRATCH.getInt(68);
            int metaGen = SCRATCH.getInt(72);
            String fresh = (hits > 0 && metaGen == rgen) ? "FRESH" : "STALE/NO-HIT";
            return String.format(
                    "req=(%d,%d) light=%s gen=%d | %s hits=%d hitLight=%d metaGen=%d"
                            + " | vis=%.4f atten=%.4f spot=%.4f ndl=%.4f"
                            + " | dist=%.3f type=%.1f cosAng=%.4f radius=%.2f"
                            + " | fragWorld=(%.3f,%.3f,%.3f)",
                    rx, ry, rlight < 0 ? "any" : Integer.toString(rlight), rgen, fresh,
                    hits, hitLight, metaGen, vis, atten, spot, ndl,
                    dist, type, cosAng, radius, fx, fy, fz);
        } catch (Throwable t) {
            return "read failed: " + t;
        }
    }

    public static String status() {
        return "installed=" + installed + " note=" + note + " binding=" + BINDING
                + " armed=" + LightTuneOverride.numProbe() + " gen=" + generation;
    }

    private static void zeroScratch() {
        SCRATCH.clear();
        for (int i = 0; i < BYTES; i++) {
            SCRATCH.put(i, (byte) 0);
        }
    }
}

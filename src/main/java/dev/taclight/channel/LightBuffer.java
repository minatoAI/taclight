package dev.taclight.channel;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 渲染线程 SSBO 生命周期:创建/上传/绑定(binding 7)/释放。
 * 必须在渲染线程且有 GL 上下文时调用;失败不影响主线程逻辑(光线退化)。
 */
public final class LightBuffer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int ssboId = -1;
    private static int lastCapacity = -1;
    private static boolean uploadLogged;
    private static long lastGridVersion = -1;
    private static java.nio.IntBuffer gridStage;
    private static java.nio.FloatBuffer palStage;

    private LightBuffer() {}

    public static synchronized void upload(List<SpotlightData> lights) {
        upload(lights, 0, null);
    }

    /** @param extraFlags 附加头部标志位(如 FLAG_DEBUG),与灯光数据一起写入。 */
    public static synchronized void upload(List<SpotlightData> lights, int extraFlags) {
        upload(lights, extraFlags, null);
    }

    /**
     * @param lights 上传灯列表;<b>注意</b>:dev 变体里 {@code SynthLightMixin} 会在本方法
     *               {@code HEAD} 用 {@code @ModifyVariable(argsOnly=true)} 把该<b>形参</b>替换成
     *               "{@code !synth} 追加后的列表"(调用方那份列表不受影响)。
     * @param grid 体素遮挡栅格快照(v0.12 DDA 遮挡;null = 无效位,GLSL 回退 SSO)。
     *             数据区仅在其 version 变化时重传(0.5MB/tick 上限,20Hz 节流在 VoxelGrid)。
     *
     * <p><b>{@code count} 口径(2026-09-25 待办 ⑰)</b>:{@code count = min(形参列表长度, MAX_LIGHTS=8)}
     * = <b>实际上传槽数</b> —— <b>含</b> {@code !synth} 合成灯,写进 SSBO 头第 0 个字,并等于 GLSL
     * 每像素遍历的灯槽数(超过 8 的灯被丢弃)。与之对照:{@code PerfStats} 的 {@code lights}=
     * 调用方列表长度 = <b>世界推导</b>灯数,<b>不含</b>合成灯 ⇒ 两者在 {@code !synth N} 下天然不等
     * ({@code ssbo count} 比 {@code lights} 大 N,直到钳到 8),<b>不得互相校验</b>。
     * 日志里的 {@code !diag ... ssbo count=} 就是这个数(由 {@link #dumpLight0()} 从 GPU 头字回读)。</p>
     */
    public static synchronized void upload(List<SpotlightData> lights, int extraFlags,
                                           VoxelField.Snapshot grid) {
        try {
            if (!isGpuUsable()) return;
            int count = Math.min(lights.size(), SpotlightBufferLayout.MAX_LIGHTS);
            int bytes = SpotlightBufferLayout.bufferSize();
            boolean realloc = ssboId == -1 || bytes != lastCapacity;
            if (ssboId == -1) {
                ssboId = GL15.glGenBuffers();
                LOGGER.info("[TacLight] SSBO created (id={}, bytes={} incl voxel tail)", ssboId, bytes);
            }
            int flags = (count > 0 ? SpotlightBufferLayout.FLAG_HAS_DATA : 0)
                    | SpotlightBufferLayout.FLAG_TIMING_PROBE | extraFlags;
            ByteBuffer buf = SpotlightBufferLayout.newBuffer(count);
            SpotlightBufferLayout.writeHeader(buf, count, 1.0f, flags);
            for (int i = 0; i < count; i++) {
                SpotlightBufferLayout.writeLight(buf, i, lights.get(i));
            }
            if (grid != null) {
                SpotlightBufferLayout.writeVoxHeader(buf,
                        grid.ox(), grid.oy(), grid.oz(), grid.dx(), grid.dy(), grid.dz());
            } else {
                SpotlightBufferLayout.writeVoxInvalid(buf);
            }
            // 形状调色板槽数(2026-10-03 R21):每帧都要写(它决定 GLSL 是否读盒区);
            // 盒区数据本身只在 version 变化时单独上传(见下)。
            int palSlots = 0;
            if (grid != null && grid.palette() != null) {
                palSlots = Math.min(grid.palette().count(), ShapePalette.MAX_SLOTS);
            }
            SpotlightBufferLayout.writeVoxPalMeta(buf, palSlots);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboId);
            if (realloc) {
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) bytes, GL15.GL_STREAM_DRAW);
                lastCapacity = bytes;
                lastGridVersion = -1;   // 重分配后内容未定义,栅格数据必须重传
            }
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, buf);
            long tailB = 0;
            if (grid != null && grid.version() != lastGridVersion) {
                if (palSlots > 0) {
                    float[] src = grid.palette().data();
                    int floats = palSlots * ShapePalette.SLOT_STRIDE;
                    if (palStage == null || palStage.capacity() < floats) {
                        palStage = java.nio.ByteBuffer
                                .allocateDirect(ShapePalette.TOTAL_FLOATS * 4)
                                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                    }
                    palStage.clear();
                    palStage.put(src, 0, floats);
                    palStage.flip();
                    GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,
                            (long) SpotlightBufferLayout.OFF_VOX_PAL_BOX, palStage);
                    tailB += (long) floats * 4;
                }
                int used = Math.min(grid.usedUints(), SpotlightBufferLayout.VOX_MAX_UINTS);
                tailB += (long) used * 4;
                if (gridStage == null || gridStage.capacity() < used) {
                    gridStage = java.nio.ByteBuffer
                            .allocateDirect(SpotlightBufferLayout.VOX_MAX_UINTS * 4)
                            .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
                }
                gridStage.clear();
                gridStage.put(grid.data(), 0, used);
                gridStage.flip();
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,
                        (long) SpotlightBufferLayout.OFF_VOX_DATA, gridStage);
                lastGridVersion = grid.version();
            }
            // !perf 上传量(2026-09-25 ⑨):头段 832B/帧恒传;调色板盒区与体素数据仅 version 变化时。
            if (PerfStats.active()) PerfStats.noteUpload(SpotlightBufferLayout.HEAD_STAGE_BYTES, tailB);
            if (!uploadLogged) { uploadLogged = true; LOGGER.info("[TacLight] upload {} light(s), flags={}", count, flags); }
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SpotlightBufferLayout.BINDING, ssboId);
            // v0.9.0:路线 P 时代的 SLOT PROBE(binding 0/1/8 冗余绑定)已删除,
            // 防 Iris/Embeddium 其它缓冲位被遮蔽(docs/06 §8.2)。
        } catch (Throwable t) {
            LOGGER.warn("[TacLight] SSBO upload failed: {}", t.toString());
        }
    }

    /** 槽位7当前绑定对象(0 = 未绑定)。诊断用。 */
    public static synchronized int binding7() {
        try {
            if (!isGpuUsable()) return -1;
            return GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, SpotlightBufferLayout.BINDING);
        } catch (Throwable t) {
            return -2;
        }
    }

    /** 每渲染帧重绑定(老项目经验:不要假定 Iris 绑定不被覆盖)。渲染线程调用。 */
    public static synchronized void rebindBase() {
        try {
            if (ssboId == -1 || !isGpuUsable()) return;
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SpotlightBufferLayout.BINDING, ssboId);
        } catch (Throwable ignored) {}
    }

    /** 回读 reserved 字(时序探针,诊断用)。必须在渲染线程调用。 */
    public static synchronized int readReserved() {
        try {
            if (ssboId == -1 || !isGpuUsable()) return 0;
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboId);
            org.lwjgl.opengl.GL42.glMemoryBarrier(
                    GL43.GL_SHADER_STORAGE_BARRIER_BIT | org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            java.nio.ByteBuffer word = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder());
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) SpotlightBufferLayout.OFF_RESERVED, word);
            return word.getInt(0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 诊断:直读 GPU 缓冲 light0 与 cookie(GLSL 写回),验证 Java 上传 vs GLSL 布局。
     * 返回串里的 {@code count=} = {@link #upload} 写进头字的<b>实际上传槽数</b>(含 {@code !synth}
     * 合成灯、钳 {@code MAX_LIGHTS=8};口径见 {@code upload} 的口径段)⇒ 它就是日志里
     * {@code !diag ... ssbo count=} 的来源,<b>不要</b>拿它与 {@code PerfStats} 的 {@code lights=} 互校。
     */
    public static synchronized String dumpLight0() {
        try {
            if (ssboId == -1 || !isGpuUsable()) return "ssbo-not-created";
            java.nio.ByteBuffer buf = SpotlightBufferLayout.newBuffer(1);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboId);
            org.lwjgl.opengl.GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, buf);
            buf.rewind();
            int count = buf.getInt();
            int flags = buf.getInt(8);
            int reserved = buf.getInt(12);
            float px = buf.getFloat(16), py = buf.getFloat(20), pz = buf.getFloat(24), rad = buf.getFloat(28);
            float dx = buf.getFloat(48), dy = buf.getFloat(52), dz = buf.getFloat(56), type = buf.getFloat(60);
            float co = buf.getFloat(64), ci = buf.getFloat(68);
            float ckX = buf.getFloat(96), ckY = buf.getFloat(100), ckZ = buf.getFloat(104), ckW = buf.getFloat(108);
            return String.format("count=%d flags=0x%x reserved=0x%x | L0 pos=(%.2f,%.2f,%.2f) r=%.1f dir=(%.3f,%.3f,%.3f) type=%.1f cos=(%.3f,%.3f) | cookie(glsl)=(%.1f,%.1f,%.1f,%.1f)",
                    count, flags, reserved, px, py, pz, rad, dx, dy, dz, type, co, ci, ckX, ckY, ckZ, ckW);
        } catch (Throwable t) {
            return "dump-error:" + t.getClass().getSimpleName();
        }
    }

    public static synchronized void release() {
        if (ssboId != -1) {
            try { GL15.glDeleteBuffers(ssboId); } catch (Throwable ignored) {}
            ssboId = -1;
            lastCapacity = -1;
        }
    }

    private static boolean isGpuUsable() {
        try {
            return GL15.glGetString(GL15.GL_VERSION) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}

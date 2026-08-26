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

    private LightBuffer() {}

    public static synchronized void upload(List<SpotlightData> lights) {
        upload(lights, 0);
    }

    /** @param extraFlags 附加头部标志位(如 FLAG_DEBUG),与灯光数据一起写入。 */
    public static synchronized void upload(List<SpotlightData> lights, int extraFlags) {
        try {
            if (!isGpuUsable()) return;
            int count = Math.min(lights.size(), 8);
            if (ssboId == -1) {
                ssboId = GL15.glGenBuffers();
                LOGGER.info("[TacLight] SSBO created (id={})", ssboId);
            }
            int flags = (count > 0 ? SpotlightBufferLayout.FLAG_HAS_DATA : 0)
                    | SpotlightBufferLayout.FLAG_TIMING_PROBE | extraFlags;
            ByteBuffer buf = SpotlightBufferLayout.newBuffer(count);
            SpotlightBufferLayout.writeHeader(buf, count, 1.0f, flags);
            for (int i = 0; i < count; i++) {
                SpotlightBufferLayout.writeLight(buf, i, lights.get(i));
            }
            int bytes = SpotlightBufferLayout.bufferSize(count);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboId);
            if (bytes != lastCapacity) {
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) bytes, GL15.GL_STREAM_DRAW);
                lastCapacity = bytes;
            }
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, buf);
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

    /** 诊断:直读 GPU 缓冲 light0 与 cookie(GLSL 写回),验证 Java 上传 vs GLSL 布局。 */
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

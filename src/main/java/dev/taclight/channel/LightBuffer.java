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

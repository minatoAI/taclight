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

    private LightBuffer() {}

    public static synchronized void upload(List<SpotlightData> lights) {
        try {
            if (!isGpuUsable()) return;
            int count = Math.min(lights.size(), 8);
            if (ssboId == -1) {
                ssboId = GL15.glGenBuffers();
                LOGGER.info("[TacLight] SSBO created (id={})", ssboId);
            }
            ByteBuffer buf = SpotlightBufferLayout.newBuffer(count);
            SpotlightBufferLayout.writeHeader(buf, count, 1.0f, count > 0 ? SpotlightBufferLayout.FLAG_HAS_DATA : 0);
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
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SpotlightBufferLayout.BINDING, ssboId);
        } catch (Throwable t) {
            LOGGER.warn("[TacLight] SSBO upload failed: {}", t.toString());
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

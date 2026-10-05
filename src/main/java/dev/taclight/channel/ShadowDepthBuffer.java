package dev.taclight.channel;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;

/**
 * S4a depth 缓冲(binding=9)生命周期(2026-10-05,spike 专用)。
 * 与 {@link LightBuffer}(binding=7)同套路:渲染线程创建/上传/重绑/释放;
 * 失败只记 warn,不影响主 SSBO(灯退化为体素/SSO,见包侧回退)。
 */
public final class ShadowDepthBuffer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int ssboId = -1;
    private static volatile boolean valid;
    private static java.nio.FloatBuffer stage;

    private ShadowDepthBuffer() {}

    /** 整块上传(头 80B + 1MB depth;S4a 完成一次传一次,非逐帧)。渲染线程调用。 */
    public static synchronized void upload(float[] data) {
        try {
            if (!isGpuUsable() || data == null
                    || data.length != ShadowDepthBaker.TOTAL_FLOATS) {
                LOGGER.warn("[TacLight] shadowdepth upload rejected(len_ok={})",
                        data != null && data.length == ShadowDepthBaker.TOTAL_FLOATS);
                return;
            }
            if (ssboId == -1) {
                ssboId = GL15.glGenBuffers();
                LOGGER.info("[TacLight] shadowdepth SSBO created (id={}, bytes={})",
                        ssboId, (long) data.length * 4);
            }
            if (stage == null || stage.capacity() < data.length) {
                stage = java.nio.ByteBuffer.allocateDirect(data.length * 4)
                        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            }
            stage.clear();
            stage.put(data);
            stage.flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboId);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, stage, GL15.GL_STATIC_DRAW);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,
                    ShadowDepthBaker.BINDING, ssboId);
            valid = true;
        } catch (Throwable t) {
            LOGGER.warn("[TacLight] shadowdepth upload failed: {}", t.toString());
        }
    }

    /** 每渲染帧重绑定(不假定 Iris 不覆盖绑定,同 binding=7 的教训)。 */
    public static synchronized void rebindBase() {
        try {
            if (ssboId == -1 || !isGpuUsable()) return;
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,
                    ShadowDepthBaker.BINDING, ssboId);
        } catch (Throwable ignored) {}
    }

    public static boolean hasValid() {
        return valid && ssboId != -1;
    }

    /** 作废(包侧回退;不删缓冲,下次 upload 复用)。 */
    public static synchronized void invalidate() {
        valid = false;
    }

    public static synchronized void release() {
        if (ssboId != -1) {
            try { GL15.glDeleteBuffers(ssboId); } catch (Throwable ignored) {}
            ssboId = -1;
        }
        valid = false;
    }

    private static boolean isGpuUsable() {
        try {
            return GL15.glGetString(GL15.GL_VERSION) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}

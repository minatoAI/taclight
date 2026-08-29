package dev.taclight.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class KeyBindings {
    public static final KeyMapping FLASHLIGHT_TOGGLE = new KeyMapping(
            "key.taclight.flashlight_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "key.categories.taclight");

    /** K:霓虹调试模式,证明这条锥形光是我们 SSBO 通道画的(与内置手电无关)。 */
    public static final KeyMapping DEBUG_TOGGLE = new KeyMapping(
            "key.taclight.debug_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.taclight");

    /** N:一行结构化诊断入日志(SSBO dump + 相机 + 灯状态 + 包名),供调试自动化 grep。 */
    public static final KeyMapping DIAG_DUMP = new KeyMapping(
            "key.taclight.diag_dump",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "key.categories.taclight");

    /** B:3 秒帧率基准(avg / 1% low / min FPS),preflight 需 maxFps:260 + 关垂直同步。 */
    public static final KeyMapping BENCH = new KeyMapping(
            "key.taclight.bench",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.taclight");

    private KeyBindings() {}
}

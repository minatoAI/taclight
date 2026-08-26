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

    private KeyBindings() {}
}

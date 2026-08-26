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

    private KeyBindings() {}
}

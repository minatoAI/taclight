package dev.taclight.client;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reversible debug-only control for Minecraft's view-bobbing option. */
public final class BobViewControl {
    private BobViewControl() { }

    public static String configure(String arg, BooleanSupplier getter, Consumer<Boolean> setter) {
        String value = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.equals("status")) return getter.getAsBoolean() ? "on" : "off";
        if (value.equals("on") || value.equals("off")) {
            boolean enabled = value.equals("on");
            setter.accept(enabled);
            return enabled ? "on" : "off";
        }
        return "无法解析 '" + arg + "' (用法: !bob <on|off|status>)";
    }
}

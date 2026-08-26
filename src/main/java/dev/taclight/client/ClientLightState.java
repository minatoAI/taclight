package dev.taclight.client;

/** 客户端灯开关状态(纯本地,不参与服务器逻辑)。 */
public final class ClientLightState {
    private static boolean on = true;

    private ClientLightState() {}

    public static boolean isOn() { return on; }

    public static void toggle() { on = !on; }
}

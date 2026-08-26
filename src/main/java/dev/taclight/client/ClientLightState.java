package dev.taclight.client;

/** 客户端灯光状态(纯本地,零网络包)。 */
public final class ClientLightState {
    private static boolean handheldOn = true;
    private static boolean gunLightOn = false;
    private static boolean debugMode = false;

    private ClientLightState() {}

    /** 手持手电筒开关(IrisItemLightProvider 读取) */
    public static boolean isOn() { return handheldOn; }
    /** 强制开启(调试模式自动开灯时用)。 */
    public static void forceHandheldOn() { handheldOn = true; }
    public static void toggle() { handheldOn = !handheldOn; }

    /** 枪挂灯状态(TaCZ 附件探针写入) */
    public static void setGunLight(boolean on) { gunLightOn = on; }
    public static boolean gunLightOn() { return gunLightOn; }

    /** 是否有任一设备激活供渲染层消费 */
    public static boolean anyDeviceOn() { return handheldOn || gunLightOn; }

    /** 霓虹调试模式(K 键):GLSL 输出纯色锥形光,与内置手电一眼区分。 */
    public static boolean debugMode() { return debugMode; }
    public static void toggleDebug() { debugMode = !debugMode; }
}

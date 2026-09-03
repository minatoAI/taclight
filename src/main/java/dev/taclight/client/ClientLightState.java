package dev.taclight.client;

/** 客户端灯光状态(纯本地,零网络包)。 */
public final class ClientLightState {
    private static boolean handheldOn = true;
    private static boolean gunLightOn = false;
    /** 手动覆写(调试开关):true=人工通过 !gun 显式设定,此后 tick 探针不再覆盖。 */
    private static volatile boolean gunManual = false;
    private static boolean debugMode = false;
    /** 自身灯运行时覆写(null=跟随配置 SELF_LIGHT_ENABLED;!selflight 可翻转)。 */
    private static volatile Boolean selfLightOverride = null;

    private ClientLightState() {}

    /** 手持手电筒开关(IrisItemLightProvider 读取) */
    public static boolean isOn() { return handheldOn; }
    /** 强制开启(调试模式自动开灯时用)。 */
    public static void forceHandheldOn() { handheldOn = true; }
    public static void toggle() { handheldOn = !handheldOn; }
    /** 服务端真源回写(S2C SyncLightS2C;命令改灯时本人客户端跟随)。 */
    public static void setHandheld(boolean on) { handheldOn = on; }

    /** 枪挂灯状态(TaCZ 附件探针写入;手动 !gun 覆写后探针不再覆盖,见 setGunLightManual) */
    public static void setGunLight(boolean on) {
        if (!gunManual) {
            gunLightOn = on;
        }
    }
    /** !gun 手动设定:写入状态 + 立手动覆写旗(探针/服务端回显不再覆盖)。 */
    public static void setGunLightManual(boolean on) {
        gunManual = true;
        gunLightOn = on;
    }
    /** 清手动覆写(恢复探针跟随;调试用,暂无中继入口)。 */
    public static void clearGunManual() { gunManual = false; }
    public static boolean gunManual() { return gunManual; }
    public static boolean gunLightOn() { return gunLightOn; }

    /** 是否有任一设备激活供渲染层消费 */
    public static boolean anyDeviceOn() { return handheldOn || gunLightOn; }

    /**
     * 自身灯是否允许上传(2026-09-03 用户需求:枪灯测试单变量观察)。
     * 配置 SELF_LIGHT_ENABLED=false 即关闭自身两盏灯的上传(远程灯不受影响);
     * 运行时 !selflight 可覆写翻转,覆写 null=跟随配置。
     */
    public static boolean selfLightEnabled() {
        Boolean o = selfLightOverride;
        if (o != null) {
            return o;
        }
        try {
            return dev.taclight.config.TacLightConfig.SELF_LIGHT_ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 运行时翻转自身灯总闸(返回翻转后状态,供中继回显)。 */
    public static boolean toggleSelfLight() {
        boolean next = !selfLightEnabled();
        selfLightOverride = next;
        return next;
    }

    /** 霓虹调试模式(K 键):GLSL 输出纯色锥形光,与内置手电一眼区分。 */
    public static boolean debugMode() { return debugMode; }
    public static void toggleDebug() { debugMode = !debugMode; }
    public static void setDebug(boolean on) { debugMode = on; }
}

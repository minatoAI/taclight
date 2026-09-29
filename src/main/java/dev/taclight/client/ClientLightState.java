package dev.taclight.client;

/** 客户端灯光状态(纯本地,零网络包)。 */
public final class ClientLightState {
    private static boolean handheldOn = true;
    private static boolean gunLightOn = false;
    /** 手动覆写(调试开关):true=人工通过 !gun 显式设定,此后 tick 探针不再覆盖。 */
    private static volatile boolean gunManual = false;
    /** 主手探针(每 tick 覆写):true=正持带 taclight:gun_light 的枪;手动偏好须与之相乘。 */
    private static volatile boolean gunProbeOn = false;
    /**
     * 持物门探针(每 tick 覆写):true=主手或副手正持 {@code taclight:flashlight}。
     *
     * <p><b>2026-09-25 用户报的 bug</b>:手持灯此前只查开关、不查手里拿的是什么 ⇒
     * 手里拿着枪时**枪灯与手持灯同时亮**,且把枪切走灯也不灭。
     * 现在与枪灯同构:开关偏好 × 持物门,并且"离手"会**自动关**。</p>
     */
    private static volatile boolean handheldProbeOn = false;
    private static boolean debugMode = false;
    /** 自身灯运行时覆写(null=跟随配置 SELF_LIGHT_ENABLED;!selflight 可翻转)。 */
    private static volatile Boolean selfLightOverride = null;

    private ClientLightState() {}

    /** 手持手电筒开关偏好(不含持物门;渲染/HUD 显示"开关"时用它)。 */
    public static boolean isOn() { return handheldOn; }
    /** 强制开启(调试模式自动开灯时用)。 */
    public static void forceHandheldOn() { handheldOn = true; }
    public static void toggle() { handheldOn = !handheldOn; }
    /** 服务端真源回写(S2C SyncLightS2C;命令改灯时本人客户端跟随)。 */
    public static void setHandheld(boolean on) { handheldOn = on; }

    // ---------------- 手持灯持物门(2026-09-25) ----------------

    /** 持物门探针写入(每 tick 无条件覆写)。 */
    public static void setHandheldProbe(boolean on) { handheldProbeOn = on; }
    public static boolean handheldProbeOn() { return handheldProbeOn; }

    /**
     * 有效手持灯 = 开关 × (持物门 ∪ 霓虹调试旁路)。
     *
     * <p>霓虹(K / {@code !neon})**故意豁免**持物门:它的用途是"证明 SSBO 通道可见",
     * 与手里拿什么无关(否则调试模式会因未持手电筒而失效)。</p>
     */
    public static boolean handheldEffective() { return effective(handheldOn, handheldProbeOn, debugMode); }

    /** 纯函数(离线契约钉死):开关 × (门 ∪ 调试旁路)。 */
    public static boolean effective(boolean switchOn, boolean probeOn, boolean debugBypass) {
        return switchOn && (probeOn || debugBypass);
    }

    /**
     * 纯函数(离线契约钉死):**离手自动关** —— 原本持有且开关为开,现在不再持有 ⇒ 清开关。
     * 调试旁路期间不清(否则一放手电筒霓虹就灭)。
     */
    public static boolean autoClear(boolean switchOn, boolean wasHolding, boolean nowHolding, boolean debugBypass) {
        return switchOn && wasHolding && !nowHolding && !debugBypass;
    }

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
    /** 主手探针写入(每 tick 无条件覆写,不受手动旗影响)。 */
    public static void setGunProbe(boolean on) { gunProbeOn = on; }
    public static boolean gunProbeOn() { return gunProbeOn; }
    /** 有效枪灯 = 开关偏好 × 持枪门:未持灯枪一律不亮(切走即灭、切回即复)。 */
    public static boolean gunLightEffective() { return gunLightOn && gunProbeOn; }
    /** 清手动覆写(恢复探针跟随;调试用,暂无中继入口)。 */
    public static void clearGunManual() { gunManual = false; }
    public static boolean gunManual() { return gunManual; }
    public static boolean gunLightOn() { return gunLightOn; }

    /** 是否有任一设备**有效**激活供渲染层消费(两盏灯都过各自的开关 × 门)。 */
    public static boolean anyDeviceOn() { return handheldEffective() || gunLightEffective(); }

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

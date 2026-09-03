package dev.taclight.client;

/**
 * TP 屏外强制渲染门禁(2026-09-03 v3 屏外不剔除渲染)。
 *
 * <p>底层机制:枪口姿态是渲染时产物,视锥外实体整跳过渲染(含 TaCZ 枪模动画计算),
 * 捕获链随之沉默 —— 屏外方向只能靠公式猜(头角+固定偏移),与真枪向(身体+动画,
 * 疾跑差 30~50°)系统性偏离,入场交接把这份偏差兑现成可见转动。
 *
 * <p>v3 不搬动画、不新增网络包:只对"距离内+开枪灯的远程玩家"绕过视锥剔除,
 * 让真实渲染链(含现有捕获钩子)在屏外照常执行,屏外捕获不断,入场即无交接差。
 *
 * <p>纯 JVM 逻辑(契约 TpOffscreenRenderGateContract 钉死),mixin 侧只做"读状态→调门禁"。
 */
public final class TpOffscreenRenderGate {
    private static volatile boolean enabled = true;

    private TpOffscreenRenderGate() {}

    public static boolean enabled() {
        return enabled;
    }

    /** 总开关(默认 on):off = 全部放行,完整回退旧行为,供对照/排障。 */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * 是否对该实体强制渲染(绕过视锥剔除)。
     *
     * @param self      是否观察者本人(本人走本地第一人称链,不强制)
     * @param gunLight  枪灯同步态(关=普通玩家,零开销)
     * @param distBlocks 观察者相机到该实体的距离(格)
     * @param maxDistBlocks 灯收集链同门禁(默认 48 格,超距实体本就不收灯)
     */
    public static boolean keepFor(boolean self, boolean gunLight, double distBlocks, double maxDistBlocks) {
        if (!enabled) {
            return false;
        }
        if (self || !gunLight) {
            return false;
        }
        if (!(distBlocks >= 0.0) || !(maxDistBlocks >= 0.0)) {
            return false;
        }
        return distBlocks <= maxDistBlocks;
    }
}

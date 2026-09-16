package dev.taclight.client;

import net.minecraft.world.entity.player.Player;

/**
 * 程序化视角扫掠(!sweep,2026-09-02 打桩测试激励):渲染帧级线性旋转本地玩家视角,
 * 供"冻结目标 + 相机扫掠"不变性测试 —— 取代不可靠的键鼠注入(坑41/46),
 * 使 TP-INVARIANCE/TP-CONTINUITY 判定可脚本化复现。
 * 进度/配置为纯函数(CameraSweepContract);基准角在首个渲染帧锁定。
 */
public final class CameraSweep {
    private static long startNanos;
    private static long durationNanos;
    private static float deltaYaw;
    private static float deltaPitch;
    private static boolean armed;
    private static boolean latched;
    private static float startYaw;
    private static float startPitch;

    private CameraSweep() {}

    /** 纯函数:线性进度 [0,1];负龄 0,超时 1,零时长 1。 */
    public static float progress(long startN, long durationN, long nowN) {
        if (durationN <= 0) {
            return 1f;
        }
        long elapsed = nowN - startN;
        if (elapsed <= 0) {
            return 0f;
        }
        float p = elapsed / (float) durationN;
        return p >= 1f ? 1f : p;
    }

    public static boolean active() {
        return armed;
    }

    public static float deltaYaw() {
        return deltaYaw;
    }

    public static float deltaPitch() {
        return deltaPitch;
    }

    public static String configure(String arg) {
        String[] tok = arg == null ? new String[0] : arg.trim().split("\\s+");
        if (tok.length == 0 || tok[0].isEmpty()) {
            return armed
                    ? String.format("sweep active yawΔ=%.1f° pitchΔ=%.1f°", deltaYaw, deltaPitch)
                    : "sweep idle";
        }
        if (tok[0].equals("stop")) {
            armed = false;
            durationNanos = 0;
            return "sweep stop";
        }
        if (tok.length != 3) {
            return "无法解析 '" + arg + "' (用法: !sweep <yaw|pitch> <总度数> <秒数> | stop)";
        }
        try {
            float deg = Float.parseFloat(tok[1]);
            float sec = Float.parseFloat(tok[2]);
            if (sec <= 0) {
                throw new NumberFormatException("seconds must be positive");
            }
            if (tok[0].equals("yaw")) {
                deltaYaw = deg;
                deltaPitch = 0f;
            } else if (tok[0].equals("pitch")) {
                deltaPitch = deg;
                deltaYaw = 0f;
            } else {
                throw new NumberFormatException("axis must be yaw|pitch");
            }
            durationNanos = (long) (sec * 1e9f);
            latched = false;
            armed = true;
            return String.format("sweep %s Δ=%.1f° t=%.1fs armed", tok[0], deg, sec);
        } catch (NumberFormatException e) {
            return "无法解析 '" + arg + "' (用法: !sweep <yaw|pitch> <总度数> <秒数> | stop)";
        }
    }

    /** 渲染帧调用:首个帧锁定基准角,线性施加;到时自动停。 */
    public static void tick(Player player) {
        if (!armed || player == null) {
            return;
        }
        long now = System.nanoTime();
        if (!latched) {
            startYaw = player.getYRot();
            startPitch = player.getXRot();
            startNanos = now;
            latched = true;
        }
        float p = progress(startNanos, durationNanos, now);
        if (deltaYaw != 0f) {
            player.setYRot(startYaw + deltaYaw * p);
        }
        if (deltaPitch != 0f) {
            player.setXRot(startPitch + deltaPitch * p);
        }
        if (p >= 1f) {
            armed = false;
            durationNanos = 0;
        }
    }
}

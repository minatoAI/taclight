package dev.taclight.client;

/**
 * CameraSweep 契约(2026-09-02 打桩测试基础):程序化视角扫掠的纯函数部分 ——
 * 线性进度钳制、配置解析(yaw/pitch/stop/坏参)、激活态与 Δ 值。
 * 施加到玩家旋转的 tick 不属纯函数,由实机 G 行数据验证。
 */
public class CameraSweepContract {
    public static void main(String[] args) {
        check(CameraSweep.progress(1000, 1000, 1000) == 0f, "进度:起点=0");
        check(Math.abs(CameraSweep.progress(1000, 1000, 1500) - 0.5f) < 1e-6, "进度:线性中点");
        check(CameraSweep.progress(1000, 1000, 3000) == 1f, "进度:终点钳 1");
        check(CameraSweep.progress(1000, 1000, 500) == 0f, "进度:负龄钳 0");
        float prev = -1f;
        boolean mono = true;
        for (long t = 1000; t <= 2000; t += 50) {
            float p = CameraSweep.progress(1000, 1000, t);
            if (p < prev) mono = false;
            prev = p;
        }
        check(mono, "进度:单调不减");

        check(CameraSweep.configure("bogus").contains("无法解析"), "配置:坏参数报错");
        check(!CameraSweep.active(), "配置:坏参数不激活");
        String yaw = CameraSweep.configure("yaw 150 8");
        check(yaw.contains("yaw") && yaw.contains("150"), "配置:yaw 扫掠回显");
        check(CameraSweep.active() && Math.abs(CameraSweep.deltaYaw() - 150f) < 1e-4, "配置:Δyaw=150 激活");
        String pitch = CameraSweep.configure("pitch -20 2");
        check(pitch.contains("pitch"), "配置:pitch 扫掠");
        check(Math.abs(CameraSweep.deltaPitch() + 20f) < 1e-4 && Math.abs(CameraSweep.deltaYaw()) < 1e-6,
                "配置:Δpitch=-20 且 yaw 归零");
        check(CameraSweep.configure("stop").contains("stop") && !CameraSweep.active(), "配置:stop 停止");
        check(CameraSweep.configure("").contains("idle"), "配置:空参回显状态");
        System.out.println("CameraSweepContract: ALL PASS (13 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

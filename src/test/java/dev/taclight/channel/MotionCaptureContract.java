package dev.taclight.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * MotionCapture 契约(09-01 运动门控采集开关):开窗/收窗/翻转/撤防状态机是
 * "真实鼠标运动 → 自动逐帧信号+连拍" 流程的地基,必须离线注入时钟钉死。
 * 每次 observe 间隔 150ms ≥ 默认参考采样间隔(100ms),即每次调用都过参考边界。
 */
public final class MotionCaptureContract {
    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("PASS: " + msg);
    }

    private static final long STEP = 150_000_000L;

    public static void main(String[] args) {
        List<String> out = new ArrayList<>();
        MotionCapture.sink = out::add;
        long nano = 1_000_000_000L;

        // ---- 1) 默认未布防,observe 无副作用 ----
        check(!MotionCapture.armed(), "默认未布防");
        MotionCapture.observe(7, 0f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 0 && out.isEmpty(), "未布防时 observe 无事件");

        // ---- 2) 布防后静止不开发会话;无会话不供图 ----
        check(MotionCapture.configure("on").contains("armed"), "布防生效");
        check(MotionCapture.armed(), "armed() 为真");
        for (int i = 0; i < 10; i++) {
            nano += STEP;
            MotionCapture.observe(7, 0f, 0f, 0, 0, 0, nano);
        }
        check(MotionCapture.sessionId() == 0 && out.isEmpty(), "静止不开发会话");
        check(!MotionCapture.shotDue(nano), "无会话时 shotDue=false");

        // ---- 3) 慢速微转(0.1°/150ms < 阈值 0.3°)不触发 ----
        for (int i = 0; i < 13; i++) {
            nano += STEP;
            MotionCapture.observe(7, 0.1f * (i + 1), 0f, 0, 0, 0, nano);
        }
        check(MotionCapture.sessionId() == 0, "低于阈值慢转不开会话");

        // ---- 4) yaw 超阈 → 开会话 s0001 ----
        nano += STEP;
        MotionCapture.observe(7, 5.0f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 1, "yaw 超阈开会话");
        check(out.stream().anyMatch(s -> s.contains("MCAP-OPEN session=s0001")), "OPEN 事件含会话号: " + out.get(out.size() - 1));

        // ---- 5) 持续运动跨过 stillMs 不断窗 ----
        for (int i = 0; i < 10; i++) {
            nano += STEP;
            MotionCapture.observe(7, 5.0f + 0.5f * (i + 1), 0f, 0, 0, 0, nano);
        }
        check(MotionCapture.sessionId() == 1 && out.stream().noneMatch(s -> s.contains("MCAP-CLOSE")),
                "持续运动 1.5s(>still 0.8s)会话不断");

        // ---- 6) 静止超时自动收窗,布防保持 ----
        float last = 5.0f + 0.5f * 10;
        for (int i = 0; i < 8; i++) {
            nano += STEP;
            MotionCapture.observe(7, last, 0f, 0, 0, 0, nano);
        }
        check(MotionCapture.sessionId() == 0, "静止 1.2s 自动收窗");
        check(out.stream().anyMatch(s -> s.contains("MCAP-CLOSE session=s0001") && s.contains("why=静止")),
                "CLOSE 事件含会话号与原因");
        check(MotionCapture.armed(), "收窗后仍布防");

        // ---- 7) 再次运动开 s0002 ----
        nano += STEP;
        MotionCapture.observe(7, last + 1.0f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 2, "收窗后再次运动开新会话(实际=" + MotionCapture.sessionId() + ")");

        // ---- 8) 静止收窗后位置变化触发 ----
        for (int i = 0; i < 8; i++) {
            nano += STEP;
            MotionCapture.observe(7, last + 1.0f, 0f, 0, 0, 0, nano);
        }
        nano += STEP;
        MotionCapture.observe(7, last + 1.0f, 0f, 0.05, 0, 0, nano);
        check(MotionCapture.sessionId() == 3, "位置变化(0.05m ≥ 0.02m)开会话");

        // ---- 9) 静止收窗后俯仰变化触发 ----
        for (int i = 0; i < 8; i++) {
            nano += STEP;
            MotionCapture.observe(7, last + 1.0f, 0f, 0.05, 0, 0, nano);
        }
        nano += STEP;
        MotionCapture.observe(7, last + 1.0f, 0.5f, 0.05, 0, 0, nano);
        check(MotionCapture.sessionId() == 4, "俯仰变化(0.5° ≥ 0.3°)开会话");

        // ---- 10) 截图节流 + 帧数上限翻转 ----
        check(MotionCapture.shotDue(nano), "开会话后立即可截图");
        MotionCapture.onShot(nano);
        check(MotionCapture.sessionShots() == 1, "onShot 计数");
        nano += 5_000_000L;
        check(!MotionCapture.shotDue(nano), "节流:间隔内不供图");
        nano += 200_000_000L;
        check(MotionCapture.shotDue(nano), "间隔后恢复供图");
        MotionCapture.configure("max=3");
        for (int k = 0; k < 2; k++) {
            nano += STEP;
            MotionCapture.observe(7, last + 1.0f, 0.5f + 0.6f * (k + 1), 0.05, 0, 0, nano);
            nano += 50_000_000L;
            MotionCapture.onShot(nano);
        }
        check(MotionCapture.sessionId() == 5, "达帧数上限(3)关闭并翻转新会话(运动未断,实际=" + MotionCapture.sessionId() + " shots=" + MotionCapture.sessionShots() + ")");
        check(MotionCapture.sessionShots() == 0, "新会话截图计数清零");
        check(out.stream().anyMatch(s -> s.contains("MCAP-CLOSE session=s0004") && s.contains("why=帧数上限")),
                "翻转时 CLOSE 原因=帧数上限");

        // ---- 11) 撤防:关会话且不再响应 ----
        String off = MotionCapture.configure("off");
        check(off.contains("disarmed"), "撤防返回: " + off);
        check(!MotionCapture.armed() && MotionCapture.sessionId() == 0, "撤防后无会话");
        int n = out.size();
        nano += STEP;
        MotionCapture.observe(7, last + 2.0f, 0f, 1, 1, 1, nano);
        check(MotionCapture.sessionId() == 0 && out.size() == n, "撤防后 observe 完全静默");

        // ---- 12) 目标切换:收窗+参考重置(不误开) ----
        MotionCapture.configure("on");
        nano += STEP;
        MotionCapture.observe(7, 20f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 6, "重新布防+运动开 s0006");
        nano += STEP;
        MotionCapture.observe(8, 20f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 0, "目标切换收窗");
        check(out.stream().anyMatch(s -> s.contains("MCAP-CLOSE session=s0006") && s.contains("why=目标切换")),
                "目标切换 CLOSE 原因明确");
        nano += STEP;
        MotionCapture.observe(8, 20f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 0, "目标切换后参考已重置,同值不误开");
        nano += STEP;
        MotionCapture.observe(8, 21f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 7, "新目标运动正常开 s0007");

        // ---- 13) yaw ±180 环绕:wrap 后 +2° 正常触发 ----
        for (int i = 0; i < 8; i++) {
            nano += STEP;
            MotionCapture.observe(8, 21f, 0f, 0, 0, 0, nano);
        }
        nano += STEP;
        MotionCapture.observe(8, -179f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 8, "建立 -179° 参考(大跳开会话)");
        nano += STEP;
        MotionCapture.observe(8, 179f, 0f, 0, 0, 0, nano);
        check(MotionCapture.sessionId() == 8 && out.stream().noneMatch(s -> s.contains("MCAP-CLOSE session=s0008")),
                "±180 环绕(+2°)不中断会话");

        // ---- 14) 参数解析与回显 ----
        String st = MotionCapture.configure("yaw=0.5 pos=0.05 fps=30 ref=50 still=500");
        check(st.contains("yaw=0.50") && st.contains("fps=30") && st.contains("ref=50ms"),
                "参数回显: " + st);
        check(MotionCapture.configure("bogus=1").contains("用法"), "非法参数返回用法提示");

        MotionCapture.sink = null;
        System.out.println("MotionCaptureContract: ALL PASS (" + passed + " checks)");
    }
}

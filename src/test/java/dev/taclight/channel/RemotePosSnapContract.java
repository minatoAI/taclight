package dev.taclight.channel;

import dev.taclight.channel.RemotePosSnap.Out;

/**
 * 位置链死推滤波契约(纯 JVM,无 MC 类型)。
 * 语义锚点:显示 = 平滑速度 v̂ 积分推进 + 每 tick 误差小比例校正(位置拉回 + 速度导引)。
 * 背景:v1(对同步目标 lerpX 做延迟段快照插值)实机 A/B 反而更糟(+27~41%)—— 目标序列
 * 突发式台阶,忠实回放=更噪;速度调制必须用<b>匀速重构</b>消(见 RemotePosSnap 头注)。
 * 核心契约:稳态逐 tick 增量恒定(v̂ 滤掉 ±20% 调制)、全程无后向跳变、瞬态有界快收敛、
 * 传送直落、静止恒等。
 */
public class RemotePosSnapContract {
    private static final long FRAME = 16_666_667L;   // 60fps 帧
    private static final long TICK = 50_000_000L;    // 50ms tick
    private static long t;

    public static void main(String[] args) {
        t = 1_000_000_000L;
        RemotePosSnap.configure("on");

        // ---- 1) 首见直取:display = C0 精确 ----
        Out o = RemotePosSnap.step(1, 2000.5, 121.0, 9.0, t);
        check(Math.abs(o.x() - 2000.5) < 1e-9 && Math.abs(o.z() - 9.0) < 1e-9,
                "首见直取 C0,实际 " + o.x() + "," + o.z());

        // ---- 2) 静止恒等:C 不变 → display 收敛到 C 并保持(1.5s 内不动) ----
        for (int i = 1; i <= 90; i++) {
            o = RemotePosSnap.step(1, 2000.5, 121.0, 9.0, t + i * FRAME);
            if (Math.abs(o.x() - 2000.5) > 0.02 || Math.abs(o.z() - 9.0) > 0.02)
                check(false, "静止漂移@" + i + " x=" + o.x());
        }
        check(true, "静止 90 帧恒等(±2cm 内)");

        // ---- 3) 稳态匀速:C 每 tick +0.216(步行)→ 显示逐 tick 增量恒定,无负增量 ----
        double cx = 0;
        double prev = 0;
        double maxDev = 0;
        boolean neg = false;
        RemotePosSnap.step(2, cx, 0, 0, t);
        for (int i = 1; i <= 60; i++) {
            cx += 0.216;
            o = RemotePosSnap.step(2, cx, 0, 0, t + i * TICK);
            if (i > 30) { // 暖机后计量
                double inc = o.x() - prev;
                maxDev = Math.max(maxDev, Math.abs(inc - 0.216));
                if (inc < -0.02) neg = true;
            }
            prev = o.x();
        }
        check(!neg, "稳态无后向跳变");
        check(maxDev < 0.03, "稳态匀速:逐 tick 增量偏差 <0.03 格(实际 " + maxDev + ")");

        // ---- 4) 帧率无关:同稳态用 4 子帧/tick 积分,增量同样恒定 ----
        cx = 0;
        prev = 0;
        maxDev = 0;
        RemotePosSnap.step(3, cx, 0, 0, t);
        for (int i = 1; i <= 40; i++) {
            for (int f = 1; f <= 4; f++) {
                if (f == 4) cx += 0.216;
                o = RemotePosSnap.step(3, cx, 0, 0, t + (i - 1) * TICK + f * (TICK / 4));
            }
            if (i > 20) {
                double inc = o.x() - prev;
                maxDev = Math.max(maxDev, Math.abs(inc - 0.216));
            }
            prev = o.x();
        }
        check(maxDev < 0.03, "帧率无关(4 子帧):增量偏差 <0.03 格(实际 " + maxDev + ")");

        // ---- 5) 起步瞬态:从静止进入匀速,无后跳、误差有界、30 tick 内跟上 ----
        cx = 0;
        RemotePosSnap.step(4, cx, 0, 0, t);
        boolean converged = false;
        double maxErr = 0;
        boolean backward = false;
        prev = 0;
        for (int i = 1; i <= 30; i++) {
            cx += 0.216;
            o = RemotePosSnap.step(4, cx, 0, 0, t + i * TICK);
            double inc = o.x() - prev;
            if (inc < -0.02) backward = true;
            maxErr = Math.max(maxErr, Math.abs(cx - o.x()));
            if (i > 20 && Math.abs(inc - 0.216) < 0.03) converged = true;
            prev = o.x();
        }
        check(!backward, "起步无后向跳变");
        check(maxErr < 0.35, "起步误差有界 <0.35 格(实际 " + maxErr + ")");
        check(converged, "起步 30 tick 内跟上匀速");

        // ---- 6) 急停:匀速中 C 停,20 tick 内收敛且无显著过冲 ----
        cx = 0;
        RemotePosSnap.step(5, cx, 0, 0, t);
        for (int i = 1; i <= 40; i++) { cx += 0.216; RemotePosSnap.step(5, cx, 0, 0, t + i * TICK); }
        prev = cx;
        boolean overshoot = false;
        boolean settled = false;
        for (int i = 41; i <= 60; i++) {
            o = RemotePosSnap.step(5, cx, 0, 0, t + i * TICK);
            if (o.x() > cx + 0.1) overshoot = true;   // 冲过停止点 10cm 以上
            if (Math.abs(o.x() - cx) < 0.05) settled = true;
            prev = o.x();
        }
        check(!overshoot, "急停过冲 <0.1 格");
        check(settled, "急停 1s 内收敛到 ±0.05 格");

        // ---- 7) 传送直落:|err|>0.6 → display 到达瞬间=新 C ----
        RemotePosSnap.step(6, 0, 0, 0, t);
        RemotePosSnap.step(6, 0, 0, 0, t + TICK);
        o = RemotePosSnap.step(6, 5, 0, 0, t + 2 * TICK);
        check(Math.abs(o.x() - 5.0) < 1e-9, "传送直落 x=5(实际 " + o.x() + ")");

        // ---- 8) 双实体状态隔离:静态实体精确保持,运动实体独立逼近自身目标 ----
        Out a = RemotePosSnap.step(7, 0, 0, 0, t);
        Out b = RemotePosSnap.step(8, 100, 0, 100, t);
        a = RemotePosSnap.step(7, 0.216, 0, 0, t + TICK);
        b = RemotePosSnap.step(8, 100, 0, 100, t + TICK);
        a = RemotePosSnap.step(7, 0.216, 0, 0, t + 2 * TICK);
        b = RemotePosSnap.step(8, 100, 0, 100, t + 2 * TICK);
        check(Math.abs(b.x() - 100.0) < 1e-9 && Math.abs(b.z() - 100.0) < 1e-9,
                "双实体隔离:静态 b 恒 100(实际 " + b.x() + ")");
        check(a.x() > 0 && a.x() < 0.25,
                "双实体隔离:运动 a 独立逼近 0.216、不串态(实际 " + a.x() + ")");

        // ---- 9) 陈旧重置:2s 未见 → 首见直取 ----
        long tStale = t + 4 * TICK + RemotePosSnap.STALE_NANOS + TICK;
        o = RemotePosSnap.step(7, 55.0, 0, 0, tStale);
        check(Math.abs(o.x() - 55.0) < 1e-9, "陈旧重置直取 55,实际 " + o.x());

        // ---- 10) off 清状态:on→off→on 后首见直取 ----
        RemotePosSnap.configure("off");
        check(!RemotePosSnap.enabled(), "off 后 enabled=false");
        RemotePosSnap.configure("on");
        o = RemotePosSnap.step(9, 7.0, 0, 0, t);
        check(Math.abs(o.x() - 7.0) < 1e-9, "重布防首见直取,实际 x=" + o.x());

        // ---- 11) peek 只读:幂等、同刻与 step 一致 ----
        RemotePosSnap.step(10, 10, 0, 0, t);
        RemotePosSnap.step(10, 10.216, 0, 0, t + TICK);
        Out p1 = RemotePosSnap.peek(10, 10.216, 0, 0, t + TICK + FRAME);
        check(p1 != null, "peek 非空");
        Out p2 = RemotePosSnap.peek(10, 10.216, 0, 0, t + TICK + FRAME);
        check(p2 != null && Math.abs(p2.x() - p1.x()) < 1e-9, "peek 幂等(只读)");
        Out s2 = RemotePosSnap.step(10, 10.216, 0, 0, t + TICK + FRAME);
        check(Math.abs(s2.x() - p1.x()) < 1e-9, "同刻 step == peek(peek 未污染状态)");
        check(RemotePosSnap.peek(999, 0, 0, 0, t) == null, "未知 id peek=null");

        // ---- 12) 状态查询 ----
        String st = RemotePosSnap.configure("");
        check(st.contains("psnap=on"), "状态串含 psnap=on:" + st);

        System.out.println("RemotePosSnapContract: PASS (" + (n - fail) + "/" + n + ")");
        if (fail > 0) System.exit(1);
    }

    private static int n, fail;
    private static void check(boolean ok, String what) {
        n++;
        if (!ok) { fail++; System.out.println("  FAIL " + what); }
    }
}

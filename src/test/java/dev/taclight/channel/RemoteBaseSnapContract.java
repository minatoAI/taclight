package dev.taclight.channel;

import dev.taclight.channel.RemoteBaseSnap.Out;

/**
 * snap+pred 基角契约(纯 JVM,无 MC 类型)。
 * 语义锚点(= lookreplay.js snap/snap+pred 臂的 Java 侧同式移植):
 * 延迟一段的快照插值 —— C_k 到达后在 [t_k, t_k+50ms] 播放 C_{k-1}→C_k,只依赖
 * 自用 C 历史(不碰 O/C 对)→ 构造上位置连续;首见直取;静止像素恒等;
 * ±180 环绕取最短弧;预测臂 = ω̂ EMA(τv)→ext EMA(τout) 两级,稳态无损、急停回落;
 * off 清状态退回调用方旧管线。
 */
public class RemoteBaseSnapContract {
    private static final long FRAME = 16_666_667L;   // 60fps 帧
    private static final long TICK = 50_000_000L;    // 50ms 快照节拍
    private static long t;

    public static void main(String[] args) {
        t = 1_000_000_000L;
        RemoteBaseSnap.configure("on");
        RemoteLookPredictor.configure("1.25");

        // ---- 1) 首见直取:display=C 精确值,ext=0(无 v̂ 历史) ----
        Out o = RemoteBaseSnap.step(1, 100f, 0f, t);
        check(Math.abs(o.yaw() - 100f) < 1e-6f, "首见直取 yaw=C0,实际 " + o.yaw());
        check(Math.abs(o.pitch() - 0f) < 1e-6f, "首见直取 pitch=C0");
        check(Math.abs(o.extYaw()) < 1e-6f && Math.abs(o.extPitch()) < 1e-6f, "首见 ext=0");

        // ---- 2) 静止像素恒等:C 不变 → display 恒为 C(超过 50ms 也不漂) ----
        for (int i = 1; i <= 6; i++) {
            o = RemoteBaseSnap.step(1, 100f, 0f, t + i * FRAME);
            if (Math.abs(o.yaw() - 100f) > 1e-6f) check(false, "静止恒等被破坏@" + i + " yaw=" + o.yaw());
        }
        check(true, "静止 6 帧恒等(>50ms 钳到段终点)");

        // ---- 3) 段延迟播放:C 跳 +10° 后,在 [t, t+50ms] 从旧值播到新值(到达瞬间不跳) ----
        long tJ = t + 8 * FRAME;
        o = RemoteBaseSnap.step(1, 110f, 0f, tJ);
        check(Math.abs(o.yaw() - 100f) < 1e-6f, "到达瞬间 display=旧值 100(延迟一段),实际 " + o.yaw());
        o = RemoteBaseSnap.step(1, 110f, 0f, tJ + TICK / 2);
        check(Math.abs(o.yaw() - 105f) < 1e-4f, "段中点 105(线性 ease),实际 " + o.yaw());
        o = RemoteBaseSnap.step(1, 110f, 0f, tJ + TICK + FRAME);
        check(Math.abs(o.yaw() - 110f) < 1e-4f, "段终精确 110,实际 " + o.yaw());

        // ---- 4) ±180 环绕取最短弧:179 → -179 应播 +2°(经 180),不掉头 -358° ----
        o = RemoteBaseSnap.step(2, 179f, 0f, t);
        check(Math.abs(o.yaw() - 179f) < 1e-6f, "环绕:首见 179");
        o = RemoteBaseSnap.step(2, -179f, 0f, t + TICK);
        check(Math.abs(o.yaw() - 179f) < 1e-6f, "环绕:到达瞬间不跳,实际 " + o.yaw());
        o = RemoteBaseSnap.step(2, -179f, 0f, t + 2 * TICK);
        float rel = ((o.yaw() - 179f) % 360f + 540f) % 360f - 180f;
        check(Math.abs(rel - 2f) < 1e-3f, "环绕:终值走最短弧 +2°(实际 rel=" + rel + ")");

        // ---- 5) pitch 无环绕直差:30→40 ease,88→-88 直差(不做 wrap) ----
        o = RemoteBaseSnap.step(3, 0f, 30f, t);
        o = RemoteBaseSnap.step(3, 0f, 40f, t + TICK);
        check(Math.abs(o.pitch() - 30f) < 1e-6f, "pitch 到达瞬间=旧值");
        o = RemoteBaseSnap.step(3, 0f, 40f, t + 2 * TICK);
        check(Math.abs(o.pitch() - 40f) < 1e-4f, "pitch 段终 40,实际 " + o.pitch());

        // ---- 6) 双实体状态隔离:交替喂不串段 ----
        Out a = RemoteBaseSnap.step(4, 0f, 0f, t);
        Out b = RemoteBaseSnap.step(5, 200f, 0f, t);
        a = RemoteBaseSnap.step(4, 10f, 0f, t + TICK);
        b = RemoteBaseSnap.step(5, 200f, 0f, t + TICK);
        a = RemoteBaseSnap.step(4, 10f, 0f, t + 2 * TICK);
        b = RemoteBaseSnap.step(5, 200f, 0f, t + 2 * TICK);
        check(Math.abs(a.yaw() - 10f) < 1e-4f && Math.abs(b.yaw() - 200f) < 1e-4f,
                "双实体隔离:a=" + a.yaw() + " b=" + b.yaw());

        // ---- 7) 陈旧重置:2s 未见 → 首见直取,不回放旧段 ----
        long tStale = t + 3 * TICK + RemoteBaseSnap.STALE_NANOS + TICK;
        o = RemoteBaseSnap.step(4, 55f, 0f, tStale);
        check(Math.abs(o.yaw() - 55f) < 1e-6f, "陈旧重置直取 55,实际 " + o.yaw());

        // ---- 8) 稳态恒速预测:ω=4°/tick → v̂→80°/s,ext→4×1.25=5°(EMA 稳态无损) ----
        float c = 0f;
        o = RemoteBaseSnap.step(6, c, 0f, t);
        for (int i = 1; i <= 60; i++) {
            c += 4f;
            o = RemoteBaseSnap.step(6, c, 0f, t + i * TICK);
        }
        check(Math.abs(o.extYaw() - 5f) < 0.1f, "稳态 ext→5.0(=ω×ticks),实际 " + o.extYaw());
        // 显示角 = 段插值(落后最新 C ≤1 tick)+ ext:稳态下应与 C 差 ≤ ~1 tick 步长量级
        float behind = c - o.yaw();
        check(behind > 0f && behind < 9f, "稳态显示角落后真值 0~9°(滞后被 ext 补偿),实际 " + behind);

        // ---- 9) 急停回落:ω→0 后 ext 单调降无翻转,3s 内 <0.5° ----
        float prevExt = o.extYaw();
        for (int i = 1; i <= 60; i++) {
            o = RemoteBaseSnap.step(6, c, 0f, t + (60 + i) * TICK);
            if (o.extYaw() > prevExt + 1e-6f || o.extYaw() < -1e-6f)
                check(false, "急停单调无翻转:ext=" + o.extYaw() + " prev=" + prevExt);
            prevExt = o.extYaw();
        }
        check(prevExt < 0.5f, "急停 3s 后 ext<0.5°,实际 " + prevExt);

        // ---- 10) 传送级钳制:180°/tick 单帧 → ext 有界 ≤12° ----
        RemoteBaseSnap.step(7, 0f, 0f, t);
        float maxExt = 0f;
        for (int i = 1; i <= 15; i++) {
            o = RemoteBaseSnap.step(7, 180f * i, 0f, t + i * TICK);
            maxExt = Math.max(maxExt, Math.abs(o.extYaw()));
        }
        check(maxExt <= 12.0f + 1e-4f, "传送级 ω 被双层钳制 ≤12°,实际 " + maxExt);

        // ---- 11) off 清状态:on→off→on 后首见直取(无旧段回放) ----
        RemoteBaseSnap.configure("off");
        check(!RemoteBaseSnap.enabled(), "off 后 enabled=false");
        RemoteBaseSnap.configure("on");
        o = RemoteBaseSnap.step(1, 7f, 0f, t);
        check(Math.abs(o.yaw() - 7f) < 1e-6f && Math.abs(o.extYaw()) < 1e-6f,
                "重布防首见直取且 ext=0,实际 yaw=" + o.yaw() + " ext=" + o.extYaw());

        // ---- 12) peek 只读:幂等、不推进状态、不污染 lastC ----
        RemoteBaseSnap.step(8, 10f, 0f, t);
        RemoteBaseSnap.step(8, 20f, 0f, t + TICK);
        Out p1 = RemoteBaseSnap.peek(8, 20f, 0f, t + TICK + TICK / 2);
        check(p1 != null && Math.abs(p1.yaw() - 15f) < 1e-4f, "peek 段中点 15,实际 " + (p1 == null ? "null" : p1.yaw()));
        Out p2 = RemoteBaseSnap.peek(8, 20f, 0f, t + TICK + TICK / 2);
        check(p2 != null && Math.abs(p2.yaw() - p1.yaw()) < 1e-6f, "peek 幂等(只读)");
        Out s2 = RemoteBaseSnap.step(8, 20f, 0f, t + TICK + TICK / 2);
        check(Math.abs(s2.yaw() - p1.yaw()) < 1e-4f, "同刻 step == peek(peek 未污染状态),实际 " + s2.yaw());
        check(RemoteBaseSnap.peek(999, 0f, 0f, t) == null, "未知 id peek=null");

        // ---- 13) 状态查询 ----
        String st = RemoteBaseSnap.configure("");
        check(st.contains("bsnap=on"), "状态串含 bsnap=on:" + st);

        System.out.println("RemoteBaseSnapContract: PASS (" + (n - fail) + "/" + n + ")");
        if (fail > 0) System.exit(1);
    }

    private static int n, fail;
    private static void check(boolean ok, String what) {
        n++;
        if (!ok) { fail++; System.out.println("  FAIL " + what); }
    }
}

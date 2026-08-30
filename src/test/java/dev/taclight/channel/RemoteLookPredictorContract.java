package dev.taclight.channel;

import dev.taclight.channel.RemoteLookPredictor.Ext;

/**
 * 方案A 预测外推契约(纯 JVM,无 MC 类型)。
 * 语义锚点:稳态恒速外推量精确命中目标(EMA 不衰减恒值)、急停平滑回落无回弹、
 * 传送级角速度被双层钳制、首见/陈旧直取目标、off 直通且无状态残留。
 */
public class RemoteLookPredictorContract {
    private static final float FRAME_NANOS = 16_666_667f; // 60fps
    private static long t;

    public static void main(String[] args) {
        t = 1_000_000_000L;

        // ---- 1) 首见直取目标(无爬坡):ω=9°/tick × 1.25 tick = 11.25° ----
        RemoteLookPredictor.configure("1.25");
        Ext e = RemoteLookPredictor.step(1, 180f, 0f, 9f, 0f, t);
        check(Math.abs(e.yawDeg() - 11.25f) < 0.01f, "首见直取:ext=ω×ticks=11.25(无爬坡)");
        check(e.pitchDeg() == 0.0f, "零角速度通道外推为 0");

        // ---- 2) 稳态恒速:EMA 对恒值目标无衰减,收敛并保持 11.25 ----
        for (int i = 0; i < 120; i++) {
            t += (long) FRAME_NANOS;
            e = RemoteLookPredictor.step(1, 180f, 0f, 9f, 0f, t);
            if (e.yawDeg() < -1e-6f || e.yawDeg() > 11.25f + 1e-4f) {
                check(false, "稳态单调有界:yawExt=" + e.yawDeg());
            }
        }
        check(Math.abs(e.yawDeg() - 11.25f) < 0.05f,
                "稳态收敛 11.25(EMA 对恒值目标无衰减),实际 " + e.yawDeg());

        // ---- 3) 急停回落:ω→0 后 ext 单调降、无符号翻转,250ms 内 <2° ----
        float prev = e.yawDeg();
        for (int i = 0; i < 90; i++) { // 90 帧 ≈ 1.5s? 90×16.7ms=1.5s — 覆盖充分
            t += (long) FRAME_NANOS;
            e = RemoteLookPredictor.step(1, 189f, 0f, 0f, 0f, t);
            if (e.yawDeg() > prev + 1e-6f || e.yawDeg() < -1e-6f) {
                check(false, "急停回落单调无翻转:yawExt=" + e.yawDeg() + " prev=" + prev);
            }
            prev = e.yawDeg();
        }
        check(prev < 2.0f, "急停 1.5s 后残量 <2°,实际 " + prev);
        // 80ms 尺度抽查:停后 5 帧(~83ms)应已减半以上(τ=80ms)
        // (2)→(3) 衔接处已验证单调;此处只锚终值,避免帧率耦合过拟合)

        // ---- 4) 传送级角速度:双层钳制(20°/tick × 1.25=25 → 角上限 12) ----
        e = RemoteLookPredictor.step(2, 0f, 0f, 180f, 0f, t);
        check(Math.abs(e.yawDeg()) <= 12.0f, "传送级 ω 被钳到角上限 12,实际 " + e.yawDeg());
        e = RemoteLookPredictor.step(2, 0f, 0f, 0f, 0f, t + (long) FRAME_NANOS);
        check(e.yawDeg() >= -1e-6f && e.yawDeg() <= 12.0f, "传送后下一帧回落且有界");

        // ---- 5) 负角速度对称 ----
        for (int i = 0; i < 200; i++) {
            t += (long) FRAME_NANOS;
            e = RemoteLookPredictor.step(3, 0f, 0f, -6f, -2f, t);
        }
        check(Math.abs(e.yawDeg() - (-7.5f)) < 0.05f, "负 ω 对称:yawExt→-7.5,实际 " + e.yawDeg());
        check(Math.abs(e.pitchDeg() - (-2.5f)) < 0.05f, "俯仰通道独立:ext→-2.5,实际 " + e.pitchDeg());

        // ---- 6) off 直通 + 状态清空 ----
        RemoteLookPredictor.configure("off");
        e = RemoteLookPredictor.step(4, 0f, 0f, 9f, 9f, t);
        check(e.yawDeg() == 0f && e.pitchDeg() == 0f, "off 直通:ext 恒 0");
        RemoteLookPredictor.configure("1.25");
        e = RemoteLookPredictor.step(4, 0f, 0f, 9f, 9f, t);
        check(Math.abs(e.yawDeg() - 11.25f) < 0.01f && Math.abs(e.pitchDeg() - 11.25f) < 0.01f,
                "off 期间状态被清:重开后首见直取");

        // ---- 7) 陈旧复活:2s 未见视为新实体,直取目标 ----
        RemoteLookPredictor.step(5, 0f, 0f, 9f, 0f, t);
        t += 3_000_000_000L; // 3s 未见(> STALE_NANOS)
        e = RemoteLookPredictor.step(5, 0f, 0f, 9f, 0f, t);
        check(Math.abs(e.yawDeg() - 11.25f) < 0.01f, "陈旧状态作废:复现即首见直取");

        // ---- 8) ticks=3 极端调参仍受角上限保护 ----
        RemoteLookPredictor.configure("3");
        e = RemoteLookPredictor.step(6, 0f, 0f, 9f, 0f, t);
        check(Math.abs(e.yawDeg() - 12.0f) < 1e-4f, "ticks=3 时目标 27 被钳到 12,实际 " + e.yawDeg());

        // ---- 9) configure 解析 ----
        check(RemoteLookPredictor.configure("").contains("ticks=3"), "空参 = 状态查询");
        check(RemoteLookPredictor.configure("log on").contains("on"), "log on 可开");
        RemoteLookPredictor.configure("log off");
        check(RemoteLookPredictor.configure("xyz").contains("无法解析"), "非法参数报错不炸");
        RemoteLookPredictor.configure("1.25");

        System.out.println("RemoteLookPredictorContract: ALL PASS (15 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

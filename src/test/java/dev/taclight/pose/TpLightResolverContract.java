package dev.taclight.pose;

import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * TpLightResolver 契约(2026-09-02 屏外枪灯连续性,纯 JVM):
 * TP 枪灯解析状态机 fresh/hold/blend/fallback —— 捕获时刻相机映射、referent 稳态保持(hold)、
 * 失效 slew 限速混合(blend)、hard 模式 = 旧二元回退。全部检查注释写明判定语义。
 */
public class TpLightResolverContract {
    public static void main(String[] args) {
        // ---- 映射基准:恒等相机下,视空间 (0,0,1) 经 Ry180 → 世界 (0,0,-1) ----
        Quaternionf idCam = new Quaternionf();
        Vector3d eye = new Vector3d(10, 64, 10);
        TpLightResolver.CaptureView cap = new TpLightResolver.CaptureView(
                new MuzzlePoseMath.Pose(0, 0, 1f, 0, 0, 1f, 0, 1, 0), ms(0), idCam, new Vector3d(eye));
        TpLightResolver.Referent ref = new TpLightResolver.Referent(1, 64, 1, 90f, 0f, false, 7);
        Vector3d fbPos = new Vector3d(0, 64, 0);
        Vector3d fbDir = new Vector3d(0, 0, 1);
        Vector3d capPos = new Vector3d(10, 64, 9); // eye + Ry180·(0,0,1)
        Vector3d capDir = new Vector3d(0, 0, -1);

        TpLightResolver.Resolved r0 = TpLightResolver.resolve(null, null, ref, fbPos, fbDir, 0f, ms(1), ms(16), true);
        check(r0.state().equals("fallback") && r0.weight() == 0f, "无捕获:fallback 权重 0");
        check(r0.pos().distance(fbPos) < 1e-9, "无捕获:锚=回退锚");

        // ---- hard 模式 = 旧二元回退:fresh 精确,过期即回退(无 hold 无 blend) ----
        TpLightResolver.Resolved rh = TpLightResolver.resolve(cap, ref, ref, fbPos, fbDir, 1f, ms(1), ms(16), false);
        check(rh.state().equals("fresh") && rh.weight() == 1f, "hard:fresh→权重 1");
        check(rh.pos().distance(capPos) < 1e-6, "hard:fresh 锚=camEye+捕获时刻相机映射(同帧行为不变)");
        check(rh.dir().distance(capDir) < 1e-6, "hard:fresh 向=捕获时刻相机映射");
        TpLightResolver.Resolved rh2 = TpLightResolver.resolve(cap, ref, ref, fbPos, fbDir, 1f, ms(400), ms(16), false);
        check(rh2.state().equals("fallback") && rh2.weight() == 0f && rh2.pos().distance(fbPos) < 1e-9,
                "hard:过期即整体回退(旧跳变语义,供 A/B 对照)");

        // ---- blend 首捕:权重按 200ms 爬升(状态仍=fresh,数据源是捕获),锚在回退与捕获之间 ----
        TpLightResolver.Resolved rb = TpLightResolver.resolve(cap, ref, ref, fbPos, fbDir, 0f, ms(1), ms(50), true);
        check(rb.state().equals("fresh") && Math.abs(rb.weight() - 0.25f) < 1e-6, "首捕爬升:状态 fresh,入速率 200ms(50ms→0.25)");
        Vector3d expectMid = new Vector3d(fbPos).lerp(capPos, 0.25);
        check(rb.pos().distance(expectMid) < 1e-6, "blend:锚=回退↔捕获线性插值");
        check(rb.dir().distance(new Vector3d(0, 0, 1)) < 1e-6, "blend:向=回退↔捕获插值后归一");

        // ---- hold:referent 稳(实体未动)→ 权重保持 1,世界锚恒=捕获位(不随相机/时间漂移) ----
        TpLightResolver.Resolved rhold = TpLightResolver.resolve(cap, ref, ref, fbPos, fbDir, 1f, ms(400), ms(16), true);
        check(rhold.state().equals("hold") && rhold.weight() == 1f, "blend:referent 稳→hold 权重 1(过期不回退)");
        check(rhold.pos().distance(capPos) < 1e-6, "hold:锚=捕获世界位(屏外期间钉死)");

        // ---- referent 变→blend 衰减:速率 dt/400ms,锚滑向回退,无跳变 ----
        TpLightResolver.Referent moved = new TpLightResolver.Referent(2, 64, 1, 90f, 0f, false, 7);
        TpLightResolver.Resolved rdec = TpLightResolver.resolve(cap, ref, moved, fbPos, fbDir, 1f, ms(400), ms(40), true);
        check(rdec.state().equals("blend") && Math.abs(rdec.weight() - 0.9f) < 1e-6, "referent 变:出速率 400ms(40ms→0.9)");
        Vector3d expectDec = new Vector3d(fbPos).lerp(capPos, 0.9f);
        check(rdec.pos().distance(expectDec) < 1e-6, "referent 变:锚按权重滑向回退");
        TpLightResolver.Resolved rz = TpLightResolver.resolve(cap, ref, moved, fbPos, fbDir, 0.02f, ms(400), ms(40), true);
        check(rz.state().equals("fallback") && rz.weight() == 0f && rz.pos().distance(fbPos) < 1e-9, "权重耗尽→fallback");

        // ---- hold 硬上限:超 10s 强制失效(防漏判变化) ----
        TpLightResolver.Resolved rmax = TpLightResolver.resolve(cap, ref, ref, fbPos, fbDir, 1f,
                ms(0) + TpLightResolver.HOLD_MAX_NANOS + 1, ms(16), true);
        check(!rmax.state().equals("hold") && rmax.weight() < 1f, "hold 超 10s 硬上限失效");

        // ---- 映射用捕获时刻相机(非当前相机):rotY90 相机下 (0,0,1)→世界 (-1,0,0) ----
        Quaternionf y90 = new Quaternionf().rotationY((float) (Math.PI / 2));
        TpLightResolver.CaptureView cap90 = new TpLightResolver.CaptureView(
                new MuzzlePoseMath.Pose(0, 0, 1f, 0, 0, 1f, 0, 1, 0), ms(0), y90, new Vector3d(eye));
        TpLightResolver.Resolved r90 = TpLightResolver.resolve(cap90, ref, ref, fbPos, fbDir, 1f, ms(1), ms(16), false);
        check(r90.pos().distance(new Vector3d(9, 64, 10)) < 1e-6,
                "映射绑定捕获时刻相机四元数(Q=Ry90·Ry180:v=(0,0,1)→(-1,0,0))");

        // ---- referentStable:5cm/2° 容忍,偏航按 wrap 判定,瞄准/手持变化即失效 ----
        check(TpLightResolver.referentStable(ref, ref), "稳态:全同");
        check(TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1.01, 64, 1, 90f, 0f, false, 7)),
                "稳态:位置 1cm 容忍");
        check(!TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1.06, 64, 1, 90f, 0f, false, 7)),
                "稳态:位置 >5cm 失效");
        check(TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1, 64, 1, 92f, 0f, false, 7)),
                "稳态:偏航 2° 容忍");
        check(!TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1, 64, 1, 93f, 0f, false, 7)),
                "稳态:偏航 >2° 失效");
        check(TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1, 64, 1, -268f, 0f, false, 7)),
                "稳态:偏航按 wrap 判定(358°≡-2°)");
        check(TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1, 64, 1, 90f, 1.5f, false, 7)),
                "稳态:俯仰 1.5° 容忍");
        check(!TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1, 64, 1, 90f, 3f, false, 7)),
                "稳态:俯仰 >2° 失效");
        check(!TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1, 64, 1, 90f, 0f, true, 7)),
                "稳态:瞄准态翻转失效");
        check(!TpLightResolver.referentStable(ref, new TpLightResolver.Referent(1, 64, 1, 90f, 0f, false, 8)),
                "稳态:手持物品变化失效");
        check(!TpLightResolver.referentStable(null, ref) && !TpLightResolver.referentStable(ref, null),
                "稳态:空 referent 不稳(从未 fresh 过不 hold)");

        // ---- advanceWeight:slew 限速,帧率无关,dt 钳制 ----
        check(TpLightResolver.advanceWeight(0.3f, true, 0) == 0.3f, "权重:dt=0 不动");
        check(Math.abs(TpLightResolver.advanceWeight(0f, true, ms(50)) - 0.25f) < 1e-6, "权重:入速率 200ms");
        check(Math.abs(TpLightResolver.advanceWeight(1f, false, ms(50)) - 0.875f) < 1e-6, "权重:出速率 400ms");
        check(TpLightResolver.advanceWeight(0.97f, true, ms(50)) == 1f, "权重:饱和 1");
        check(TpLightResolver.advanceWeight(0.02f, false, ms(50)) == 0f, "权重:饱和 0");
        float whole = TpLightResolver.advanceWeight(0f, true, ms(100));
        float piecewise = 0f;
        for (int i = 0; i < 5; i++) {
            piecewise = TpLightResolver.advanceWeight(piecewise, true, ms(20));
        }
        check(Math.abs(whole - 0.5f) < 1e-6 && Math.abs(whole - piecewise) < 1e-4,
                "权重:帧率无关(整步==分段) whole=" + whole + " piecewise=" + piecewise);
        check(Math.abs(TpLightResolver.advanceWeight(0f, true, 10_000_000_000L) - 0.5f) < 1e-6,
                "权重:超大 dt 钳到 100ms");

        System.out.println("TpLightResolverContract: ALL PASS (34 checks)");
    }

    private static long ms(long v) {
        return v * 1_000_000L;
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

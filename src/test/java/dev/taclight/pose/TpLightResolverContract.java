package dev.taclight.pose;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * TpLightResolver 契约(2026-09-02 入场跳变根治,纯 JVM):
 * 局部偏移重构跟随 —— 位置变化由重构吸收(步行/传送不破坏 hold),只有姿态(偏航/俯仰/
 * 瞄准/手持)降级;fresh 锚=捕获精确世界位,爬升只桥接摆动级差量;捕获时刻相机映射;
 * hard 模式 = 二元切换。全部检查注释写明判定语义。
 */
public class TpLightResolverContract {
    public static void main(String[] args) {
        // ---- 偏航锚点(MC 约定):0=南(+z), 90=西(−x), −90≡270=东(+x);右手=前顺时针−90° ----
        check(TpLightResolver.yawForward(0f).distance(new Vector3f(0, 0, 1)) < 1e-6, "yawForward(0)=+z(南)");
        check(TpLightResolver.yawForward(90f).distance(new Vector3f(-1, 0, 0)) < 1e-6, "yawForward(90)=−x(西)");
        check(TpLightResolver.yawForward(-90f).distance(new Vector3f(1, 0, 0)) < 1e-6, "yawForward(−90)=+x(东)");
        check(TpLightResolver.yawRight(0f).distance(new Vector3f(-1, 0, 0)) < 1e-6, "yawRight(0)=−x(面南右手在西)");
        check(TpLightResolver.yawRight(-90f).distance(new Vector3f(0, 0, 1)) < 1e-6,
                "yawRight(−90)=+z(面东右手在南,与 s0012 实测 right=+0.142 同系)");

        // ---- 局部↔世界往返:非轴角 37° 双向一致 ----
        Vector3d off37 = new Vector3d(0.6, -0.2, 0.3);
        Vector3f loc37 = TpLightResolver.worldToLocal(off37, 37f);
        Vector3f f37 = TpLightResolver.yawForward(37f);
        Vector3f r37 = TpLightResolver.yawRight(37f);
        Vector3d back37 = new Vector3d(f37.x() * loc37.x() + r37.x() * loc37.z(), loc37.y(),
                f37.z() * loc37.x() + r37.z() * loc37.z());
        check(back37.distance(off37) < 1e-6, "局部↔世界往返(37°)恒等");

        // ---- 出厂默认偏移 = 双会话实测标定(307 帧腰射步行均值,截断 3 位) ----
        check(Math.abs(TpLightResolver.DEFAULT_OFF_LOCAL.x() - 1.064f) < 1e-4
                && Math.abs(TpLightResolver.DEFAULT_OFF_LOCAL.y() + 0.326f) < 1e-4
                && Math.abs(TpLightResolver.DEFAULT_OFF_LOCAL.z() - 0.142f) < 1e-4,
                "默认偏移钉死为实测标定 (1.064,−0.326,0.142)");

        // ---- 主 fixture:眼位 (100,64,50) 面东(−90),恒等相机映射 +Z→−Z ----
        // 捕获枪口 = 眼位 + 实测偏移 = (101.038, 63.674, 50.142);camEye 反推:capPos = camEye + (0,0,−1)
        Vector3d eyeE = new Vector3d(100, 64, 50);
        Vector3d capPosE = new Vector3d(101.064, 63.674, 50.142);
        Quaternionf idCam = new Quaternionf();
        TpLightResolver.CaptureView capE = new TpLightResolver.CaptureView(
                new MuzzlePoseMath.Pose(0, 0, 1f, 0, 0, 1f, 0, 1, 0), ms(0), idCam,
                new Vector3d(101.064, 63.674, 51.142));
        TpLightResolver.Referent refE = new TpLightResolver.Referent(100, 64, 50, -90f, 0f, false, 7);
        Vector3d fbDir = new Vector3d(1, 0, 0);

        // ---- 无捕获:pos = 眼位 + 默认偏移随偏航旋转(出厂校准,不再退眼位+0.45·视线近似) ----
        TpLightResolver.Resolved r0 = TpLightResolver.resolve(null, null, refE, fbDir, 0f, ms(1), ms(16), true);
        check(r0.state().equals("fallback") && r0.weight() == 0f, "无捕获:fallback 权重 0");
        check(r0.pos().distance(new Vector3d(101.064, 63.674, 50.142)) < 1e-3,
                "无捕获:锚 = 眼位 + 默认偏移随偏航旋转(面东→前1.064/上−0.326/右0.142)");

        // ---- hold 跟随(本次修复核心):referent 位置北移 5 格,同姿态 → 灯随人平移,权重保持 1 ----
        TpLightResolver.Referent walked = new TpLightResolver.Referent(100, 64, 55, -90f, 0f, false, 7);
        TpLightResolver.Resolved rFollow = TpLightResolver.resolve(capE, refE, walked, fbDir, 1f, ms(400), ms(16), true);
        check(rFollow.state().equals("hold") && rFollow.weight() == 1f,
                "hold:位置变化不失效(旧版步行即破 hold=入场跳变根因)");
        check(rFollow.pos().distance(new Vector3d(101.064, 63.674, 55.142)) < 1e-6,
                "hold:锚 = 捕获位 + referent 位移(灯随人平移 5 格)");

        // ---- 旋转随动:referent 转身面南(90°,>2° 破 hold 入 blend)但偏移随实时偏航旋转仍跟随 ----
        TpLightResolver.Referent turned = new TpLightResolver.Referent(100, 64, 55, 0f, 0f, false, 7);
        TpLightResolver.Resolved rTurn = TpLightResolver.resolve(capE, refE, turned, fbDir, 1f, ms(400), ms(16), true);
        check(rTurn.state().equals("blend"), "转身 90°:>2° 破稳态入 blend");
        // 面东捕获 offLocal=(1.064,−0.326,0.142);面南:前=+z,右=−x → 世界 (−0.142,−0.326,+1.038)
        check(rTurn.pos().distance(new Vector3d(100 - 0.142, 64 - 0.326, 55 + 1.064)) < 1e-3,
                "blend:局部偏移随实时偏航旋转(转身灯随身体转向,不弃跟)");

        // ---- hard 模式:fresh 精确 / 过期即重构(二元,无爬升) ----
        TpLightResolver.Resolved rh = TpLightResolver.resolve(capE, refE, refE, fbDir, 0f, ms(1), ms(16), false);
        check(rh.state().equals("fresh") && rh.weight() == 1f, "hard:fresh→权重 1");
        check(rh.pos().distance(capPosE) < 1e-6, "hard:fresh 锚=捕获精确世界位(camEye+捕获时刻相机映射)");
        TpLightResolver.Resolved rh2 = TpLightResolver.resolve(capE, refE, refE, fbDir, 1f, ms(400), ms(16), false);
        check(rh2.state().equals("fallback") && rh2.weight() == 0f && rh2.pos().distance(capPosE) < 1e-6,
                "hard:过期→重构(ref==ref 时重构≡捕获位,二元无滑动)");

        // ---- blend 首捕爬升:referent 行进中,锚在 重构(随人)↔捕获精确位(冻结) 之间按权重插值 ----
        TpLightResolver.Referent drift = new TpLightResolver.Referent(100.05, 64, 50, -90f, 0f, false, 7);
        TpLightResolver.Resolved rb = TpLightResolver.resolve(capE, refE, drift, fbDir, 0f, ms(1), ms(50), true);
        check(rb.state().equals("fresh") && Math.abs(rb.weight() - 0.25f) < 1e-6,
                "首捕爬升:状态 fresh,入速率 200ms(50ms→0.25)");
        Vector3d reconDrift = new Vector3d(101.114, 63.674, 50.142);
        Vector3d expectMid = new Vector3d(reconDrift).lerp(capPosE, 0.25);
        check(rb.pos().distance(expectMid) < 1e-6, "爬升:锚 = 重构(随人)↔捕获精确位(冻结) 线性插值");

        // ---- 入场桥接小差:爬升首帧步进 ≤0.02 格(旧版实测 0.148=用户可见跳变) ----
        double firstStep = rb.pos().distance(reconDrift);
        check(firstStep <= 0.02, "入场首帧步进 ≤0.02 格(=0.25×摆动级差量) firstStep=" + firstStep);

        // ---- blend:referent 姿态微变(偏航 +5°)→ 权重衰减,锚=重构(仍跟随) ----
        TpLightResolver.Referent yawMoved = new TpLightResolver.Referent(100, 64, 50, -85f, 0f, false, 7);
        TpLightResolver.Resolved rdec = TpLightResolver.resolve(capE, refE, yawMoved, fbDir, 1f, ms(400), ms(40), true);
        check(rdec.state().equals("blend") && Math.abs(rdec.weight() - 0.9f) < 1e-6, "姿态变:出速率 400ms(40ms→0.9)");
        check(rdec.pos().distance(reconstructExpect(yawMoved)) < 1e-6, "blend:锚 = 重构(降级不弃跟,容差含龄期衰减)");

        // ---- 束向符号对齐(2026-09-03 实机钉死):束骨沿局部 ±Z 拉伸,捕获侧符号未定;
        // resolver 必须以"枪口−持灯者捕获眼位"为外向基准对齐,否则 fresh/hold 方向反 180°
        // (hold 帧 dYaw=179.98° 实测),且 dir=lerp(fallback,capDir) 过渡穿零向量 →
        // 归一化后单帧扫动 >100°(用户"入场突变"真凶)。 ----
        // fz=(−1,0,0) → Q·Ry180 映射后朝东(已向外)→ 原样保留
        TpLightResolver.CaptureView capFwd = new TpLightResolver.CaptureView(
                new MuzzlePoseMath.Pose(0, 0, 1f, -1, 0, 0, 0, 1, 0), ms(0), idCam,
                new Vector3d(101.064, 63.674, 51.142));
        TpLightResolver.Resolved rFwd = TpLightResolver.resolve(capFwd, refE, refE, fbDir, 1f, ms(400), ms(16), true);
        check(rFwd.state().equals("hold") && rFwd.dir().x() > 0.99,
                "束向已向外(朝东):hold 方向原样保留");
        // fz=(1,0,0) → 映射后朝西(反平行)→ 必须翻转到枪口前方
        TpLightResolver.CaptureView capRev = new TpLightResolver.CaptureView(
                new MuzzlePoseMath.Pose(0, 0, 1f, 1, 0, 0, 0, 1, 0), ms(0), idCam,
                new Vector3d(101.064, 63.674, 51.142));
        TpLightResolver.Resolved rRev = TpLightResolver.resolve(capRev, refE, refE, fbDir, 1f, ms(400), ms(16), true);
        check(rRev.dir().x() > 0.99, "束向反平行(朝西):对齐翻转到枪口前方(dot(束向,枪口−眼)≥0)");
        // 爬升中段:两向同向后 lerp 不穿零向量,fresh weight=0.25 时方向仍朝东(不扫动)
        TpLightResolver.Resolved rRevMid = TpLightResolver.resolve(capRev, refE, refE, fbDir, 0f, ms(1), ms(50), true);
        check(rRevMid.state().equals("fresh") && rRevMid.dir().x() > 0.99,
                "爬升中段:方向线性过渡不穿零(单帧扫动 ≤ 摆动级)");
        // 对齐基准 = capturedRef(捕获时刻眼位),非 liveRef:referent 远走后 hold 仍按捕获帧判定
        TpLightResolver.Referent farAway = new TpLightResolver.Referent(130, 64, 90, -90f, 0f, false, 7);
        TpLightResolver.Resolved rFar = TpLightResolver.resolve(capRev, refE, farAway, fbDir, 1f, ms(400), ms(16), true);
        check(rFar.dir().x() > 0.99, "对齐基准=捕获时刻 referent(非实时眼位),传送后 hold 方向不变");

        // ---- hold 硬上限:超 10s 强制降级(防漏判姿态变化) ----
        TpLightResolver.Resolved rmax = TpLightResolver.resolve(capE, refE, refE, fbDir, 1f,
                ms(0) + TpLightResolver.HOLD_MAX_NANOS + 1, ms(16), true);
        check(!rmax.state().equals("hold") && rmax.weight() < 1f, "hold 超 10s 硬上限失效");

        // ---- 映射用捕获时刻相机(非当前相机):rotY90 相机下 (0,0,1)→世界 (−1,0,0) ----
        // 捕获位=(9,64,10);捕获帧眼位=(10,64,10) 面东 → 自校准局部偏移=(前−1,右0) → 重构≡捕获位
        Quaternionf y90 = new Quaternionf().rotationY((float) (Math.PI / 2));
        TpLightResolver.CaptureView cap90 = new TpLightResolver.CaptureView(
                new MuzzlePoseMath.Pose(0, 0, 1f, 0, 0, 1f, 0, 1, 0), ms(0), y90, new Vector3d(10, 64, 10));
        TpLightResolver.Referent ref90 = new TpLightResolver.Referent(10, 64, 10, -90f, 0f, false, 7);
        TpLightResolver.Resolved r90 = TpLightResolver.resolve(cap90, ref90, ref90, fbDir, 1f, ms(1), ms(16), false);
        check(r90.pos().distance(new Vector3d(9, 64, 10)) < 1e-3,
                "映射绑定捕获时刻相机四元数(Q=Ry90·Ry180:v=(0,0,1)→(−1,0,0);容差含 1ms 龄期衰减)");

        // ---- 证据随龄衰减(坑78):捕获偏移随年龄线性衰减到默认先验,10s 归零 ----
        // 构造一个"非典型姿态"捕获:局部偏移 (前2.064,0,0) ≠ 默认 —— 模拟边缘/坠落姿投毒源
        TpLightResolver.CaptureView capAlt = new TpLightResolver.CaptureView(
                new MuzzlePoseMath.Pose(0, 0, 1f, 0, 0, 1f, 0, 1, 0), ms(0), idCam,
                new Vector3d(102.064, 63.674, 51.142)); // capPos=(102.064,63.674,50.142) → 偏移前向 2.064
        TpLightResolver.Resolved rFresh = TpLightResolver.resolve(capAlt, refE, refE, fbDir, 1f, ms(0), ms(16), true);
        check(rFresh.pos().distance(new Vector3d(102.064, 63.674, 50.142)) < 1e-3,
                "龄期 0:全额采用捕获偏移(新鲜证据压过先验)");
        TpLightResolver.Resolved rHalf = TpLightResolver.resolve(capAlt, refE, refE, fbDir, 1f,
                TpLightResolver.HOLD_MAX_NANOS / 2, ms(16), true);
        check(rHalf.pos().distance(new Vector3d(101.564, 63.674, 50.142)) < 1e-3,
                "龄期 5s:捕获偏移与默认先验各半(连续衰减,无跳变)");
        TpLightResolver.Resolved rStale = TpLightResolver.resolve(capAlt, refE, refE, fbDir, 1f,
                TpLightResolver.HOLD_MAX_NANOS + ms(1), ms(16), true);
        check(rStale.pos().distance(new Vector3d(101.064, 63.674, 50.142)) < 1e-3,
                "龄期 >10s:完全让位默认先验(陈旧捕获不再投毒)");

        // ---- referentStable:只判姿态;位置任意变化不失效(步行/跳跃/传送全由重构吸收) ----
        check(TpLightResolver.referentStable(refE, refE), "稳态:全同");
        check(TpLightResolver.referentStable(refE, new TpLightResolver.Referent(100.01, 64, 50, -90f, 0f, false, 7)),
                "稳态:位置 1cm 不失效");
        check(TpLightResolver.referentStable(refE, new TpLightResolver.Referent(103, 65, 60, -90f, 0f, false, 7)),
                "稳态:位置 3~14 格(步行+跳跃)不失效");
        check(TpLightResolver.referentStable(refE, new TpLightResolver.Referent(150, 80, 50, -90f, 0f, false, 7)),
                "稳态:传送 50 格不失效(重构瞬时跟随)");
        check(TpLightResolver.referentStable(refE, new TpLightResolver.Referent(100, 64, 50, -88f, 0f, false, 7)),
                "稳态:偏航 2° 容忍");
        check(!TpLightResolver.referentStable(refE, new TpLightResolver.Referent(100, 64, 50, -87f, 0f, false, 7)),
                "稳态:偏航 >2° 失效");
        check(TpLightResolver.referentStable(refE, new TpLightResolver.Referent(100, 64, 50, 268f, 0f, false, 7)),
                "稳态:偏航按 wrap 判定(268°≡−92°,差 2°)");
        check(TpLightResolver.referentStable(refE, new TpLightResolver.Referent(100, 64, 50, -90f, 1.5f, false, 7)),
                "稳态:俯仰 1.5° 容忍");
        check(!TpLightResolver.referentStable(refE, new TpLightResolver.Referent(100, 64, 50, -90f, 3f, false, 7)),
                "稳态:俯仰 >2° 失效");
        check(!TpLightResolver.referentStable(refE, new TpLightResolver.Referent(100, 64, 50, -90f, 0f, true, 7)),
                "稳态:瞄准态翻转失效");
        check(!TpLightResolver.referentStable(refE, new TpLightResolver.Referent(100, 64, 50, -90f, 0f, false, 8)),
                "稳态:手持物品变化失效");
        check(!TpLightResolver.referentStable(null, refE) && !TpLightResolver.referentStable(refE, null),
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

        System.out.println("TpLightResolverContract: ALL PASS (50 checks)");
    }

    /** blend 断言用的重构期望值(面东捕获的局部偏移 (1.038,−0.326,0.142) 随 live 偏航旋转)。 */
    private static Vector3d reconstructExpect(TpLightResolver.Referent live) {
        Vector3f f = TpLightResolver.yawForward(live.yaw());
        Vector3f r = TpLightResolver.yawRight(live.yaw());
        return new Vector3d(
                live.x() + f.x() * 1.064f + r.x() * 0.142f,
                live.y() - 0.326f,
                live.z() + f.z() * 1.064f + r.z() * 0.142f);
    }

    private static long ms(long v) {
        return v * 1_000_000L;
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

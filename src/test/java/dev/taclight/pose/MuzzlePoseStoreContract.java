package dev.taclight.pose;

import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * MuzzlePoseStore 契约(第三人称按实体 id 的捕获存储,纯 JVM):
 * put/get 回环、同 id 覆盖、不同 id 独立、maxAge 新鲜门(过期 get=null 但条目保留供 hold)、
 * peek 无龄期完整条目(捆绑捕获时刻相机)、存储为拷贝、prune 以 holdMax 为界。
 * 时间一律以 ms() 显式构造,避免纳秒/毫秒混写。
 * 2026-09-02 语义变更:过期不再移除(屏外 hold 需要跨龄读取),prune 界=holdMax;
 * 旧"过期即移除"语义是回退跳变链的一环,随 hold 机制一并废止。
 */
public class MuzzlePoseStoreContract {
    private static final long MAX_AGE = 300_000_000L;   // 300ms,与 MuzzlePoseCapture 同值
    private static final long HOLD_MAX = 10_000_000_000L; // 10s,与 TpLightResolver 同值

    public static void main(String[] args) {
        MuzzlePoseMath.Pose p1 = new MuzzlePoseMath.Pose(0.1f, 0.2f, 0.3f, 0f, 0f, -1f, 0f, 1f, 0f);
        MuzzlePoseMath.Pose p2 = new MuzzlePoseMath.Pose(1.1f, 1.2f, 1.3f, 0f, 0f, -1f, 0f, 1f, 0f);
        MuzzlePoseMath.Pose p3 = new MuzzlePoseMath.Pose(2f, 2f, 2f, 0f, 0f, -1f, 0f, 1f, 0f);
        Quaternionf q = new Quaternionf().rotationY(1.0f);
        Vector3d ce = new Vector3d(1, 2, 3);

        MuzzlePoseStore s = new MuzzlePoseStore(MAX_AGE, HOLD_MAX);
        s.put(7, p1, ms(0), q, ce, 90f, 5f);
        check(s.get(7, ms(0) + 1) == p1, "put/get 回环(同一实例)");
        MuzzlePoseStore.Entry e = s.peek(7);
        check(e != null && e.pose() == p1 && e.nanos() == ms(0), "peek:完整条目(无龄期)");
        check(e.camRot() != null && Math.abs(e.camRot().y - Math.sin(0.5f)) < 1e-6,
                "peek:捆绑捕获时刻相机四元数");
        check(e.camEye().distance(ce) < 1e-9 && e.camYaw() == 90f && e.camPitch() == 5f,
                "peek:捆绑相机眼位/偏航/俯仰");
        q.set(0, 0, 0, 1);
        ce.set(9, 9, 9);
        check(Math.abs(s.peek(7).camRot().y - Math.sin(0.5f)) < 1e-6 && s.peek(7).camEye().x == 1,
                "存储为拷贝,put 后改源不串");

        s.put(7, p2, ms(10), q, ce, 0f, 0f);
        check(s.get(7, ms(11)) == p2, "同 id 覆盖");
        s.put(9, p3, ms(10), q, ce, 0f, 0f);
        check(s.get(9, ms(11)) == p3 && s.get(7, ms(11)) == p2, "不同 id 独立");

        // 过期:get=null(新鲜门),条目保留(屏外 hold 数据源)
        check(s.get(7, ms(10) + MAX_AGE + 1) == null, "过期 get=null(新鲜门)");
        check(s.peek(7) != null && s.peek(7).pose() == p2, "过期条目保留,peek 可见");

        // prune 界=holdMax:介于 maxAge..holdMax 保留,超 holdMax 清除
        s.prune(ms(10) + HOLD_MAX);
        check(s.peek(7) != null, "prune 边界:恰 holdMax 保留");
        s.prune(ms(10) + HOLD_MAX + 1);
        check(s.peek(7) == null, "prune 超 holdMax 清除");

        // get 恰 maxAge 边界仍新鲜(严格 > 才过期)
        s.put(13, p1, ms(2000), q, ce, 0f, 0f);
        check(s.get(13, ms(2000) + MAX_AGE) == p1, "恰在 maxAge 边界仍有效");
        System.out.println("MuzzlePoseStoreContract: ALL PASS (12 checks)");
    }

    private static long ms(long v) {
        return v * 1_000_000L;
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

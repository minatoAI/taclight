package dev.taclight.pose;

/**
 * MuzzlePoseStore 契约(第三人称按实体 id 的捕获存储,纯 JVM):
 * put/get 回环、同 id 覆盖、不同 id 独立、过期返回 null 且移除、prune 清扫、maxAge 边界。
 * 时间一律以 ms() 显式构造,避免纳秒/毫秒混写。
 */
public class MuzzlePoseStoreContract {
    private static final long MAX_AGE = 300_000_000L; // 300ms,与 MuzzlePoseCapture 同值

    public static void main(String[] args) {
        MuzzlePoseMath.Pose p1 = new MuzzlePoseMath.Pose(0.1f, 0.2f, 0.3f, 0f, 0f, -1f, 0f, 1f, 0f);
        MuzzlePoseMath.Pose p2 = new MuzzlePoseMath.Pose(1.1f, 1.2f, 1.3f, 0f, 0f, -1f, 0f, 1f, 0f);
        MuzzlePoseMath.Pose p3 = new MuzzlePoseMath.Pose(2f, 2f, 2f, 0f, 0f, -1f, 0f, 1f, 0f);

        MuzzlePoseStore s = new MuzzlePoseStore(MAX_AGE);
        s.put(7, p1, ms(0));
        check(s.get(7, ms(0) + 1) == p1, "put/get 回环(同一实例)");
        check(s.size() == 1, "size=1");

        s.put(7, p2, ms(10));
        check(s.get(7, ms(11)) == p2, "同 id 覆盖");

        s.put(9, p3, ms(10));
        check(s.get(9, ms(11)) == p3 && s.get(7, ms(11)) == p2, "不同 id 独立");

        // 过期:超过 maxAge 返回 null 且条目被移除(惰性)
        check(s.get(7, ms(10) + MAX_AGE + 1) == null, "过期返回 null");
        check(s.size() == 1, "过期条目被移除");

        // prune 主动清扫:9(10ms 龄 1290ms>300ms)、11(1000ms 龄 300ms+1>300ms)清除,12 保留
        s.put(11, p1, ms(1000));
        s.put(12, p1, ms(1200));
        s.prune(ms(1300) + 1);
        check(s.size() == 1 && s.get(12, ms(1301)) != null, "prune 只留新鲜条目");

        // 边界:恰好 maxAge 仍有效(严格 > 才过期)
        s.put(13, p1, ms(2000));
        check(s.get(13, ms(2000) + MAX_AGE) == p1, "恰在 maxAge 边界仍有效");
        System.out.println("MuzzlePoseStoreContract: ALL PASS (9 checks)");
    }

    private static long ms(long v) {
        return v * 1_000_000L;
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

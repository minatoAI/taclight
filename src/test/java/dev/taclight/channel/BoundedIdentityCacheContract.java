package dev.taclight.channel;

/**
 * {@link BoundedIdentityCache} 有界性契约(2026-09-18,Lead 复核项 ①)。
 *
 * <p>背景:{@code VoxelGrid.SHAPE_CACHE} 以 {@code VoxelShape} 实例为键;
 * fence/wall 这类位置相关方块 {@code getOcclusionShape} 每次都返回新实例 ⇒
 * 无界身份键缓存 = 随帧数持续增长的内存泄漏。本契约断言"上限 + 满则清空"确实成立,
 * 并钉住身份(而非 equals)语义、容量参数校验、以及值类型泛化(承载"双身份校验"的值)。</p>
 */
public class BoundedIdentityCacheContract {
    private static int checks;

    public static void main(String[] args) {
        // ---- 1. 容量参数校验 ----
        try {
            new BoundedIdentityCache<String, Integer>(0);
            throw new AssertionError("FAIL cap=0 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            check(true, "cap<1 构造被拒绝(IllegalArgumentException)");
        }

        // ---- 2. 基本读写 + 未命中语义 ----
        BoundedIdentityCache<String, Integer> c = new BoundedIdentityCache<>(8);
        check(c.cap() == 8 && c.size() == 0, "新缓存 cap=8 / size=0");
        check(c.get("missing") == null, "未命中返回 null(调用方据此重算,缓存不承载语义)");
        c.put("a", 1);
        c.put("b", 2);
        check(c.size() == 2 && c.get("a") == 1 && c.get("b") == 2, "写入后可取回(size=2)");

        // ---- 3. 有界性(核心断言):任意次写入后 size 永不超过 cap ----
        BoundedIdentityCache<Object, Integer> b = new BoundedIdentityCache<>(8);
        boolean bounded = true;
        int maxSeen = 0;
        for (int i = 0; i < 1000; i++) {
            b.put(new Object(), i);
            maxSeen = Math.max(maxSeen, b.size());
            if (b.size() > b.cap()) bounded = false;
        }
        check(bounded, "1000 次写入(每次新实例)后 size() ≤ cap(实际峰值 " + maxSeen + ", cap=" + b.cap() + ")");
        check(b.size() <= 8, "终态 size=" + b.size() + " ≤ 8");
        check(maxSeen <= 8, "峰值 size=" + maxSeen + " ≤ 8(不存在「写入瞬间越界」的窗口)");

        // ---- 4. 满则清空 + 最近写入可取回(清空不丢正确性:未命中即重算) ----
        BoundedIdentityCache<Object, Integer> e = new BoundedIdentityCache<>(4);
        Object last = null;
        for (int i = 0; i < 100; i++) {
            last = new Object();
            e.put(last, i);
        }
        check(e.get(last) == Integer.valueOf(99), "触发清空后,最后一次写入仍可取回(值 = 99)");
        check(e.size() >= 1 && e.size() <= e.cap(),
                "终态 size=" + e.size() + " ∈ [1, cap=" + e.cap() + "](清空只在写满时发生,故终态非空且有界)");

        // ---- 5. 身份键语义(VoxelShape 不覆写 equals/hashCode ⇒ 身份即语义) ----
        BoundedIdentityCache<String, Integer> idc = new BoundedIdentityCache<>(8);
        String k1 = new String("same");
        String k2 = new String("same");
        idc.put(k1, 7);
        check(idc.get(k1) == 7, "同一实例命中");
        check(idc.get(k2) == null && k1.equals(k2),
                "equals 相等但不同实例 = 未命中(身份键;这正是 SHAPE_CACHE 想要的语义:同实例必同码)");
        check(idc.size() == 1, "第二个实例未写入 ⇒ size 仍为 1");

        // ---- 6. 值类型泛化(SHAPE_CACHE 存 ShapeCode(coll 实例 + 码)) ----
        BoundedIdentityCache<String, Object> v = new BoundedIdentityCache<>(2);
        Object payload = new int[]{1, 2};
        v.put("occ", payload);
        check(v.get("occ") == payload, "值可为任意对象(引用同一实例,供双身份校验)");

        // ---- 7. clear 语义 ----
        idc.clear();
        check(idc.size() == 0, "clear() 后 size=0");

        System.out.println("BoundedIdentityCacheContract: ALL PASS (" + checks + " checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

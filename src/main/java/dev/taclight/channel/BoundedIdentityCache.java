package dev.taclight.channel;

import java.util.IdentityHashMap;

/**
 * 有界"身份键"缓存(2026-09-18 雪地方格阵列根因轮的缓存护栏,Lead 复核项 ①)。
 *
 * <p>为什么需要它:{@code VoxelGrid.SHAPE_CACHE} 以 {@code VoxelShape} <b>实例</b>为键
 * (码是形状的纯函数、位置相关性已被 shape 编码 ⇒ 同一实例必然同码,不重演根因③)。
 * 但位置相关方块(栅栏/墙的 {@code Shapes.or})每次解析都产生<b>新实例</b> ⇒
 * 身份键缓存会随帧数无限增长 = 内存泄漏。故这里给出容量上限 + 满则整体清空的兜底
 * (与 {@code VoxelGrid.CLASS_CACHE} 既有 4096 策略同款)。</p>
 *
 * <p>语义(<b>有界性是可断言的硬性质</b>):任意次 {@code put} 之后
 * {@code size() <= cap()};容量已满时先 {@link #clear()} 再写入。不区分冷热(无 LRU):
 * 触发清空后命中率短期下降,但结果永远正确(缓存只是加速,不承载语义)。</p>
 *
 * <p>值类型泛化以便承载"双身份校验"的值(见 {@code VoxelGrid} 的 {@code ShapeCode}:
 * 主键 = 遮挡形实例,值里再存碰撞形实例做一致性校验)。纯 JVM(零 MC 依赖),
 * 有界性由 {@code BoundedIdentityCacheContract} 断言。</p>
 */
public final class BoundedIdentityCache<K, V> {
    private final IdentityHashMap<K, V> map = new IdentityHashMap<>();
    private final int cap;

    public BoundedIdentityCache(int cap) {
        if (cap < 1) throw new IllegalArgumentException("cap 必须 >= 1,实际 " + cap);
        this.cap = cap;
    }

    /** 未命中返回 null(与 IdentityHashMap 一致;缓存不承载语义,调用方必须能重算)。 */
    public V get(K key) {
        return map.get(key);
    }

    /** 写入;容量已满则先整体清空(有界性保证:返回后 {@code size() <= cap()})。 */
    public void put(K key, V value) {
        if (map.size() >= cap) map.clear();
        map.put(key, value);
    }

    public int size() {
        return map.size();
    }

    public int cap() {
        return cap;
    }

    public void clear() {
        map.clear();
    }
}

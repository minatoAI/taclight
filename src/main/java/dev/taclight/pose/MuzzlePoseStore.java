package dev.taclight.pose;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 第三人称枪口捕获存储(2026-09-02 配件适配里程碑②):按实体 id 保存最近一次
 * 捕获姿态 + 时间戳。渲染线程写(实体渲染时)、同线程读(上传钩子),无并发。
 * 纯 JVM 类(无 MC 类型),离线契约可测。
 */
public final class MuzzlePoseStore {
    /** 单条捕获:姿态 + 采集时刻(纳秒)。 */
    public record Entry(MuzzlePoseMath.Pose pose, long nanos) {}

    private final long maxAgeNanos;
    private final Map<Integer, Entry> byEntity = new HashMap<>();

    public MuzzlePoseStore(long maxAgeNanos) {
        this.maxAgeNanos = maxAgeNanos;
    }

    /** 写入/覆盖;顺带惰性清理过期条目。 */
    public void put(int entityId, MuzzlePoseMath.Pose pose, long nowNanos) {
        prune(nowNanos);
        byEntity.put(entityId, new Entry(pose, nowNanos));
    }

    /** 过期即 null(调用方回退眼位近似),并移除过期条目。 */
    public MuzzlePoseMath.Pose get(int entityId, long nowNanos) {
        Entry e = byEntity.get(entityId);
        if (e == null) {
            return null;
        }
        if (nowNanos - e.nanos() > maxAgeNanos) {
            byEntity.remove(entityId);
            return null;
        }
        return e.pose();
    }

    public void prune(long nowNanos) {
        Iterator<Map.Entry<Integer, Entry>> it = byEntity.entrySet().iterator();
        while (it.hasNext()) {
            if (nowNanos - it.next().getValue().nanos() > maxAgeNanos) {
                it.remove();
            }
        }
    }

    public int size() {
        return byEntity.size();
    }
}

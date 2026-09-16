package dev.taclight.pose;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * 第三人称枪口捕获存储(2026-09-02 配件适配里程碑②):按实体 id 保存最近一次
 * 捕获姿态 + 时间戳 + <b>捕获时刻相机</b>(视空间姿态必须与采集帧相机成对存储,
 * 上传侧用该相机映射 → 世界锚在捕获帧固定;跨帧用"当前相机"映射会把相机旋转
 * 漏进世界方向,坑68 家族教训)。
 * 渲染线程写(实体渲染时)、同线程读(上传钩子),无并发。纯 JVM 类,离线契约可测。
 * 2026-09-02 语义变更:过期不再移除(get 只做新鲜门,条目保留供屏外 hold 跨龄读取;
 * prune 界 = holdMax 而非 maxAge)——旧"过期即移除"是回退跳变链的一环。
 */
public final class MuzzlePoseStore {
    /** 单条捕获:姿态 + 采集时刻(纳秒)+ 捕获时刻相机(yaw/pitch 供打桩,四元数供映射)。 */
    public record Entry(MuzzlePoseMath.Pose pose, long nanos, Quaternionf camRot,
                        Vector3d camEye, float camYaw, float camPitch) {
        public long ageNanos(long nowNanos) {
            return nowNanos - nanos;
        }
    }

    private final long maxAgeNanos;
    private final long holdMaxNanos;
    private final Map<Integer, Entry> byEntity = new HashMap<>();

    public MuzzlePoseStore(long maxAgeNanos, long holdMaxNanos) {
        this.maxAgeNanos = maxAgeNanos;
        this.holdMaxNanos = holdMaxNanos;
    }

    /** 写入/覆盖(相机捆绑深拷贝);顺带惰性清理超 holdMax 条目。 */
    public void put(int entityId, MuzzlePoseMath.Pose pose, long nowNanos,
                    Quaternionf camRot, Vector3d camEye, float camYaw, float camPitch) {
        prune(nowNanos);
        byEntity.put(entityId, new Entry(pose, nowNanos,
                camRot == null ? new Quaternionf() : new Quaternionf(camRot),
                camEye == null ? new Vector3d() : new Vector3d(camEye),
                camYaw, camPitch));
    }

    /** 新鲜门:超 maxAge 返回 null(条目保留);调用方回退/hold 自行决策。 */
    public MuzzlePoseMath.Pose get(int entityId, long nowNanos) {
        Entry e = byEntity.get(entityId);
        if (e == null || nowNanos - e.nanos() > maxAgeNanos) {
            return null;
        }
        return e.pose();
    }

    /** 无龄期完整条目(hold 与打桩数据源;可能早已过期,新鲜判定由调用方做)。 */
    public Entry peek(int entityId) {
        return byEntity.get(entityId);
    }

    /** 以 holdMax 为界的惰性清扫(捕获过期 ≠ 条目可删,故界取 holdMax)。 */
    public void prune(long nowNanos) {
        Iterator<Map.Entry<Integer, Entry>> it = byEntity.entrySet().iterator();
        while (it.hasNext()) {
            if (nowNanos - it.next().getValue().nanos() > holdMaxNanos) {
                it.remove();
            }
        }
    }

    public int size() {
        return byEntity.size();
    }
}

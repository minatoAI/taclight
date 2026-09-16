package dev.taclight.client;

import dev.taclight.pose.MuzzlePoseMath;
import dev.taclight.pose.MuzzlePoseStore;
import dev.taclight.pose.TpLightResolver;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * 客户端枪口姿态捕获(由 BeamRendererMixin 驱动,渲染线程写入)。
 * 两条链(2026-09-02 里程碑②):
 * - 第一人称:单槽,矩阵=手部渲染空间(坑60 标定),服务本地枪灯;
 * - 第三人称:按实体 id 的 MuzzlePoseStore,矩阵=level 渲染相机空间(含实体平移),
 *   且与捕获时刻相机成对存储(上传侧用该相机映射,世界锚在捕获帧固定)。
 * 新鲜门 300ms;屏外 hold/连续混合解析见 TpLightResolver(过期不再销毁条目)。
 */
public final class MuzzlePoseCapture {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MuzzlePoseCapture.class);
    private static final long MAX_AGE_NANOS = 300_000_000L; // 300ms 内有效

    private static volatile boolean hasCapture;
    private static volatile MuzzlePoseMath.Pose last;
    private static volatile long lastNanos;
    private static boolean marked;
    private static boolean tpMarked;
    private static final MuzzlePoseStore TP_STORE =
            new MuzzlePoseStore(MAX_AGE_NANOS, TpLightResolver.HOLD_MAX_NANOS);

    private MuzzlePoseCapture() {}

    /** mixin 调用(渲染线程):ours 且节点为激光骨时,从矩阵抽取姿态。
     *  firstPerson=true 存 FP 单槽(entityId 忽略);否则存按实体 id 的 TP 槽。 */
    public static void capture(boolean ours, String nodeName, Matrix4f matrix,
                               boolean firstPerson, int entityId) {
        if (!ours || matrix == null) {
            return;
        }
        if (!MuzzlePoseMath.supportedNodeName(nodeName)) {
            return;
        }
        float[] m = new float[16];
        matrix.get(m);
        var r = MuzzlePoseMath.derive(m);
        if (!r.valid()) {
            return;
        }
        long now = System.nanoTime();
        if (firstPerson) {
            hasCapture = true;
            last = r.pose();
            lastNanos = now;
            if (!marked) {
                marked = true;
                LOGGER.info("[TacLight] muzzle capture armed (fp)");
            }
        } else {
            storeTp(entityId, r.pose(), now, null, null, 0f, 0f);
        }
    }

    /**
     * TP 直接捕获(2026-09-02 标定):origin = 束骨遍历后矩阵平移(束起点=枪口,
     * 视空间),dir = 该矩阵 +Z 列归一(束拉伸轴)。节点/有效性校验同矩阵路径;
     * 仅服务 TP 槽。camRot/camEye/camYaw/camPitch = 捕获时刻相机(映射基准捆绑)。
     * 空间→世界换算见 MuzzlePoseMath.muzzleViewDirToWorldTP + TpLightResolver。
     */
    public static void captureTp(boolean ours, String nodeName,
                                 float ox, float oy, float oz,
                                 float fx, float fy, float fz, int entityId,
                                 org.joml.Quaternionf camRot, org.joml.Vector3d camEye,
                                 float camYaw, float camPitch) {
        if (!ours) {
            return;
        }
        if (!MuzzlePoseMath.supportedNodeName(nodeName)) {
            return;
        }
        if (!Float.isFinite(ox) || !Float.isFinite(oy) || !Float.isFinite(oz)) {
            return;
        }
        storeTp(entityId, new MuzzlePoseMath.Pose(ox, oy, oz, fx, fy, fz, 0, 1, 0), System.nanoTime(),
                camRot, camEye, camYaw, camPitch);
    }

    private static void storeTp(int entityId, MuzzlePoseMath.Pose pose, long now,
                                org.joml.Quaternionf camRot, org.joml.Vector3d camEye,
                                float camYaw, float camPitch) {
        TP_STORE.put(entityId, pose, now, camRot, camEye, camYaw, camPitch);
        if (!tpMarked) {
            tpMarked = true;
            LOGGER.info("[TacLight] muzzle capture armed (tp, entity={})", entityId);
        }
    }

    /** 最近 maxAgeNanos 内是否有第一人称捕获;过期返回 null(回退相机近似)。 */
    public static MuzzlePoseMath.Pose consumeFresh() {
        if (!hasCapture) {
            return null;
        }
        if (System.nanoTime() - lastNanos > MAX_AGE_NANOS) {
            return null;
        }
        return last;
    }

    /** 第三人称:无龄期完整条目(捕获姿态+捕获时刻相机);新鲜/hold 决策在 TpLightResolver。 */
    public static MuzzlePoseStore.Entry peekThirdPerson(int entityId) {
        return TP_STORE.peek(entityId);
    }

    /** 诊断:TP 存储当前条目数。 */
    public static int tpCapturedCount() {
        return TP_STORE.size();
    }
}

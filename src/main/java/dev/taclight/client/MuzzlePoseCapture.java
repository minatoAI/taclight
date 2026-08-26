package dev.taclight.client;

import dev.taclight.pose.MuzzlePoseMath;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * 客户端枪口姿态捕获(由 BeamRendererMixin 驱动,渲染线程写入)。
 * 只存"视图空间"姿态 + 捕获时间戳(纳秒);上传器按需取用并换算场景坐标。
 */
public final class MuzzlePoseCapture {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MuzzlePoseCapture.class);
    private static final long MAX_AGE_NANOS = 300_000_000L; // 300ms 内有效

    private static volatile boolean hasCapture;
    private static volatile MuzzlePoseMath.Pose last;
    private static volatile long lastNanos;
    private static boolean marked;

    private MuzzlePoseCapture() {}

    /** mixin 调用(渲染线程):ours 且节点为激光骨时,从矩阵抽取姿态。 */
    public static void capture(boolean ours, String nodeName, Matrix4f matrix, boolean firstPerson) {
        if (!ours || !firstPerson || matrix == null) {
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
        hasCapture = true;
        last = r.pose();
        lastNanos = System.nanoTime();
        if (!marked) {
            marked = true;
            LOGGER.info("[TacLight] muzzle capture armed");
        }
    }

    /** 最近 maxAgeNanos 内是否有捕获;过期返回 null(回退相机近似)。 */
    public static MuzzlePoseMath.Pose consumeFresh() {
        if (!hasCapture) {
            return null;
        }
        if (System.nanoTime() - lastNanos > MAX_AGE_NANOS) {
            return null;
        }
        return last;
    }
}

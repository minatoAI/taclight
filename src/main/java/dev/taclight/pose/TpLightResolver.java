package dev.taclight.pose;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * TP 枪灯解析状态机(2026-09-02 屏外连续性,用户报告"光源离开屏幕空间时跳变"):
 * 把"出视锥 300ms 后整体回退眼位近似"的硬跳变改为三级连续策略 ——
 * <ul>
 *   <li><b>fresh</b>(≤300ms,枪模在渲染):捕获精确;</li>
 *   <li><b>hold</b>(枪模屏外但持灯者 referent 未动:位置/偏航/俯仰/瞄准/手持全不变):
 *       沿用捕获世界位 —— 映射绑定<b>捕获时刻相机</b>,锚在捕获帧即已固定,观察者
 *       随便转视角/走动都不破坏(参考系稳定即缓存有效);</li>
 *   <li><b>blend</b>(referent 变化):权重按出 400ms/入 200ms slew 限速,锚连续滑向
 *       眼位+0.45·视线近似;再捕获对称爬回 —— 无跳变,只有有界速率的滑动。</li>
 * </ul>
 * hard 模式=旧二元回退(无 hold 无混合),供 A/B 对照与跳变定量(!tpfb hard)。
 * 纯 JVM(joml),离线契约 TpLightResolverContract 34 项钉死。
 */
public final class TpLightResolver {
    public static final long FRESH_NANOS = 300_000_000L;
    public static final long HOLD_MAX_NANOS = 10_000_000_000L;
    public static final float HOLD_POS_EPS = 0.05f;
    public static final float HOLD_YAW_EPS = 2.0f;
    public static final float HOLD_PITCH_EPS = 2.0f;
    public static final long BLEND_OUT_NANOS = 400_000_000L;
    public static final long BLEND_IN_NANOS = 200_000_000L;
    public static final long MAX_STEP_NANOS = 100_000_000L;

    /** 持灯者活体 referent(稳态判定输入);itemHash 用 Item 身份,跨帧稳定。 */
    public record Referent(double x, double y, double z, float yaw, float pitch, boolean aiming, int itemHash) {}

    /** 捕获快照:视空间姿态 + 采集时刻 + 捕获时刻相机(映射基准,缺一不可)。 */
    public record CaptureView(MuzzlePoseMath.Pose pose, long captureNanos, Quaternionf camRot, Vector3d camEye) {}

    public record Resolved(Vector3d pos, Vector3d dir, String state, float weight) {}

    private TpLightResolver() {}

    /** 稳态判定:活体 referent 与捕获快照逐项比较;任一超差即失效。空 referent 不稳。 */
    public static boolean referentStable(Referent captured, Referent live) {
        if (captured == null || live == null) {
            return false;
        }
        double dx = live.x - captured.x, dy = live.y - captured.y, dz = live.z - captured.z;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > HOLD_POS_EPS) {
            return false;
        }
        if (Math.abs(wrapDegrees(live.yaw - captured.yaw)) > HOLD_YAW_EPS) {
            return false;
        }
        if (Math.abs(live.pitch - captured.pitch) > HOLD_PITCH_EPS) {
            return false;
        }
        return live.aiming == captured.aiming && live.itemHash == captured.itemHash;
    }

    /** 权重 slew 限速:入 200ms/出 400ms;dt 钳 100ms(帧率无关,卡顿不越级)。 */
    public static float advanceWeight(float prev, boolean valid, long dtNanos) {
        long dt = Math.max(0L, Math.min(dtNanos, MAX_STEP_NANOS));
        if (valid) {
            return Math.min(1f, prev + dt / (float) BLEND_IN_NANOS);
        }
        return Math.max(0f, prev - dt / (float) BLEND_OUT_NANOS);
    }

    /**
     * 主解析。hard 模式:valid=fresh,权重二值(旧跳变语义)。
     * 映射恒用 cap.camRot/camEye(捕获时刻相机)—— 跨帧用"当前相机"映射旧视空间姿态
     * 会把相机旋转漏进世界方向(坑68 家族教训,含 fresh 窗口内的快扫掠)。
     */
    public static Resolved resolve(CaptureView cap, Referent capturedRef, Referent liveRef,
                                   Vector3d fallbackPos, Vector3d fallbackDir,
                                   float prevWeight, long nowNanos, long dtNanos, boolean blendMode) {
        if (cap == null) {
            return new Resolved(new Vector3d(fallbackPos), normalize(fallbackDir), "fallback", 0f);
        }
        long age = nowNanos - cap.captureNanos();
        boolean fresh = age >= 0 && age <= FRESH_NANOS;
        boolean holdable = !fresh && blendMode
                && age <= HOLD_MAX_NANOS && referentStable(capturedRef, liveRef);
        boolean valid = fresh || holdable;
        float weight = blendMode ? advanceWeight(prevWeight, valid, dtNanos) : (valid ? 1f : 0f);
        Vector3f off = MuzzlePoseMath.muzzleViewDirToWorldTP(
                cap.pose().ox(), cap.pose().oy(), cap.pose().oz(), cap.camRot());
        Vector3f fwd = MuzzlePoseMath.muzzleViewDirToWorldTP(
                cap.pose().fx(), cap.pose().fy(), cap.pose().fz(), cap.camRot());
        Vector3d capPos = new Vector3d(cap.camEye()).add(off.x(), off.y(), off.z());
        Vector3d capDir = normalize(new Vector3d(fwd.x(), fwd.y(), fwd.z()));
        String state = fresh ? "fresh" : holdable ? "hold" : weight > 0f ? "blend" : "fallback";
        Vector3d pos = new Vector3d(fallbackPos).lerp(capPos, weight);
        Vector3d dir = normalize(new Vector3d(fallbackDir).lerp(capDir, weight));
        return new Resolved(pos, dir, state, weight);
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    private static Vector3d normalize(Vector3d v) {
        double len = v.length();
        return len > 1e-9 ? v.mul(1.0 / len) : new Vector3d(0, 1, 0);
    }
}

package dev.taclight.pose;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * TP 枪灯解析状态机(2026-09-02 两轮实机报告闭环:屏外跳变 + 步行入场卡顿跳变)。
 *
 * <p>核心语义 = <b>局部偏移重构跟随</b>:捕获给出"枪口相对持灯者眼位的局部偏移
 * (前/上/右)",这个偏移在姿态(偏航/俯仰/瞄准/手持)不变时是常量;把它绕实时偏航
 * 旋转回世界系、加到实时眼位上,灯就在任何平移(步行/坠落/传送)下精确跟随持灯者,
 * 与是否在视锥内无关。三个状态只描述数据可信度:
 * <ul>
 *   <li><b>fresh</b>(≤300ms,枪模在渲染):锚=捕获精确世界位(渲染姿态,含步行摆动);
 *       权重爬升桥接 重构↔精确 的摆动级小差(入 200ms,首帧步进 ≤0.02 格,不可感);</li>
 *   <li><b>hold</b>(枪模屏外,姿态 referent 未变):锚=重构(跟随);</li>
 *   <li><b>blend/fallback</b>(姿态变了或从无捕获):锚=重构(跟随),可信度降级仅体现在
 *       偏移可能过期(如瞄准切换 ~0.4 格),再捕获时由 fresh 桥接拉回。</li>
 * </ul>
 *
 * <p>与旧版(世界钉死+眼位0.45近似)的本质差异:旧版把"位置变化"当失效(步行即破
 * hold→滑落 fallback→入场 0.75 格差量分帧兑现=用户看到的卡顿跳变);新版位置由重构
 * 吸收,只有<b>姿态</b>变化才降级。映射恒用捕获时刻相机(坑68 家族教训)。
 * hard 模式(!tpfb hard)= fresh/重构 二元切换(无爬升桥接),供 A/B 对照。
 *
 * <p>纯 JVM(joml),离线契约 TpLightResolverContract 钉死;默认偏移为双会话 307 帧
 * 实测(残差上限 0.176 格);捕获自校准偏移随捕获年龄线性衰减到默认先验(坑78:
 * 陈旧姿态投毒防线,10s 归零)。
 */
public final class TpLightResolver {
    public static final long FRESH_NANOS = 300_000_000L;
    public static final long HOLD_MAX_NANOS = 10_000_000_000L;
    public static final float HOLD_YAW_EPS = 2.0f;
    public static final float HOLD_PITCH_EPS = 2.0f;
    public static final long BLEND_OUT_NANOS = 400_000_000L;
    public static final long BLEND_IN_NANOS = 200_000_000L;
    public static final long MAX_STEP_NANOS = 100_000_000L;

    /**
     * 出厂默认枪口局部偏移(fwd/up/right,格)。2026-09-02 实机标定:两场独立步行会话
     * (s0012 旧构建 n=149 + s0001 新构建 n=158,共 307 帧腰射 fresh)合并均值,
     * 残差上限 0.176 格(步行摆动级)。仅在"从无捕获"(capturedRef==null)时使用;
     * 有捕获即自校准覆盖。
     */
    public static final Vector3f DEFAULT_OFF_LOCAL = new Vector3f(1.064f, -0.326f, 0.142f);

    /**
     * 持灯者活体 referent(x/y/z=<b>眼位</b>(psnap 平滑基),yaw/pitch=bsnap 平滑角;
     * 位置只作重构锚,不作稳态门禁)。itemHash 用 Item 身份,跨帧稳定。
     */
    public record Referent(double x, double y, double z, float yaw, float pitch, boolean aiming, int itemHash) {}

    /** 捕获快照:视空间姿态 + 采集时刻 + 捕获时刻相机(映射基准,缺一不可)。 */
    public record CaptureView(MuzzlePoseMath.Pose pose, long captureNanos, Quaternionf camRot, Vector3d camEye) {}

    public record Resolved(Vector3d pos, Vector3d dir, String state, float weight) {}

    private TpLightResolver() {}

    /**
     * 稳态判定:<b>只判姿态</b>(偏航/俯仰/瞄准/手持)——位置变化由重构跟随吸收,
     * 步行/坠落/传送都不再破坏 hold(2026-09-02 用户报告"步行入场跳变"的根因之一)。
     * 空 referent 不稳(从未 fresh 过不 hold)。
     */
    public static boolean referentStable(Referent captured, Referent live) {
        if (captured == null || live == null) {
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
     * 主解析。锚点语义:fresh=捕获精确世界位,其余=局部偏移重构(跟随);
     * pos = lerp(重构, 锚, weight) —— fresh 爬升只桥接摆动级差量,其余状态恒在重构上。
     * 映射恒用 cap.camRot/camEye(捕获时刻相机)—— 跨帧用"当前相机"映射旧视空间姿态
     * 会把相机旋转漏进世界方向(坑68 家族教训,含 fresh 窗口内的快扫掠)。
     */
    public static Resolved resolve(CaptureView cap, Referent capturedRef, Referent liveRef,
                                   Vector3d fallbackDir,
                                   float prevWeight, long nowNanos, long dtNanos, boolean blendMode) {
        long age = cap == null ? Long.MAX_VALUE : Math.max(0L, nowNanos - cap.captureNanos());
        Vector3d recon = reconstruct(liveRef, capturedRef, cap, age);
        if (cap == null) {
            return new Resolved(recon, normalize(fallbackDir), "fallback", 0f);
        }
        boolean fresh = age <= FRESH_NANOS;
        boolean holdable = !fresh && blendMode
                && age <= HOLD_MAX_NANOS && referentStable(capturedRef, liveRef);
        boolean valid = fresh || holdable;
        float weight = blendMode ? advanceWeight(prevWeight, valid, dtNanos) : (valid ? 1f : 0f);
        Vector3d anchor = fresh ? capPosExact(cap) : recon;
        String state = fresh ? "fresh" : holdable ? "hold" : weight > 0f ? "blend" : "fallback";
        Vector3d pos = new Vector3d(recon).lerp(anchor, weight);
        Vector3f fwd = MuzzlePoseMath.muzzleViewDirToWorldTP(
                cap.pose().fx(), cap.pose().fy(), cap.pose().fz(), cap.camRot());
        Vector3d capDir = normalize(new Vector3d(fwd.x(), fwd.y(), fwd.z()));
        Vector3d dir = normalize(new Vector3d(fallbackDir).lerp(capDir, weight));
        return new Resolved(pos, dir, state, weight);
    }

    /**
     * 局部偏移重构:有捕获用捕获自校准偏移(相对 capturedRef 眼位,随 liveRef 偏航旋转),
     * 否则用出厂默认(腰射标定)。偏移的俯仰不随视线旋转(腰射持枪大致保持水平)。
     * <b>证据随龄衰减(2026-09-02 坑78)</b>:捕获偏移反映的是"捕获那一刻的姿态",
     * 持灯者此后可能已变换姿态(传送/重生/坠落/边缘站姿)——陈旧捕获会投毒重构
     * (实机:边缘姿态偏移 (1.5,−0.5,−1.0) 污染整段接近,入场 1.21 格跳变)。
     * 故捕获偏移随年龄线性衰减到出厂先验,10s(HOLD_MAX)归零:新鲜证据压过先验,
     * 陈旧证据让位先验。
     */
    private static Vector3d reconstruct(Referent live, Referent captured, CaptureView cap, long ageNanos) {
        Vector3f off = new Vector3f(DEFAULT_OFF_LOCAL);
        if (live == null) {
            live = captured;
        }
        if (live == null) {
            return new Vector3d();
        }
        if (cap != null && captured != null) {
            Vector3d capPos = capPosExact(cap);
            Vector3d worldOff = new Vector3d(capPos.x - captured.x, capPos.y - captured.y, capPos.z - captured.z);
            Vector3f capturedOff = worldToLocal(worldOff, captured.yaw);
            float t = Math.min(1f, ageNanos / (float) HOLD_MAX_NANOS);
            off = capturedOff.lerp(off, t);
        }
        Vector3f f = yawForward(live.yaw);
        Vector3f r = yawRight(live.yaw);
        return new Vector3d(
                live.x + f.x() * off.x() + r.x() * off.z(),
                live.y + off.y(),
                live.z + f.z() * off.x() + r.z() * off.z());
    }

    /** 捕获精确世界位 = 捕获时刻相机眼位 + 捕获时刻相机映射的视空间枪口偏移。 */
    private static Vector3d capPosExact(CaptureView cap) {
        Vector3f off = MuzzlePoseMath.muzzleViewDirToWorldTP(
                cap.pose().ox(), cap.pose().oy(), cap.pose().oz(), cap.camRot());
        return new Vector3d(cap.camEye()).add(off.x(), off.y(), off.z());
    }

    /** MC 偏航约定:yaw 0=+z(南),90=−x(西),±180=−z(北),270(≡−90)=+x(东)。 */
    public static Vector3f yawForward(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vector3f((float) -Math.sin(r), 0f, (float) Math.cos(r));
    }

    /** 玩家右手方向(前方绕 Y 顺时针 −90°):yaw 0(面南)→ 右 = −x(西)。 */
    public static Vector3f yawRight(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vector3f((float) -Math.cos(r), 0f, (float) -Math.sin(r));
    }

    /** 世界偏移 → 局部(前/上/右);局部 off.x=前,off.y=上,off.z=右。 */
    public static Vector3f worldToLocal(Vector3d worldOff, float yaw) {
        Vector3f f = yawForward(yaw);
        Vector3f r = yawRight(yaw);
        return new Vector3f(
                (float) (worldOff.x * f.x() + worldOff.z * f.z()),
                (float) worldOff.y,
                (float) (worldOff.x * r.x() + worldOff.z * r.z()));
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

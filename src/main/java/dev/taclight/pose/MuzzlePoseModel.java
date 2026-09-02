package dev.taclight.pose;

import org.joml.Vector3d;

/**
 * CPU 侧枪口姿态模型 v1 = 方向族(2026-09-03 方案B,用户批准;纯 JVM)。
 *
 * <p>解决两个实机现象的主体:①屏外 fallback 方向原为头部视线,而真枪口随姿态族
 * 俯仰(移动 −6.0°/静止 −2.0°,步行与疾跑同族)——hold 判破瞬间方向在族间跳变,
 * 8m 处光池 ~0.9m 摆动(用户"入场跳变/加载卡顿"与"屏外光晕钉在头部前方");
 * ②方向族连续化后,入场交接差量缩到捕获摆动残差(~0.35°)。</p>
 *
 * <p>标定来源:313 帧 fresh 捕获拟合(evidence/2026-09-02-tp-walkin-jump s0001+
 * s0003;静止 n=113/步行 n=131/疾跑 n=51)。偏航摆 ±1.76°@8.75rad/s 不建模
 * (自由振荡器无法与动画锁相,平均收益为负);位置族差 ≤0.04 格由 DEFAULT
 * 先验+捕获自校准覆盖。瞄准(ADS)枪轴=相机轴,族偏移不适用。</p>
 *
 * <p>MC 约定与 TpLightResolver 同系:yaw 0=+z(南),90=−x(西),−90=+x(东);
 * 俯仰为 MC 实体 xRot 语义(<b>正=低头,负=抬头</b>,Vec3.directionFromRotation 同系:
 * look.y=−sin(xRot))。方向恒单位化。</p>
 */
public final class MuzzlePoseModel {
    /** 静止族俯仰偏移(°,xRot 正增量=枪口压到视线下方):枪口低于视线 2.0°。 */
    public static final float PITCH_IDLE_DEG = 2.0f;
    /** 移动族俯仰偏移(°):枪口低于视线 6.0°(步行/疾跑同族,rms 0.35°)。 */
    public static final float PITCH_MOVE_DEG = 6.0f;
    /** 移动包络速度窗(m/s):0.3 以下=静止,1.2 以上=全移动。 */
    public static final float ENV_MIN_SPEED = 0.3f;
    public static final float ENV_MAX_SPEED = 1.2f;

    private MuzzlePoseModel() {}

    /** 移动包络:smoothstep(ENV_MIN→ENV_MAX),饱和 [0,1]。 */
    public static float moveEnvelope(float speed) {
        float t = (speed - ENV_MIN_SPEED) / (ENV_MAX_SPEED - ENV_MIN_SPEED);
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /** 姿态族俯仰偏移(°):lerp(静止, 移动, env)。 */
    public static float pitchOffsetDeg(float env) {
        return PITCH_IDLE_DEG + (PITCH_MOVE_DEG - PITCH_IDLE_DEG) * env;
    }

    /**
     * 模型方向:瞄准=精确视线;否则=视线俯仰加姿态族偏移(枪口低于视线,xRot 正增量)。
     * MC 约定:输入 pitch 为实体 xRot(正=低头),
     * dir=(−sin(yaw)·cos(p), −sin(p), cos(yaw)·cos(p)),恒单位化。
     * 首版误按"正=抬头"+y=+sin(p) 合成:实机低头 8.4° 时束向朝上 6.4°(用户
     * "往上看灯朝下照地面"),2026-09-03 G 行钉死后翻正。
     */
    public static Vector3d dirFromModel(float yawDeg, float pitchDeg, float env, boolean aiming) {
        float p = pitchDeg + (aiming ? 0f : pitchOffsetDeg(env));
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(p);
        double cp = Math.cos(pitch);
        return new Vector3d(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }

    /**
     * 上传侧胶水(同源可测):由前后两帧眼位与帧时长估计速度 → 包络 → 模型方向。
     * dt≤0(首帧)保守取静止族;速度钳 10 m/s(传送饱和,不做方向尖峰)。
     */
    public static Vector3d fallbackDir(float yawDeg, float pitchDeg, boolean aiming,
                                       double prevX, double prevY, double prevZ,
                                       double x, double y, double z, long dtNanos) {
        float speed = 0f;
        if (dtNanos > 0) {
            double d = Math.sqrt((x - prevX) * (x - prevX) + (y - prevY) * (y - prevY) + (z - prevZ) * (z - prevZ));
            speed = (float) Math.min(10.0, d / (dtNanos / 1_000_000_000.0));
        }
        return dirFromModel(yawDeg, pitchDeg, moveEnvelope(speed), aiming);
    }
}

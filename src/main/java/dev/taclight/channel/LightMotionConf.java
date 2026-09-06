package dev.taclight.channel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 逐灯时间复用置信度(2026-09-06 !tm 立项,TemporalReuseContract 守护)。
 *
 * <p>体积光时间复用把上一帧光束重投影混合进本帧(权重 0.75),其成立前提是
 * "光束场逐帧不变"。相机运动由 GLSL 重投影精确对齐;灯自身的运动(玩家手持/
 * 枪灯跟视角、远程玩家移动、瞬移、开关)会改变光束场 → 必须按灯降权,否则拖影。
 * 本类按"灯身份键"缓存上一帧位姿,差分出 [0,1] 置信度,经 SSBO vlParams.w
 * 透传给 GLSL(原恒 0 的保留槽位,布局 96B 不变)。</p>
 *
 * <p>公式:{@code conf = exp(-K_POS·Δpos) × exp(-K_DIR·Δangle)}:
 * 步行 0.07 格/帧 → ≈0.65;甩头 0.16 rad/帧 → ×0.53;瞬移 10 格 → <0.001;
 * 首帧/重开(键不存在)= 0 = 全新鲜。{@link #endFrame()} 每帧末尾逐出缺席键
 * (灯关/玩家离开后再出现 = 首帧语义)。</p>
 */
public final class LightMotionConf {
    /** 位置衰减率:conf ×= exp(-K_POS·Δpos)。步行 0.072 格/帧 → 0.65。 */
    public static final float K_POS = 6.0f;
    /** 方向衰减率:conf ×= exp(-K_DIR·Δangle)。甩头 540°/s(60fps)→ 0.53。 */
    public static final float K_DIR = 4.0f;

    /** 灯身份键 → 上帧位姿 {x,y,z,dx,dy,dz}。 */
    private static final Map<String, float[]> PREV = new HashMap<>();
    /** 本帧出现过的键(endFrame 用 retainAll 逐出缺席者)。 */
    private static final Set<String> SEEN = new HashSet<>();

    private LightMotionConf() {}

    /** 上传器逐灯调用(在 clampLightsOutOfSolid 之后,差分最终上传位姿):
     *  返回 [0,1] 置信度;首帧/重开 = 0(全新鲜,防历史拖影)。 */
    public static float conf(String key, double x, double y, double z,
                             double dx, double dy, double dz) {
        float[] p = PREV.get(key);
        float c;
        if (p == null) {
            c = 0.0f;
        } else {
            double dPos = Math.sqrt(sq(x - p[0]) + sq(y - p[1]) + sq(z - p[2]));
            double lenPrev = Math.sqrt(sq(p[3]) + sq(p[4]) + sq(p[5]));
            double lenCur = Math.sqrt(sq(dx) + sq(dy) + sq(dz));
            double dAng = 0.0;
            if (lenPrev > 1e-6 && lenCur > 1e-6) {   // 零向量(未定义方向)不设转角惩罚
                double dot = (p[3] * dx + p[4] * dy + p[5] * dz) / (lenPrev * lenCur);
                dAng = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
            }
            c = (float) (Math.exp(-K_POS * dPos) * Math.exp(-K_DIR * dAng));
        }
        PREV.put(key, new float[]{(float) x, (float) y, (float) z, (float) dx, (float) dy, (float) dz});
        SEEN.add(key);
        return c;
    }

    /** 上传器每帧末尾调用一次:逐出本帧未出现的灯键(灯关/离开后重开 = 首帧语义)。 */
    public static void endFrame() {
        PREV.keySet().retainAll(SEEN);
        SEEN.clear();
    }

    /** 契约/测试用:清空全部状态(等同重启实例语义)。 */
    public static void reset() {
        PREV.clear();
        SEEN.clear();
    }

    private static double sq(double v) {
        return v * v;
    }
}

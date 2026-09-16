package dev.taclight.channel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 位置链死推滤波(2026-09-01 深夜③ v2):远程灯锚点 = 对同步位置 C 做死推 + 速度导引,
 * 消除"平移/缩放时光晕边缘闪烁"(用户实测复现,真实 WASD 步行采集定位)。
 *
 * <p>病灶(证据 evidence/2026-09-01-position-flicker/):灯锚点 getEyePosition(pt)=lerp(o→C)
 * 与原版身体渲染同式 —— 但位置同步链在 20Hz 包节奏下,o→C 的每 tick 增量有 ±20% 速度调制,
 * 墙光斑 1:1 放大(平移会话光斑残差 ≈100px;方向链=0,旋转链消融三轮不变,已排除方向链)。
 * 另有起停瞬态(去趋势窗跟不上的加减速段)贡献大摆动。</p>
 *
 * <p>v1(对同步目标 lerpX/Y/Z 做延迟段快照插值)实机 A/B 反而更糟(+27~41%):实测目标序列
 * 是突发式台阶(单步 ~0.43 格,非匀速),快照插值忠实回放突发 → 更噪。结论:<b>任何"忠实回放
 * 样本"的方案都消不掉速度调制,必须用匀速重构</b>。v2 = 经典死推:显示按平滑速度 v̂ 积分推进;
 * 每 tick 用误差(err = C − display)小比例双向校正 —— 稳态逐 tick 增量 = v̂×dt ≈ 常数(±20%
 * 调制被 EMA 滤掉),构造上无后向跳变,瞬态收敛有界;传送(|err|>0.6)直接落位。</p>
 *
 * <p>纯 JVM 可测(无 MC 类型;契约 = RemotePosSnapContract)。调用方每渲染帧对每个被收集
 * 远程玩家调 {@link #step}(喂 getX/getY/getZ,与身体渲染同源);LOOKTRACE 探针用
 * {@link #peek} 只读。实现为本项目自研(红线 0 照搬)。运行时开关(客户端本地):
 * {@code !psnap <on|off>}(off 退回 getEyePosition 旧管线,供 A/B 对照;默认 on)。</p>
 */
public final class RemotePosSnap {
    /** 实体状态过期:2s 未见视为新实体(首见直取)。 */
    static final long STALE_NANOS = 2_000_000_000L;
    /** v̂ EMA 时间常数(s):稳态速度估计的平滑度/起步响应折中。 */
    static final float TAU_V_SECONDS = 0.05f;
    /** 每 tick 位置校正比例(误差的这部分直接并入显示):小值=无感 nudge,大值=快收敛。 */
    static final float ERR_PULL_PER_TICK = 0.35f;
    /** 每 tick 速度导引增益(1/s):err → v̂,稳态消除常值滞后并压制停走过冲。 */
    static final float ERR_STEER_PER_TICK = 8.0f;
    /** v̂ 钳制(m/s):步行 4.3/疾跑 5.6,15 覆盖绝大多数机动。 */
    static final float MAX_SPEED_MPS = 15f;
    /** 传送判定:|err| 超此值直接落位(格)。 */
    static final float TELEPORT_METERS = 0.6f;
    /** tick 校正最小间隔(ns):C 不变(急停)时也必须按 tick 校正,否则 v̂ 永不衰减;
     *  25ms(≈2 次/tick)让停走反应更快,顺带提高 v̂ 平滑度。 */
    static final long CORR_MIN_INTERVAL_NANOS = 25_000_000L;
    /** 超前钳制(格):显示最多越过最新 C 前方此值(急停滑行的物理上限;不阻碍后方追赶)。 */
    static final float STOP_OVERSHOOT_CAP = 0.08f;

    private static volatile boolean enabled = true;

    /** 每实体状态(仅渲染线程访问,无需并发容器)。 */
    static final class State {
        double dispX, dispY, dispZ;       // 显示锚点(脚部坐标,眼高由调用方加)
        float vHatX, vHatY, vHatZ;        // m/s,平滑速度估计
        double lastCX, lastCY, lastCZ;
        long lastCorrNano;
        long lastNano;
    }
    private static final Map<Integer, State> STATES = new HashMap<>();

    /** step/peek 结果(格;x/y/z = 显示锚点,眼高由调用方加)。 */
    public record Out(double x, double y, double z) {}

    private RemotePosSnap() {}

    public static boolean enabled() { return enabled; }

    /**
     * 每帧推进该实体的死推滤波,返回当前显示锚点。
     *
     * @param id   实体 id(状态键)
     * @param cX   同步位置 x(getX(),与身体渲染同源)
     * @param cY   同步位置 y(getY())
     * @param cZ   同步位置 z(getZ())
     * @param nowNano 当前帧 System.nanoTime(单调)
     */
    public static Out step(int id, double cX, double cY, double cZ, long nowNano) {
        State st = STATES.get(id);
        boolean fresh = st == null || nowNano - st.lastNano > STALE_NANOS;
        if (fresh) {
            st = new State();
            st.dispX = cX; st.dispY = cY; st.dispZ = cZ;
            st.lastCX = cX; st.lastCY = cY; st.lastCZ = cZ;
            STATES.put(id, st);
        } else {
            double dt = Math.max(1e-6, (nowNano - st.lastNano) * 1e-9);
            // 1) 死推:显示按平滑速度积分推进(帧率无关),但单向钳制:最多越过最新
            //    C 前方 STOP_OVERSHOOT_CAP(急停滑行封顶;后方追赶不受限,不产生跳变)
            double nx = st.dispX + st.vHatX * dt;
            nx = st.vHatX > 0 ? Math.min(nx, Math.max(st.dispX, cX + STOP_OVERSHOOT_CAP))
                    : (st.vHatX < 0 ? Math.max(nx, Math.min(st.dispX, cX - STOP_OVERSHOOT_CAP)) : nx);
            st.dispX = nx;
            double ny = st.dispY + st.vHatY * dt;
            ny = st.vHatY > 0 ? Math.min(ny, Math.max(st.dispY, cY + STOP_OVERSHOOT_CAP))
                    : (st.vHatY < 0 ? Math.max(ny, Math.min(st.dispY, cY - STOP_OVERSHOOT_CAP)) : ny);
            st.dispY = ny;
            double nz = st.dispZ + st.vHatZ * dt;
            nz = st.vHatZ > 0 ? Math.min(nz, Math.max(st.dispZ, cZ + STOP_OVERSHOOT_CAP))
                    : (st.vHatZ < 0 ? Math.max(nz, Math.min(st.dispZ, cZ - STOP_OVERSHOOT_CAP)) : nz);
            st.dispZ = nz;
            // 2) tick 校正:C 变化帧或距上次校正 ≥1 tick(急停时 C 不变,校正也必须走,
            //    否则 v̂ 永不衰减 → 滑行不止)
            boolean cChanged = cX != st.lastCX || cY != st.lastCY || cZ != st.lastCZ;
            boolean corrDue = nowNano - st.lastCorrNano >= CORR_MIN_INTERVAL_NANOS;
            if (cChanged || corrDue) {
                double dX = cX - st.lastCX, dY = cY - st.lastCY, dZ = cZ - st.lastCZ;
                float tgtVX = clampAbs((float) (dX / 0.05), MAX_SPEED_MPS);
                float tgtVY = clampAbs((float) (dY / 0.05), MAX_SPEED_MPS);
                float tgtVZ = clampAbs((float) (dZ / 0.05), MAX_SPEED_MPS);
                float aV = 1f - (float) Math.exp(-0.05f / TAU_V_SECONDS); // 校正每 tick 一次
                st.vHatX += (tgtVX - st.vHatX) * aV;
                st.vHatY += (tgtVY - st.vHatY) * aV;
                st.vHatZ += (tgtVZ - st.vHatZ) * aV;
                st.lastCX = cX; st.lastCY = cY; st.lastCZ = cZ;
                st.lastCorrNano = nowNano;
                double errX = cX - st.dispX, errY = cY - st.dispY, errZ = cZ - st.dispZ;
                double err = Math.sqrt(errX * errX + errY * errY + errZ * errZ);
                if (err > TELEPORT_METERS) {
                    // 传送:直接落位,速度按最新样本重置
                    st.dispX = cX; st.dispY = cY; st.dispZ = cZ;
                    st.vHatX = tgtVX; st.vHatY = tgtVY; st.vHatZ = tgtVZ;
                } else {
                    // 小比例位置拉回 + 速度导引(消除常值滞后;两者皆有界,无跳变)
                    st.dispX += errX * ERR_PULL_PER_TICK;
                    st.dispY += errY * ERR_PULL_PER_TICK;
                    st.dispZ += errZ * ERR_PULL_PER_TICK;
                    st.vHatX += (float) (errX * ERR_STEER_PER_TICK * 0.05);
                    st.vHatY += (float) (errY * ERR_STEER_PER_TICK * 0.05);
                    st.vHatZ += (float) (errZ * ERR_STEER_PER_TICK * 0.05);
                }
            }
        }
        st.lastNano = nowNano;
        pruneStale(nowNano);
        return new Out(st.dispX, st.dispY, st.dispZ);
    }

    /** 探针只读:该实体当前显示锚点(不推进状态);未跟踪返回 null(调用方退回 lerp)。 */
    public static Out peek(int id, double cX, double cY, double cZ, long nowNano) {
        State st = STATES.get(id);
        if (st == null) return null;
        double dt = Math.max(0, (nowNano - st.lastNano) * 1e-9);
        return new Out(st.dispX + st.vHatX * dt, st.dispY + st.vHatY * dt, st.dispZ + st.vHatZ * dt);
    }

    /** !psnap 入口:空 = 状态;on/off。返回人读结果。 */
    public static String configure(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) {
            return "psnap=" + (enabled ? "on" : "off") + " (用法: !psnap <on|off>;off=退回 getEyePosition 旧管线)";
        }
        if (a.equalsIgnoreCase("on")) {
            enabled = true;
            STATES.clear();
            return "snap+pred 位置链 on(状态已清,首见直取)";
        }
        if (a.equalsIgnoreCase("off")) {
            enabled = false;
            STATES.clear();
            return "snap+pred 位置链 off(退回 getEyePosition 旧管线)";
        }
        return "无法解析 '" + a + "' (用法: !psnap <on|off>)";
    }

    /** 状态表修剪:size 超 32 才线性扫(每帧调用摊销可忽略)。 */
    private static void pruneStale(long nowNano) {
        if (STATES.size() <= 32) return;
        Iterator<Map.Entry<Integer, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            if (nowNano - it.next().getValue().lastNano > STALE_NANOS) it.remove();
        }
    }

    private static float clampAbs(float v, float cap) {
        return v > cap ? cap : (v < -cap ? -cap : v);
    }
}

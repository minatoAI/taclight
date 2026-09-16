package dev.taclight.channel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * snap+pred(2026-09-01 深夜,用户批准):远程灯基角 = 延迟一段的快照插值 + 两级 EMA 预测。
 * 修复"边缘闪烁"非对称病灶(用户双向实测:B 看 Dev 明显闪 / Dev 看 B 不闪 → 病灶 =
 * 观察端远程姿态重建,渲染无罪)。
 *
 * <p>机理(证据 evidence/2026-09-01-asymmetry-confirm/,真鼠标 7 会话 6291 帧):现行基角
 * rotLerp(pt,O,C) 与原版头部渲染同式,但 O/C 双通道在 tick 边界的收敛时序分歧使回放段
 * 被污染(源1 锯齿;真鼠标下纹波 3.2-11.2°≈墙面 36-127cm、单帧尖峰至 15.8°)。本类改为
 * <b>延迟一段快照插值</b>:只依赖自用 C 样本历史,C_k 到达后在 [t_k, t_k+50ms] 播放
 * C_{k-1}→C_k —— 构造上位置连续(段起点恒 = 上段终点),对 O/C 异常免疫;代价是显示比
 * 最新 C 滞后 ≤1 tick,由预测臂补偿(ω̂ EMA τv=0.15s → ext EMA τout=0.08s,保留方案A
 * 超前对抗同步链 ~100-250ms 滞后)。消融离线回放:纹波砍 52-69%、尖峰约减半;
 * chaser 因稳态滞后 -6~-16° 淘汰。离线分析器同式 = tools/lookreplay.js snap/snap+pred 臂。</p>
 *
 * <p>纯 JVM 可测(无 MC 类型;契约 = RemoteBaseSnapContract)。调用方(采集端)每渲染帧对
 * 每个被收集远程玩家调 {@link #step};LOOKTRACE 探针用 {@link #peek} 只读取当前显示角。
 * 快照插值为公开通用技术,实现为本项目自研(红线 0 照搬)。运行时开关(客户端本地):
 * {@code !bsnap <on|off>}(off 退回 rotLerp+方案A 旧管线,供 A/B 对照;默认 on)。
 * 超前 tick 数仍由 {@code !extrap} 控制(与旧管线共享旋钮)。</p>
 */
public final class RemoteBaseSnap {
    /** 快照节拍(20Hz tick):延迟段播放窗长。 */
    static final long TICK_NANOS = 50_000_000L;
    /** 实体状态过期:2s 未见视为新实体(首见直取)。 */
    static final long STALE_NANOS = 2_000_000_000L;
    /** v̂(角速度)EMA 时间常数(s)—— 消融标定,与 lookreplay snap+pred 臂同值。 */
    static final float TAU_V_SECONDS = 0.15f;

    private static volatile boolean enabled = true;

    /** 每实体状态(仅渲染线程访问,无需并发容器)。 */
    static final class State {
        float cum, from, to;          // yaw 段(cum 空间,未解缠)
        float cumP, fromP, toP;       // pitch 段(无环绕)
        long segStartNano;
        float lastCYaw, lastCPitch;
        float vHatYaw, vHatPitch;     // °/s,前级 EMA
        float extYaw, extPitch;       // °,后级 EMA(超前量)
        long lastNano;
    }
    private static final Map<Integer, State> STATES = new HashMap<>();

    /** step/peek 结果(度;yaw/pitch = 基角显示值,ext* = 超前量,调用方相加)。 */
    public record Out(float yaw, float pitch, float extYaw, float extPitch) {}

    private RemoteBaseSnap() {}

    public static boolean enabled() { return enabled; }

    /**
     * 每帧推进该实体的快照插值与预测状态,返回当前显示角 + 超前量。
     *
     * @param id       实体 id(状态键)
     * @param cYaw     最新同步头水平角 yHeadRot(快照 C;±180 内任意表示均可)
     * @param cPitch   最新同步俯仰角 xRot
     * @param nowNano  当前帧 System.nanoTime(单调)
     */
    public static Out step(int id, float cYaw, float cPitch, long nowNano) {
        State st = STATES.get(id);
        boolean fresh = st == null || nowNano - st.lastNano > STALE_NANOS;
        if (fresh) { st = new State(); STATES.put(id, st); }
        float dYaw = fresh ? 0f : wrapDegrees(cYaw - st.lastCYaw);
        float dPitch = fresh ? 0f : (cPitch - st.lastCPitch);
        if (fresh) {
            st.cum = st.from = st.to = cYaw;
            st.cumP = st.fromP = st.toP = cPitch;
            st.segStartNano = nowNano;
        } else if (dYaw != 0f || dPitch != 0f) {
            if (dYaw != 0f) { st.cum += dYaw; st.from = st.to; st.to = st.cum; }
            if (dPitch != 0f) { st.cumP += dPitch; st.fromP = st.toP; st.toP = st.cumP; }
            st.segStartNano = nowNano;
        }
        float dt = fresh ? 0f : (nowNano - st.lastNano) * 1e-9f;
        // 预测臂(两级):ω̂(°/s,单 tick 差值换算,传送级钳制)→ tgt = ω̂×ticks×0.05 → ext EMA
        if (!fresh && dt > 0f) {
            float aV = 1f - (float) Math.exp(-dt / TAU_V_SECONDS);
            st.vHatYaw += (clampAbs(dYaw, RemoteLookPredictor.MAX_OMEGA_DEG_PER_TICK) / 0.05f - st.vHatYaw) * aV;
            st.vHatPitch += (clampAbs(dPitch, RemoteLookPredictor.MAX_OMEGA_DEG_PER_TICK) / 0.05f - st.vHatPitch) * aV;
        }
        float ticks = RemoteLookPredictor.currentTicks();
        float tgtYaw = clampAbs(st.vHatYaw * ticks * 0.05f, RemoteLookPredictor.MAX_EXT_DEG);
        float tgtPitch = clampAbs(st.vHatPitch * ticks * 0.05f, RemoteLookPredictor.MAX_EXT_DEG);
        if (fresh) {
            st.extYaw = tgtYaw;
            st.extPitch = tgtPitch;
        } else if (dt > 0f) {
            float aO = 1f - (float) Math.exp(-dt / RemoteLookPredictor.EMA_TAU_SECONDS);
            st.extYaw += (tgtYaw - st.extYaw) * aO;
            st.extPitch += (tgtPitch - st.extPitch) * aO;
        }
        st.lastCYaw = cYaw;
        st.lastCPitch = cPitch;
        st.lastNano = nowNano;
        pruneStale(nowNano);
        float frac = Math.max(0f, Math.min(1f, (nowNano - st.segStartNano) / (float) TICK_NANOS));
        return new Out(st.from + (st.to - st.from) * frac,
                st.fromP + (st.toP - st.fromP) * frac, st.extYaw, st.extPitch);
    }

    /** 探针只读:该实体当前显示角(不推进状态);未跟踪返回 null(调用方退回 lerp)。 */
    public static Out peek(int id, float cYaw, float cPitch, long nowNano) {
        State st = STATES.get(id);
        if (st == null) return null;
        float frac = Math.max(0f, Math.min(1f, (nowNano - st.segStartNano) / (float) TICK_NANOS));
        return new Out(st.from + (st.to - st.from) * frac,
                st.fromP + (st.toP - st.fromP) * frac, st.extYaw, st.extPitch);
    }

    /** !bsnap 入口:空 = 状态;on/off。返回人读结果。 */
    public static String configure(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) {
            return "bsnap=" + (enabled ? "on" : "off") + " (用法: !bsnap <on|off>;off=退回 rotLerp+方案A 旧管线)";
        }
        if (a.equalsIgnoreCase("on")) {
            enabled = true;
            STATES.clear();
            return "snap+pred 基角 on(状态已清,首见直取)";
        }
        if (a.equalsIgnoreCase("off")) {
            enabled = false;
            STATES.clear();
            return "snap+pred 基角 off(退回 rotLerp+方案A 旧管线)";
        }
        return "无法解析 '" + a + "' (用法: !bsnap <on|off>)";
    }

    /** 状态表修剪:size 超 32 才线性扫(每帧调用摊销可忽略)。 */
    private static void pruneStale(long nowNano) {
        if (STATES.size() <= 32) return;
        Iterator<Map.Entry<Integer, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            if (nowNano - it.next().getValue().lastNano > STALE_NANOS) it.remove();
        }
    }

    private static float wrapDegrees(float d) {
        float f = d % 360f;
        if (f >= 180f) f -= 360f;
        if (f < -180f) f += 360f;
        return f;
    }

    private static float clampAbs(float v, float cap) {
        return v > cap ? cap : (v < -cap ? -cap : v);
    }
}

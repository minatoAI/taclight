package dev.taclight.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 方案A(2026-08-31,用户批准):远程灯方向预测外推 —— 对抗原版实体同步的可感滞后。
 *
 * <p>背景(用户实机复测):坑36 同源插值修复后远程移动闪烁"基本消失",但 B 眼里远程光斑
 * 的转动明显滞后 A 的本地视角。根因 = 原版玩家实体同步链固有延迟(A 端 20Hz 打包 ≤50ms
 * + 集成服 20Hz tick 转发 0-50ms + B 端 lerpSteps=3 渐近收敛(位置/身体朝向;head yaw
 * 近直达)+ 渲染 1-tick 插值窗)≈ 100-250ms;修闪烁前同延迟被 20Hz 台阶抖动掩盖,
 * 平滑后暴露为滞后 —— 同一数据的两种症状,非修复引入。B 无法预知 A 的输入,延迟不可归零,
 * 但可预测补偿:远程实体转动的角速度由 (O→current) 每 tick 差值即得(与渲染同源、零新包),
 * 显示角外推 {@code extrapTicks} 个 tick,抵消插值窗+部分管线延迟。</p>
 *
 * <p>形态:角速度钳制(传送/死亡/上载具的巨大差值不作预测)+ 外推角上限(急转最大超前)
 * + 外推量 EMA(时间常数 {@link #EMA_TAU_SECONDS}:稳态恒速时外推量精确等于目标、无衰减;
 * 起/停手时平滑爬坡/回落 —— 防 20Hz 台阶(坑36 同族)与急停回弹)。状态按实体 id 存放,
 * {@link #STALE_NANOS} 未见即清(首见直取目标,无爬坡)。</p>
 *
 * <p>纯 JVM 可测(无 MC 类型;契约 = RemoteLookPredictorContract)。调用方负责:
 * ① yaw 角速度先 wrapDegrees(跨 ±180 时取最短弧);② 把返回外推角加到同源插值角上。
 * 运行时调参(客户端本地,重启无需):{@code !extrap <0-3|off>} / {@code !extrap log on|off}
 * (DebugCommandRelay;/taclight 走服务端,管不到 B 的客户端状态,故必须 bang 命令)。</p>
 */
public final class RemoteLookPredictor {
    private static final Logger LOG = LoggerFactory.getLogger("TacLight");

    /** 默认预测前推 tick 数(1 tick=50ms;实机标定起点,可 !extrap 调)。 */
    static final float DEFAULT_EXTRAP_TICKS = 1.25f;
    /** 角速度钳制(°/tick):约 400°/s 以上的突变视为传送/异常,不作预测。 */
    static final float MAX_OMEGA_DEG_PER_TICK = 20.0f;
    /** 单通道外推角上限(°):急转时最大超前量(20×1.25=25 仍会被它压到 12)。 */
    static final float MAX_EXT_DEG = 12.0f;
    /** 外推量 EMA 时间常数(s):80ms —— 稳态无损、起停 ~2τ(160ms)内平滑过渡。 */
    static final float EMA_TAU_SECONDS = 0.08f;
    /** 实体状态过期:2s 未见视为新实体(首见直取目标)。 */
    static final long STALE_NANOS = 2_000_000_000L;

    private static volatile float extrapTicks = DEFAULT_EXTRAP_TICKS;
    private static volatile boolean diagLog;

    /** snap 臂(RemoteBaseSnap)共享超前 tick 数旋钮(!extrap 仍为其真源)。 */
    static float currentTicks() { return extrapTicks; }

    /** 每实体外推状态(仅渲染线程访问,无需并发容器)。 */
    static final class State {
        float yawExt, pitchExt;
        long lastNano;
        float logYaw = Float.NaN, logPitch = Float.NaN;
    }
    private static final Map<Integer, State> STATES = new HashMap<>();

    /** 外推结果(度;调用方:yHead+extYaw / xRot+extPitch)。 */
    public record Ext(float yawDeg, float pitchDeg) {
        public static final Ext ZERO = new Ext(0f, 0f);
    }

    private RemoteLookPredictor() {}

    /**
     * 单帧更新并返回该实体的角度外推量。
     *
     * @param id             实体 id(状态键)
     * @param baseYawDeg     同源插值后的水平角(仅校准日志用)
     * @param basePitchDeg   同源插值后的俯仰角(仅校准日志用)
     * @param omegaYawDeg    每 tick 头部水平角速度 = wrapDegrees(yHeadRot − yHeadRotO)
     * @param omegaPitchDeg  每 tick 俯仰角速度 = xRot − xRotO
     * @param nowNano        当前帧 System.nanoTime(单调)
     */
    public static Ext step(int id, float baseYawDeg, float basePitchDeg,
                           float omegaYawDeg, float omegaPitchDeg, long nowNano) {
        float ticks = extrapTicks;
        if (ticks <= 0f) {
            STATES.remove(id);
            return Ext.ZERO;
        }
        float tgtYaw = clampAbs(clampAbs(omegaYawDeg, MAX_OMEGA_DEG_PER_TICK) * ticks, MAX_EXT_DEG);
        float tgtPitch = clampAbs(clampAbs(omegaPitchDeg, MAX_OMEGA_DEG_PER_TICK) * ticks, MAX_EXT_DEG);
        State st = STATES.get(id);
        if (st == null || nowNano - st.lastNano > STALE_NANOS) {
            st = new State();
            st.yawExt = tgtYaw;
            st.pitchExt = tgtPitch;
            st.lastNano = nowNano;
            STATES.put(id, st);
        } else {
            float dt = (nowNano - st.lastNano) * 1e-9f;
            st.lastNano = nowNano;
            float a = dt >= EMA_TAU_SECONDS ? 1.0f : 1.0f - (float) Math.exp(-dt / EMA_TAU_SECONDS);
            st.yawExt += (tgtYaw - st.yawExt) * a;
            st.pitchExt += (tgtPitch - st.pitchExt) * a;
        }
        pruneStale(nowNano);
        if (diagLog) {
            float predYaw = baseYawDeg + st.yawExt;
            float predPitch = basePitchDeg + st.pitchExt;
            if (Float.isNaN(st.logYaw) || Math.abs(predYaw - st.logYaw) > 0.5f
                    || Math.abs(predPitch - st.logPitch) > 0.5f) {
                st.logYaw = predYaw;
                st.logPitch = predPitch;
                LOG.info("[TacLight] EXTRAP id={} truth=({},{}) pred=({},{}) omega=({},{}) ext=({},{})",
                        id, fmt(baseYawDeg), fmt(basePitchDeg), fmt(predYaw), fmt(predPitch),
                        fmt(omegaYawDeg), fmt(omegaPitchDeg), fmt(st.yawExt), fmt(st.pitchExt));
            }
        }
        return new Ext(st.yawExt, st.pitchExt);
    }

    /** 探针用:该实体当前外推量(未跟踪返回 0;只读不改状态)。 */
    public static float peekExtYaw(int id) {
        State st = STATES.get(id);
        return st == null ? 0f : st.yawExt;
    }

    /** !extrap 入口:空 = 状态;"off" = 关;"log on/off" = 校准日志;数字 = ticks(0-3)。返回人读结果。 */
    public static String configure(String arg) {        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) {
            return "ticks=" + extrapTicks + " diagLog=" + diagLog
                    + " (用法: !extrap <0-3|off|log on|log off>)";
        }
        if (a.equalsIgnoreCase("off")) {
            extrapTicks = 0f;
            STATES.clear();
            return "预测关闭(ticks=0,退回纯同源插值)";
        }
        if (a.equalsIgnoreCase("log on")) {
            diagLog = true;
            return "校准日志 on(EXTRAP 行入 latest.log)";
        }
        if (a.equalsIgnoreCase("log off")) {
            diagLog = false;
            return "校准日志 off";
        }
        try {
            float v = Float.parseFloat(a);
            v = Math.max(0f, Math.min(3f, v));
            extrapTicks = v;
            if (v <= 0f) STATES.clear();
            return "extrapTicks=" + v;
        } catch (NumberFormatException e) {
            return "无法解析 '" + a + "' (用法: !extrap <0-3|off|log on|log off>)";
        }
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

    private static String fmt(float v) {
        return String.format("%.2f", v);
    }
}

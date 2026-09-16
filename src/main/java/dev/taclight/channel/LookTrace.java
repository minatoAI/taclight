package dev.taclight.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 09-01 边缘闪烁消融实验·信号探针(纯 JVM 可测,MC 读取胶水在 ClientSpotlightUploader)。
 *
 * <p>背景:用户体感日志实证远程光旋转闪烁有两嫌疑源——①基础角 20Hz tick 边界锯齿
 * (rotLerp(pt,yHeadRotO,yHeadRot) 实测边界前跳回退,机制未钉死);②方案A ext 纹波
 * (EMA τ=80ms 对 20Hz 台阶近直通)。既有 {@code !extrap log} 是 >0.5° 变化门控采样,
 * 会混叠、且看不到 O/C 原始对 —— 消融需要<b>逐帧全量</b>的角度链路底座。</p>
 *
 * <p>形态:{@code !looktrace [on|off|帧数]} 开启后每渲染帧记录一行(数字=固定窗口帧数
 * 自动停;on=门控模式无上限,由 MotionCapture 或手动 off 收窗):
 * {@code LOOKTRACE t=.. pt=.. hO=.. hC=.. bO=.. bC=.. pO=.. pC=.. base=(yaw,pitch) om=.. ext=.. posO=(x,y,z) posC=(x,y,z)}
 * —— h=头部 yaw 对(O,C)、b=身体 yaw 对、p=俯仰对;base=与渲染同源插值角;om=每 tick
 * 角速度;ext=方案A 当前外推量;posO/posC=实体位置前后对(移动闪烁留痕,pos 尾置,
 * lookreplay 行正则尾部不锚定故向后兼容)。O 变化即 tick 边界,离线可重建锯齿/台阶结构。
 * 采样对象 = 距准星最近非自身 LivingEntity(玩家或怪/盔甲架均可,机制同类)。</p>
 *
 * <p>纯态类:时间由调用方传入;输出走可插拔 sink(默认日志,契约注入收集器)。</p>
 */
public final class LookTrace {
    private static final Logger LOG = LoggerFactory.getLogger("TacLight");
    static final int MIN_FRAMES = 60;
    static final int MAX_FRAMES = 7200;
    static final int DEFAULT_FRAMES = 1200;

    /** 行输出 sink(契约注入收集器;默认游戏日志)。 */
    static volatile Consumer<String> sink = LOG::info;

    private static volatile boolean active;
    private static boolean unbounded;
    private static int remaining;
    private static long t0Nano;
    private static int targetId = -1;
    private static String targetType = "?";

    private LookTrace() {}

    /** !looktrace 入口:空=状态;"off"=停;"on"=门控模式;数字=帧数并开启。返回人读结果。 */
    public static synchronized String configure(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) {
            return active ? "on(" + (unbounded ? "gated" : "剩余 " + remaining + " 帧") + ",target=" + targetId + ")" : "off";
        }
        if (a.equalsIgnoreCase("off")) {
            stop("手动");
            return "off";
        }
        if (a.equalsIgnoreCase("on")) {
            begin(-1);
            return "on(门控模式,由 MotionCapture/手动 off 收窗)";
        }
        try {
            int frames = Math.max(MIN_FRAMES, Math.min(MAX_FRAMES, Integer.parseInt(a)));
            begin(frames);
            return "on(" + frames + " 帧,自动停)";
        } catch (NumberFormatException e) {
            return "无法解析 '" + a + "' (用法: !looktrace <on|off|帧数>)";
        }
    }

    /** frames &lt; 0 = 门控模式(无窗口上限)。 */
    private static void begin(int frames) {
        active = true;
        unbounded = frames < 0;
        remaining = Math.max(frames, 0);
        t0Nano = 0L; // 首行对零,避免绝对时钟进日志
        targetId = -1;
        targetType = "?";
    }

    private static void stop(String why) {
        if (active) emit("LOOKTRACE-END rows=" + (!unbounded && remaining <= 0 ? "window" : unbounded ? "gated" : "manual") + " why=" + why);
        active = false;
        unbounded = false;
        targetId = -1;
    }

    public static synchronized boolean active() {
        return active;
    }

    /**
     * 每帧一条(胶水层已选好目标并读好字段)。首帧或目标变更时先发 START 标记。
     * @param frameNano 调用方 System.nanoTime(仅用于相对时间)
     */
    public static synchronized void row(int entityId, String entityType, long frameNano,
                                        float pt, float hO, float hC, float bO, float bC,
                                        float pO, float pC, float baseYaw, float basePitch,
                                        float omYaw, float extYaw,
                                        double xO, double yO, double zO, double xC, double yC, double zC,
                                        double tX, double tY, double tZ, double dX, double dY, double dZ) {
        if (!active) return;
        if (t0Nano == 0L) t0Nano = frameNano;
        if (entityId != targetId) {
            targetId = entityId;
            targetType = entityType == null ? "?" : entityType;
            emit("LOOKTRACE-START id=" + targetId + " type=" + targetType
                    + " 剩余=" + (unbounded ? "gated" : remaining));
        }
        float tMs = (frameNano - t0Nano) / 1e6f;
        emit(String.format("LOOKTRACE t=%.1f pt=%.4f hO=%.3f hC=%.3f bO=%.3f bC=%.3f pO=%.3f pC=%.3f base=(%.3f,%.3f) om=%.3f ext=%.3f"
                        + " posO=(%.2f,%.2f,%.2f) posC=(%.2f,%.2f,%.2f)"
                        + " tgt=(%.3f,%.3f,%.3f) disp=(%.3f,%.3f,%.3f)",
                tMs, pt, hO, hC, bO, bC, pO, pC, baseYaw, basePitch, omYaw, extYaw,
                xO, yO, zO, xC, yC, zC, tX, tY, tZ, dX, dY, dZ));
        if (!unbounded && --remaining <= 0) stop("窗口");
    }

    private static void emit(String line) {
        Consumer<String> s = sink;
        if (s != null) s.accept("[TacLight] " + line);
    }
}

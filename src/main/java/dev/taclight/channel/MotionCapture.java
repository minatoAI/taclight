package dev.taclight.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 09-01 运动门控采集开关(纯 JVM 状态机;MC 读取胶水在 ClientSpotlightUploader,
 * 截图执行端在 ClientEvents——帧末主帧缓冲已含最终画面,与 F2 同源且免前台窗口)。
 *
 * <p>背景:边缘闪烁定位需要"真实鼠标运动"下的逐帧信号+连拍,固定窗口(!looktrace N)
 * 要人盯秒表、还容易扫出窗口外。本开关布防后自动门控:被观察角色(最近 LivingEntity)
 * 朝向/位置在参考采样间隔内的变化超阈值 → 开会话(逐帧 LOOKTRACE 行 + 目标帧率截图);
 * 运动持续则刷新截止线,静止 stillMs 后自动收窗回布防态,可连续多会话。</p>
 *
 * <p>状态机:{@code armed →(超阈运动)session k →(静止 stillMs / 目标切换 / maxShots 翻转 /
 * 手动撤防)→ armed}。参考采样:每 refMs 才比较一次位姿差(逐帧差在高帧率下会被
 * 阈值淹没);阈值含 ±180° 环绕(wrapDegrees 语义)。单会话截图达 maxShots 时
 * 关闭并立即重开(运动未断则采集不断),会话号单调递增供离线分段。</p>
 *
 * <p>!mcap 指令:{@code on|off|yaw=|pitch=|pos=|ref=|still=|fps=|max=},空=状态回显。
 * 纯态类:时钟由调用方注入,契约全离线驱动。</p>
 */
public final class MotionCapture {
    private static final Logger LOG = LoggerFactory.getLogger("TacLight");
    /** 事件输出 sink(MCAP-OPEN/CLOSE 行;契约注入收集器,默认游戏日志)。 */
    static volatile Consumer<String> sink = LOG::info;

    private static volatile boolean armed;
    private static float yawThresh = 0.30f;
    private static float pitchThresh = 0.30f;
    private static float posThresh = 0.02f;
    private static long refMs = 100;
    private static long stillMs = 800;
    private static long shotGapMs = 16;   // ≈60fps 连拍
    private static int maxShots = 1200;   // 单会话上限(60fps ≈ 20s),防长扫磁盘失控

    private static int session;                // 会话号单调递增(s0001, s0002, … 不复用)
    private static boolean sessionOpen;        // 当前是否有打开的会话(与计数器分离)
    private static int sessionShots;
    private static long sessionOpenNano;
    private static long lastRefNano = Long.MIN_VALUE;
    private static long motionDeadlineNano;
    private static long lastShotNano = Long.MIN_VALUE;
    private static int refId = -1;
    private static float refYaw, refPitch;
    private static double refX, refY, refZ;

    private MotionCapture() {}

    /** !mcap 入口。空=状态;"on"/"off";key=val 调参。返回人读结果。 */
    public static synchronized String configure(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) return status();
        if (a.equalsIgnoreCase("off")) {
            armed = false;
            close("手动撤防");
            return "disarmed";
        }
        if (a.equalsIgnoreCase("on")) {
            armed = true;
            return status();
        }
        for (String tok : a.split("\\s+")) {
            int eq = tok.indexOf('=');
            String key = eq < 0 ? tok : tok.substring(0, eq);
            String val = eq < 0 ? "" : tok.substring(eq + 1);
            try {
                switch (key) {
                    case "yaw" -> yawThresh = Math.max(0.05f, Float.parseFloat(val));
                    case "pitch" -> pitchThresh = Math.max(0.05f, Float.parseFloat(val));
                    case "pos" -> posThresh = Math.max(0.001f, Float.parseFloat(val));
                    case "ref" -> refMs = Math.max(20, Math.min(2000, Long.parseLong(val)));
                    case "still" -> stillMs = Math.max(100, Math.min(10000, Long.parseLong(val)));
                    case "fps" -> shotGapMs = Math.max(8, Math.min(1000, (long) (1000.0 / Double.parseDouble(val))));
                    case "max" -> maxShots = Math.max(1, Math.min(100000, Integer.parseInt(val)));
                    default -> {
                        return "无法解析 '" + tok + "' (用法: !mcap <on|off|yaw=|pitch=|pos=|ref=|still=|fps=|max=>)";
                    }
                }
            } catch (NumberFormatException e) {
                return "无法解析 '" + tok + "' (用法: !mcap <on|off|yaw=|pitch=|pos=|ref=|still=|fps=|max=>)";
            }
        }
        return status();
    }

    private static String status() {
        return (armed ? "armed " : "idle ") + "session=" + (sessionOpen ? String.format("s%04d", session) : "none")
                + " shots=" + sessionShots
                + String.format(" yaw=%.2f pitch=%.2f pos=%.3f ref=%dms still=%dms fps=%d max=%d",
                yawThresh, pitchThresh, posThresh, refMs, stillMs, (int) (1000 / shotGapMs), maxShots);
    }

    public static synchronized boolean armed() { return armed; }

    /** 当前会话号(0=无会话);截图胶水据此建目录 mcap/s%04d。 */
    public static synchronized int sessionId() { return sessionOpen ? session : 0; }

    public static synchronized int sessionShots() { return sessionShots; }

    /**
     * 每帧喂入被观察实体位姿(胶水层选取,与 LookTrace 同一目标)。
     * 只在参考采样边界(refMs)上比较位姿差;超阈运动刷新截止线并按需开会话;
     * 截止线过期(静止)自动收窗。
     */
    public static synchronized void observe(int entityId, float headYaw, float pitch,
                                            double x, double y, double z, long nowNano) {
        if (!armed) return;
        long ms = nowNano / 1_000_000L;
        if (entityId != refId) {
            close("目标切换");
            resetRef(entityId, headYaw, pitch, x, y, z, ms);
            return;
        }
        if (lastRefNano != Long.MIN_VALUE && ms - lastRefNano < refMs) return;
        float dYaw = Math.abs(wrapDegrees(headYaw - refYaw));
        float dPitch = Math.abs(pitch - refPitch);
        double dx = x - refX, dy = y - refY, dz = z - refZ;
        double dPos = Math.sqrt(dx * dx + dy * dy + dz * dz);
        resetRef(entityId, headYaw, pitch, x, y, z, ms);
        boolean motion = dYaw >= yawThresh || dPitch >= pitchThresh || dPos >= posThresh;
        if (motion) {
            motionDeadlineNano = (ms + stillMs) * 1_000_000L;
            if (!sessionOpen) open(nowNano);
        }
        if (sessionOpen && ms * 1_000_000L >= motionDeadlineNano) close("静止");
    }

    /** 截图胶水按此节流取图(true=该帧应截图)。 */
    public static synchronized boolean shotDue(long nowNano) {
        if (!sessionOpen || sessionShots >= maxShots) return false;
        long ms = nowNano / 1_000_000L;
        return lastShotNano == Long.MIN_VALUE || ms - lastShotNano / 1_000_000L >= shotGapMs;
    }

    /** 截图胶水完成取图后回执;达上限时关闭并(运动未断)立即翻转新会话。 */
    public static synchronized void onShot(long nowNano) {
        if (!sessionOpen) return;
        long ms = nowNano / 1_000_000L;
        lastShotNano = nowNano;
        sessionShots++;
        if (sessionShots >= maxShots) {
            close("帧数上限");
            if (ms * 1_000_000L < motionDeadlineNano) open(nowNano);
        }
    }

    private static void resetRef(int entityId, float headYaw, float pitch,
                                 double x, double y, double z, long ms) {
        refId = entityId;
        refYaw = headYaw;
        refPitch = pitch;
        refX = x;
        refY = y;
        refZ = z;
        lastRefNano = ms;
    }

    private static void open(long nowNano) {
        session++;
        sessionOpen = true;
        sessionShots = 0;
        sessionOpenNano = nowNano;
        lastShotNano = Long.MIN_VALUE;
        emit(String.format("MCAP-OPEN session=s%04d thr(yaw/pitch/pos)=%.2f/%.2f/%.3f ref=%dms still=%dms fps=%d max=%d",
                session, yawThresh, pitchThresh, posThresh, refMs, stillMs, (int) (1000 / shotGapMs), maxShots));
    }

    private static void close(String why) {
        if (!sessionOpen) return;
        float durS = (System.nanoTime() - sessionOpenNano) / 1e9f;
        emit(String.format("MCAP-CLOSE session=s%04d shots=%d dur=%.1fs why=%s", session, sessionShots, durS, why));
        sessionOpen = false;
        sessionShots = 0;
    }

    /** ±180° 环绕安全的角度差(纯 JVM,不依赖 MC Mth)。 */
    private static float wrapDegrees(float d) {
        d = d % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    private static void emit(String line) {
        Consumer<String> s = sink;
        if (s != null) s.accept("[TacLight] " + line);
    }
}

package dev.taclight.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Consumer;

/** Motion-gated recorder state and monotonic screenshot deadline scheduler. */
public final class MotionCapture {
    private static final Logger LOG = LoggerFactory.getLogger("TacLight");
    static volatile Consumer<String> sink = LOG::info;

    private static final float DEFAULT_YAW = 0.30f;
    private static final float DEFAULT_PITCH = 0.30f;
    private static final float DEFAULT_POS = 0.02f;
    private static final long DEFAULT_REF_MS = 100;
    private static final long DEFAULT_STILL_MS = 800;
    private static final double DEFAULT_FPS = 60.0;
    private static final int DEFAULT_MAX = 1200;

    private static boolean armed;
    private static float yawThresh = DEFAULT_YAW;
    private static float pitchThresh = DEFAULT_PITCH;
    private static float posThresh = DEFAULT_POS;
    private static long refMs = DEFAULT_REF_MS;
    private static long stillMs = DEFAULT_STILL_MS;
    private static double targetFps = DEFAULT_FPS;
    private static long shotPeriodNs = Math.round(1_000_000_000d / DEFAULT_FPS);
    private static int maxShots = DEFAULT_MAX;

    private static int session;
    private static boolean sessionOpen;
    private static int sessionShots;
    private static long sessionOpenNano;
    private static long motionDeadlineNano;
    private static long nextShotDeadlineNano = Long.MIN_VALUE;

    private static int refId = -1;
    private static long remoteRefNano = Long.MIN_VALUE;
    private static float refYaw, refPitch;
    private static double refX, refY, refZ;
    private static long localRefNano = Long.MIN_VALUE;
    private static float localRefYaw, localRefPitch;
    private static double localRefX, localRefY, localRefZ;

    private MotionCapture() { }

    public static synchronized String configure(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) return status();
        if (a.equalsIgnoreCase("off")) {
            shutdown("manual-off");
            return "disarmed";
        }

        boolean enable = false;
        for (String tok : a.split("\\s+")) {
            if (tok.equalsIgnoreCase("on")) {
                enable = true;
                continue;
            }
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
                    case "fps" -> setFps(Double.parseDouble(val));
                    case "max" -> maxShots = Math.max(1, Math.min(100000, Integer.parseInt(val)));
                    default -> { return usage(tok); }
                }
            } catch (NumberFormatException e) {
                return usage(tok);
            }
        }
        if (enable) {
            close("re-arm");
            armed = true;
            resetReferencesAndDeadline();
        }
        return status();
    }

    private static void setFps(double fps) {
        if (!Double.isFinite(fps) || fps <= 0) throw new NumberFormatException("fps");
        targetFps = Math.max(1.0, Math.min(125.0, fps));
        shotPeriodNs = Math.round(1_000_000_000d / targetFps);
        nextShotDeadlineNano = Long.MIN_VALUE;
    }

    private static String usage(String tok) {
        return "无法解析 '" + tok + "' (用法: !rec <on|off|yaw=|pitch=|pos=|ref=|still=|fps=|max=>)";
    }

    private static String status() {
        return (armed ? "armed(local+remote) " : "idle ")
                + "session=" + (sessionOpen ? String.format(Locale.ROOT, "s%04d", session) : "none")
                + " shots=" + sessionShots
                + String.format(Locale.ROOT, " yaw=%.2f pitch=%.2f pos=%.3f ref=%dms still=%dms fps=%s max=%d",
                yawThresh, pitchThresh, posThresh, refMs, stillMs, formatFps(targetFps), maxShots);
    }

    private static String formatFps(double fps) {
        if (fps == Math.rint(fps)) return String.format(Locale.ROOT, "%.0f", fps);
        return String.format(Locale.ROOT, "%.3f", fps).replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }

    public static synchronized boolean armed() { return armed; }
    public static synchronized int sessionId() { return sessionOpen ? session : 0; }
    public static synchronized int sessionShots() { return sessionShots; }
    static synchronized long periodNs() { return shotPeriodNs; }

    public static synchronized void observe(int entityId, float headYaw, float pitch,
                                            double x, double y, double z, long nowNano) {
        if (!armed) return;
        if (entityId != refId || remoteRefNano == Long.MIN_VALUE) {
            if (refId != -1 && entityId != refId) close("target-switch");
            setRemoteRef(entityId, headYaw, pitch, x, y, z, nowNano);
            return;
        }
        if (nowNano - remoteRefNano < refMs * 1_000_000L) return;
        float dYaw = Math.abs(wrapDegrees(headYaw - refYaw));
        float dPitch = Math.abs(pitch - refPitch);
        double dx = x - refX, dy = y - refY, dz = z - refZ;
        double dPos = Math.sqrt(dx * dx + dy * dy + dz * dz);
        setRemoteRef(entityId, headYaw, pitch, x, y, z, nowNano);
        sampleMotion(dYaw >= yawThresh || dPitch >= pitchThresh || dPos >= posThresh, nowNano);
    }

    public static synchronized void observeLocal(float yaw, float pitch,
                                                  double x, double y, double z, long nowNano) {
        if (!armed) return;
        if (localRefNano == Long.MIN_VALUE) {
            setLocalRef(yaw, pitch, x, y, z, nowNano);
            return;
        }
        if (nowNano - localRefNano < refMs * 1_000_000L) return;
        float dYaw = Math.abs(wrapDegrees(yaw - localRefYaw));
        float dPitch = Math.abs(pitch - localRefPitch);
        double dx = x - localRefX, dy = y - localRefY, dz = z - localRefZ;
        double dPos = Math.sqrt(dx * dx + dy * dy + dz * dz);
        setLocalRef(yaw, pitch, x, y, z, nowNano);
        sampleMotion(dYaw >= yawThresh || dPitch >= pitchThresh || dPos >= posThresh, nowNano);
    }

    private static void sampleMotion(boolean motion, long nowNano) {
        if (motion) {
            motionDeadlineNano = saturatingAdd(nowNano, stillMs * 1_000_000L);
            if (!sessionOpen) open(nowNano);
        }
        if (sessionOpen && nowNano >= motionDeadlineNano) close("still");
    }

    /** Accumulator scheduler: returns at most one token and accounts every additional crossed deadline as D. */
    public static synchronized FrameRecorder.ShotToken reserveShot(long nowNano, long renderFrame) {
        if (!sessionOpen) return null;
        if (nextShotDeadlineNano == Long.MIN_VALUE) nextShotDeadlineNano = nowNano;
        if (nowNano < nextShotDeadlineNano) return null;

        long crossed = 1L + (nowNano - nextShotDeadlineNano) / shotPeriodNs;
        long droppedLong = crossed - 1L;
        int dropped = droppedLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) droppedLong;
        long advance;
        try { advance = Math.multiplyExact(crossed, shotPeriodNs); }
        catch (ArithmeticException overflow) { advance = Long.MAX_VALUE; }
        nextShotDeadlineNano = saturatingAdd(nextShotDeadlineNano, advance);
        if (dropped > 0) FrameRecorder.recordDropped(nowNano / 1_000_000L, renderFrame, dropped, "deadline");

        FrameRecorder.ShotToken token = FrameRecorder.reserveShot(nowNano / 1_000_000L, renderFrame);
        if (token == null) return null;
        sessionShots++;
        if (sessionShots >= maxShots) {
            close("max-shots");
            if (armed && nowNano < motionDeadlineNano) open(nowNano);
        }
        return token;
    }

    private static void open(long nowNano) {
        session++;
        sessionOpen = true;
        sessionShots = 0;
        sessionOpenNano = nowNano;
        nextShotDeadlineNano = nowNano;
        FrameRecorder.onOpen(session, targetFps);
        if (!FrameRecorder.active()) {
            sessionOpen = false;
            armed = false;
            emit("MCAP-HARD-FAIL session=s" + String.format(Locale.ROOT, "%04d", session)
                    + " error=" + FrameRecorder.lastError());
            resetReferencesAndDeadline();
            return;
        }
        emit(String.format(Locale.ROOT,
                "MCAP-OPEN session=s%04d dir=%s thr(yaw/pitch/pos)=%.2f/%.2f/%.3f ref=%dms still=%dms fps=%s max=%d",
                session, FrameRecorder.currentSessionDir(), yawThresh, pitchThresh, posThresh,
                refMs, stillMs, formatFps(targetFps), maxShots));
    }

    private static void close(String why) {
        if (!sessionOpen) return;
        int closingSession = session;
        int closingShots = sessionShots;
        double durS = Math.max(0L, System.nanoTime() - sessionOpenNano) / 1e9;
        FrameRecorder.onClose(why);
        sessionOpen = false;
        sessionShots = 0;
        nextShotDeadlineNano = Long.MIN_VALUE;
        emit(String.format(Locale.ROOT, "MCAP-CLOSE session=s%04d shots=%d dur=%.1fs why=%s",
                closingSession, closingShots, durS, why));
    }

    /** World/off shutdown: idempotently closes, disarms, and clears local/remote references and deadlines. */
    public static synchronized void shutdown(String reason) {
        close(reason == null ? "shutdown" : reason);
        armed = false;
        resetReferencesAndDeadline();
    }

    private static void resetReferencesAndDeadline() {
        refId = -1;
        remoteRefNano = Long.MIN_VALUE;
        localRefNano = Long.MIN_VALUE;
        motionDeadlineNano = 0;
        nextShotDeadlineNano = Long.MIN_VALUE;
    }

    private static void setRemoteRef(int entityId, float yaw, float pitch,
                                     double x, double y, double z, long nowNano) {
        refId = entityId;
        refYaw = yaw;
        refPitch = pitch;
        refX = x;
        refY = y;
        refZ = z;
        remoteRefNano = nowNano;
    }

    private static void setLocalRef(float yaw, float pitch,
                                    double x, double y, double z, long nowNano) {
        localRefYaw = yaw;
        localRefPitch = pitch;
        localRefX = x;
        localRefY = y;
        localRefZ = z;
        localRefNano = nowNano;
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    private static float wrapDegrees(float d) {
        d %= 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    private static void emit(String line) {
        Consumer<String> s = sink;
        if (s != null) s.accept("[TacLight] " + line);
    }

    static synchronized void resetForTest() {
        shutdown("test-reset");
        armed = false;
        yawThresh = DEFAULT_YAW;
        pitchThresh = DEFAULT_PITCH;
        posThresh = DEFAULT_POS;
        refMs = DEFAULT_REF_MS;
        stillMs = DEFAULT_STILL_MS;
        targetFps = DEFAULT_FPS;
        shotPeriodNs = Math.round(1_000_000_000d / DEFAULT_FPS);
        maxShots = DEFAULT_MAX;
        session = 0;
        sessionOpen = false;
        sessionShots = 0;
        sessionOpenNano = 0;
        resetReferencesAndDeadline();
        sink = LOG::info;
    }
}

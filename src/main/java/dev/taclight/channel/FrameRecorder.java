package dev.taclight.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Durable per-render-frame recorder with token-routed asynchronous screenshot completion. */
public final class FrameRecorder {
    private static final Logger LOG = LoggerFactory.getLogger("TacLight");
    private static final DateTimeFormatter RUN_CLOCK = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);
    private static final int FLUSH_ROWS = 256;

    private static File baseDir;
    private static File runDir;
    private static Session current;
    private static final Map<Long, Session> sessions = new HashMap<>();
    private static long nextSessionKey;
    private static String lastError;

    /** Immutable ownership handle passed from synchronous reservation to the asynchronous callback. */
    public record ShotToken(long sessionKey, String sessionLabel, int seq, long requestTMs,
                            long renderFrame, String filename, File sessionDir, File expectedFile) { }

    private static final class Session {
        final long key;
        final String label;
        final File dir;
        final BufferedWriter out;
        int rows;
        int buffered;
        int nextShotSeq;
        int pending;
        int requested;
        int succeeded;
        int failed;
        int dropped;
        boolean closing;
        boolean closed;
        String closeReason;

        Session(long key, String label, File dir, BufferedWriter out) {
            this.key = key;
            this.label = label;
            this.dir = dir;
            this.out = out;
        }
    }

    private FrameRecorder() { }

    /** Starts a process run namespace immediately; repeated calls never reuse an existing run directory. */
    public static synchronized void setBaseDir(File dir) {
        if (current != null || sessions.values().stream().anyMatch(s -> !s.closed)) {
            fail("setBaseDir while recording sessions are still open", null);
            return;
        }
        baseDir = dir;
        runDir = null;
        lastError = null;
        if (dir == null) return;
        Path mcap = dir.toPath().resolve("mcap");
        String stem = "run-" + RUN_CLOCK.format(LocalDateTime.now()) + "-p" + ProcessHandle.current().pid();
        try {
            Files.createDirectories(mcap);
            for (int n = 0; ; n++) {
                Path candidate = mcap.resolve(n == 0 ? stem : stem + "-" + n);
                try {
                    Files.createDirectory(candidate);
                    runDir = candidate.toFile();
                    return;
                } catch (java.nio.file.FileAlreadyExistsException collision) {
                    // Collision is expected for fast restarts/tests; try the next suffix atomically.
                }
            }
        } catch (IOException e) {
            fail("cannot create run directory under " + mcap, e);
        }
    }

    public static synchronized boolean active() { return current != null && !current.closing && !current.closed; }
    public static synchronized File currentRunDir() { return runDir; }
    public static synchronized File currentSessionDir() { return current == null ? null : current.dir; }
    public static synchronized String currentSessionLabel() { return current == null ? null : current.label; }
    public static synchronized String lastError() { return lastError; }

    public static synchronized void onOpen(int sessionNumber, double targetFps) {
        if (current != null && !current.closing && !current.closed) {
            fail("cannot open s" + sessionNumber + ": current session " + current.label + " is active", null);
            return;
        }
        if (runDir == null) {
            fail("cannot open recording session: base/run directory unavailable", null);
            return;
        }
        String label = String.format(Locale.ROOT, "s%04d", sessionNumber);
        Path dir = runDir.toPath().resolve(label);
        try {
            Files.createDirectory(dir);
            BufferedWriter writer = Files.newBufferedWriter(dir.resolve("frames.csv"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Session opened = new Session(++nextSessionKey, label, dir.toFile(), writer);
            sessions.put(opened.key, opened);
            current = opened;
            writeRaw(opened, header(label, runDir.getName(), targetFps));
            flush(opened);
        } catch (IOException e) {
            fail("cannot open recording session " + dir, e);
        }
    }

    /** Marks current closing. Footer is written only after every reserved screenshot completes. */
    public static synchronized void onClose(String reason) {
        Session s = current;
        if (s == null) return;
        current = null;
        if (s.closing || s.closed) return;
        s.closing = true;
        s.closeReason = sanitize(reason == null ? "unspecified" : reason);
        flush(s);
        finishIfReady(s);
    }

    /** Appends C/L/R rows to the current session only. */
    public static synchronized void append(String row) {
        Session s = current;
        if (s == null || s.closing || s.closed) return;
        writeData(s, row);
    }

    /** Writes P synchronously and increments pending before returning a callback ownership token. */
    public static synchronized ShotToken reserveShot(long requestTMs, long renderFrame) {
        Session s = current;
        if (s == null || s.closing || s.closed) return null;
        if (lastError != null) return null;
        int seq = ++s.nextShotSeq;
        String filename = String.format(Locale.ROOT, "shot-%06d.png", seq);
        File expected = new File(new File(s.dir, "screenshots"), filename);
        ShotToken token = new ShotToken(s.key, s.label, seq, requestTMs, renderFrame, filename, s.dir, expected);
        s.pending++;
        s.requested++;
        writeData(s, pendingRow(token));
        flush(s); // P must be durable before Screenshot.grab is scheduled.
        return token;
    }

    /** Routes S/F to the token's original session, including after close and a later session open. */
    public static synchronized void completeShot(ShotToken token, boolean success, String detail) {
        if (token == null) return;
        Session s = sessions.get(token.sessionKey());
        if (s == null || s.closed || !s.label.equals(token.sessionLabel())) {
            fail("screenshot callback has no live owner for " + token.sessionLabel() + "#" + token.seq(), null);
            return;
        }
        if (s.pending <= 0) {
            fail("duplicate/unbalanced screenshot callback for " + token.sessionLabel() + "#" + token.seq(), null);
            return;
        }
        if (success) {
            s.succeeded++;
            writeData(s, successRow(token));
        } else {
            s.failed++;
            writeData(s, failureRow(token, detail));
        }
        s.pending--;
        flush(s);
        finishIfReady(s);
    }

    /** Records target deadlines skipped because one render callback can schedule at most one screenshot. */
    public static synchronized void recordDropped(long tMs, long renderFrame, int count, String reason) {
        if (count <= 0) return;
        Session s = current;
        if (s == null || s.closing || s.closed) return;
        s.dropped += count;
        writeData(s, droppedRow(tMs, renderFrame, count, reason));
    }

    private static void finishIfReady(Session s) {
        if (!s.closing || s.closed || s.pending != 0) return;
        writeRaw(s, footer(s));
        try {
            s.out.flush();
            s.out.close();
            s.closed = true;
            sessions.remove(s.key);
        } catch (IOException e) {
            fail("cannot finalize " + s.dir, e);
        }
    }

    private static void writeData(Session s, String row) {
        writeRaw(s, row);
        s.rows++;
        if (++s.buffered >= FLUSH_ROWS) flush(s);
    }

    private static void writeRaw(Session s, String row) {
        if (s.closed) {
            fail("write attempted after close: " + s.dir, null);
            return;
        }
        try {
            s.out.write(row);
            s.out.newLine();
        } catch (IOException e) {
            fail("recording write failed: " + s.dir, e);
        }
    }

    private static void flush(Session s) {
        if (s == null || s.closed) return;
        try {
            s.out.flush();
            s.buffered = 0;
        } catch (IOException e) {
            fail("recording flush failed: " + s.dir, e);
        }
    }

    private static void fail(String message, Throwable cause) {
        lastError = cause == null ? message : message + ": " + cause;
        if (cause == null) LOG.error("[TacLight] REC HARD-FAIL {}", message);
        else LOG.error("[TacLight] REC HARD-FAIL {}", message, cause);
    }

    public static String header(String label, String runLabel, double targetFps) {
        return "# TacLight rec " + label + " run=" + runLabel
                + " targetFps=" + formatFps(targetFps)
                + " rows=C(camera/player/walk-bob) L(light) R(remote-chain) P(pending) S(success) F(fail) D(dropped) t=monotonic-ms frame=render-frame";
    }

    private static String footer(Session s) {
        return "# END rows=" + s.rows + " requested=" + s.requested + " succeeded=" + s.succeeded
                + " failed=" + s.failed + " dropped=" + s.dropped + " reason=" + s.closeReason;
    }

    public static String cameraRow(long tMs, long frame,
                                   double camX, double camY, double camZ,
                                   double camYaw, double camPitch,
                                   double px, double py, double pz,
                                   double eyeX, double eyeY, double eyeZ,
                                   float walkDist, float walkDistO, float bob, float oBob,
                                   boolean bobEnabled) {
        return "C," + tMs + "," + frame
                + "," + f5(camX) + "," + f5(camY) + "," + f5(camZ)
                + "," + f3(camYaw) + "," + f3(camPitch)
                + "," + f5(px) + "," + f5(py) + "," + f5(pz)
                + "," + f5(eyeX) + "," + f5(eyeY) + "," + f5(eyeZ)
                + "," + f5(walkDist) + "," + f5(walkDistO)
                + "," + f5(bob) + "," + f5(oBob) + "," + bobEnabled;
    }

    public static String lightRow(long tMs, long frame, int idx,
                                  float ax, float ay, float az,
                                  float dx, float dy, float dz,
                                  float r, float i, float cosO, float cosI) {
        return "L," + tMs + "," + frame + "," + idx
                + "," + f5(ax) + "," + f5(ay) + "," + f5(az)
                + "," + f5(dx) + "," + f5(dy) + "," + f5(dz)
                + "," + f5(r) + "," + f5(i) + "," + f5(cosO) + "," + f5(cosI);
    }

    public static String remoteRow(long tMs, long frame, int id, float pt,
                                   float hO, float hC, float bO, float bC,
                                   float pO, float pC, float baseYaw, float basePitch,
                                   float omYaw, float extYaw,
                                   double xO, double yO, double zO,
                                   double xC, double yC, double zC,
                                   double tX, double tY, double tZ,
                                   double dX, double dY, double dZ) {
        return "R," + tMs + "," + frame + "," + id
                + "," + f5(pt)
                + "," + f5(hO) + "," + f5(hC) + "," + f5(bO) + "," + f5(bC)
                + "," + f5(pO) + "," + f5(pC)
                + "," + f5(baseYaw) + "," + f5(basePitch)
                + "," + f5(omYaw) + "," + f5(extYaw)
                + "," + f5(xO) + "," + f5(yO) + "," + f5(zO)
                + "," + f5(xC) + "," + f5(yC) + "," + f5(zC)
                + "," + f5(tX) + "," + f5(tY) + "," + f5(tZ)
                + "," + f5(dX) + "," + f5(dY) + "," + f5(dZ);
    }

    private static String pendingRow(ShotToken t) {
        return "P," + t.requestTMs() + "," + t.renderFrame() + "," + t.seq() + "," + t.filename();
    }

    private static String successRow(ShotToken t) {
        return "S," + t.requestTMs() + "," + t.renderFrame() + "," + t.seq() + "," + t.filename();
    }

    private static String failureRow(ShotToken t, String detail) {
        return "F," + t.requestTMs() + "," + t.renderFrame() + "," + t.seq() + "," + t.filename()
                + "," + sanitize(detail == null ? "unknown" : detail);
    }

    private static String droppedRow(long tMs, long renderFrame, int count, String reason) {
        return "D," + tMs + "," + renderFrame + "," + count + "," + sanitize(reason == null ? "unspecified" : reason);
    }

    private static String sanitize(String value) {
        return value.replace(',', ';').replace('\r', ' ').replace('\n', ' ');
    }

    private static String formatFps(double fps) {
        if (fps == Math.rint(fps)) return String.format(Locale.ROOT, "%.0f", fps);
        return String.format(Locale.ROOT, "%.3f", fps).replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }

    private static String f3(double v) { return String.format(Locale.ROOT, "%.3f", v); }
    private static String f5(double v) { return String.format(Locale.ROOT, "%.5f", v); }

    static synchronized void resetForTest() {
        for (Session s : sessions.values().toArray(Session[]::new)) {
            try { s.out.close(); } catch (IOException ignored) { }
        }
        sessions.clear();
        current = null;
        baseDir = null;
        runDir = null;
        lastError = null;
        nextSessionKey = 0;
    }
}

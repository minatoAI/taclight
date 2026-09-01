package dev.taclight.channel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure-JVM contracts for motion gating, deadline scheduling, rollover and re-arming. */
public final class MotionCaptureContract {
    private static int passed;
    private static final long STEP = 150_000_000L;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("PASS: " + msg);
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("taclight-motion-contract");
        List<String> out = new ArrayList<>();
        try {
            FrameRecorder.resetForTest();
            FrameRecorder.setBaseDir(tmp.toFile());
            MotionCapture.resetForTest();
            MotionCapture.sink = out::add;
            long nano = 1_000_000_000L;

            check(!MotionCapture.armed(), "默认未布防");
            check(MotionCapture.configure("on fps=60 max=3").contains("fps=60"), "status 显示 target fps=60");

            // First remote/local samples establish baselines only.
            MotionCapture.observe(7, 0, 0, 0, 0, 0, nano);
            MotionCapture.observeLocal(0, 0, 0, 0, 0, nano);
            check(MotionCapture.sessionId() == 0, "re-arm 第一采样只建远程/本地基线");
            nano += STEP;
            MotionCapture.observe(7, 1, 0, 0, 0, 0, nano);
            check(MotionCapture.sessionId() == 1, "超过阈值开启 s0001");

            long firstDeadline = nano;
            FrameRecorder.ShotToken t1 = MotionCapture.reserveShot(firstDeadline, 10);
            check(t1 != null && t1.sessionLabel().equals("s0001") && t1.seq() == 1, "首 deadline 立即 reserve token");
            check(MotionCapture.reserveShot(firstDeadline + 16_000_000L, 11) == null,
                    "60fps: 16ms 尚未到 round(1e9/60) deadline");
            FrameRecorder.ShotToken t2 = MotionCapture.reserveShot(firstDeadline + 16_666_667L, 12);
            check(t2 != null && t2.seq() == 2, "60fps: 16,666,667ns 到第二 deadline");
            FrameRecorder.ShotToken t3 = MotionCapture.reserveShot(firstDeadline + 66_666_668L, 13);
            check(t3 != null && t3.seq() == 3, "跨 deadline 仍只预留当前截图 token");
            check(MotionCapture.sessionId() == 2 && t3.sessionLabel().equals("s0001"),
                    "max 边界 token 归旧会话后才 close/reopen");
            check(MotionCapture.sessionShots() == 0, "rollover 新会话计数清零");

            FrameRecorder.completeShot(t1, true, "ok");
            FrameRecorder.completeShot(t2, false, "encode-fail");
            FrameRecorder.completeShot(t3, true, "ok");
            String old = Files.readString(t1.sessionDir().toPath().resolve("frames.csv"));
            check(old.contains("D,") && old.contains(",2,deadline"), "deadline accumulator 记录跨过的两个 D dropped");
            check(old.contains("requested=3") && old.contains("succeeded=2") && old.contains("failed=1")
                            && old.contains("dropped=2") && old.contains("reason=max-shots"),
                    "rollover 旧会话 footer counters 完整");

            // off must close/disarm and clear both references/deadline; re-arm first samples baseline only.
            check(MotionCapture.configure("off").equals("disarmed"), "off 撤防");
            check(!MotionCapture.armed() && MotionCapture.sessionId() == 0, "off 关闭 current session");
            MotionCapture.configure("on");
            nano += STEP;
            MotionCapture.observe(7, 100, 20, 10, 10, 10, nano);
            MotionCapture.observeLocal(100, 20, 10, 10, 10, nano);
            check(MotionCapture.sessionId() == 0, "重新 on 后第一采样只重建基线");
            nano += STEP;
            MotionCapture.observeLocal(101, 20, 10, 10, 10, nano);
            check(MotionCapture.sessionId() == 3, "re-arm 后第二采样运动正常开新会话");

            MotionCapture.shutdown("world-unload");
            int events = out.size();
            MotionCapture.shutdown("world-unload");
            check(!MotionCapture.armed() && MotionCapture.sessionId() == 0 && out.size() == events,
                    "world-unload shutdown 幂等关闭/撤防");
            MotionCapture.configure("on");
            nano += STEP;
            MotionCapture.observe(7, -45, 3, 4, 5, 6, nano);
            MotionCapture.observeLocal(-45, 3, 4, 5, 6, nano);
            check(MotionCapture.sessionId() == 0, "world-unload 后第一远程/本地采样只建基线");

            check(MotionCapture.configure("fps=59.94").contains("fps=59.94"), "fps 接受 double 并显示目标值");
            check(MotionCapture.periodNs() == Math.round(1_000_000_000d / 59.94d), "periodNs=round(1e9/fps)");
        } finally {
            MotionCapture.resetForTest();
            FrameRecorder.resetForTest();
            try (var walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println("MotionCaptureContract: ALL PASS (" + passed + " checks)");
    }
}

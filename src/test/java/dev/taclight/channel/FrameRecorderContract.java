package dev.taclight.channel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Trusted recording protocol contracts: unique runs, token ownership and durable accounting. */
public final class FrameRecorderContract {
    private static int passed;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("PASS: " + msg);
    }

    private static void writeShot(FrameRecorder.ShotToken token, int gray) throws Exception {
        if (token == null) throw new AssertionError("FAIL: integration shot token is null");
        Path file = token.expectedFile().toPath();
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        int rgb = (gray << 16) | (gray << 8) | gray;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) image.setRGB(x, y, rgb);
        }
        ImageIO.write(image, "png", file.toFile());
        FrameRecorder.completeShot(token, true, "ok");
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("taclight-rec-contract");
        try {
            FrameRecorder.resetForTest();
            FrameRecorder.setBaseDir(tmp.toFile());
            Path run1 = FrameRecorder.currentRunDir().toPath();
            check(run1.getParent().equals(tmp.resolve("mcap")), "runDir 位于唯一 mcap/run-* 层级");
            check(run1.getFileName().toString().matches("run-\\d{8}-\\d{6}-\\d{3}-p\\d+(?:-\\d+)?"),
                    "runDir 名含 wallclock/pid/collision suffix: " + run1.getFileName());

            FrameRecorder.onOpen(1, 60.0);
            Path s1 = FrameRecorder.currentSessionDir().toPath();
            check(Files.readString(s1.resolve("frames.csv"), StandardCharsets.UTF_8).contains("targetFps=60"),
                    "生产 header 固化目标 FPS 元数据");
            check(FrameRecorder.currentSessionLabel().equals("s0001") && s1.equals(run1.resolve("s0001")),
                    "暴露当前会话目录与 label");

            String c = FrameRecorder.cameraRow(1234, 5,
                    2004.123456, 122.623456, 3.512345, 215.0, -10.0,
                    2004.123456, 121.623456, 3.512345,
                    2004.123456, 122.423456, 3.512345,
                    12.25f, 12.0f, 0.18f, 0.15f, true);
            String[] fc = c.split(",");
            check(fc.length == 19, "C 行包含 walkDist/walkDistO/bob/oBob/bobEnabled 新列");
            check(fc[3].equals("2004.12346") && fc[11].equals("2004.12346"),
                    "C 世界位置至少 1e-5 精度: " + fc[3] + "," + fc[11]);
            check(fc[14].equals("12.25000") && fc[18].equals("true"), "C 真实 bob 驱动列值");
            FrameRecorder.append(c);

            FrameRecorder.ShotToken old = FrameRecorder.reserveShot(1250, 6);
            check(old != null && old.seq() == 1 && old.requestTMs() == 1250 && old.renderFrame() == 6,
                    "reserveShot 返回不可变 token 并同步编号");
            check(old.filename().equals("shot-000001.png")
                            && old.expectedFile().equals(s1.resolve("screenshots").resolve(old.filename()).toFile()),
                    "token 携带显式文件名/目录/expected file");
            String beforeClose = Files.readString(s1.resolve("frames.csv"), StandardCharsets.UTF_8);
            check(beforeClose.contains("P,1250,6,1,shot-000001.png"), "reserveShot 同步写 P 行");

            FrameRecorder.onClose("rollover");
            String pendingClose = Files.readString(s1.resolve("frames.csv"), StandardCharsets.UTF_8);
            check(!pendingClose.contains("# END"), "pending>0 时延迟 footer/close");

            FrameRecorder.onOpen(2, 60.0);
            Path s2 = FrameRecorder.currentSessionDir().toPath();
            check(FrameRecorder.active() && s2.equals(run1.resolve("s0002")), "closing 与 current session 可并存");
            FrameRecorder.completeShot(old, true, "ok");
            String closedOld = Files.readString(s1.resolve("frames.csv"), StandardCharsets.UTF_8);
            check(closedOld.contains("S,1250,6,1,shot-000001.png"), "close 后 callback 仍写回旧会话 S");
            check(closedOld.contains("# END rows=") && closedOld.contains("requested=1")
                            && closedOld.contains("succeeded=1") && closedOld.contains("failed=0")
                            && closedOld.contains("dropped=0") && closedOld.contains("reason=rollover"),
                    "footer 含 rows/requested/succeeded/failed/dropped/reason counters");

            FrameRecorder.ShotToken fail = FrameRecorder.reserveShot(1300, 7);
            FrameRecorder.completeShot(fail, false, "missing-file");
            FrameRecorder.recordDropped(1317, 8, 3, "deadline");
            FrameRecorder.onClose("test-end");
            String closed2 = Files.readString(s2.resolve("frames.csv"), StandardCharsets.UTF_8);
            check(closed2.contains("P,1300,7,1,shot-000001.png"), "新会话独立 P 序号");
            check(closed2.contains("F,1300,7,1,shot-000001.png,missing-file"), "失败 callback 写 F 行");
            check(closed2.contains("D,1317,8,3,deadline"), "调度丢帧写 D 行");
            check(closed2.contains("requested=1") && closed2.contains("succeeded=0")
                            && closed2.contains("failed=1") && closed2.contains("dropped=3"),
                    "footer 汇总 P/S/F/D counters");

            FrameRecorder.resetForTest();
            FrameRecorder.setBaseDir(tmp.toFile());
            Path run2 = FrameRecorder.currentRunDir().toPath();
            check(!run2.equals(run1) && Files.isDirectory(run1) && Files.isDirectory(run2),
                    "同 baseDir 两次 run 不复用目录");
            check(FrameRecorder.lastError() == null, "正常 I/O 无硬失败状态");

            FrameRecorder.onOpen(1, 60.0);
            Path integration = FrameRecorder.currentSessionDir().toPath();
            FrameRecorder.append(FrameRecorder.cameraRow(2000, 100,
                    0, 64, 0, 0, 0, 0, 63, 0, 0, 64, 0,
                    0, 0, 0, 0, true));
            FrameRecorder.append(FrameRecorder.cameraRow(2020, 101,
                    0, 64, 0, 0, 0, 0, 63, 0, 0, 64, 0,
                    0.1f, 0, 0.1f, 0, true));
            writeShot(FrameRecorder.reserveShot(2000, 100), 64);
            writeShot(FrameRecorder.reserveShot(2020, 101), 192);
            FrameRecorder.recordDropped(2021, 102, 3, "deadline");
            FrameRecorder.onClose("integration");

            Path analyzer = Path.of(System.getProperty("user.dir"), "tools", "rec-analyze.js");
            Process process = new ProcessBuilder("node", analyzer.toString(), integration.toString())
                    .redirectErrorStream(true)
                    .start();
            String analyzerOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int analyzerExit = process.waitFor();
            check(analyzerExit == 0 && analyzerOutput.contains("REC-ANALYZE PASS"),
                    "Java 生产 CSV 可由实际 Node 分析器消费: " + analyzerOutput.trim());
            String summary = Files.readString(integration.resolve("summary.json"), StandardCharsets.UTF_8);
            check(summary.contains("\"dropped\": 3") && summary.contains("\"S\": 2"),
                    "跨语言集成保留 shot frame/seq 与 ΣD.count");

            // G 行(2026-09-02 TP 捕获链打桩):34 列,字符串字段原样,逗号消毒
            String g = FrameRecorder.gunRow(3000, 200, 42, "hold", 1.0f, "blend", "col",
                    0.1f, 0.2f, 0.3f, 90f, 5f, 0f, 0f, 0f, 1f,
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 91f, 2f, true, 77, true);
            check(g.startsWith("G,3000,200,42,hold,1.000,blend,col,"), "G 行:前缀/状态/权重/模式");
            check(g.split(",").length >= 34, "G 行:34 列(打桩全链字段)");
            check(FrameRecorder.gunRow(1, 1, 1, "bl,end", 0f, "blend", "col",
                    0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, false)
                    .contains("bl;end"), "G 行:state 逗号消毒");
        } finally {
            FrameRecorder.resetForTest();
            try (var walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
        System.out.println("FrameRecorderContract: ALL PASS (" + passed + " checks)");
    }
}

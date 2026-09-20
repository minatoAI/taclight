package dev.taclight.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调试快照最小闭环契约(2026-09-19)。纯 JVM,不加载 MC、不起游戏,native 全 mock:
 * <ul>
 *   <li>目录命名不覆盖:同基名连建 → 目录各异且旧目录内容不动;</li>
 *   <li>JSON 键齐全:snapshot.json 顶层键 = {@link DebugSnapshotter#JSON_KEYS},
 *       pose/light/tune 段键齐全,null 可读值如实落 null;</li>
 *   <li>无 RenderDoc 语义三态:生产路径(无 probe){@code isAvailable=false /
 *       getStatus=absent / triggerCapture=false};mock 注入假句柄翻转三态;
 *       抛异常的探针仍被吞为 absent(绝不抛);</li>
 *   <li>接线文本级:relay 的 !snap / 命令的 snap+MP 守卫 / F9 键位 / 截图 try/catch。</li>
 * </ul>
 */
public class DebugSnapshotContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        RenderDocGate.clearTestProbe();
        absentTriple("生产路径(无 probe,纯 JVM=无 native 环境)");

        // ---------------- mock 翻转三态 ----------------
        RenderDocGate.setTestProbe(new RenderDocGate.Probe() {
            @Override
            public long moduleHandle(String moduleName) {
                check(moduleName.equals(RenderDocGate.MODULE), "探针收到的模块名 = " + RenderDocGate.MODULE);
                return 12345L;
            }

            @Override
            public boolean requestCapture() {
                return true;
            }
        });
        check(RenderDocGate.isAvailable(), "mock present: isAvailable=true");
        check(RenderDocGate.getStatus().equals(RenderDocGate.PRESENT), "mock present: getStatus=present");
        check(RenderDocGate.triggerCapture(), "mock present: triggerCapture=true");

        RenderDocGate.setTestProbe(new RenderDocGate.Probe() {
            @Override
            public long moduleHandle(String moduleName) {
                return 0;
            }

            @Override
            public boolean requestCapture() {
                throw new IllegalStateException("不应被调用(未注入即短路)");
            }
        });
        absentTriple("mock absent(句柄 0)");
        // 未注入时 trigger 不得调用探针(上段 requestCapture 抛即露馅,已覆盖)。

        RenderDocGate.setTestProbe(new RenderDocGate.Probe() {
            @Override
            public long moduleHandle(String moduleName) {
                throw new RuntimeException("假 native 崩");
            }

            @Override
            public boolean requestCapture() {
                throw new RuntimeException("假 native 崩");
            }
        });
        absentTriple("mock 抛异常探针(绝不抛→absent 三态)");
        RenderDocGate.clearTestProbe();
        absentTriple("clearTestProbe 后回生产路径");

        // ---------------- 目录命名不覆盖 ----------------
        Path root = Files.createTempDirectory("snap-contract");
        String base = DebugSnapshotter.dirBaseFor(System.currentTimeMillis());
        check(base.matches("\\d{8}-\\d{6}-\\d{3}"), "目录基名形如 yyyyMMdd-HHmmss-SSS,实际=" + base);
        Path d1 = DebugSnapshotter.resolveUniqueDir(root, base);
        Path d2 = DebugSnapshotter.resolveUniqueDir(root, base);
        check(Files.isDirectory(d1) && Files.isDirectory(d2), "两次 resolve 都建出目录");
        check(!d1.equals(d2), "同基名两次 resolve 目录各异(" + d1.getFileName() + " vs " + d2.getFileName() + ")");
        Files.writeString(d1.resolve("marker.txt"), "do-not-touch");
        Path d3 = DebugSnapshotter.resolveUniqueDir(root, base);
        check(Files.readString(d1.resolve("marker.txt")).equals("do-not-touch"),
                "第三次 resolve 不覆盖旧目录内容(marker 完好)");
        check(d3.getFileName().toString().equals(base + "-3"), "第三个目录追加 -3,实际=" + d3.getFileName());

        // ---------------- JSON 键齐全(含 null 与转义) ----------------
        Map<String, Object> pose = new LinkedHashMap<>();
        pose.put("x", null);
        pose.put("y", null);
        pose.put("z", null);
        pose.put("yaw", null);
        pose.put("pitch", null);
        pose.put("note", "no-world:mc.player=null");
        Map<String, Object> light = new LinkedHashMap<>();
        light.put("handheld", true);
        light.put("gun", false);
        light.put("gunEffective", false);
        light.put("neon", false);
        light.put("self", null);
        light.put("note", null);
        Map<String, Object> tune = new LinkedHashMap<>();
        for (String k : DebugSnapshotter.TUNE_KEYS) {
            tune.put(k, k.equals("note") ? null : "off(默认)");
        }
        List<String> notes = new ArrayList<>();
        notes.add("voxel: unreadable(FakeT)");
        DebugSnapshotter.SnapData data = new DebugSnapshotter.SnapData(
                "quote\"back\\slash\nnewline", "2026-09-19T00:00:00+08:00", 1L,
                RenderDocGate.ABSENT, pose, light, tune, "voxel=on grid=null", notes);
        String json = DebugSnapshotter.buildSnapshotJson(data);
        for (String k : DebugSnapshotter.JSON_KEYS) {
            check(json.contains("\"" + k + "\":"), "snapshot.json 含顶层键 \"" + k + "\"");
        }
        for (String k : DebugSnapshotter.POSE_KEYS) {
            check(json.contains("\"" + k + "\":"), "pose 段含键 \"" + k + "\"");
        }
        for (String k : DebugSnapshotter.LIGHT_KEYS) {
            check(json.contains("\"" + k + "\":"), "light 段含键 \"" + k + "\"");
        }
        for (String k : DebugSnapshotter.TUNE_KEYS) {
            check(json.contains("\"" + k + "\":"), "tune 段含键 \"" + k + "\"");
        }
        check(json.contains("\"x\":null"), "读不到的 pose 写 null(非缺键非字符串)");
        check(json.contains("\"note\":\"no-world:mc.player=null\""), "读不到注明原因(pose.note)");
        check(json.contains("\"handheld\":true") && json.contains("\"gunEffective\":false"),
                "布尔现值如实落盘(非字符串)");
        check(json.contains("\\\"") && json.contains("\\\\") && json.contains("\\n"),
                "reason 特殊字符转义(引号/反斜杠/换行)");
        check(json.contains("\"voxel\":\"voxel=on grid=null\""), "体素状态行入 JSON");

        // ---------------- note 首行 renderdoc 状态 ----------------
        String note = DebugSnapshotter.buildNoteText(data, "ok(screenshots/)", "skipped(renderdoc absent)");
        String firstLine = note.split("\n")[0];
        check(firstLine.equals("renderdoc=absent"), "note 首行 = renderdoc 状态,实际=" + firstLine);
        check(note.contains("screenshot=") && note.contains("capture="), "note 含截图与抓取结果行");

        // ---------------- 接线文本级 ----------------
        String relay = read("src/main/java/dev/taclight/client/DebugCommandRelay.java");
        check(relay.contains("!snap") && relay.contains("DebugSnapshotter.saveSnapshot"),
                "relay 含 !snap 分支且调同一 saveSnapshot");
        String cmd = read("src/main/java/dev/taclight/command/TacLightCommand.java");
        check(cmd.contains("literal(\"snap\")"), "命令树含 snap 分支(仿 light 分支写法)");
        check(cmd.contains("snapMpGuard") && cmd.contains("isDedicatedServer")
                        && cmd.contains("仅单人/客户端生效"),
                "snap 分支有 MP 守卫(专用服回仅单人,client 引用之后)");
        check(cmd.contains("DebugSnapshotter.saveSnapshot"), "snap 分支调同一 saveSnapshot");
        String keys = read("src/main/java/dev/taclight/client/TacSnapshotKeys.java");
        check(keys.contains("GLFW_KEY_F9") && keys.contains("DebugSnapshotter.saveSnapshot"),
                "F9 键位(TacSnapshotKeys)调同一 saveSnapshot");
        String snap = read("src/main/java/dev/taclight/client/DebugSnapshotter.java");
        check(snap.contains("Screenshot.grab") && snap.contains("catch (Throwable"),
                "截图用 Screenshot.grab 且抄 !shot 的 try/catch");
        check(snap.contains("debug-snapshots") && snap.contains("snapshot.json") && snap.contains("note.txt"),
                "落盘 <gameDir>/debug-snapshots/<时间戳>/snapshot.json + note.txt");
        String gate = read("src/main/java/dev/taclight/client/RenderDocGate.java");
        check(gate.contains("GetModuleHandle") && gate.contains("LWJGL"),
                "RenderDocGate 含二选一注释(JNA/Kernel32 选定 + LWJGL 弃选理由)");
        check(gate.contains("isAvailable") && gate.contains("triggerCapture") && gate.contains("getStatus"),
                "RenderDocGate 含三方法 isAvailable/triggerCapture/getStatus");

        System.out.println("DebugSnapshotContract: ALL PASS (" + checks + " checks)");
    }

    /** 无 RenderDoc 语义三态:可用=false / 状态=absent / 触发=false。 */
    private static void absentTriple(String scene) {
        check(!RenderDocGate.isAvailable(), scene + ": isAvailable=false");
        check(RenderDocGate.getStatus().equals(RenderDocGate.ABSENT), scene + ": getStatus=absent");
        check(!RenderDocGate.triggerCapture(), scene + ": triggerCapture=false");
    }

    private static String read(String rel) throws Exception {
        Path p = Path.of(rel);
        if (!Files.exists(p)) {
            throw new AssertionError("FAIL 缺失文件: " + rel);
        }
        return Files.readString(p);
    }

    private static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("FAIL " + what);
        }
        checks++;
        System.out.println("  PASS " + what);
    }
}

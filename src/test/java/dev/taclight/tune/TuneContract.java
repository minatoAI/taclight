package dev.taclight.tune;

import dev.taclight.channel.LightTuneOverride;
import dev.taclight.debug.tune.TuneService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /taclight tune} 晋升契约(2026-09-19):九旋钮正式命令 + toml 持久化。
 *
 * <p>三层断言(纯 JVM,零 Forge/零 MC 启动):
 * <ol>
 *   <li><b>行为</b>:真覆盖层入口({@code LightTuneOverride.configure*},与生产恒等)+
 *       假 Sink/假 Gate,断言每旋钮 范围/越界/status/off 三态 + 持久化路由
 *       (写哪个键/什么值/save 几次;status 零接触)。</li>
 *   <li><b>路由真值表</b>:cone 一写二(outer=v/inner=v×0.5);off 写回默认值(防"off 后仍是旧值"
 *       的静默假成功);voxel on/off 字面语义;save 失败仍 ok 但明示"重启不保留"。{@code restore}
 *       只回填新增键非默认值,既有键(直读 config)与默认值(即 off 态)不碰。</li>
 *   <li><b>接线(源码文本,旧码必红)</b>:命令树含 tune + MP 守卫文案;config 含 5 新键 +
 *       cone 下限放宽;恢复钩子在位;voxel 门隔离 VoxelGrid;beamonly/occl/tm 未进表;
 *       文件中继保留(双路径)。改回旧行为(删 tune 分支/删新键/范围漂移)即变红。</li>
 * </ol></p>
 *
 * <p>零残留:首尾全 off(覆盖层是跨契约静态态,LightTuneContract 同规)。</p>
 */
public class TuneContract {
    private static int checks;

    // ---- 假件 ----

    private static class FakeSink implements TunePersist.Sink {
        final Map<String, Double> doubles = new HashMap<>();
        final Map<String, Boolean> bools = new HashMap<>();
        final List<String> sets = new ArrayList<>();
        int saves;
        String saveError = "";
        final Map<String, Double> readDoubles = new HashMap<>();
        final Map<String, Boolean> readBools = new HashMap<>();

        @Override
        public void setDouble(String key, double value) {
            doubles.put(key, value);
            sets.add(key + "=" + value);
        }

        @Override
        public void setBoolean(String key, boolean value) {
            bools.put(key, value);
            sets.add(key + "=" + value);
        }

        @Override
        public double getDouble(String key, double fallback) {
            return readDoubles.getOrDefault(key, fallback);
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            return readBools.getOrDefault(key, fallback);
        }

        @Override
        public String save() {
            saves++;
            return saveError;
        }
    }

    private static class FakeGate implements TunePersist.VoxelGate {
        boolean on = true;
        int applies;
        Boolean restored;

        @Override
        public String apply(String arg) {
            applies++;
            if ("on".equals(arg)) on = true;
            else if ("off".equals(arg)) on = false;
            return status();
        }

        @Override
        public String status() {
            return on ? "voxel=on grid=null" : "voxel=off(fallback SSO)";
        }

        @Override
        public void restore(boolean newOn) {
            restored = newOn;
            on = newOn;
        }
    }

    /** 生产恒等入口:直调 LightTuneOverride(与 TacLightCommand.TUNE_APPLIER 同函数)。 */
    private static final TunePersist.KnobApplier REAL = (knob, arg) -> switch (knob) {
        case "bright" -> LightTuneOverride.configureBright(arg);
        case "dist" -> LightTuneOverride.configureDist(arg);
        case "atten" -> LightTuneOverride.configureAtten(arg);
        case "knee" -> LightTuneOverride.configureKnee(arg);
        case "beam" -> LightTuneOverride.configureBeam(arg);
        case "scat" -> LightTuneOverride.configureScat(arg);
        case "beamcap" -> LightTuneOverride.configureBeamcap(arg);
        case "cone" -> LightTuneOverride.configureCone(arg);
        default -> "bad arg " + arg + " (unknown knob " + knob + ")";
    };

    public static void main(String[] args) throws Exception {
        resetAll();

        // ---- 注册表:九旋钮 + 范围同源 + 映射表 ----
        check(TuneKnobs.all().size() == 9, "九旋钮全晋升(8 数值 + voxel)");
        check(TuneKnobs.names().equals("bright/dist/atten/knee/beam/scat/beamcap/cone/voxel"),
                "注册顺序与命令名: " + TuneKnobs.names());
        rangeIs("bright", 0.5f, 30.0f, new String[]{"intensity"}, new double[]{6.0});
        rangeIs("dist", 4.0f, 96.0f, new String[]{"radius"}, new double[]{36.0});
        rangeIs("atten", 0.2f, 20.0f, new String[]{"attenK"}, new double[]{20.0});
        rangeIs("knee", 0.2f, 8.0f, new String[]{"kneeGain"}, new double[]{2.0});
        rangeIs("beam", 0.0f, 1.0f, new String[]{"beamDensity"}, new double[]{0.25});
        rangeIs("scat", 0.0f, 0.9f, new String[]{"scatFloor"}, new double[]{0.04});
        rangeIs("beamcap", 0.25f, 8.0f, new String[]{"beamCapM"}, new double[]{1.0});
        rangeIs("cone", 2.0f, 45.0f, new String[]{"coneOuterDeg", "coneInnerDeg"}, new double[]{8.0, 4.0});
        rangeIs("voxel", 0.0f, 0.0f, new String[]{"voxelEnabled"}, new double[]{1.0});
        // 未晋升钉死:beamonly/occl/tm 留中继(理由见 TuneKnobs)。
        check(TuneKnobs.byName("beamonly") == null, "beamonly 未晋升(调试观察位)");
        check(TuneKnobs.byName("occl") == null, "occl 未晋升(性能 A/B 开关)");
        check(TuneKnobs.byName("tm") == null, "tm 未晋升(性能 A/B 开关)");
        check(TuneKnobs.byName("BRIGHT") != null, "命令名大小写不敏感");

        // ---- 每旋钮三态 + 持久化(真覆盖层 + 假 Sink) ----
        numericKnob("bright", "12", "99", "0.1", "abc",
                () -> Math.abs(LightTuneOverride.brightnessFor(6.0f) - 12.0f) < 1e-6,
                () -> Math.abs(LightTuneOverride.brightnessFor(6.0f) - 6.0f) < 1e-6,
                "intensity", 12.0, 6.0);
        numericKnob("dist", "24", "200", "1", "far",
                () -> Math.abs(LightTuneOverride.radiusFor(50.9f, 96.0f) - 24.0f) < 1e-6,
                () -> Math.abs(LightTuneOverride.radiusFor(50.9f, 96.0f) - 50.9f) < 1e-6,
                "radius", 24.0, 36.0);
        numericKnob("atten", "1.0", "99", "0.01", "soft",
                () -> Math.abs(LightTuneOverride.attenK() - 1.0f) < 1e-6,
                () -> LightTuneOverride.attenK() == 0.0f,
                "attenK", 1.0, 20.0);
        numericKnob("knee", "3.0", "20", "0.1", "soft",
                () -> Math.abs(LightTuneOverride.kneeG() - 3.0f) < 1e-6,
                () -> Math.abs(LightTuneOverride.kneeG() - 2.0f) < 1e-6,
                "kneeGain", 3.0, 2.0);
        numericKnob("beam", "0.35", "2.0", "-0.1", "fog",
                () -> Math.abs(LightTuneOverride.beamDensityOr(0.05f) - 0.35f) < 1e-6,
                () -> Math.abs(LightTuneOverride.beamDensityOr(0.05f) - 0.05f) < 1e-6,
                "beamDensity", 0.35, 0.25);
        numericKnob("scat", "0.3", "0.95", "-0.1", "wide",
                () -> Math.abs(LightTuneOverride.scatOr(0.04f) - 0.3f) < 1e-6,
                () -> LightTuneOverride.scatOr(0.04f) == 0.04f,
                "scatFloor", 0.3, 0.04);
        numericKnob("beamcap", "2.0", "9", "0.1", "cap",
                () -> Math.abs(LightTuneOverride.beamCapM() - 2.0f) < 1e-6,
                () -> LightTuneOverride.beamCapM() == 0.0f,
                "beamCapM", 2.0, 1.0);
        numericKnob("cone", "6", "60", "1", "wide",
                () -> Math.abs(LightTuneOverride.coneDeg() - 6.0f) < 1e-6,
                () -> LightTuneOverride.coneDeg() == 0.0f,
                "coneOuterDeg", 6.0, 8.0);

        // cone 一写二(inner=v×0.5)。
        {
            FakeSink s = new FakeSink();
            FakeGate g = new FakeGate();
            TuneService.Result r = TuneService.tune("cone", "10", s, g, REAL);
            check(r.ok() && Math.abs(s.doubles.get("coneOuterDeg") - 10.0) < 1e-9
                    && Math.abs(s.doubles.get("coneInnerDeg") - 5.0) < 1e-9,
                    "cone 一写二 outer=10/inner=5");
            TuneService.tune("cone", "off", s, g, REAL);
            check(Math.abs(s.doubles.get("coneOuterDeg") - 8.0) < 1e-9
                    && Math.abs(s.doubles.get("coneInnerDeg") - 4.0) < 1e-9,
                    "cone off 写回默认 8/4(防 off 后仍是旧值)");
        }

        // voxel 字面语义(不对称:off=禁用≠回默认;回默认请 on)。
        {
            FakeSink s = new FakeSink();
            FakeGate g = new FakeGate();
            TuneService.Result on = TuneService.tune("voxel", "on", s, g, REAL);
            check(on.ok() && on.message().startsWith("[TacLight]")
                    && Boolean.TRUE.equals(s.bools.get("voxelEnabled")) && s.saves == 1,
                    "voxel on 持久化 true");
            TuneService.Result off = TuneService.tune("voxel", "off", s, g, REAL);
            check(off.ok() && !g.on && Boolean.FALSE.equals(s.bools.get("voxelEnabled")) && s.saves == 2,
                    "voxel off=字面禁用并持久化 false(非回默认)");
            int saves = s.saves;
            TuneService.Result st = TuneService.tune("voxel", "status", s, g, REAL);
            check(st.ok() && s.saves == saves, "voxel status 零写盘");
            TuneService.Result nul = TuneService.tune("voxel", null, s, g, REAL);
            check(nul.ok() && s.saves == saves, "voxel 无参=status,零写盘");
            TuneService.Result bad = TuneService.tune("voxel", "maybe", s, g, REAL);
            check(!bad.ok() && bad.message().contains("on/off/status") && s.saves == saves,
                    "voxel 非法值拒绝(中继的静默回状态在此是错误)");
        }

        // 未知/help/全状态。
        {
            FakeSink s = new FakeSink();
            FakeGate g = new FakeGate();
            TuneService.Result u = TuneService.tune("brite", "12", s, g, REAL);
            check(!u.ok() && u.message().contains("未知旋钮") && u.message().contains("bright/dist")
                    && s.saves == 0, "未知旋钮回用法,零写盘");
            TuneService.Result h = TuneService.tune("help", null, s, g, REAL);
            check(h.ok() && h.message().contains("/taclight tune") && s.saves == 0, "help 只读");
            TuneService.Result all = TuneService.statusAll(g, REAL);
            check(all.ok() && all.message().startsWith("[TacLight]") && s.saves == 0,
                    "全状态只读零写盘");
            for (TuneKnobs.Knob k : TuneKnobs.all()) {
                check(all.message().contains("\n  " + k.name() + ": "),
                        "全状态含 " + k.name());
            }
        }

        // save 失败:覆盖层已生效 + 如实回显(不静默假成功,仍返回 ok)。
        {
            FakeSink s = new FakeSink();
            s.saveError = "disk-full(synthetic)";
            FakeGate g = new FakeGate();
            TuneService.Result r = TuneService.tune("bright", "12", s, g, REAL);
            check(r.ok() && r.message().contains("写入失败") && r.message().contains("重启不保留")
                    && Math.abs(LightTuneOverride.brightnessFor(6.0f) - 12.0f) < 1e-6,
                    "save 失败也生效但明示不保留");
        }

        // restore:只回填新增键非默认值;既有键/默认值/voxel 按规矩。
        {
            FakeSink s = new FakeSink();
            s.readDoubles.put("attenK", 1.5);
            s.readDoubles.put("kneeGain", 2.0);
            s.readDoubles.put("bright", 99.0); // 既有键(键名 intensity,此处故意错键,双重保险)
            s.readDoubles.put("intensity", 12.0); // 既有键非默认也不回填(直读 config)
            s.readDoubles.put("beamCapM", 1.0);
            s.readBools.put("voxelEnabled", false);
            FakeGate g = new FakeGate();
            List<String> applied = new ArrayList<>();
            String report = TunePersist.restore(s, g, (knob, arg) -> {
                applied.add(knob + "=" + arg);
                return REAL.apply(knob, arg);
            });
            check(applied.size() == 1 && applied.get(0).equals("atten=1.5"),
                    "restore 只回填 atten 非默认值(实际 " + applied + ")");
            check(Math.abs(LightTuneOverride.attenK() - 1.5f) < 1e-6, "restore 回填生效");
            check(Boolean.FALSE.equals(g.restored), "restore 恢复 voxel=off");
            check(report.contains("atten=1.5") && report.contains("voxel=off"), "restore 日志行: " + report);
        }

        // ---- 接线(源码文本,旧码必红) ----
        String cmd = read("src/main/java/dev/taclight/debug/command/TacLightCommand.java");
        check(cmd.contains(".literal(\"tune\")"), "命令树含 tune 分支(删分支即红)");
        check(cmd.contains("isDedicatedServer"), "MP 守卫在位(删守卫即红)");
        check(cmd.contains("仅单人/客户端生效"), "MP 回显文案在位");
        check(cmd.contains("[TacLight] TUNE"), "日志 [TacLight] 前缀在位(grep 依赖)");
        check(cmd.contains("StringArgumentType.string()"), "数值用 string()(word 拒 '.')");
        String cfg = read("src/main/java/dev/taclight/config/TacLightConfig.java");
        for (String key : new String[]{"\"attenK\"", "\"kneeGain\"", "\"scatFloor\"", "\"beamCapM\"",
                "\"voxelEnabled\"", "ATTEN_K", "KNEE_GAIN", "SCAT_FLOOR", "BEAM_CAP_M", "VOXEL_ENABLED"}) {
            check(cfg.contains(key), "config 含新键 " + key + "(删键即红)");
        }
        check(cfg.contains("\"coneOuterDeg\", 8.0, 2.0"), "cone 外下限放宽到 2.0");
        check(cfg.contains("\"coneInnerDeg\", 4.0, 1.0"), "cone 内下限放宽到 1.0");
        String tick = read("src/main/java/dev/taclight/client/ClientEvents.java");
        check(tick.contains("tuneRestored") && tick.contains("TunePersist.restore"),
                "开机恢复钩子在位(删钩子即红)");
        String gate = read("src/main/java/dev/taclight/client/TuneClientGate.java");
        check(gate.contains("VoxelGrid.configure"), "体素门直调 VoxelGrid(改直引即红)");
        check(gate.contains("bad arg"), "体素门白名单(非法不静默成功)");
        String relay = read("src/main/java/dev/taclight/client/DebugCommandRelay.java");
        check(relay.contains("!bright") && relay.contains("/taclight tune"),
                "中继保留 + 指路 tune(双路径不断)");
        String knobs = read("src/main/java/dev/taclight/tune/TuneKnobs.java");
        check(!knobs.contains("beamonly") || knobs.contains("内存态(未晋升)"),
                "未晋升三旋钮在表外(说明在注释)");

        resetAll();
        System.out.println("TuneContract: ALL PASS (" + checks + " checks)");
    }

    // ---- 小件 ----

    private interface BoolCheck {
        boolean get();
    }

    private static void rangeIs(String name, float min, float max, String[] keys, double[] defs) {
        TuneKnobs.Knob k = TuneKnobs.byName(name);
        check(k != null, "注册含 " + name);
        check(Math.abs(k.min() - min) < 1e-6 && Math.abs(k.max() - max) < 1e-6,
                name + " 范围同源 " + min + ".." + max);
        check(java.util.Arrays.equals(k.configKeys(), keys)
                && java.util.Arrays.equals(k.configDefaults(), defs),
                name + " 映射 " + String.join("+", keys));
    }

    /** 单数值旋钮三态 + 持久化全套(真覆盖层 + 假 Sink)。 */
    private static void numericKnob(String name, String good, String over, String under, String bad,
                                    BoolCheck applied, BoolCheck backToDefault,
                                    String key, double goodVal, double defVal) {
        FakeSink s = new FakeSink();
        FakeGate g = new FakeGate();
        // status:只读。
        TuneService.Result st = TuneService.tune(name, "status", s, g, REAL);
        check(st.ok() && st.message().startsWith("[TacLight]") && s.saves == 0 && s.sets.isEmpty(),
                name + " status 只读零写盘");
        TuneService.Result nul = TuneService.tune(name, null, s, g, REAL);
        check(nul.ok() && s.saves == 0, name + " 无参=status");
        // set:生效 + 写盘。
        TuneService.Result r = TuneService.tune(name, good, s, g, REAL);
        check(r.ok() && r.message().contains("taclight-client.toml") && r.message().contains(key)
                && applied.get(), name + " " + good + " 生效并持久化");
        check(s.sets.contains(key + "=" + goodVal) && s.saves == 1, name + " 写 " + key + "=" + goodVal);
        // 越界上下 + 非法:拒绝、不污染、不写盘。
        int saves = s.saves;
        int n = s.sets.size();
        TuneService.Result ro = TuneService.tune(name, over, s, g, REAL);
        check(!ro.ok() && ro.message().contains("拒绝") && applied.get()
                && s.saves == saves && s.sets.size() == n, name + " 上越界拒绝不污染(" + over + ")");
        TuneService.Result ru = TuneService.tune(name, under, s, g, REAL);
        check(!ru.ok() && applied.get() && s.saves == saves, name + " 下越界拒绝不污染(" + under + ")");
        TuneService.Result rb = TuneService.tune(name, bad, s, g, REAL);
        check(!rb.ok() && applied.get() && s.saves == saves, name + " 非法拒绝(" + bad + ")");
        // off:回默认 + 写回默认值。
        TuneService.Result off = TuneService.tune(name, "off", s, g, REAL);
        check(off.ok() && backToDefault.get() && s.sets.contains(key + "=" + defVal),
                name + " off 回默认并写回 " + key + "=" + defVal);
        // 边界端点合法(同源双向对账:覆盖层改范围不改表即红)。
        TuneKnobs.Knob k = TuneKnobs.byName(name);
        FakeSink s2 = new FakeSink();
        check(TuneService.tune(name, Float.toString(k.min()), s2, g, REAL).ok(),
                name + " 下端点 " + k.min() + " 合法");
        check(TuneService.tune(name, Float.toString(k.max()), s2, g, REAL).ok(),
                name + " 上端点 " + k.max() + " 合法");
        TuneService.tune(name, "off", new FakeSink(), g, REAL);
    }

    private static void resetAll() {
        LightTuneOverride.configureBright("off");
        LightTuneOverride.configureDist("off");
        LightTuneOverride.configureAtten("off");
        LightTuneOverride.configureKnee("off");
        LightTuneOverride.configureBeam("off");
        LightTuneOverride.configureScat("off");
        LightTuneOverride.configureBeamcap("off");
        LightTuneOverride.configureCone("off");
    }

    private static String read(String rel) throws Exception {
        return Files.readString(Path.of(rel));
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

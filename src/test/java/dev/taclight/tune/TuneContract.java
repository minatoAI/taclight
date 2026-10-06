package dev.taclight.tune;

import dev.taclight.channel.LightTuneOverride;
import dev.taclight.client.GunControl;
// 2026-10-06:TuneService 已搬回发布面**同包**(原 dev.taclight.debug.tune ⇒ 随
// exclude 'dev/taclight/debug/**' 被发布件剔除)。同包引用无需 import ——
// 这行 import 的消失本身就是搬移痕迹,不是漏写。

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
 *       假 Sink/假 Gate,断言每旋钮 范围/越界/status/clear 三态 + 持久化路由
 *       (写哪个键/什么值/save 几次;status 零接触)。</li>
 *   <li><b>路由真值表</b>:cone 一写二(outer=v/inner=v×0.5);clear 写回默认值(防"clear 后仍是旧值"
 *       的静默假成功;旧写法 off 是兼容别名,等价性单独验);voxel on/off 字面语义;save 失败仍 ok
 *       但明示"重启不保留"。{@code restore} 只回填新增键非默认值,既有键(直读 config)与默认值
 *       (即 clear 态)不碰。</li>
 *   <li><b>用词/文案闸门(2026-10-06 用户反馈)</b>:status 回显不得出现 {@code off(...)};
 *       每个旋钮必须有"调大/调小"(voxel 为开/关)说明;用法文案用 clear;voxel 的 clear 明确拒绝
 *       并指路 on(不许静默当成 off)。</li>
 *   <li><b>接线(源码文本,旧码必红)</b>:命令树含 tune + MP 守卫文案;config 含 5 新键 +
 *       cone 下限放宽;恢复钩子在位;voxel 门隔离 VoxelGrid;beamonly/occl/tm 未进表;
 *       文件中继保留(双路径)。改回旧行为(删 tune 分支/删新键/范围漂移)即变红。</li>
 * </ol></p>
 *
 * <p>零残留:首尾全 clear(覆盖层是跨契约静态态,LightTuneContract 同规)。</p>
 */
public class TuneContract {
    private static int checks;

    // ---- 假件 ----

    private static class FakeSink implements TunePersist.Sink {
        final Map<String, Double> doubles = new HashMap<>();
        final Map<String, Integer> ints = new HashMap<>();
        final Map<String, Boolean> bools = new HashMap<>();
        final List<String> sets = new ArrayList<>();
        int saves;
        String saveError = "";
        final Map<String, Double> readDoubles = new HashMap<>();
        final Map<String, Integer> readInts = new HashMap<>();
        final Map<String, Boolean> readBools = new HashMap<>();

        @Override
        public void setDouble(String key, double value) {
            doubles.put(key, value);
            sets.add(key + "=" + value);
        }

        @Override
        public void setInt(String key, int value) {
            ints.put(key, value);
            // 整数键的记账文本必须是整数(契约按"键=值"逐字对账):"12" 而不是 "12.0"
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
        public int getInt(String key, int fallback) {
            return readInts.getOrDefault(key, fallback);
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
        case "held" -> LightTuneOverride.configureHeld(arg);
        default -> "bad arg " + arg + " (unknown knob " + knob + ")";
    };

    public static void main(String[] args) throws Exception {
        resetAll();

        // ---- 注册表:九旋钮 + 范围同源 + 映射表 ----
        check(TuneKnobs.all().size() == 10, "十旋钮全晋升(8 双精度 + voxel 布尔 + held 整数)");
        check(TuneKnobs.names().equals("bright/dist/atten/knee/beam/scat/beamcap/cone/voxel/held"),
                "注册顺序与命令名: " + TuneKnobs.names());
        check(TuneKnobs.isInt(TuneKnobs.byName("held")) && !TuneKnobs.isInt(TuneKnobs.byName("bright")),
                "held 是整数旋钮(持久化走 setInt),其余数值旋钮不是");
        check(TuneKnobs.isIntKey("heldLightLevel") && !TuneKnobs.isIntKey("intensity"),
                "整数键判定按 config 键(不是按旋钮名):heldLightLevel 是,其它不是");
        rangeIs("bright", 0.5f, 30.0f, new String[]{"intensity"}, new double[]{6.0});
        rangeIs("dist", 4.0f, 96.0f, new String[]{"radius"}, new double[]{36.0});
        rangeIs("atten", 0.2f, 20.0f, new String[]{"attenK"}, new double[]{20.0});
        rangeIs("knee", 0.2f, 8.0f, new String[]{"kneeGain"}, new double[]{2.0});
        rangeIs("beam", 0.0f, 1.0f, new String[]{"beamDensity"}, new double[]{0.25});
        rangeIs("scat", 0.0f, 0.9f, new String[]{"scatFloor"}, new double[]{0.04});
        rangeIs("beamcap", 0.25f, 8.0f, new String[]{"beamCapM"}, new double[]{1.0});
        rangeIs("cone", 2.0f, 45.0f, new String[]{"coneOuterDeg", "coneInnerDeg"}, new double[]{8.0, 4.0});
        rangeIs("voxel", 0.0f, 0.0f, new String[]{"voxelEnabled"}, new double[]{1.0});
        rangeIs("held", 0.0f, 15.0f, new String[]{"heldLightLevel"}, new double[]{10.0});
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

        // held:整数键(0..15)+ 默认 10 单一真源 + 枪灯消费点同源。
        {
            FakeSink s = new FakeSink();
            FakeGate g = new FakeGate();
            check(LightTuneOverride.heldLevel() == LightTuneOverride.DEFAULT_HELD_LEVEL
                            && LightTuneOverride.DEFAULT_HELD_LEVEL == 10,
                    "held 默认 10(DEFAULT_HELD_LEVEL 单一真源)");
            check(GunControl.gunBlockLightEmission(true, true, true, false) == 10,
                    "枪灯消费点默认取同一真源 = 10");
            TuneService.Result r = TuneService.tune("held", "12", s, g, REAL);
            check(r.ok() && LightTuneOverride.heldLevel() == 12, "held 12 生效");
            check(s.ints.get("heldLightLevel") == 12 && s.sets.contains("heldLightLevel=12"),
                    "held 写整数 12(不是 12.0): " + s.sets);
            check(s.saves == 1, "held set 一次 save");
            check(GunControl.gunBlockLightEmission(true, true, true, false) == 12,
                    "枪灯消费点跟随 held 覆盖(与手电同源)");
            check(TuneService.tune("held", "0", s, g, REAL).ok() && LightTuneOverride.heldLevel() == 0,
                    "held 0 = 关掉氛围底光(0 是合法值,不是 clear 语义)");
            check(TuneService.tune("held", "15", s, g, REAL).ok() && LightTuneOverride.heldLevel() == 15,
                    "held 15 = 火把级(上端点合法)");
            int saves = s.saves;
            // 小数必须拒绝:方块光等级是整数,静默取整 = "回显 12 但你填 12.5" 的假成功
            TuneService.Result frac = TuneService.tune("held", "12.5", s, g, REAL);
            check(!frac.ok() && LightTuneOverride.heldLevel() == 15 && s.saves == saves,
                    "held 拒绝小数且不污染当前值: " + frac.message());
            TuneService.Result over = TuneService.tune("held", "16", s, g, REAL);
            check(!over.ok() && LightTuneOverride.heldLevel() == 15 && s.saves == saves,
                    "held 上越界拒绝(16)");
            TuneService.Result under = TuneService.tune("held", "-1", s, g, REAL);
            check(!under.ok() && LightTuneOverride.heldLevel() == 15 && s.saves == saves,
                    "held 下越界拒绝(-1)");
            TuneService.Result clr = TuneService.tune("held", "clear", s, g, REAL);
            check(clr.ok() && LightTuneOverride.heldLevel() == 10
                            && s.ints.get("heldLightLevel") == 10,
                    "held clear 回默认 10 并写回整数 10");
            TuneService.tune("held", "clear", s, g, REAL);
        }

        // cone 一写二(inner=v×0.5)。
        {
            FakeSink s = new FakeSink();
            FakeGate g = new FakeGate();
            TuneService.Result r = TuneService.tune("cone", "10", s, g, REAL);
            check(r.ok() && Math.abs(s.doubles.get("coneOuterDeg") - 10.0) < 1e-9
                    && Math.abs(s.doubles.get("coneInnerDeg") - 5.0) < 1e-9,
                    "cone 一写二 outer=10/inner=5");
            TuneService.tune("cone", "clear", s, g, REAL);
            check(Math.abs(s.doubles.get("coneOuterDeg") - 8.0) < 1e-9
                    && Math.abs(s.doubles.get("coneInnerDeg") - 4.0) < 1e-9,
                    "cone clear 写回默认 8/4(防清除后仍是旧值)");
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

        // ---- 三层入口(2026-10-06 用户定案:先 help 概览 → list 索引 → <名> help 展开) ----
        {
            FakeSink s = new FakeSink();
            FakeGate g = new FakeGate();
            // 第 1 层:概览 = 它是什么 + 一条**真实可用**的示例 + 下一步指路
            TuneService.Result h0 = TuneService.tune(null, null, s, g, REAL);
            String hp = h0.message();
            check(h0.ok() && s.saves == 0, "无参 tune = 概览,零写盘");
            check(hp.contains("/taclight tune bright 12"), "概览含一条真实可用的示例(bright 12): " + hp);
            check(hp.contains("list") && hp.contains("<名> help") && hp.contains("status"),
                    "概览指路 list / <名> help / status");
            check(TuneService.tune("help", null, s, g, REAL).message().equals(hp)
                            && TuneService.tune("?", null, s, g, REAL).message().equals(hp),
                    "tune help / tune ? 与无参等价(同一层)");
            // 第 2 层:索引 = 有哪些可调(只给短标签,不给数值/范围 —— 与 status 不重复)
            TuneService.Result l = TuneService.tune("list", null, s, g, REAL);
            String ls = l.message();
            check(l.ok() && s.saves == 0, "list 只读零写盘");
            check(ls.contains("可调项(" + TuneKnobs.all().size() + ")"), "索引写明可调项数量: " + ls.split("\n")[0]);
            // 层与层不重复:索引只给标签,不含数值字段(判据取 status 表专用的分隔符与"默认 "字段)
            check(!ls.contains(" = ") && !ls.contains("默认 "),
                    "索引只给标签,不含数值字段(那是 status 的活;层与层不重复)");
            for (TuneKnobs.Knob k : TuneKnobs.all()) {
                check(ls.contains("\n  " + k.name()) && ls.contains(k.label()),
                        "索引含 " + k.name() + " 及其短标签 '" + k.label() + "'");
            }
            check(ls.contains("cone help"), "索引给出 选一个加 help 的示例: /taclight tune cone help");
            // 第 3 层:单项展开(范围/默认/当前 + 调大调小 + 讲解 + 用法)
            for (TuneKnobs.Knob k : TuneKnobs.all()) {
                TuneService.Result kh = TuneService.tune(k.name(), "help", s, g, REAL);
                String km = kh.message();
                check(kh.ok() && km.contains(k.name() + " help"), k.name() + " help 可展开");
                check(km.contains(k.label()) && km.contains("范围") && km.contains("默认")
                                && km.contains("当前"),
                        k.name() + " help 含标签/范围/默认/当前值");
                check(km.contains(k.hint()) && km.contains(k.note()) && km.contains("用法"),
                        k.name() + " help 含调大调小 + 讲解 + 用法");
            }
            check(s.saves == 0, "三层入口全程零写盘");
            // 顺手写法:help <名> == <名> help
            check(TuneService.tune("help", "cone", s, g, REAL).message()
                            .equals(TuneService.tune("cone", "help", s, g, REAL).message()),
                    "help <名> 与 <名> help 等价");
            // 保留字不可与旋钮名相撞(撞了分层入口会被旋钮吃掉)
            for (String r : TuneService.RESERVED) {
                check(TuneKnobs.byName(r) == null, "保留字 '" + r + "' 不是旋钮名");
            }
            // list/status 不接受参数(不静默忽略)
            TuneService.Result lx = TuneService.tune("list", "12", s, g, REAL);
            check(!lx.ok() && lx.message().contains("不接受参数"), "list 带参拒绝: " + lx.message());
            TuneService.Result sx = TuneService.tune("status", "12", s, g, REAL);
            check(!sx.ok() && sx.message().contains("不接受参数"), "status 带参拒绝");
            // 未知旋钮指路索引(不再一股脑把名字全倒出来)
            TuneService.Result u = TuneService.tune("brite", "12", s, g, REAL);
            check(!u.ok() && u.message().contains("未知旋钮") && u.message().contains("/taclight tune list")
                    && s.saves == 0, "未知旋钮指路 list,零写盘: " + u.message());
        }

        // 看数:status(全部当前值,四项表)。
        {
            FakeSink s = new FakeSink();
            FakeGate g = new FakeGate();
            TuneService.Result all = TuneService.statusAll(g, REAL);
            check(all.ok() && all.message().startsWith("[TacLight]") && s.saves == 0,
                    "全状态只读零写盘");
            check(TuneService.tune("status", null, s, g, REAL).message().equals(all.message()),
                    "tune status == statusAll(同一个看数入口)");
            check(all.ok() && all.message().startsWith("[TacLight]") && s.saves == 0,
                    "全状态只读零写盘");
            // 2026-10-06 用户定案:每行四项齐全 —— 当前值 / 范围 / 默认值 / 调大调小说明。
            String[] lines = all.message().split("\n");
            for (TuneKnobs.Knob k : TuneKnobs.all()) {
                String line = null;
                for (String l : lines) {
                    if (l.startsWith("  " + k.name() + " ")) { line = l; break; }
                }
                check(line != null, "全状态含 " + k.name());
                if (line == null) continue;
                check(line.contains("范围") && line.contains("默认") && line.contains(k.hint())
                                && line.contains(k.clearTo()),
                        k.name() + " 四项齐全(当前值/范围/默认值/调大调小): " + line);
            }
        }

        // ---- 2026-10-06 用词/文案闸门(用户反馈:"off 看不懂" + "括号说明没意义") ----
        {
            FakeSink s = new FakeSink();
            FakeGate g = new FakeGate();
            String st = TuneService.tune("bright", "status", s, g, REAL).message();
            check(!st.contains("off("), "status 回显不再出现 off(...)(off 不是可填的值): " + st);
            check(st.contains("未覆盖"), "status 回显写明覆盖态(未覆盖/已覆盖): " + st);
            check(st.contains("调大") && st.contains("调小"), "status 回显含调大/调小说明: " + st);
            check(st.contains("clear"), "status 回显指路 clear(清除覆盖): " + st);
            check(st.contains("范围") && st.contains("默认"), "status 回显含范围与默认值字段: " + st);
            String usageMsg = TuneService.tune("help", null, s, g, REAL).message();
            check(usageMsg.contains("clear") && !usageMsg.contains("off=回默认"),
                    "概览文案用 clear(旧词 off 不再出现在文案里): " + usageMsg);
            check(usageMsg.contains("voxel 是字面开关"), "概览区分 voxel 的 on/off 字面语义");
            // 三层文案各自的职责(每个旋钮):短标签 / 调大调小 / 讲解 都要有且不空
            for (TuneKnobs.Knob k : TuneKnobs.all()) {
                check(k.label() != null && !k.label().isEmpty() && k.label().length() <= 12,
                        k.name() + " 有短标签(索引一行扫得完): " + k.label());
                check(k.note() != null && k.note().length() >= 10,
                        k.name() + " 有展开讲解(note,help 用): " + k.note());
            }
            // held 的讲解必须点名它到底是什么(术语)与两条边界(用户 2026-10-06 专门问过)
            TuneKnobs.Knob hk = TuneKnobs.byName("held");
            check(hk.note().contains("heldBlockLightValue") && hk.note().contains("Dynamic Handheld Lighting"),
                    "held 讲解点名术语(heldBlockLightValue / Dynamic Handheld Lighting)");
            check(hk.note().contains("不影响锥形主光") && hk.note().contains("整数"),
                    "held 讲解写明边界(不影响锥形主光 / 整数): " + hk.note());
            // 每个旋钮都必须有"调大/调小"(voxel 是布尔,走 on/off 说明)。
            for (TuneKnobs.Knob k : TuneKnobs.all()) {
                boolean ok = TuneKnobs.isBoolean(k)
                        ? (k.hint().contains("开=") && k.hint().contains("关="))
                        : (k.hint().contains("调大") && k.hint().contains("调小"));
                check(ok, k.name() + " 有调大/调小(或开/关)说明: " + k.hint());
            }
            // voxel 的 clear 必须**明确拒绝**并指路 on(不许静默当成 off —— 那正是用户困惑的来源)。
            TuneService.Result vc = TuneService.tune("voxel", "clear", s, g, REAL);
            check(!vc.ok() && vc.message().contains("回默认请"), "voxel clear 拒绝并指路 on: " + vc.message());
            check(s.saves == 0, "voxel clear 被拒后零写盘");
            // 覆盖层的旧词 off 与正名 clear 在**同一层**必须等价(别名不是第二个语义)。
            String c1 = LightTuneOverride.configureBeam("clear");
            LightTuneOverride.configureBeam("0.7");
            String c2 = LightTuneOverride.configureBeam("off");
            check(c1.equals(c2), "override 层 clear/off 回显一致: " + c1 + " vs " + c2);
            LightTuneOverride.configureBeam("clear");
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
            s.readInts.put("heldLightLevel", 12);
            s.readBools.put("voxelEnabled", false);
            FakeGate g = new FakeGate();
            List<String> applied = new ArrayList<>();
            String report = TunePersist.restore(s, g, (knob, arg) -> {
                applied.add(knob + "=" + arg);
                return REAL.apply(knob, arg);
            });
            check(applied.contains("atten=1.5") && applied.contains("held=12") && applied.size() == 2,
                    "restore 只回填非默认值(atten=1.5 与整数键 held=12;实际 " + applied + ")");
            check(Math.abs(LightTuneOverride.heldLevel() - 12) < 1e-9, "restore 回填整数键 held=12 生效");
            check(Math.abs(LightTuneOverride.attenK() - 1.5f) < 1e-6, "restore 回填生效");
            check(Boolean.FALSE.equals(g.restored), "restore 恢复 voxel=off");
            check(report.contains("atten=1.5") && report.contains("held=12") && report.contains("voxel=off"),
                    "restore 日志行(含整数键 held=12): " + report);
        }

        // ---- 接线(源码文本,旧码必红) ----
        // 2026-10-06 发布面回归:路径从 dev/taclight/debug/command/TacLightCommand.java
        // 改为 dev/taclight/command/TacLightCommand.java —— **命令类若再被搬回 debug 包,
        // 本行 read() 直接抛异常(文件不存在),不是静默跳过**;构件层另由
        // InteropPackagingContract + HudCommandContract 的正控闸门钉死。
        String cmd = read("src/main/java/dev/taclight/command/TacLightCommand.java");
        check(cmd.contains(".literal(\"tune\")"), "命令树含 tune 分支(删分支即红)");
        check(cmd.contains("isDedicatedServer"), "MP 守卫在位(删守卫即红)");
        check(cmd.contains("仅单人/客户端生效"), "MP 回显文案在位");
        check(cmd.contains("[TacLight] TUNE"), "日志 [TacLight] 前缀在位(grep 依赖)");
        check(cmd.contains("StringArgumentType.string()"), "数值用 string()(word 拒 '.')");
        check(cmd.contains("CMDS RegisterCommandsEvent firing"), "可观测标记在位(SubscriberScopeContract 依赖)");
        check(cmd.contains("dev.taclight.debug.command.DebugCommandChildren"),
                "dev-only 子命令走反射挂载(发布件里该类不存在 ⇒ 命令树少几支而不崩)");
        // 2026-10-06:加 held 时差点漏接**生产** applier(TUNE_APPLIER)—— 本契约的 REAL applier
        // 漏接当场被行为断言抓到,生产侧没有等价行为断言 ⇒ 用源码文本把"每个旋钮都有分支"钉死。
        // voxel 例外:它走客户端门(TuneClientGate),不进 applier。
        for (TuneKnobs.Knob k : TuneKnobs.all()) {
            if (TuneKnobs.isBoolean(k)) continue;
            check(cmd.contains("case \"" + k.name() + "\" ->"),
                    "生产 TUNE_APPLIER 含 " + k.name() + " 分支(加旋钮必须同时接生产入口)");
        }
        // 发布面命令树**真建树**断言(不是源码文本):brigadier 树里必须有 /taclight tune
        // 且 tune 下 name/value 两级参数在位。buildRoot() 是纯构建、零副作用、不加载 dev-only 类。
        var root = dev.taclight.command.TacLightCommand.buildRoot().build();
        var tune = root.getChild("tune");
        check(tune != null, "[建树] /taclight tune 在 brigadier 树里(发布面命令树真的建出来了)");
        check(tune != null && tune.getChild("name") != null && tune.getChild("name").getChild("value") != null,
                "[建树] tune 下 name/value 两级参数在位(数值含 '.' 靠 string() 收)");
        check(root.getChild("kit") == null,
                "[建树] buildRoot() 不含 dev-only 分支(kit 由 DebugCommandChildren 反射挂载)");
        String cfg = read("src/main/java/dev/taclight/config/TacLightConfig.java");
        for (String key : new String[]{"\"attenK\"", "\"kneeGain\"", "\"scatFloor\"", "\"beamCapM\"",
                "\"voxelEnabled\"", "\"heldLightLevel\"", "ATTEN_K", "KNEE_GAIN", "SCAT_FLOOR",
                "BEAM_CAP_M", "VOXEL_ENABLED", "HELD_LIGHT_LEVEL"}) {
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
        // clear:清除覆盖回默认 + 写回默认值(2026-10-06 正名;off 是兼容别名,下面单验等价)。
        TuneService.Result clear = TuneService.tune(name, "clear", s, g, REAL);
        check(clear.ok() && backToDefault.get() && s.sets.contains(key + "=" + defVal),
                name + " clear 回默认并写回 " + key + "=" + defVal);
        TuneService.Result offAlias = TuneService.tune(name, "off", s, g, REAL);
        check(offAlias.ok() && backToDefault.get(), name + " 旧写法 off == clear(别名等价)");
        // 边界端点合法(同源双向对账:覆盖层改范围不改表即红)。
        TuneKnobs.Knob k = TuneKnobs.byName(name);
        FakeSink s2 = new FakeSink();
        check(TuneService.tune(name, Float.toString(k.min()), s2, g, REAL).ok(),
                name + " 下端点 " + k.min() + " 合法");
        check(TuneService.tune(name, Float.toString(k.max()), s2, g, REAL).ok(),
                name + " 上端点 " + k.max() + " 合法");
        TuneService.tune(name, "clear", new FakeSink(), g, REAL);
    }

    private static void resetAll() {
        LightTuneOverride.configureBright("clear");
        LightTuneOverride.configureDist("clear");
        LightTuneOverride.configureAtten("clear");
        LightTuneOverride.configureKnee("clear");
        LightTuneOverride.configureBeam("clear");
        LightTuneOverride.configureScat("clear");
        LightTuneOverride.configureBeamcap("clear");
        LightTuneOverride.configureCone("clear");
        LightTuneOverride.configureHeld("clear");
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

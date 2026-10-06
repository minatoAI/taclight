package dev.taclight.tune;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /taclight tune} 旋钮注册表(2026-09-19 八旋钮晋升正式命令;2026-10-06 加 held 共十旋钮)。
 *
 * <p>纯数据 + 纯逻辑:零 Forge / 零 MC 引用,JVM 契约可直驱。范围与
 * {@code LightTuneOverride} 同源(改覆盖层范围必须同步改这里,契约双向钉死);
 * {@code configKeys} 是 {@code config/taclight-client.toml [spotlight]} 小节内的
 * toml 键名(不是 Java 字段名)。{@code kind} 决定持久化路由:
 * DOUBLE → {@code Sink.setDouble}、INT → {@code Sink.setInt}、BOOL → {@code Sink.setBoolean}
 * (int 键目前只有 {@code heldLightLevel};Forge 的 {@code ConfigValue<Integer>} 用 set(double) 会炸)。</p>
 *
 * <p><b>三层文案</b>(2026-10-06 用户定案"命令调节有点杂乱,把它分层"):
 * <ul>
 *   <li>{@code label} —— 索引({@code tune list})里的短标签,一行一个;</li>
 *   <li>{@code hint} —— "调大/调小会怎样"({@code status} 与 {@code <名> help} 都带);</li>
 *   <li>{@code note} —— {@code <名> help} 才展开的补充讲解(坑/边界/联动),每个旋钮都必须有。</li>
 * </ul>
 * 三层分开的理由:索引要能一眼扫完(短),status 要能对着调(中),help 要能讲清楚为什么(长)——
 * 一份文案同时干三件事,就会变成又长又读不完的一坨(改造前的 {@code usage()} 就是这样)。</p>
 *
 * <p>持久化二分(help/status 如实说明,不许"假持久"):
 * <ul>
 *   <li><b>真持久</b>(10 旋钮全覆盖):bright/dist/beam/cone 复用既有键
 *       (intensity/radius/beamDensity/coneOuterDeg+coneInnerDeg);
 *       atten/knee/scat/beamcap/voxel 用 2026-09-19 新增键;
 *       held 用 2026-10-06 新增整数键 heldLightLevel。</li>
 *   <li><b>内存态(未晋升)</b>:beamonly/occl/tm 仍只走文件命令中继,不进本表——
 *       beamonly 是调试观察位(关表面照明,正式命令里放出来会被当"灯坏了");
 *       occl/tm 是性能 A/B 开关(默认开,off=慢速对照,持久化会把"慢"带过重启)。</li>
 * </ul></p>
 *
 * <p><b>2026-10-06 用词改造(用户反馈)</b>:数值旋钮"清除覆盖、回默认值"的动词统一为
 * {@code clear}(旧写法 {@code off} 仍接受,是兼容别名)。原先把该动作叫 {@code off},
 * 用户会把状态回显里的 {@code off(默认 intensity=6.0)} 读成"一个可以填的值",
 * 而且与 {@code voxel} 的字面开关 {@code off} 撞词 —— 一个词两种语义,是设计缺陷不是文案瑕疵。
 * 现在:数值旋钮 = {@code clear};voxel 保留 {@code on/off} 字面开关(回默认请 {@code on})。</p>
 */
public final class TuneKnobs {
    /** 持久化类型:决定写盘走 Sink 的哪个方法(见类注释)。 */
    public enum Kind { DOUBLE, INT, BOOL }

    /**
     * 旋钮元数据:name=命令名;kind=持久化类型;min/max=合法域(含端点);
     * clearTo=clear(清除覆盖)后回到的有效值文案;label/hint/note=三层文案(见类注释)。
     */
    public record Knob(String name, Kind kind, float min, float max, String clearTo,
                       String[] configKeys, double[] configDefaults,
                       String label, String hint, String note) {}

    private static final Map<String, Knob> BY_NAME = new LinkedHashMap<>();

    private static void put(Knob k) {
        BY_NAME.put(k.name(), k);
    }

    private static void add(String name, String label, float min, float max, String clearTo,
                            String[] configKeys, double[] configDefaults, String hint, String note) {
        put(new Knob(name, Kind.DOUBLE, min, max, clearTo, configKeys, configDefaults, label, hint, note));
    }

    /** 整数旋钮(min/max 仍是 float 域,但值域是整数;覆盖层负责拒绝小数)。 */
    private static void addInt(String name, String label, float min, float max, String clearTo,
                               String[] configKeys, double[] configDefaults, String hint, String note) {
        put(new Knob(name, Kind.INT, min, max, clearTo, configKeys, configDefaults, label, hint, note));
    }

    /** 布尔旋钮:min/max 占位(validate 不走数值域,走 on/off 枚举)。 */
    private static void addBool(String name, String label, String clearTo,
                                String[] configKeys, double[] configDefaults, String hint, String note) {
        put(new Knob(name, Kind.BOOL, 0.0f, 0.0f, clearTo, configKeys, configDefaults, label, hint, note));
    }

    static {
        add("bright", "亮度", 0.5f, 30.0f, "6.0",
                new String[]{"intensity"}, new double[]{6.0},
                "调大=更亮、照得更远;调小=更暗、照得更近",
                "亮度是基准值,照距按 √(亮度/6) 自动跟着变;想直接指定照距用 dist(它绕过这个耦合)。");
        add("dist", "照距", 4.0f, 96.0f, "36.0",
                new String[]{"radius"}, new double[]{36.0},
                "调大=照得更远(上限96格);调小=照得更近",
                "直接指定照距(格),不受亮度耦合影响;上限 96 格。和 bright 同时改时,照距以 dist 为准。");
        add("atten", "衰减(尾巴长短)", 0.2f, 20.0f, "20.0",
                new String[]{"attenK"}, new double[]{20.0},
                "调大=衰减更快、远处更暗(尾巴更短);调小=衰减更慢、远处更亮(尾巴更长)",
                "K 越小尾巴拖得越长。20.0 是主包标定值(2026-09-06 扫参冻结),clear 回它。");
        add("knee", "近场软肩(贴脸过曝)", 0.2f, 8.0f, "2.0",
                new String[]{"kneeGain"}, new double[]{2.0},
                "调大=近处压得更狠(贴脸过曝更少);调小=近处更亮",
                "治的是\"站在光路里贴脸过曝\"的近场压缩,默认即开(2.0),不是一个开关。");
        add("beam", "体积光柱浓淡", 0.0f, 1.0f, "0.25",
                new String[]{"beamDensity"}, new double[]{0.25},
                "调大=光柱更浓;调小=更淡(0=完全关光柱)",
                "显式 0 就是关掉光柱(合法值,不会回退默认)——这点与 atten/knee 的 0 哨兵语义不同。");
        add("scat", "光柱轴向底亮", 0.0f, 0.9f, "0.04",
                new String[]{"scatFloor"}, new double[]{0.04},
                "调大=光柱轴向更亮(更像实体光);调小=更偏侧面丁达尔",
                "0=纯侧面丁达尔(正对光源零体积叠加);调大把光柱填实,代价是正面看可能发灰。");
        add("beamcap", "多灯重叠上限", 0.25f, 8.0f, "1.0",
                new String[]{"beamCapM"}, new double[]{1.0},
                "调大=多灯重叠更亮(软上限更高);调小=重叠更容易被压暗",
                "软上限 cap=2.0×m。单灯低于半帽点时恒等 ⇒ 只影响多灯重叠,单灯观感不受它影响。");
        add("cone", "光锥宽窄", 2.0f, 45.0f, "8/4",
                new String[]{"coneOuterDeg", "coneInnerDeg"}, new double[]{8.0, 4.0},
                "调大=光锥更宽(散);调小=更窄(更接近平行光)",
                "一写二:设外锥 v 会把内锥一起写成 v×0.5(内锥=全亮核心)。8/4 是\"接近平行光\"档。");
        addBool("voxel", "体素遮挡开关", "on",
                new String[]{"voxelEnabled"}, new double[]{1.0},
                "开=方块遮挡生效(推荐);关=回退SSO老路径(慢速对照,仅调试)",
                "字面开关:off 是**禁用遮挡**(不是回默认),回默认请 on。关掉会回退 SSO 老路径,明显更慢,"
                        + "只用于性能对照。");
        // held:Iris/Oculus 的 heldBlockLightValue(手持光照值,方块光等级量纲 0..15)。
        // 与其余旋钮**不同族**:消费点是物品/枪的 getLightEmission(光影包用它算玩家周围氛围光,
        // Complementary 的 Dynamic Handheld Lighting),不是 SSBO 里的锥形主光 ⇒ 锥形光不受它影响。
        addInt("held", "手持光照值(氛围底光)", 0.0f, 15.0f, "10",
                new String[]{"heldLightLevel"}, new double[]{10.0},
                "调大=周围氛围光更亮(接近火把);调小=更暗(0=只留锥形光,不影响锥形主光)",
                "Iris/Oculus 的 heldBlockLightValue(手持方块光值):光影包用它算玩家周围那圈暖氛围底光"
                        + "(Complementary 里叫 Dynamic Handheld Lighting,默认开)。整数(方块光等级没有小数);"
                        + "只在包选项开着时可见;不影响锥形主光(那条走 SSBO)。默认 10。");
    }

    private TuneKnobs() {}

    /** 命令名查表(大小写不敏感);未知返回 null(调用方回索引)。 */
    public static Knob byName(String name) {
        if (name == null) return null;
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** 十旋钮(注册顺序=list/status/help 展示顺序)。 */
    public static List<Knob> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_NAME.values()));
    }

    /** "bright/dist/atten/..."(错误文案用)。 */
    public static String names() {
        return String.join("/", BY_NAME.keySet());
    }

    /** 是否布尔旋钮(voxel)。 */
    public static boolean isBoolean(Knob knob) {
        return knob.kind() == Kind.BOOL;
    }

    /** 是否整数旋钮(held);持久化层据此走 Sink.setInt/getInt。 */
    public static boolean isInt(Knob knob) {
        return knob.kind() == Kind.INT;
    }

    /** config 键是否整数型(持久化层按**键**路由,见 {@code TunePersist.writePairs/restore})。 */
    public static boolean isIntKey(String key) {
        if (key == null) return false;
        for (Knob k : BY_NAME.values()) {
            if (k.kind() == Kind.INT && k.configKeys().length > 0 && k.configKeys()[0].equals(key)) return true;
        }
        return false;
    }

    /** 名称对齐宽度(索引/全状态表用;最长名 beamcap=7)。 */
    public static int nameWidth() {
        int w = 0;
        for (String n : BY_NAME.keySet()) w = Math.max(w, n.length());
        return w;
    }

    /** 标签对齐宽度(索引用;中文按 1 计,只是排版近似)。 */
    public static int labelWidth() {
        int w = 0;
        for (Knob k : BY_NAME.values()) w = Math.max(w, k.label().length());
        return w;
    }
}

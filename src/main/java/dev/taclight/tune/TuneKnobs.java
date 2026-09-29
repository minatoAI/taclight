package dev.taclight.tune;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /taclight tune} 旋钮注册表(2026-09-19 八旋钮晋升正式命令)。
 *
 * <p>纯数据 + 纯逻辑:零 Forge / 零 MC 引用,JVM 契约可直驱。范围与
 * {@code LightTuneOverride} 同源(改覆盖层范围必须同步改这里,契约双向钉死);
 * {@code configKeys} 是 {@code config/taclight-client.toml [spotlight]} 小节内的
 * toml 键名(不是 Java 字段名),空数组 = 内存态(见下)。</p>
 *
 * <p>持久化二分(help/status 如实说明,不许"假持久"):
 * <ul>
 *   <li><b>真持久</b>(9 旋钮全覆盖):bright/dist/beam/cone 复用既有键
 *       (intensity/radius/beamDensity/coneOuterDeg+coneInnerDeg);
 *       atten/knee/scat/beamcap/voxel 用 2026-09-19 新增键
 *       (attenK/kneeGain/scatFloor/beamCapM/voxelEnabled)。</li>
 *   <li><b>内存态(未晋升)</b>:beamonly/occl/tm 仍只走文件命令中继,不进本表——
 *       beamonly 是调试观察位(关表面照明,正式命令里放出来会被当"灯坏了");
 *       occl/tm 是性能 A/B 开关(默认开,off=慢速对照,持久化会把"慢"带过重启)。</li>
 * </ul></p>
 *
 * <p>voxel 语义不对称(契约钉死):数值旋钮的 {@code off}=回默认值;
 * voxel 的 on/off 是<b>字面开关</b>(默认 on),off=禁用并持久化 {@code voxelEnabled=false},
 * 回默认请 {@code tune voxel on}。</p>
 */
public final class TuneKnobs {
    /** 旋钮元数据:name=命令名;min/max=合法域(含端点);offDefault=off 回到的有效值文案。 */
    public record Knob(String name, float min, float max, String offDefault,
                       String[] configKeys, double[] configDefaults, String help) {}

    private static final Map<String, Knob> BY_NAME = new LinkedHashMap<>();

    private static void add(String name, float min, float max, String offDefault,
                            String[] configKeys, double[] configDefaults, String help) {
        BY_NAME.put(name, new Knob(name, min, max, offDefault, configKeys, configDefaults, help));
    }

    static {
        add("bright", 0.5f, 30.0f, "6.0",
                new String[]{"intensity"}, new double[]{6.0},
                "绝对亮度(与 intensity 同域)");
        add("dist", 4.0f, 96.0f, "36.0",
                new String[]{"radius"}, new double[]{36.0},
                "绝对照距/格(bypass √亮度耦合,钳制≤96)");
        add("atten", 0.2f, 20.0f, "20.0",
                new String[]{"attenK"}, new double[]{20.0},
                "衰减系数K(越小尾越长;20.0=主包标定,2026-09-06冻结)");
        add("knee", 0.2f, 8.0f, "2.0",
                new String[]{"kneeGain"}, new double[]{2.0},
                "近场软肩G(越大近场压得越狠;默认即开)");
        add("beam", 0.0f, 1.0f, "0.25",
                new String[]{"beamDensity"}, new double[]{0.25},
                "体积光束密度(0=完全关光束;与atten/knee的0哨兵不同,显式0就是关)");
        add("scat", 0.0f, 0.9f, "0.04",
                new String[]{"scatFloor"}, new double[]{0.04},
                "体积光轴向底亮份额f(0=纯侧面丁达尔)");
        add("beamcap", 0.25f, 8.0f, "1.0",
                new String[]{"beamCapM"}, new double[]{1.0},
                "体积光重叠软上限倍率m(cap=2.0×m;单灯恒等)");
        add("cone", 2.0f, 45.0f, "8/4",
                new String[]{"coneOuterDeg", "coneInnerDeg"}, new double[]{8.0, 4.0},
                "外锥半角/度(内锥=外×0.5;off=直通config 8/4)");
        // voxel:布尔旋钮,min/max 占位(validate 不走数值域,走 on/off 枚举)。
        add("voxel", 0.0f, 0.0f, "on",
                new String[]{"voxelEnabled"}, new double[]{1.0},
                "体素DDA遮挡总开关(off=回退SSO)");
    }

    private TuneKnobs() {}

    /** 命令名查表(大小写不敏感);未知返回 null(调用方回用法)。 */
    public static Knob byName(String name) {
        if (name == null) return null;
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** 九旋钮(注册顺序=help 展示顺序)。 */
    public static List<Knob> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_NAME.values()));
    }

    /** "bright/dist/atten/..."(help/错误文案用)。 */
    public static String names() {
        return String.join("/", BY_NAME.keySet());
    }

    /** 是否布尔旋钮(目前仅 voxel)。 */
    public static boolean isBoolean(Knob knob) {
        return "voxel".equals(knob.name());
    }
}

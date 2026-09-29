package dev.taclight.tune;

import dev.taclight.TacLightMod;
import dev.taclight.config.TacLightConfig;

/**
 * tune 持久化层(2026-09-19):覆盖层(即时生效) + toml(重启保留)双写。
 *
 * <p>可测性设计:FORTUNE——本仓此前<b>零</b> {@code ForgeConfigSpec.set/save} 先例
 * (grep 全仓无命中),{@code SPEC.save()} 是否存在、CLIENT spec 在集成服线程 set
 * 是否落盘,均<b>未经构建验证</b>。故 Forge 接触面收敛到 {@link Sink} 一个接口:
 * JVM 契约跑假 Sink(断言"写了哪个键/什么值/save 调了几次"),真机跑 {@link #forgeSink()}。
 * save 失败不吞:经 {@link SaveOutcome} 如实回显"内存已生效但重启不保留",不静默假成功。</p>
 *
 * <p>双写语义(契约钉死):
 * <ul>
 *   <li>set 值:覆盖层激活 + 每个 config 键写入 + save。cone 一写二
 *       (outer=v,inner=v×0.5);voxel 写布尔。</li>
 *   <li>off:覆盖层失活 + config 键<b>写回默认值</b> + save(否则"off 后仍是上次调参值"
 *       = 静默假成功;voxel 例外:on/off 字面语义,off 写 {@code false})。</li>
 *   <li>status/无参/help:只读,<b>零</b> sink 接触(含 save 也不调)。</li>
 * </ul></p>
 *
 * <p>开机恢复 {@link #restore}:只处理新增键(atten/knee/scat/beamcap/voxel)——
 * 既有键(bright/dist/beam/cone)的消费者每帧直读 config,无需回填;新增键的消费者
 * 只认覆盖层/GSL 默认,故非默认值才回填激活(默认值=off 态,回填与否效果一致,
 * 不回填则 status 保持诚实的 off)。调用点见 ClientEvents(客户端首 tick 一次性)。</p>
 */
public final class TunePersist {
    /** Forge/假两用存储口:键 = toml 键名(如 "intensity"),与 TuneKnobs.configKeys 同源。 */
    public interface Sink {
        void setDouble(String key, double value);
        void setBoolean(String key, boolean value);
        double getDouble(String key, double fallback);
        boolean getBoolean(String key, boolean fallback);
        /** 返回 ""=成功,非空=失败原因(调用方如实回显)。 */
        String save();
    }

    /** 体素开关门(生产走 VoxelGrid;契约走假门——VoxelGrid 带 client 引用,JVM 合约不碰)。 */
    public interface VoxelGate {
        /** 与 VoxelGrid.configure 同语义:on/off 切换,其它回状态。返回状态串。 */
        String apply(String arg);
        String status();
        void restore(boolean on);
    }

    /** save 结果(命令回显用)。 */
    public record SaveOutcome(boolean ok, String detail) {}

    private TunePersist() {}

    // ---- 路由:数值旋钮 set ----

    /** set 值后应写入的 (键,值) 对;cone 一写二。调用方先做覆盖层校验,通过才调。 */
    public static String[][] setPairs(TuneKnobs.Knob knob, double value) {
        String[] keys = knob.configKeys();
        if ("cone".equals(knob.name())) {
            return new String[][]{{keys[0], Double.toString(value)}, {keys[1], Double.toString(value * 0.5)}};
        }
        return new String[][]{{keys[0], Double.toString(value)}};
    }

    /** off 后应写回的 (键,默认值) 对;voxel 不走这里(字面语义,见 setVoxel)。 */
    public static String[][] offPairs(TuneKnobs.Knob knob) {
        String[] keys = knob.configKeys();
        double[] defs = knob.configDefaults();
        String[][] out = new String[keys.length][2];
        for (int i = 0; i < keys.length; i++) out[i] = new String[]{keys[i], Double.toString(defs[i])};
        return out;
    }

    /** 执行 setPairs/offPairs 的写盘(含 save),返回 outcome(失败也返回,不抛)。 */
    public static SaveOutcome writePairs(Sink sink, String[][] pairs) {
        try {
            for (String[] kv : pairs) sink.setDouble(kv[0], Double.parseDouble(kv[1]));
            String err = sink.save();
            if (err != null && !err.isEmpty()) return new SaveOutcome(false, err);
            return new SaveOutcome(true, "ok");
        } catch (Throwable t) {
            return new SaveOutcome(false, t.toString());
        }
    }

    /** voxel 字面写盘。 */
    public static SaveOutcome writeVoxel(Sink sink, boolean on) {
        try {
            sink.setBoolean("voxelEnabled", on);
            String err = sink.save();
            if (err != null && !err.isEmpty()) return new SaveOutcome(false, err);
            return new SaveOutcome(true, "ok");
        } catch (Throwable t) {
            return new SaveOutcome(false, t.toString());
        }
    }

    // ---- 开机恢复(客户端首 tick 一次,见 ClientEvents) ----

    /**
     * 回填新增键的非默认值进覆盖层;voxel 总是恢复。返回日志行(调用方以 [TacLight] 前缀记日志)。
     * 既有键不碰(消费者直读 config)。
     */
    public static String restore(Sink sink, VoxelGate gate, KnobApplier applier) {
        StringBuilder sb = new StringBuilder("restored:");
        boolean any = false;
        for (TuneKnobs.Knob knob : TuneKnobs.all()) {
            if (TuneKnobs.isBoolean(knob)) continue;
            if (isLiveKey(knob)) continue;
            double cur;
            try {
                cur = sink.getDouble(knob.configKeys()[0], knob.configDefaults()[0]);
            } catch (Throwable t) {
                continue;
            }
            if (Math.abs(cur - knob.configDefaults()[0]) > 1e-9) {
                try {
                    applier.apply(knob.name(), Float.toString((float) cur));
                } catch (Throwable t) {
                    TacLightMod.LOGGER.warn("[TacLight] TUNE restore {}={} failed: {}", knob.name(), cur, t.toString());
                    continue;
                }
                sb.append(' ').append(knob.name()).append('=').append((float) cur);
                any = true;
            }
        }
        boolean voxel;
        try {
            voxel = sink.getBoolean("voxelEnabled", true);
        } catch (Throwable t) {
            voxel = true;
        }
        try {
            gate.restore(voxel);
        } catch (Throwable t) {
            TacLightMod.LOGGER.warn("[TacLight] TUNE restore voxel failed: {}", t.toString());
        }
        sb.append(voxel ? " voxel=on" : " voxel=off");
        if (!any) sb.append(" (numeric all default)");
        return sb.toString();
    }

    /** 覆盖层函数入口(生产=LightTuneOverride.configure*;契约=直调同函数,故恒等)。 */
    public interface KnobApplier {
        String apply(String knobName, String arg);
    }

    /** 消费者直读 config 的既有键:tune 只写盘,不回填。 */
    public static boolean isLiveKey(TuneKnobs.Knob knob) {
        String n = knob.name();
        return "bright".equals(n) || "dist".equals(n) || "beam".equals(n) || "cone".equals(n);
    }

    // ---- 生产 Sink(唯一碰 Forge 的地方) ----

    /** 生产 Sink:慎用——只在 SP/客户端线程经命令与恢复钩子调用;专用服永不走到(见命令 MP 守卫)。 */
    public static Sink forgeSink() {
        return new Sink() {
            @Override
            public void setDouble(String key, double value) {
                switch (key) {
                    case "intensity" -> TacLightConfig.INTENSITY.set(value);
                    case "radius" -> TacLightConfig.RADIUS.set(value);
                    case "beamDensity" -> TacLightConfig.BEAM_DENSITY.set(value);
                    case "coneOuterDeg" -> TacLightConfig.CONE_OUTER_DEG.set(value);
                    case "coneInnerDeg" -> TacLightConfig.CONE_INNER_DEG.set(value);
                    case "attenK" -> TacLightConfig.ATTEN_K.set(value);
                    case "kneeGain" -> TacLightConfig.KNEE_GAIN.set(value);
                    case "scatFloor" -> TacLightConfig.SCAT_FLOOR.set(value);
                    case "beamCapM" -> TacLightConfig.BEAM_CAP_M.set(value);
                    default -> throw new IllegalArgumentException("unknown tune key " + key);
                }
            }

            @Override
            public void setBoolean(String key, boolean value) {
                if ("voxelEnabled".equals(key)) {
                    TacLightConfig.VOXEL_ENABLED.set(value);
                    return;
                }
                throw new IllegalArgumentException("unknown tune key " + key);
            }

            @Override
            public double getDouble(String key, double fallback) {
                try {
                    return switch (key) {
                        case "intensity" -> TacLightConfig.INTENSITY.get();
                        case "radius" -> TacLightConfig.RADIUS.get();
                        case "beamDensity" -> TacLightConfig.BEAM_DENSITY.get();
                        case "coneOuterDeg" -> TacLightConfig.CONE_OUTER_DEG.get();
                        case "coneInnerDeg" -> TacLightConfig.CONE_INNER_DEG.get();
                        case "attenK" -> TacLightConfig.ATTEN_K.get();
                        case "kneeGain" -> TacLightConfig.KNEE_GAIN.get();
                        case "scatFloor" -> TacLightConfig.SCAT_FLOOR.get();
                        case "beamCapM" -> TacLightConfig.BEAM_CAP_M.get();
                        default -> fallback;
                    };
                } catch (Throwable t) {
                    return fallback;
                }
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                try {
                    if ("voxelEnabled".equals(key)) return TacLightConfig.VOXEL_ENABLED.get();
                    return fallback;
                } catch (Throwable t) {
                    return fallback;
                }
            }

            @Override
            public String save() {
                // FORTUNE:本仓零 save 先例;Forge 1.20 的 ForgeConfigSpec.save() 是否存在、
                // CLIENT spec 跨线程 set 后 save 是否落盘,均未经构建验证(用户禁构建)。
                // 失败走返回值,不抛(命令层如实回显"内存已生效但重启不保留")。
                try {
                    TacLightConfig.SPEC.save();
                    return "";
                } catch (Throwable t) {
                    TacLightMod.LOGGER.warn("[TacLight] TUNE toml save failed: {}", t.toString());
                    return t.toString();
                }
            }
        };
    }
}

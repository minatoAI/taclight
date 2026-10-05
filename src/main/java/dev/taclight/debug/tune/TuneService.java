package dev.taclight.debug.tune;

import dev.taclight.tune.TuneKnobs;
import dev.taclight.tune.TunePersist;

/**
 * {@code /taclight tune <name> [<value>|status|off]} 编排(2026-09-19)。
 *
 * <p>纯编排:JVM 契约以假 Sink/假 Gate 直驱(断言范围/越界/status/off 三态 +
 * 持久化路由);生产由 TacLightCommand 传入真 Sink({@link TunePersist#forgeSink()})、
 * 真 Gate(TuneClientGate) 与真覆盖层入口。MP 守卫不在这里(要服务端对象,见命令层)。</p>
 *
 * <p>校验策略:范围与 LightTuneOverride <b>同源</b>——合法性由各
 * {@code configure*} 的 {@code "range..."/"bad arg..."} 返回判定,本类不复制范围常量
 * (复制即漂移;范围常量只活在覆盖层 + TuneKnobs 展示元数据,契约双向对账)。
 * 覆盖层先行:校验失败直接返回错误,<b>不碰</b> sink(含 save 也不调)。</p>
 */
public final class TuneService {
    /** 编排结果:ok=命令返回 1 还是 0;message=已带 [TacLight] 前缀的回显+日志原文。 */
    public record Result(boolean ok, String message) {}

    private TuneService() {}

    public static Result tune(String name, String arg,
                              TunePersist.Sink sink, TunePersist.VoxelGate gate,
                              TunePersist.KnobApplier applier) {
        if (name == null || name.isEmpty() || "help".equalsIgnoreCase(name) || "?".equals(name)) {
            return new Result(true, usage());
        }
        TuneKnobs.Knob knob = TuneKnobs.byName(name);
        if (knob == null) {
            return new Result(false, "[TacLight] tune 未知旋钮 '" + name + "'。" + usage());
        }
        String a = arg == null ? "" : arg.trim();
        if (TuneKnobs.isBoolean(knob)) {
            return tuneVoxel(knob, a, sink, gate);
        }
        return tuneNumeric(knob, a, sink, applier);
    }

    /** 无参 tune = 九旋钮全状态 + 用法(只读,零 sink 接触)。 */
    public static Result statusAll(TunePersist.VoxelGate gate, TunePersist.KnobApplier applier) {
        StringBuilder sb = new StringBuilder("[TacLight] tune status (调参本机即时生效,已写 toml 重启保留):");
        for (TuneKnobs.Knob knob : TuneKnobs.all()) {
            String st;
            try {
                st = TuneKnobs.isBoolean(knob) ? gate.status() : applier.apply(knob.name(), "status");
            } catch (Throwable t) {
                st = "read-failed:" + t.toString();
            }
            sb.append('\n').append("  ").append(knob.name()).append(": ").append(st);
        }
        sb.append('\n').append(usageTail());
        return new Result(true, sb.toString());
    }

    public static String usage() {
        return "[TacLight] tune 用法: /taclight tune <" + TuneKnobs.names() + "> [<值>|status|off>]"
                + "(无参=status;off=回默认并写回toml;voxel 的 off=禁用,回默认请 on)。" + usageTail();
    }

    private static String usageTail() {
        return "例: /taclight tune bright 12 | /taclight tune cone status | /taclight tune atten off";
    }

    // ---- 数值旋钮 ----

    private static Result tuneNumeric(TuneKnobs.Knob knob, String arg,
                                      TunePersist.Sink sink, TunePersist.KnobApplier applier) {
        if (arg.isEmpty() || "status".equalsIgnoreCase(arg)) {
            String st = applier.apply(knob.name(), "status");
            return new Result(true, "[TacLight] tune " + knob.name() + " status: " + st + " (范围 "
                    + trimNum(knob.min()) + ".." + trimNum(knob.max()) + ",off→" + knob.offDefault() + ")");
        }
        if ("off".equalsIgnoreCase(arg)) {
            String st = applier.apply(knob.name(), "off");
            TunePersist.SaveOutcome out = TunePersist.writePairs(sink, TunePersist.offPairs(knob));
            return new Result(true, "[TacLight] tune " + knob.name() + " -> " + st + persistNote(knob, out, true));
        }
        String st = applier.apply(knob.name(), arg);
        if (st.startsWith("range") || st.startsWith("bad arg")) {
            return new Result(false, "[TacLight] tune " + knob.name() + " 拒绝 '" + arg + "': " + st
                    + " (范围 " + trimNum(knob.min()) + ".." + trimNum(knob.max()) + ",或 status/off)");
        }
        double v;
        try {
            v = Double.parseDouble(arg.trim());
        } catch (NumberFormatException e) {
            return new Result(false, "[TacLight] tune " + knob.name() + " 拒绝 '" + arg + "': 非数值");
        }
        TunePersist.SaveOutcome out = TunePersist.writePairs(sink, TunePersist.setPairs(knob, v));
        return new Result(true, "[TacLight] tune " + knob.name() + " -> " + st + persistNote(knob, out, false));
    }

    private static String persistNote(TuneKnobs.Knob knob, TunePersist.SaveOutcome out, boolean isOff) {
        String keys = String.join("+", knob.configKeys());
        if (out.ok()) {
            return " (已写入 taclight-client.toml [" + keys + "]"
                    + (isOff ? "=默认值" : "") + ",本机即时生效,重启保留)";
        }
        return " (覆盖层已生效,但 toml 写入失败:" + out.detail() + "——重启不保留!)";
    }

    // ---- voxel(布尔,不对称语义) ----

    private static Result tuneVoxel(TuneKnobs.Knob knob, String arg,
                                    TunePersist.Sink sink, TunePersist.VoxelGate gate) {
        if (arg.isEmpty() || "status".equalsIgnoreCase(arg)) {
            return new Result(true, "[TacLight] tune voxel status: " + gate.status()
                    + " (on/off 字面开关,默认 on;off=禁用并持久化)");
        }
        if ("on".equalsIgnoreCase(arg) || "off".equalsIgnoreCase(arg)) {
            boolean on = "on".equalsIgnoreCase(arg);
            String st = gate.apply(arg.toLowerCase(java.util.Locale.ROOT));
            TunePersist.SaveOutcome out = TunePersist.writeVoxel(sink, on);
            String note = out.ok() ? " (已写入 taclight-client.toml [voxelEnabled],重启保留)"
                    : " (覆盖层已生效,但 toml 写入失败:" + out.detail() + "——重启不保留!)";
            return new Result(true, "[TacLight] tune voxel -> " + st + note);
        }
        return new Result(false, "[TacLight] tune voxel 拒绝 '" + arg + "': 只要 on/off/status");
    }

    private static String trimNum(float f) {
        if (f == (long) f) return Long.toString((long) f);
        return Float.toString(f);
    }
}

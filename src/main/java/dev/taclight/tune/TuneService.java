package dev.taclight.tune;

/**
 * {@code /taclight tune} 编排(2026-09-19)。
 *
 * <p>纯编排:JVM 契约以假 Sink/假 Gate 直驱(断言范围/越界/status/clear 三态 +
 * 持久化路由);生产由 {@link dev.taclight.command.TacLightCommand} 传入真 Sink
 * ({@link TunePersist#forgeSink()})、真 Gate(TuneClientGate) 与真覆盖层入口。
 * MP 守卫不在这里(要服务端对象,见命令层)。</p>
 *
 * <p><b>2026-10-06 发布面回归修复</b>:本类原在 {@code dev.taclight.debug.tune/**}
 * (2026-09-29 R12 B1 整族搬移),随 {@code exclude 'dev/taclight/debug/**'} 一起离开发布件
 * —— 而 tune 是 2026-09-19 就明确定案的<b>发布面功能</b>(见
 * {@code docs/tune-正式调参命令-2026-09-19.md} 首段"发布包可用"),发布件里
 * {@code /taclight tune} 实际不存在(用户实测发现)。现搬回发布面命名空间
 * {@code dev.taclight.tune/**},并由 {@code InteropPackagingContract} 与
 * {@code HudCommandContract} 的构件正控闸门钉死。</p>
 *
 * <p><b>2026-10-06 分层(用户定案"命令调节有点杂乱,把它分层")</b>:命令入口按"上手顺序"分三层,
 * 每层只干一件事(层与层的文案不重复):
 * <ol>
 *   <li>{@code /taclight tune}(= {@code tune help})—— <b>概览</b>:一句话说清它是什么 +
 *       <b>一条真实可用的示例</b>({@code tune bright 12})+ 下一步指路(list / &lt;名&gt; help / status);</li>
 *   <li>{@code /taclight tune list} —— <b>索引</b>:十个可调项一行一个(只有短标签),
 *       并告诉用户"选一个加 help 看具体含义";</li>
 *   <li>{@code /taclight tune &lt;名&gt; help} —— <b>单项展开</b>:范围/默认/当前值 +
 *       调大调小 + 补充讲解({@link TuneKnobs.Knob#note()})+ 用法示例。</li>
 * </ol>
 * 另有 {@code tune status}(全部当前值,四项表)与 {@code tune &lt;名&gt;}(单项当前值)负责"看数"。
 * 保留字 {@code list/status/help/?} 不可作旋钮名(契约钉死)。</p>
 *
 * <p><b>2026-10-06 用词与文案改造(用户反馈)</b>:用户看到
 * {@code tune bright status: off(默认 intensity=6.0)} 后问"这写个 OFF 是啥意思,是我填 OFF 就是默认值吗"
 * —— 一针见血。原来 {@code off} 同时承担两种语义(数值旋钮的"清除覆盖回默认"、voxel 的"字面禁用"),
 * 且状态回显里的 {@code off(...)} 长得像一个可填的值。现在:
 * <ul>
 *   <li>数值旋钮的清除动词统一为 <b>{@code clear}</b>(旧写法 {@code off} 仍接受,兼容别名,
 *       但不再出现在任何用法/回显文案里);voxel 保留字面开关 {@code on/off}。</li>
 *   <li>状态回显一律"有效值 + 覆盖态",不再有 {@code off(...)}。</li>
 *   <li>每项后面的说明从"这个键叫什么"改成<b>调大/调小会怎样</b>({@link TuneKnobs.Knob#hint()})。</li>
 * </ul></p>
 *
 * <p>校验策略:范围与 LightTuneOverride <b>同源</b>——合法性由各
 * {@code configure*} 的 {@code "range..."/"bad arg..."} 返回判定,本类不复制范围常量
 * (复制即漂移;范围常量只活在覆盖层 + TuneKnobs 展示元数据,契约双向对账)。
 * 覆盖层先行:校验失败直接返回错误,<b>不碰</b> sink(含 save 也不调)。</p>
 */
public final class TuneService {
    /** 编排结果:ok=命令返回 1 还是 0;message=已带 [TacLight] 前缀的回显+日志原文。 */
    public record Result(boolean ok, String message) {}

    /** 保留字(不能当旋钮名);契约钉死它们与注册表不撞,否则分层入口会被旋钮名吃掉。 */
    public static final String[] RESERVED = {"list", "status", "help", "?"};

    private TuneService() {}

    public static Result tune(String name, String arg,
                              TunePersist.Sink sink, TunePersist.VoxelGate gate,
                              TunePersist.KnobApplier applier) {
        String n = name == null ? "" : name.trim();
        String a = arg == null ? "" : arg.trim();

        // ---- 第 1 层:概览(无参 / help)。help 带参 = "help <名>" 的顺手写法 ----
        if (n.isEmpty() || "help".equalsIgnoreCase(n) || "?".equals(n)) {
            if (a.isEmpty()) return new Result(true, help());
            TuneKnobs.Knob k = TuneKnobs.byName(a);
            if (k != null) return new Result(true, knobHelp(k, gate, applier));
            return new Result(false, unknownKnob(a));
        }
        // ---- 第 2 层:索引 ----
        if ("list".equalsIgnoreCase(n)) {
            return a.isEmpty() ? new Result(true, list()) : extraArgReject("list", a);
        }
        // ---- 看数:全部当前值 ----
        if ("status".equalsIgnoreCase(n)) {
            return a.isEmpty() ? statusAll(gate, applier) : extraArgReject("status", a);
        }
        TuneKnobs.Knob knob = TuneKnobs.byName(n);
        if (knob == null) return new Result(false, unknownKnob(name));
        // ---- 第 3 层:单项展开 ----
        if ("help".equalsIgnoreCase(a) || "?".equals(a)) {
            return new Result(true, knobHelp(knob, gate, applier));
        }
        if (TuneKnobs.isBoolean(knob)) {
            return tuneVoxel(knob, a, sink, gate);
        }
        return tuneNumeric(knob, a, sink, applier);
    }

    // ---- 第 1 层:概览(短:它是什么 + 一条真能用的示例 + 下一步去哪) ----

    public static String help() {
        return "[TacLight] tune —— 边看边调手电/枪灯的光效(即时生效,并写回 config/taclight-client.toml 重启保留)\n"
                + "  直接能用的一条: /taclight tune bright 12      把亮度调到 12(敲下去立刻变,聊天栏回执)\n"
                + "  有哪些可调:     /taclight tune list\n"
                + "  某一项怎么用:   /taclight tune <名> help       例: /taclight tune cone help\n"
                + "  看当前值:       /taclight tune status(全部)| /taclight tune <名>(单项)\n"
                + "  还原某一项:     /taclight tune <名> clear\n"
                + "  注: voxel 是字面开关 on/off(回默认请 on);调参只对单人/主机的本机生效。";
    }

    // ---- 第 2 层:索引(有哪些可调;只给短标签,不给数值) ----

    public static String list() {
        StringBuilder sb = new StringBuilder("[TacLight] tune 可调项(")
                .append(TuneKnobs.all().size()).append("):");
        int w = TuneKnobs.nameWidth();
        for (TuneKnobs.Knob k : TuneKnobs.all()) {
            sb.append('\n').append("  ").append(pad(k.name(), w)).append("  ").append(k.label());
        }
        sb.append('\n').append("  看某一项的用法与范围: /taclight tune <名> help     例: /taclight tune cone help")
          .append('\n').append("  看当前值(全部):       /taclight tune status");
        return sb.toString();
    }

    // ---- 第 3 层:单项展开(范围/默认/当前 + 调大调小 + 讲解 + 用法) ----

    public static String knobHelp(TuneKnobs.Knob knob, TunePersist.VoxelGate gate,
                                  TunePersist.KnobApplier applier) {
        boolean vox = TuneKnobs.isBoolean(knob);
        String cur;
        try {
            cur = valueOnly(vox ? gate.status() : applier.apply(knob.name(), "status"));
        } catch (Throwable t) {
            cur = "read-failed:" + t.toString();
        }
        String usage = vox
                ? "/taclight tune voxel on | /taclight tune voxel off | /taclight tune voxel status"
                : "/taclight tune " + knob.name() + " <值> | /taclight tune " + knob.name()
                        + " status | /taclight tune " + knob.name() + " clear";
        return "[TacLight] tune " + knob.name() + " help\n"
                + "  " + knob.name() + " —— " + knob.label() + "\n"
                + "  范围 " + rangeText(knob) + "   默认 " + knob.clearTo() + "   当前 " + cur + "\n"
                + "  " + knob.hint() + "\n"
                + "  说明: " + knob.note() + "\n"
                + "  用法: " + usage;
    }

    // ---- 看数:全部当前值(每行四项:当前值/范围/默认值/调大调小) ----

    /**
     * 无参 status = 十旋钮全状态(只读,零 sink 接触)。
     *
     * <p>每行 <b>四项齐全</b>(2026-10-06 用户定案):当前值 → 范围 → 默认值 → 调大/调小说明。
     * 例:{@code bright  = 12.0(已覆盖)   范围 0.5..30   默认 6.0   调大=更亮…;调小=更暗…}。
     * voxel 是布尔旋钮,范围写 {@code on/off};此处只给 on/off 状态,完整网格诊断在
     * {@code /taclight tune voxel status} 单旋钮查询里给。</p>
     */
    public static Result statusAll(TunePersist.VoxelGate gate, TunePersist.KnobApplier applier) {
        StringBuilder sb = new StringBuilder("[TacLight] tune status(改过的项已写 toml,重启保留):");
        int w = TuneKnobs.nameWidth();
        for (TuneKnobs.Knob knob : TuneKnobs.all()) {
            boolean vox = TuneKnobs.isBoolean(knob);
            String st;
            try {
                st = vox ? voxelOnOff(gate.status()) : valueOnly(applier.apply(knob.name(), "status"));
            } catch (Throwable t) {
                st = "read-failed:" + t.toString();
            }
            sb.append('\n').append("  ").append(pad(knob.name(), w)).append(" = ").append(st)
              .append("   范围 ").append(rangeText(knob))
              .append("   默认 ").append(knob.clearTo())
              .append("   ").append(knob.hint());
        }
        sb.append('\n').append("清除某项覆盖(回默认并写回 toml): /taclight tune <名> clear")
          .append("   |   例: /taclight tune cone 12")
          .append('\n').append("某一项怎么用: /taclight tune <名> help");
        return new Result(true, sb.toString());
    }

    /** 范围文案:voxel 是字面开关(on/off),其余是 min..max。 */
    private static String rangeText(TuneKnobs.Knob knob) {
        if (TuneKnobs.isBoolean(knob)) return "on/off";
        return trimNum(knob.min()) + ".." + trimNum(knob.max());
    }

    private static String unknownKnob(String name) {
        return "[TacLight] tune 未知旋钮 '" + name + "'。看有哪些可调: /taclight tune list";
    }

    private static Result extraArgReject(String word, String arg) {
        return new Result(false, "[TacLight] tune " + word + " 不接受参数(收到 '" + arg + "')。"
                + "看某一项怎么用: /taclight tune <名> help");
    }

    // ---- 数值旋钮 ----

    private static Result tuneNumeric(TuneKnobs.Knob knob, String arg,
                                      TunePersist.Sink sink, TunePersist.KnobApplier applier) {
        if (arg.isEmpty() || "status".equalsIgnoreCase(arg)) {
            String st = valueOnly(applier.apply(knob.name(), "status"));
            return new Result(true, "[TacLight] tune " + knob.name() + ": " + st
                    + "   范围 " + rangeText(knob)
                    + "   默认 " + knob.clearTo()
                    + "\n  " + knob.hint()
                    + "\n  展开讲解: /taclight tune " + knob.name() + " help"
                    + "\n  清除覆盖(回默认 " + knob.clearTo() + " 并写回 toml): /taclight tune "
                    + knob.name() + " clear");
        }
        // clear=清除覆盖回默认(旧写法 off 仍接受:2026-10-06 前的用词,别名不再出现在文案里)。
        if ("clear".equalsIgnoreCase(arg) || "off".equalsIgnoreCase(arg)) {
            String st = valueOnly(applier.apply(knob.name(), "clear"));
            TunePersist.SaveOutcome out = TunePersist.writePairs(sink, TunePersist.offPairs(knob));
            return new Result(true, "[TacLight] tune " + knob.name() + " -> " + st + persistNote(knob, out, true));
        }
        String raw = applier.apply(knob.name(), arg);
        if (raw.startsWith("range") || raw.startsWith("bad arg")) {
            // 拒绝文案由本层自己拼:覆盖层的 bad arg 串里还带着旧词 "off"(中继用),不该漏给用户。
            return new Result(false, "[TacLight] tune " + knob.name() + " 拒绝 '" + arg + "': 范围 "
                    + rangeText(knob) + "(或 status/clear;讲解: " + knob.name() + " help)");
        }
        double v;
        try {
            v = Double.parseDouble(arg.trim());
        } catch (NumberFormatException e) {
            return new Result(false, "[TacLight] tune " + knob.name() + " 拒绝 '" + arg + "': 非数值");
        }
        TunePersist.SaveOutcome out = TunePersist.writePairs(sink, TunePersist.setPairs(knob, v));
        return new Result(true, "[TacLight] tune " + knob.name() + " -> " + valueOnly(raw)
                + persistNote(knob, out, false));
    }

    private static String persistNote(TuneKnobs.Knob knob, TunePersist.SaveOutcome out, boolean isClear) {
        String keys = String.join("+", knob.configKeys());
        if (out.ok()) {
            return " (已写入 taclight-client.toml [" + keys + "]"
                    + (isClear ? "=默认值" : "") + ",本机即时生效,重启保留)";
        }
        return " (覆盖层已生效,但 toml 写入失败:" + out.detail() + "——重启不保留!)";
    }

    // ---- voxel(布尔,不对称语义:off=字面禁用,不是回默认) ----

    private static Result tuneVoxel(TuneKnobs.Knob knob, String arg,
                                    TunePersist.Sink sink, TunePersist.VoxelGate gate) {
        if (arg.isEmpty() || "status".equalsIgnoreCase(arg)) {
            return new Result(true, "[TacLight] tune voxel: " + valueOnly(gate.status())
                    + "   范围 " + rangeText(knob) + "   默认 " + knob.clearTo()
                    + "\n  " + knob.hint()
                    + "\n  展开讲解: /taclight tune voxel help"
                    + "\n  开关写法: /taclight tune voxel <on|off>(off=字面禁用并持久化,回默认请 on)");
        }
        if ("on".equalsIgnoreCase(arg) || "off".equalsIgnoreCase(arg)) {
            boolean on = "on".equalsIgnoreCase(arg);
            String st = valueOnly(gate.apply(arg.toLowerCase(java.util.Locale.ROOT)));
            TunePersist.SaveOutcome out = TunePersist.writeVoxel(sink, on);
            String note = out.ok() ? " (已写入 taclight-client.toml [voxelEnabled],重启保留)"
                    : " (覆盖层已生效,但 toml 写入失败:" + out.detail() + "——重启不保留!)";
            return new Result(true, "[TacLight] tune voxel -> " + st + note);
        }
        // clear 对 voxel 无意义(它不是"覆盖"而是字面开关):明确拒绝 + 指路,不静默当成 off。
        return new Result(false, "[TacLight] tune voxel 拒绝 '" + arg + "': 只要 on/off/status"
                + "(voxel 是字面开关,没有 clear;回默认请 tune voxel on)");
    }

    private static String trimNum(float f) {
        if (f == (long) f) return Long.toString((long) f);
        return Float.toString(f);
    }

    /**
     * voxel 当前值只取 on/off:列表里 {@code box=(…)+32x24x32 builds=7 lastBuildMs=1.20} 这类
     * 网格诊断对调参是噪音(它们属于"体素网格健康度",不是旋钮值);要看全的走
     * {@code /taclight tune voxel status} 单旋钮查询。off 的 {@code (fallback SSO)} 是语义的一部分,保留。
     */
    private static String voxelOnOff(String st) {
        String v = valueOnly(st);
        if (v.startsWith("on")) return "on";
        if (v.startsWith("off")) return v.startsWith("off(") ? v.substring(0, v.indexOf(')') + 1) : "off";
        return v;
    }

    /**
     * 去掉覆盖层状态串里的"键名前缀"({@code bright=12.0} → {@code 12.0};
     * {@code voxel=on grid=null} → {@code on grid=null})—— 调用方已经写明是哪个旋钮,
     * 回显里再重复一遍键名只是噪音。判定用"第一个 '=' 在 '(' 之前"⇒ 只剥键名,不碰值/说明。
     */
    private static String valueOnly(String st) {
        if (st == null) return "null";
        int eq = st.indexOf('=');
        int paren = st.indexOf('(');
        if (eq >= 0 && (paren < 0 || eq < paren)) return st.substring(eq + 1);
        return st;
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
}

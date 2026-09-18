package dev.taclight.interop;

import java.util.ArrayList;
import java.util.List;

/**
 * 补丁执行器(方案C 里程碑2):对一段已加载的包源文本执行模板算子。
 * 纯函数 / 零 IO / 零 MC 依赖,JVM 契约(PatchExecutorContract)可全量覆盖。
 *
 * <p>失败语义(计划文档 §4):selector 未命中 = 跳过返回 null(该文件不注入,不是错误);
 * selectorCount 不符 / 任一算子锚点缺失 / 文本已含 marker = 中止返回 null——
 * 调用方保留原文,绝不留部分修改(fail-safe,防注入半成品触发 Iris 静默禁包)。
 * 算子顺序应用,后序算子看到前序结果。</p>
 *
 * <p><b>2026-09-19 闸门换位(interop 包名/哈希闸门修复,硬裁定 ②)</b>:
 * {@link #applyDetailed} 成为<b>真闸门</b>——它在<b>真实运行时文本</b>上判定"能不能注入":
 * ① 每个算子的锚点必须<b>逐字</b>命中(可用模板声明的备选锚点 {@link Op#anchorAlternatives},
 * 仍是逐字,不做模糊匹配);② selector 与出现次数必须相符;③ <b>注后自检</b>
 * ({@link #selfCheck}:marker 恰 1 次 + 必需签名齐全 + 宿主括号平衡未破坏)。任一不满足 =
 * 不注入 + <b>可操作失败原因</b>(哪个算子、试过哪些锚点)。</p>
 *
 * <p>为什么备选锚点必要:模板锚必须按<b>运行时</b>文本编写(坑80),而运行时文本是 jcpp 之后
 * 的形态,与包内原始文件不同(实测 {@code #version  130} 双空格 vs 原始文件单空格)。
 * 两种形态都给出来 = 既对运行时正确、又对离线 fixture 可判定,且不引入模糊匹配。</p>
 */
public final class PatchExecutor {
    /** 幂等标记:文本已含此串 = 已注入(或路线 P 派生包),跳过。路线 P 同名约定。 */
    public static final String MARKER = "TACLIGHT_PATCH_BEGIN";

    private PatchExecutor() {}

    /** 单个算子。op ∈ {replaceFirst, insertAfterLine, insertBeforeLine, insertAtEnd}。 */
    public static final class Op {
        public final String op;
        public final String anchor;
        public final String content;
        /** 备选锚点(逐字;不含 {@link #anchor} 本身;顺序尝试)。2026-09-19 新增。 */
        public final List<String> anchorAlternatives;

        public Op(String op, String anchor, String content) {
            this(op, anchor, content, List.of());
        }

        public Op(String op, String anchor, String content, List<String> anchorAlternatives) {
            this.op = op;
            this.anchor = anchor;
            this.content = content;
            this.anchorAlternatives = anchorAlternatives == null ? List.of() : List.copyOf(anchorAlternatives);
        }

        /** 尝试顺序 = 首选锚点 + 备选(去空白/去重)。 */
        public List<String> anchors() {
            List<String> out = new ArrayList<>();
            if (anchor != null && !anchor.isBlank()) out.add(anchor);
            for (String a : anchorAlternatives) {
                if (a != null && !a.isBlank() && !out.contains(a)) out.add(a);
            }
            return out;
        }
    }

    /** 执行结果:成功 = {@code patched != null}(failure 为 null);失败 = 可操作原因。 */
    public static final class Result {
        public final String patched;
        public final String failure;
        public final int injectedChars;
        /**
         * selector 是否命中。false = "这段源不是本模板的目标文件"(静默,不是错误);
         * true 且失败 = "是目标文件但注入不了"(必须可操作地报因)。2026-09-19 新增。
         */
        public final boolean selectorHit;

        Result(String patched, String failure, int injectedChars, boolean selectorHit) {
            this.patched = patched;
            this.failure = failure;
            this.injectedChars = injectedChars;
            this.selectorHit = selectorHit;
        }

        public boolean ok() {
            return patched != null;
        }
    }

    private static final class Attempt {
        final String text;
        final String reason;

        Attempt(String text, String reason) {
            this.text = text;
            this.reason = reason;
        }
    }

    /**
     * @param selector      主锚点;null = 无条件应用。文本不含 selector 时返回 null(跳过)。
     * @param selectorCount selector 期望出现次数;null = 不校验。不符时中止(防误伤同锚点程序)。
     * @return 注入后文本;null = 不注入(调用方用原文)。
     */
    public static String apply(String text, String selector, Integer selectorCount, List<Op> ops) {
        return applyDetailed(text, selector, selectorCount, ops, List.of()).patched;
    }

    /**
     * 真闸门(2026-09-19):执行 + 自检 + 可操作失败原因。
     *
     * @param requiredSymbols 注入后必须出现的符号(如 {@code taclight_surface_lighting(});
     *                        含 {@link #MARKER} 时额外要求 marker 恰 1 次
     *                        (注入内容自带 marker 时自动要求,无需调用方声明)。
     */
    public static Result applyDetailed(String text, String selector, Integer selectorCount,
                                      List<Op> ops, List<String> requiredSymbols) {
        if (text == null || ops == null || ops.isEmpty()) {
            return new Result(null, "无文本或无算子", 0, false);
        }
        if (text.contains(MARKER)) {
            return new Result(null, "文本已含 marker(幂等跳过)", 0, false);
        }
        boolean selectorHit = true;
        if (selector != null) {
            int count = countOccurrences(text, selector);
            selectorHit = count > 0;
            if (count == 0) return new Result(null, "selector 未命中(该文件不注入)", 0, false);
            if (selectorCount != null && count != selectorCount) {
                return new Result(null, "selector 命中 " + count + " 次,期望 " + selectorCount
                        + "(防误伤同锚点其他程序)", 0, true);
            }
        }
        String working = text;
        for (int i = 0; i < ops.size(); i++) {
            Op o = ops.get(i);
            Attempt a = applyOne(working, o);
            if (a.text == null) {
                return new Result(null, "算子 #" + (i + 1) + "(" + o.op + ")失败:" + a.reason, 0, selectorHit);
            }
            working = a.text;
        }
        String check = selfCheck(text, working, ops, requiredSymbols);
        if (check != null) return new Result(null, "注后自检失败:" + check, 0, selectorHit);
        return new Result(working, null, working.length() - text.length(), selectorHit);
    }

    /**
     * 注后自检(硬裁定 ②):marker 计数必须<b>精确等于</b>"注入内容里的 marker 数"(原文必须 0)/
     * 必需签名齐全 / 宿主括号平衡未被破坏。返回 null = 通过。
     *
     * <p><b>2026-09-19 实测修正(对裁定原文"marker 恰 1 次"的更正,已上报)</b>:真实模板一次注入
     * 会落地 <b>3 个</b> marker —— 两个 patch 区标记({@code BEGIN/END})+ 内联核自带的一个 marker
     * 注释({@code inlineCoreText()} 首行)。故不变式不是"恰 1 次",而是
     * <b>"原文 0 个 + 注入内容 N 个 ⇒ 结果恰 N 个"</b>(N=0 的合成用例同样成立,不误杀)。
     * 这比"恰 1 次"更强:任何意外复制/丢失 marker 都会被抓到。</p>
     */
    public static String selfCheck(String original, String patched, List<Op> ops, List<String> requiredSymbols) {
        int expected = 0;
        for (Op o : ops) {
            if (o.content != null) expected += countOccurrences(o.content, MARKER);
        }
        int before = countOccurrences(original, MARKER);
        int after = countOccurrences(patched, MARKER);
        if (before != 0) return "原文已含 marker " + before + " 次(幂等应已前置拦截)";
        if (after != expected) return "marker 计数异常:注入内容含 " + expected + " 个,结果 " + after + " 个";
        if (requiredSymbols != null) {
            for (String sym : requiredSymbols) {
                if (sym == null || sym.isBlank() || MARKER.equals(sym)) continue;
                if (!patched.contains(sym)) return "缺必需签名 " + sym;
            }
        }
        int balBefore = braceBalance(original);
        int balAfter = braceBalance(patched);
        if (balBefore != balAfter) return "宿主花括号平衡被破坏(" + balBefore + " → " + balAfter + ")";
        return null;
    }

    private static int braceBalance(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') n++;
            else if (c == '}') n--;
        }
        return n;
    }

    private static Attempt applyOne(String text, Op o) {
        if (o == null || o.op == null) return new Attempt(null, "算子为空");
        switch (o.op) {
            case "replaceFirst": {
                if (o.content == null) return new Attempt(null, "content 为空");
                for (String a : o.anchors()) {
                    int i = text.indexOf(a);
                    if (i >= 0) {
                        return new Attempt(text.substring(0, i) + o.content + text.substring(i + a.length()), null);
                    }
                }
                return new Attempt(null, "锚点全部未命中 " + o.anchors());
            }
            case "insertAfterLine": {
                if (o.content == null) return new Attempt(null, "content 为空");
                for (String a : o.anchors()) {
                    int i = text.indexOf(a);
                    if (i < 0) continue;
                    int lineEnd = text.indexOf('\n', i);
                    if (lineEnd < 0) return new Attempt(text + "\n" + normalizeTail(o.content), null);
                    return new Attempt(text.substring(0, lineEnd + 1) + normalizeTail(o.content)
                            + text.substring(lineEnd + 1), null);
                }
                return new Attempt(null, "锚点全部未命中 " + o.anchors());
            }
            case "insertBeforeLine": {
                // 在含锚点的行之前插入(宿主 uniform 声明后、宿主函数定义前 = 外包
                // 内联核心的正确位置;02:32 实机:版本行后注入先于宿主声明 = C1503)
                if (o.content == null) return new Attempt(null, "content 为空");
                for (String a : o.anchors()) {
                    int i = text.indexOf(a);
                    if (i < 0) continue;
                    int lineStart = text.lastIndexOf('\n', i) + 1;
                    return new Attempt(text.substring(0, lineStart) + normalizeTail(o.content)
                            + text.substring(lineStart), null);
                }
                return new Attempt(null, "锚点全部未命中 " + o.anchors());
            }
            case "insertAtEnd": {
                if (o.content == null) return new Attempt(null, "content 为空");
                String base = text.endsWith("\n") ? text : text + "\n";
                return new Attempt(base + normalizeTail(o.content), null);
            }
            default:
                return new Attempt(null, "未知算子 " + o.op); // 未知算子 = 中止
        }
    }

    /** content 尾部归一化:去掉多余尾换行,保证恰好一个(无双空行,续行不粘连)。 */
    private static String normalizeTail(String content) {
        String c = content;
        while (c.endsWith("\n")) c = c.substring(0, c.length() - 1);
        return c + "\n";
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}

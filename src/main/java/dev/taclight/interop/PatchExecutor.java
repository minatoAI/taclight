package dev.taclight.interop;

import java.util.List;

/**
 * 补丁执行器(方案C 里程碑2):对一段已加载的包源文本执行模板算子。
 * 纯函数 / 零 IO / 零 MC 依赖,JVM 契约(PatchExecutorContract)可全量覆盖。
 *
 * <p>失败语义(计划文档 §4):selector 未命中 = 跳过返回 null(该文件不注入,不是错误);
 * selectorCount 不符 / 任一算子锚点缺失 / 文本已含 marker = 中止返回 null——
 * 调用方保留原文,绝不留部分修改(fail-safe,防注入半成品触发 Iris 静默禁包)。
 * 算子顺序应用,后序算子看到前序结果。
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

        public Op(String op, String anchor, String content) {
            this.op = op;
            this.anchor = anchor;
            this.content = content;
        }
    }

    /**
     * @param selector      主锚点;null = 无条件应用。文本不含 selector 时返回 null(跳过)。
     * @param selectorCount selector 期望出现次数;null = 不校验。不符时中止(防误伤同锚点程序)。
     * @return 注入后文本;null = 不注入(调用方用原文)。
     */
    public static String apply(String text, String selector, Integer selectorCount, List<Op> ops) {
        if (text == null || ops == null || ops.isEmpty()) return null;
        if (text.contains(MARKER)) return null; // 幂等:已注入的文本不再动
        if (selector != null) {
            int count = countOccurrences(text, selector);
            if (count == 0) return null; // selector 未命中 = 该文件跳过
            if (selectorCount != null && count != selectorCount) return null;
        }
        String working = text;
        for (Op o : ops) {
            String next = applyOne(working, o);
            if (next == null) return null; // all-or-nothing:任一失败,整体不留痕
            working = next;
        }
        return working;
    }

    private static String applyOne(String text, Op o) {
        if (o == null || o.op == null) return null;
        switch (o.op) {
            case "replaceFirst": {
                if (o.anchor == null || o.content == null) return null;
                int i = text.indexOf(o.anchor);
                if (i < 0) return null;
                return text.substring(0, i) + o.content + text.substring(i + o.anchor.length());
            }
            case "insertAfterLine": {
                if (o.anchor == null || o.content == null) return null;
                int i = text.indexOf(o.anchor);
                if (i < 0) return null;
                int lineEnd = text.indexOf('\n', i);
                if (lineEnd < 0) return text + "\n" + normalizeTail(o.content);
                return text.substring(0, lineEnd + 1) + normalizeTail(o.content)
                        + text.substring(lineEnd + 1);
            }
            case "insertBeforeLine": {
                // 在含锚点的行之前插入(宿主 uniform 声明后、宿主函数定义前 = 外包
                // 内联核心的正确位置;02:32 实机:版本行后注入先于宿主声明 = C1503)
                if (o.anchor == null || o.content == null) return null;
                int i = text.indexOf(o.anchor);
                if (i < 0) return null;
                int lineStart = text.lastIndexOf('\n', i) + 1;
                return text.substring(0, lineStart) + normalizeTail(o.content)
                        + text.substring(lineStart);
            }
            case "insertAtEnd": {
                if (o.content == null) return null;
                String base = text.endsWith("\n") ? text : text + "\n";
                return base + normalizeTail(o.content);
            }
            default:
                return null; // 未知算子 = 中止
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

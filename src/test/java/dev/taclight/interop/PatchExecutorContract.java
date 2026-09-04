package dev.taclight.interop;

import java.util.List;

/**
 * 补丁执行器契约(方案C 里程碑2,计划文档 §4/§6)。
 * 钉死:五算子语义 / selector 门控 / selectorCount 唯一性 / 锚点缺失=整体不留痕 /
 * marker 幂等 / 算子顺序应用(后序看到前序结果)。纯函数,零 IO。
 */
public class PatchExecutorContract {
    private static int checks;

    public static void main(String[] args) {
        markerConstant();
        insertAfterLine();
        insertBeforeLine();
        replaceFirst();
        insertAtEnd();
        sequentialOps();
        selectorGating();
        countGate();
        allOrNothing();
        markerIdempotent();
        noSelector();
        unknownOp();
        contentVerbatim();
        lastLine();
        System.out.println("PatchExecutorContract: ALL PASS (" + checks + " checks)");
    }

    private static void markerConstant() {
        check(PatchExecutor.MARKER.equals("TACLIGHT_PATCH_BEGIN"),
                "marker 常量 = 路线 P 同名约定 TACLIGHT_PATCH_BEGIN");
    }

    private static void insertAfterLine() {
        String t = "a\nbbANCHOR cc\nd";
        String r = PatchExecutor.apply(t, null, null, List.of(
                new PatchExecutor.Op("insertAfterLine", "ANCHOR", "X1\nX2")));
        check("a\nbbANCHOR cc\nX1\nX2\nd".equals(r),
                "insertAfterLine = 在含锚点行后插入整行(content 归一化补尾换行): " + r);
        // content 已带尾换行不得产生双空行
        String r2 = PatchExecutor.apply(t, null, null, List.of(
                new PatchExecutor.Op("insertAfterLine", "ANCHOR", "X1\n")));
        check("a\nbbANCHOR cc\nX1\nd".equals(r2), "content 尾换行归一化(无双空行): " + r2);
    }

    private static void insertBeforeLine() {
        String t = "a\nbbANCHOR cc\nd";
        String r = PatchExecutor.apply(t, null, null, List.of(
                new PatchExecutor.Op("insertBeforeLine", "ANCHOR", "X1\nX2")));
        check("a\nX1\nX2\nbbANCHOR cc\nd".equals(r),
                "insertBeforeLine = 在含锚点行前插入整行(宿主 uniform 声明后、函数定义前): " + r);
        String r2 = PatchExecutor.apply("ANCHOR cc\nd", null, null, List.of(
                new PatchExecutor.Op("insertBeforeLine", "ANCHOR", "X")));
        check("X\nANCHOR cc\nd".equals(r2), "锚点在首行时插入到文本开头: " + r2);
        check(PatchExecutor.apply(t, null, null,
                List.of(new PatchExecutor.Op("insertBeforeLine", "缺失", "x"))) == null,
                "insertBeforeLine 锚点缺失 = 中止(null)");
    }

    private static void replaceFirst() {
        String t = "v 330\nmid v 330\ntail";
        String r = PatchExecutor.apply(t, null, null, List.of(
                new PatchExecutor.Op("replaceFirst", "v 330", "v 430 core")));
        check("v 430 core\nmid v 330\ntail".equals(r), "replaceFirst 只替换首个出现");
        check(PatchExecutor.apply(t, null, null,
                List.of(new PatchExecutor.Op("replaceFirst", "不存在", "x"))) == null,
                "replaceFirst 锚点缺失 = 整体中止(null)");
    }

    private static void insertAtEnd() {
        String r = PatchExecutor.apply("a\nb", null, null, List.of(
                new PatchExecutor.Op("insertAtEnd", null, "c = 1")));
        check("a\nb\nc = 1\n".equals(r), "insertAtEnd 追加并补尾换行: " + r);
    }

    private static void sequentialOps() {
        String t = "#version 330\nbody";
        String r = PatchExecutor.apply(t, null, null, List.of(
                new PatchExecutor.Op("replaceFirst", "#version 330", "#version 430 core"),
                new PatchExecutor.Op("insertAfterLine", "#version 430 core", "INJECTED")));
        check("#version 430 core\nINJECTED\nbody".equals(r),
                "算子顺序应用,后序看到前序结果: " + r);
    }

    private static void selectorGating() {
        String t = "x\nsel y\nz";
        List<PatchExecutor.Op> ops = List.of(new PatchExecutor.Op("insertAtEnd", null, "e"));
        check(PatchExecutor.apply(t, "sel", null, ops) != null, "selector 命中 = 应用");
        check(PatchExecutor.apply("no match here", "sel", null, ops) == null,
                "selector 未命中 = 跳过(该文件不注入)");
    }

    private static void countGate() {
        String t = "s\ns\ns";
        List<PatchExecutor.Op> ops = List.of(new PatchExecutor.Op("insertAtEnd", null, "e"));
        check(PatchExecutor.apply(t, "s", 3, ops) != null, "selectorCount 相符 = 应用");
        check(PatchExecutor.apply(t, "s", 1, ops) == null,
                "selectorCount 不符(3 处 vs 期望 1)= 中止,防误伤同锚点其他程序");
    }

    private static void allOrNothing() {
        // 第二个算子锚点缺失:第一个算子的修改不得留下
        String t = "a\nb";
        String r = PatchExecutor.apply(t, null, null, List.of(
                new PatchExecutor.Op("insertAtEnd", null, "first"),
                new PatchExecutor.Op("replaceFirst", "缺失锚点", "x")));
        check(r == null && t.equals("a\nb"), "任一算子失败 = 整体不留痕(副本上执行)");
    }

    private static void markerIdempotent() {
        String t = "// TACLIGHT_PATCH_BEGIN x\nbody";
        check(PatchExecutor.apply(t, null, null,
                List.of(new PatchExecutor.Op("insertAtEnd", null, "e"))) == null,
                "文本已含 marker = 幂等跳过(返回 null)");
    }

    private static void noSelector() {
        String r = PatchExecutor.apply("a", null, null,
                List.of(new PatchExecutor.Op("insertAtEnd", null, "b")));
        check("a\nb\n".equals(r), "selector=null = 无条件应用(shaders.properties 类)");
    }

    private static void unknownOp() {
        check(PatchExecutor.apply("a", null, null,
                List.of(new PatchExecutor.Op("bogusOp", "a", "x"))) == null, "未知算子 = 中止");
    }

    private static void contentVerbatim() {
        String t = "m(AAA x)\nrest";
        String r = PatchExecutor.apply(t, "AAA", 1, List.of(
                new PatchExecutor.Op("insertAfterLine", "AAA", "\t\t\tindent.keep();\n\t\t\tmore")));
        check("m(AAA x)\n\t\t\tindent.keep();\n\t\t\tmore\nrest".equals(r),
                "多行 content 逐字插入(缩进保留): " + r);
    }

    private static void lastLine() {
        String r = PatchExecutor.apply("head\ntail AAA", null, null, List.of(
                new PatchExecutor.Op("insertAfterLine", "AAA", "after")));
        check("head\ntail AAA\nafter\n".equals(r), "锚点行是末行时插入到其后: " + r);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

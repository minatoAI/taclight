package dev.taclight.interop;

import java.util.List;

/**
 * 模板库契约(方案C 里程碑2,计划文档 §4/§6)。
 * 钉死:JSON 解析与字段校验 / INLINE_CORE 占位符替换(替换后无残留,且内联文本
 * 携带 SSBO 声明、surface 入口、GAIN 标定、marker)/ iterationT 冒烟模板真实存在且形态正确。
 */
public class TemplateLibraryContract {
    private static int checks;

    public static void main(String[] args) {
        placeholderConstant();
        loadIterationTemplate();
        noPlaceholderResidue();
        inlineCoreThroughTemplate();
        malformedRejected();
        missingFieldsRejected();
        missingResource();
        System.out.println("TemplateLibraryContract: ALL PASS (" + checks + " checks)");
    }

    private static void placeholderConstant() {
        check(TemplateLibrary.INLINE_CORE_PLACEHOLDER.equals("<INLINE_CORE>"),
                "占位符常量 = <INLINE_CORE>(计划 §4)");
    }

    private static void loadIterationTemplate() {
        TemplateLibrary.Template t = TemplateLibrary.load(
                "/shader_patches/templates/iterationT-3.2.0.json").orElse(null);
        check(t != null, "iterationT 冒烟模板可从 resources 加载");
        if (t == null) return;
        check("iterationT".equals(t.familyId) && "iterationT 3.2.0".equals(t.packName),
                "familyId/packName 与计划一致: " + t.familyId + " / " + t.packName);
        check(t.packHash.containsKey("shaders/composite.fsh")
                        && t.packHash.get("shaders/composite.fsh").matches("[0-9a-f]{16}"),
                "packHash 含 composite.fsh 的 16 位 sha256 前缀");
        check(t.files.size() == 1 && "shaders/composite.fsh".equals(t.files.get(0).file),
                "冒烟模板只动 composite.fsh(计划 §4:properties 声明经实证可省)");
        TemplateLibrary.FileRule rule = t.files.get(0);
        check(rule.selector != null && rule.selector.contains("finalComposite += HeldLighting")
                        && rule.selectorCount != null && rule.selectorCount == 1,
                "selector = HeldLighting 调用行且要求唯一出现");
        check(rule.ops.size() == 3
                        && "replaceFirst".equals(rule.ops.get(0).op)
                        && "insertBeforeLine".equals(rule.ops.get(1).op)
                        && "insertAfterLine".equals(rule.ops.get(2).op),
                "算子序列 = 版本抬升 → HeldLighting 定义前内联注入(坑80:先于宿主声明会 C1503)\n"
                        + "                        → 调用点插入");
        check(rule.ops.get(0).anchor.equals("#version  330")
                        && rule.ops.get(0).content.equals("#version 430 core"),
                "版本算子锚 = 运行时实证形态(坑80:patchComposite 输入经 Iris 规范化,\n"
                        + "#version 行为双空格,锚必须按 DUMP 实测文本编写)");
        check(rule.ops.get(2).content.contains("taclight_surface_lighting(viewPos, gbuffer.albedo"),
                "调用点算子 = 2.0 core 新签名(非路线 P 旧签名)");
    }

    private static void noPlaceholderResidue() {
        TemplateLibrary.Template t = TemplateLibrary.load(
                "/shader_patches/templates/iterationT-3.2.0.json").orElse(null);
        if (t == null) { check(false, "模板加载失败"); return; }
        boolean residue = t.files.stream()
                .flatMap(f -> f.ops.stream())
                .anyMatch(op -> op.content != null
                        && op.content.contains(TemplateLibrary.INLINE_CORE_PLACEHOLDER));
        check(!residue, "INLINE_CORE 占位符替换后无残留");
    }

    private static void inlineCoreThroughTemplate() {
        TemplateLibrary.Template t = TemplateLibrary.load(
                "/shader_patches/templates/iterationT-3.2.0.json").orElse(null);
        if (t == null) { check(false, "模板加载失败"); return; }
        String injected = t.files.get(0).ops.get(1).content;
        check(injected.contains("layout(std430, binding = 7)"), "内联文本携带 SSBO 声明");
        check(injected.contains("vec3 taclight_surface_lighting"), "内联文本含照明主入口定义");
        check(injected.contains(TemplateLibrary.INLINE_CORE_PLACEHOLDER) == false
                        && injected.contains(PatchExecutor.MARKER),
                "内联注入块含 marker(幂等锚)");
    }

    private static void malformedRejected() {
        check(!TemplateLibrary.fromJson("not json at all {").isPresent(), "非 JSON = 拒绝");
        check(!TemplateLibrary.fromJson("[]").isPresent(), "JSON 数组(非对象)= 拒绝");
    }

    private static void missingFieldsRejected() {
        check(!TemplateLibrary.fromJson("{\"familyId\":\"x\"}").isPresent(), "缺 packName/files = 拒绝");
        check(!TemplateLibrary.fromJson(
                "{\"familyId\":\"x\",\"packName\":\"p\",\"files\":[]}").isPresent(),
                "files 空数组 = 拒绝");
        check(!TemplateLibrary.fromJson(
                "{\"familyId\":\"x\",\"packName\":\"p\",\"files\":[{\"file\":\"a\"}]}").isPresent(),
                "file rule 缺 ops/selector = 拒绝");
    }

    private static void missingResource() {
        check(!TemplateLibrary.load("/shader_patches/templates/no-such-pack.json").isPresent(),
                "不存在的模板资源 = empty(不抛异常)");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

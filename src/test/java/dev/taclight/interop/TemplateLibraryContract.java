package dev.taclight.interop;

import java.util.List;

/**
 * 模板库契约(方案C 里程碑2,计划文档 §4/§6)。
 * 钉死:JSON 解析与字段校验 / INLINE_CORE 占位符替换(替换后无残留,且内联文本
 * 携带 SSBO 声明、surface 入口、GAIN 标定、marker)/ iterationT 冒烟模板真实存在且形态正确/
 * 调用点亮度归一化(与本家包同链路)。
 */
public class TemplateLibraryContract {
    private static int checks;

    public static void main(String[] args) {
        placeholderConstant();
        loadIterationTemplate();
        loadComplementaryTemplate();
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
        // 2026-09-19 闸门换位:锚 = 运行时(双空格)首选 + 原始文件(单空格)备选,逐字不做模糊匹配;
        // 离线 fixture(原始文件口径)与真机运行时(规范化口径)因此都能判定"锚点在不在"。
        check(rule.ops.get(0).anchors != null && rule.ops.get(0).anchors.contains("#version 330"),
                "版本算子备选锚 = 原始文件形态(#version 单空格): " + rule.ops.get(0).anchors);
        check(t.requiredSymbols != null
                        && t.requiredSymbols.contains("taclight_surface_lighting(")
                        && t.requiredSymbols.contains("taclight_shoulder3("),
                "注后自检必需签名: " + t.requiredSymbols);
        check(rule.ops.get(2).content.contains("taclight_surface_lighting(viewPos, gbuffer.albedo"),
                "调用点算子 = 2.0 core 新签名(非路线 P 旧签名)");
        // 2026-09-03 真实感调参(用户体感"光晕太亮照不清"):调用点必须与宿主物理量纲一致。
        // 宿主 composite 尾部:finalComposite/=MAIN_OUTPUT_FACTOR(=2048,实测 Lib/Settings.glsl:484)
        // 后再 LinearToCurve——宿主内光照量级是"输出前量纲",直接加物理 radiance 高千倍过曝。
        // 调用点 = (radiance×GAIN/2048)进 shoulder3(T=0.55/Q=0.15,本家包同参数)。
        String callOp = rule.ops.get(2).content;
        check(callOp.contains("taclight_shoulder3(")
                        && callOp.contains("TACLIGHT_LIGHT_GAIN")
                        && callOp.contains("/ 2048.0")
                        && callOp.contains("0.55") && callOp.contains("0.15"),
                "调用点 = 按宿主 MAIN_OUTPUT_FACTOR(=2048)归一化的 GAIN×shoulder3");
    }

    private static void noPlaceholderResidue() {
        for (String name : new String[]{
                "/shader_patches/templates/iterationT-3.2.0.json",
                "/shader_patches/templates/complementary-r5.9.json"}) {
            TemplateLibrary.Template t = TemplateLibrary.load(name).orElse(null);
            if (t == null) { check(false, "模板加载失败: " + name); return; }
            boolean residue = t.files.stream()
                    .flatMap(f -> f.ops.stream())
                    .anyMatch(op -> op.content != null
                            && op.content.contains(TemplateLibrary.INLINE_CORE_PLACEHOLDER));
            check(!residue, "INLINE_CORE 占位符替换后无残留(" + name + ")");
        }
    }

    // 2026-09-03 Complementary r5.9(用户要求试注入+自适应曝光观察):
    // 前向 gbuffers 注入——宿主在 gbuffers_terrain(DoLighting 将光照乘进 albedo)
    // 完成全部光照(含自有 heldLighting),color.rgb 此时=已照亮反照率;
    // 注入点在其后做加性锥光,走宿主下游 tonemap(DoCompTonemap,composite5,手动曝光
    // TM_EXPOSURE 无自适应),曝光不吃手电反馈。SSBO binding=7 与宿主体素
    // binding=0/3 无冲突。前向注入不走 composite 族=auto exposure(如 iterationT
    // 的 colortex1 分块 AE)在 composite 之前采样不到注入光,无反馈压制。
    private static void loadComplementaryTemplate() {
        TemplateLibrary.Template t = TemplateLibrary.load(
                "/shader_patches/templates/complementary-r5.9.json").orElse(null);
        check(t != null, "Complementary r5.9 模板可从 resources 加载");
        if (t == null) return;
        check("complementary".equals(t.familyId) && "ComplementaryReimagined".equals(t.packName),
                "familyId/packName: " + t.familyId + " / " + t.packName);
        check(t.packHash.containsKey("shaders/program/gbuffers_terrain.glsl")
                        && t.packHash.get("shaders/program/gbuffers_terrain.glsl").matches("[0-9a-f]{16}"),
                "packHash 含 gbuffers_terrain.glsl 的 16 位 sha256 前缀");
        check(t.files.size() == 3
                        && "shaders/program/gbuffers_terrain.glsl".equals(t.files.get(0).file)
                        && "shaders/program/gbuffers_entities.glsl".equals(t.files.get(1).file)
                        && "shaders/program/gbuffers_hand.glsl".equals(t.files.get(2).file),
                "Complementary 模板三文件:terrain(地形)+entities(实体)+hand(手部);"
                        + "实体/手部走 patchVanilla,地形走 patchSodium(2026-09-04 半透明修复)");
        TemplateLibrary.FileRule fRule = t.files.get(0);
        // 片元半体:版本行后 extension 开 SSBO + DoLighting 定义前文件域内联 + 调用点后加性锥光。
        // 2026-09-04 落盘取证:patchSodium 输入 #version 130 双空格 + DoLighting 定义在
        // main 之前(2960 行)、调用点在 main 内(9601 行);SSBO 用 extension(宿主同式),
        // 不抬升版本(430 会杀掉宿主 texture2D/varying 兼容路径)。core 放文件域词法前序。
        check(fRule.selector != null
                        && fRule.selector.contains("smoothnessG, highlightMult, emission);")
                        && fRule.selectorCount != null && fRule.selectorCount == 1,
                "片元规则 selector = DoLighting 调用尾行且唯一(地形变体全尾,block 等变体尾不同)");
        check(fRule.ops.size() == 4
                        && "insertBeforeLine".equals(fRule.ops.get(0).op)
                        && "insertAfterLine".equals(fRule.ops.get(1).op)
                        && "insertBeforeLine".equals(fRule.ops.get(2).op)
                        && "insertAfterLine".equals(fRule.ops.get(3).op),
                "片元规则算子 = 调用前 raw 快照 → 版本行后 extension → DoLighting 定义前文件域内联 → 调用后加性锥光");
        check(fRule.ops.get(0).anchor.contains("DoLighting(color, shadowMult, playerPos, viewPos,")
                        && fRule.ops.get(0).content.contains("taclightRawAlbedo"),
                "快照算子锚 = DoLighting 调用首行(调用前一行声明 raw albedo)");
        check(fRule.ops.get(1).anchor.equals("#version  130")
                        && fRule.ops.get(1).content.contains("GL_ARB_shader_storage_buffer_object"),
                "extension 算子锚 = 运行时实测形态(#version 双空格,宿主 SSBO 同式)");
        check(fRule.ops.get(1).anchors != null && fRule.ops.get(1).anchors.contains("#version 130"),
                "extension 算子备选锚 = 原始文件形态(#version 单空格;2026-09-19 闸门换位): "
                        + fRule.ops.get(1).anchors);
        check(t.requiredSymbols != null
                        && t.requiredSymbols.contains("taclight_surface_lighting(")
                        && t.requiredSymbols.contains("taclight_shoulder3("),
                "注后自检必需签名: " + t.requiredSymbols);
        check(fRule.ops.get(2).anchor.equals("void DoLighting("),
                "片元内联锚 = void DoLighting( 定义行(运行时片元半体 2960 行实测)");
        // 已知良好清单(硬裁定②):r5.9.3 = 锚点验证过但整文件哈希漂移的那一版;故意不存哈希
        check(t.knownGoodPacks != null
                        && PackFingerprint.isKnownGood("ComplementaryReimagined_r5.9.3.zip", t.knownGoodPacks),
                "已知良好清单含用户实测版(锚点验证): " + t.knownGoodPacks);
        check(t.knownGoodPacks.stream().noneMatch(k -> k.matches("[0-9a-f]{16}")),
                "已知良好清单条目 = 包名而非哈希(硬裁定②:F6 原始文件不是运行时 oracle): " + t.knownGoodPacks);
        // 2026-09-03 晚:内联核心从"DoLighting 函数体内"搬到"顶点 main 体内
        // GetLightMapCoordinates 行后"(实机三连 missing ';' at '{':函数体内声明 +
        // 宿主 #ifdef/#endif 包裹下的 AST 声明解析失败;顶点 main 体是持续编译的
        // 真分支,//Program// 锚在 patchSodium 输入侧是注释行,文本不存在=Iris
        // jcpp/合并阶段已剥离注释,selector 零命中=零注入无崩溃)。片元 DoLighting
        // FRAGMENT main 不动,调用点仍在其体内(逐片元执行)。
        // 2026-09-04 落盘取证修正://Program// 在运行时输入侧真实存在(顶点半体
        // 实测),旧判断("注释被剥离")有误;真正 root cause = 顶点/片元分半到达,
        // 单规则双锚点跨半体注定 miss。现拆两条规则,core 放文件域(词法前序)。
        String callOp = fRule.ops.get(3).content;
        // 2026-09-04 体感硬边修复:调用点 albedo 必须是 DoLighting 之前的 raw 反照率
        // (taclightRawAlbedo 快照,DoLighting 调用前一行声明):DoLighting 已把宿主光照
        // (太阳阴影/月光/火把/heldLight)乘进 color.rgb,锥光再以它为 albedo 叠加 =
        // 宿主阴影被二次放大,形成与灯锥无关的硬切分界(体感:草地石板中央横贯亮暗带)。
        // 旧断言(color.rgb)已作废,见坑100。
        check(callOp.contains("taclight_surface_lighting(viewPos, taclightRawAlbedo, normalize(normalM)")
                        && callOp.contains("0.55") && callOp.contains("0.15"),
                "调用点 = DoLighting 前 raw albedo×GAIN×shoulder3(本家同参,无 2048 除法)");
        check(!callOp.contains("taclight_surface_lighting(viewPos, color.rgb"),
                "调用点禁用 DoLighting 后 color.rgb 作 albedo(宿主光照二次放大=硬边,坑100)");
        check(!callOp.contains("/ 2048.0"),
                "调用点无 /2048(Complementary 前向光照无 iterationT 式输出前除法)");
        // 2026-09-04 实体半透明修复:entities/hand 调用点须乘 color.a(alpha 门)——
        // 地形 alpha 恒 1 行为不变;半透处锥光跟压,不再有透层感。影子由宿主 DoLighting 给出。
        for (int fi = 1; fi <= 2; fi++) {
            TemplateLibrary.FileRule er = t.files.get(fi);
            check(er.selectorCount != null && er.selectorCount == 1 && er.ops.size() == 4,
                    "实体/手部规则形态 = selector 唯一 + 4 算子(raw 快照+extension+内联+调用)(" + er.file + ")");
            String ecall = er.ops.get(3).content;
            check(ecall.contains("taclight_surface_lighting(viewPos, taclightRawAlbedo, normalize(normalM)")
                            && ecall.contains("* color.a"),
                    "实体/手部调用点 raw albedo×alpha 门: " + er.file);
        }
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

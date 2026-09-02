package dev.taclight.interop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * 内联核心契约(方案C 里程碑2,计划文档 §3/§6)。
 * 钉死:resources 内联副本与 pack/shaders/lib 真源逐字节一致(SHA-256 对账,防漂移)/
 * 内联文本形态(math+core 拼接去 include 行 + prelude):无 #version、无残留 #include、
 * SSBO 声明在、遮挡钩子函数化、GAIN 标定在、入口定义在、花括号平衡、
 * 指令全解析(坑80:patchComposite 输入已过 jcpp,AST 阶段禁活指令)。
 */
public class InlineCoreContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        byte[] coreRes = resource("/shader_patches/inline/taclight_core.glsl");
        byte[] mathRes = resource("/shader_patches/inline/taclight_math.glsl");

        check(coreRes != null && mathRes != null, "内联资源文件存在(gradle copyInlineCore 产物)");
        if (coreRes == null || mathRes == null) {
            System.out.println("InlineCoreContract: FAIL(资源缺失)");
            return;
        }
        byte[] coreSrc = Files.readAllBytes(Path.of("pack/shaders/lib/taclight_core.glsl"));
        byte[] mathSrc = Files.readAllBytes(Path.of("pack/shaders/lib/taclight_math.glsl"));
        check(MessageDigest.isEqual(sha256(coreRes), sha256(coreSrc)),
                "内联 core 与 pack/shaders/lib/taclight_core.glsl 逐字节一致");
        check(MessageDigest.isEqual(sha256(mathRes), sha256(mathSrc)),
                "内联 math 与 pack/shaders/lib/taclight_math.glsl 逐字节一致");

        String inline = TemplateLibrary.inlineCoreText();
        check(!inline.lines().anyMatch(l -> l.trim().startsWith("#version")),
                "内联文本不携带 #version(版本由模板算子管理)");
        check(!inline.contains("#include \"/lib/taclight_math.glsl\""),
                "core 的 math include 行已剥离(内联免 include 解析)");
        // 坑80(2026-09-03 实机):Iris 1.7 对 composite 族在 patchComposite 前跑 jcpp,
        // 指令已解析;注入发生在其后,AST 阶段出现活 #define = ShaderCompileException
        // "Unparsed preprocessor directives" → 整包禁用。内联文本必须指令全解析形态。
        check(inline.lines().noneMatch(l -> l.trim().startsWith("#")),
                "内联文本零预处理指令行(坑80:jcpp 后 AST 阶段禁指令)");
        check(inline.lines().noneMatch(l -> l.trim().startsWith("uniform")),
                "内联文本零 uniform 声明行(宿主 composite 必声明 Iris 标准附件,重复 = C1038 冲突)");
        check(!inline.contains("TACLIGHT_MATH_INCLUDED")
                        && !inline.contains("TACLIGHT_CORE_INCLUDED"),
                "include 守卫无残留(指令全解析)");
        check(inline.contains("layout(std430, binding = 7)"), "SSBO binding=7 声明在");
        check(inline.contains("float taclight_occlusion_at(vec2 uv) { return 1.0; }")
                        && inline.contains("taclight_occlusion_at(suv)"),
                "遮挡钩子函数化(未适配包保守 1.0,主遮挡=体素 DDA;调用点已改写)");
        check(inline.contains("const float TACLIGHT_LIGHT_GAIN = 2.2;"),
                "prelude 提供 GAIN 标定 const(core 无兜底,计划 §3)");
        check(inline.contains("const uint TACLIGHT_FLAG_HAS_DATA = 1u;")
                        && inline.contains("const float TACLIGHT_ATTEN_K = 2.0;"),
                "对象式宏 → const 常量(uint/float 类型推断)");
        check(inline.contains("vec3 taclight_surface_lighting"), "照明主入口定义在");
        long open = inline.chars().filter(c -> c == '{').count();
        long close = inline.chars().filter(c -> c == '}').count();
        check(open == close && open > 0, "花括号平衡(防注入后 Iris 静默禁包): " + open + "/" + close);
        check(inline.indexOf("taclight_surface_lighting") > inline.indexOf("layout(std430"),
                "定义顺序:SSBO 声明先于入口(词法前序=GLSL 可用)");
        check(inline.indexOf("taclight_occlusion_at(vec2") < inline.indexOf("taclight_occlusion_at(suv)"),
                "定义顺序:遮挡函数先于调用点(GLSL 词法前序)");

        System.out.println("InlineCoreContract: ALL PASS (" + checks + " checks)");
    }

    private static byte[] resource(String path) throws Exception {
        try (var in = InlineCoreContract.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }

    @SuppressWarnings("unused")
    private static String readUtf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}

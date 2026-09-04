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
                        && inline.contains("const float TACLIGHT_ATTEN_K = 5.0;"),
                "对象式宏 → const 常量(uint/float 类型推断;ATTEN_K=5.0 真实感调参 2026-09-03)");
        check(inline.contains("vec3 taclight_surface_lighting"), "照明主入口定义在");
        long open = inline.chars().filter(c -> c == '{').count();
        long close = inline.chars().filter(c -> c == '}').count();
        check(open == close && open > 0, "花括号平衡(防注入后 Iris 静默禁包): " + open + "/" + close);
        check(inline.indexOf("taclight_surface_lighting") > inline.indexOf("layout(std430"),
                "定义顺序:SSBO 声明先于入口(词法前序=GLSL 可用)");
        check(inline.indexOf("taclight_occlusion_at(vec2") < inline.indexOf("taclight_occlusion_at(suv)"),
                "定义顺序:遮挡函数先于调用点(GLSL 词法前序)");

        forwardSlim();

        System.out.println("InlineCoreContract: ALL PASS (" + checks + " checks)");
    }

    private static byte[] resource(String path) throws Exception {
        try (var in = InlineCoreContract.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** 前向精简版契约(gbuffers_terrain AST 崩溃减负,2026-09-03 Complementary 实机):
     * 坐标/衰减/肩部数学与完整版逐词一致(共享真源,不漂移)+ surface 前向重写 +
     * SSO/GGX/绿锥/体素 DDA 不进前向(恒可见桩,遮挡由宿主 DoLighting 主管) +
     * 零指令 + 花括号平衡 + 锚点缺失抛异常 fail-safe。 */
    private static void forwardSlim() {
        String fwd = TemplateLibrary.inlineCoreTextForward();
        check(fwd.contains("inline-core-forward"),
                "前向精简块含 marker(幂等锚)");
        check(fwd.contains("const float TACLIGHT_ATTEN_K = 2.0;"),
                "前向精简 prelude K=2.0(2026-09-04 用户体感:远射,中段抬起不涨峰值;完整版保持 5.0)");
        for (String anchor : new String[]{
                "layout(std430, binding = 7)", "vec3 taclight_world_to_scene(",
                "vec3 taclight_scene_to_view(", "vec3 taclight_view_to_world(",
                "float taclight_attenuation(", "vec3 taclight_soft_knee3(",
                "vec3 taclight_shoulder3(", "float taclight_vox_transmit(",
                "vec3 taclight_surface_lighting(vec3 fragView"}) {
            check(fwd.contains(anchor), "前向精简含锚点: " + anchor);
        }
        // 禁入项:gbuffers AST 高危面(SSO 主循环/自体豁免常数/GGX/绿锥宏/
        // 整数位运算 DDA ——实机三连 missing ';' at '{' 20:54/21:06/21:26;
        // 标量 DDA(float/int 步进,2026-09-04 穿墙修复)允许进前向:无 ivec3/bvec3/
        // 位运算(idx>>4 同款行),分类/衰减与完整版同源。
        // 注:SSBO 声明块注释含 idx>>4 字样(纯注释,零语句),故只查 DDA 索引语句
        // `voxData[idx >> 4]` 带空格下标者(声明 `voxData[]`/标量 `voxData[word]` 不在黑名单内)
        check(!fwd.contains("taclight_sample_shadow")
                        && !fwd.contains("TACLIGHT_SSO_SELF_FREE")
                        && !fwd.contains("taclight_ggx")
                        && !fwd.contains("GREEN_CONE")
                        && !fwd.contains("voxData[idx >> 4]")
                        && !fwd.contains("ivec3(") && !fwd.contains("bvec3")
                        && !fwd.contains("taclight_sso("),
                "前向精简不含 SSO/GGX/绿锥/整数位运算 DDA(标量 DDA 除外)");
        check(fwd.lines().noneMatch(l -> l.trim().startsWith("#")),
                "前向精简零预处理指令行(坑80 同理适用 gbuffers AST)");
        check(fwd.lines().noneMatch(l -> l.trim().startsWith("uniform")),
                "前向精简零 uniform 声明行(宿主 gbuffers 已声明 Iris 标准附件)");
        // shoulder3 源码注释含 tanh 字样(恒等式说明),故只断言"无 tanh 调用":
        // 含 tanh( 且行首非 // 注释 —— exp 恒等式实现,GLSL 1.30 路径安全
        check(fwd.lines().noneMatch(l -> l.contains("tanh(") && !l.trim().startsWith("//")),
                "前向精简无 tanh 调用行(注释提及除外,exp 恒等式实现)");
        long open = fwd.chars().filter(c -> c == '{').count();
        long close = fwd.chars().filter(c -> c == '}').count();
        check(open == close && open > 0, "前向精简花括号平衡: " + open + "/" + close);
        // 同一数学:精简版 attenuation/shoulder3 与完整版逐词一致
        // (子串抽取同一 directiveFree 真源,防"两份数学漂移");体素 DDA 不进前向,
        // 前向恒可见桩与完整版真 DDA 语义不同是设计使然(遮挡由宿主 DoLighting 主管)
        String full = TemplateLibrary.inlineCoreText();
        for (String fn : new String[]{"float taclight_attenuation(",
                "vec3 taclight_shoulder3("}) {
            check(extractFn(full, fn).equals(extractFn(fwd, fn)),
                    "前向/完整版同函数逐词一致: " + fn);
        }
        check(extractFn(fwd, "float taclight_vox_fetch(").replaceAll("\\s+", "").contains("voxData[word]"),
                "前向标量取数 taclight_vox_fetch 经 voxData[word] 读 2bit 分类(无位运算)");
        String fwdVox = extractFn(fwd, "float taclight_vox_transmit(").replaceAll("\\s+", "");
        check(fwdVox.contains("voxOrigin.w<=0.0") && fwdVox.contains("taclight_vox_fetch(")
                        && fwdVox.contains("return0.0") && fwdVox.contains("T*=0.40") && fwdVox.contains("T*=0.75"),
                "前向 vox_transmit = 标量 DDA(栅格无效 -1/实心 0/树叶 0.4/植被 0.75,2026-09-04 穿墙修复)");
    }

    /** 按签名抽取函数体(花括号配平),契约级白盒比对。 */
    private static String extractFn(String src, String sig) {
        int s = src.indexOf(sig);
        if (s < 0) return "";
        int b = src.indexOf('{', s);
        int depth = 0;
        for (int i = b; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(s, i + 1);
            }
        }
        return "";
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

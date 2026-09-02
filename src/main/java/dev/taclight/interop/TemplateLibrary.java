package dev.taclight.interop;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 模板库(方案C 里程碑2):resources 内 per-family 补丁模板(路线 P JSON 格式复活)。
 * &lt;INLINE_CORE&gt; 占位符在加载期替换为内联照明核心(prelude+math+core,见 inlineCoreText)。
 * 任何解析/字段/形态问题 = empty(不注入)——未知包安全,调用方零动作。
 * 手写字段校验而非 Gson 反射直绑:坏模板必须在加载期被拒,而不是注入期炸。
 */
public final class TemplateLibrary {
    public static final String INLINE_CORE_PLACEHOLDER = "<INLINE_CORE>";
    private static final String TEMPLATE_DIR = "/shader_patches/templates/";

    public static final class Op {
        public String op;
        public String anchor;
        public String content;
    }

    public static final class FileRule {
        public String file;
        public String selector;
        public Integer selectorCount;
        public List<Op> ops;
    }

    public static final class Template {
        public String familyId;
        public String packName;
        public Map<String, String> packHash;
        public List<FileRule> files;
    }

    private static volatile String inlineCoreCache;

    private TemplateLibrary() {}

    /** 模板索引(index.txt 每行一个文件名)全量加载;缺索引/坏行静默跳过。 */
    public static List<Template> loadAll() {
        List<Template> out = new ArrayList<>();
        try (InputStream in = TemplateLibrary.class.getResourceAsStream(TEMPLATE_DIR + "index.txt")) {
            if (in == null) return out;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                load(TEMPLATE_DIR + s).ifPresent(out::add);
            }
        } catch (Exception ignored) {
            // 索引读不到 = 无模板 = 零注入(与未知包同语义)
        }
        return out;
    }

    public static Optional<Template> load(String resourcePath) {
        try (InputStream in = TemplateLibrary.class.getResourceAsStream(resourcePath)) {
            if (in == null) return Optional.empty();
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 解析并校验模板 JSON;INLINE_CORE 占位符在此替换。任何问题 = empty。 */
    public static Optional<Template> fromJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Template t = new Template();
            t.familyId = reqString(root, "familyId");
            t.packName = reqString(root, "packName");
            if (!root.has("files") || !root.get("files").isJsonArray()
                    || root.getAsJsonArray("files").size() == 0) {
                return Optional.empty();
            }
            if (root.has("packHash") && root.get("packHash").isJsonObject()) {
                t.packHash = new java.util.LinkedHashMap<>();
                for (var e : root.getAsJsonObject("packHash").entrySet()) {
                    t.packHash.put(e.getKey(), e.getValue().getAsString());
                }
            }
            t.files = new ArrayList<>();
            for (var fe : root.getAsJsonArray("files")) {
                if (!fe.isJsonObject()) return Optional.empty();
                JsonObject fo = fe.getAsJsonObject();
                FileRule rule = new FileRule();
                rule.file = reqString(fo, "file");
                rule.selector = fo.has("selector") && !fo.get("selector").isJsonNull()
                        ? fo.get("selector").getAsString() : null;
                rule.selectorCount = fo.has("selectorCount") && !fo.get("selectorCount").isJsonNull()
                        ? fo.get("selectorCount").getAsInt() : null;
                if (!fo.has("ops") || !fo.get("ops").isJsonArray()
                        || fo.getAsJsonArray("ops").size() == 0) {
                    return Optional.empty();
                }
                rule.ops = new ArrayList<>();
                for (var oe : fo.getAsJsonArray("ops")) {
                    if (!oe.isJsonObject()) return Optional.empty();
                    JsonObject oo = oe.getAsJsonObject();
                    Op op = new Op();
                    op.op = reqString(oo, "op");
                    op.anchor = oo.has("anchor") && !oo.get("anchor").isJsonNull()
                            ? oo.get("anchor").getAsString() : null;
                    op.content = reqString(oo, "content");
                    if ("insertAtEnd".equals(op.op)) {
                        if (op.content == null) return Optional.empty();
                    } else if (op.anchor == null || op.content == null) {
                        return Optional.empty();
                    }
                    op.content = op.content.replace(INLINE_CORE_PLACEHOLDER, inlineCoreText());
                    rule.ops.add(op);
                }
                t.files.add(rule);
            }
            return Optional.of(t);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 内联照明核心 = marker 行 + prelude + math + core(剥掉 core 对 math 的 #include 行,
     * ShaderCoreContract 钉死 core 仅此一行 include),并做<b>指令全解析</b>变换:
     * include 守卫删除、对象式宏 → const 常量(uint/float 推断)、遮挡钩子宏 → 真函数。
     * <p>坑80(2026-09-03 实机):Iris 1.7 对 composite 族源在 patchComposite <b>之前</b>
     * 完成 jcpp 预处理,其后的 transformInternal AST 阶段遇到活 #define 直接
     * ShaderCompileException("Unparsed preprocessor directives")→ 整包禁用。自研包
     * 不受影响(其指令被 jcpp 正常解析);运行时注入在 jcpp 之后,故内联文本必须
     * 零指令形态。资源由 gradle copyInlineCore 从 pack/shaders/lib 拷入
     * (InlineCoreContract SHA 对账防漂移)。
     */
    public static String inlineCoreText() {
        String r = inlineCoreCache;
        if (r != null) return r;
        String math = readResource("/shader_patches/inline/taclight_math.glsl");
        String core = readResource("/shader_patches/inline/taclight_core.glsl");
        if (math == null || core == null) {
            throw new IllegalStateException("inline core resources missing — copyInlineCore 未执行?");
        }
        String prelude = "// TACLIGHT interop prelude(外包适配:无 colortex3,SSO 走 core 保守默认)\n"
                + "const float TACLIGHT_LIGHT_GAIN = 2.2;\n";
        String coreNoInclude = core.lines()
                .filter(l -> !l.trim().startsWith("#include"))
                .collect(Collectors.joining("\n"));
        r = "/* " + PatchExecutor.MARKER + " inline-core (injected by TacLight interop) */\n"
                + prelude + directiveFree(math) + "\n" + directiveFree(coreNoInclude) + "\n";
        inlineCoreCache = r;
        return r;
    }

    /** 遮挡钩子的运行时形态:宏不可用(jcpp 已过),改真函数;调用点同步改写。 */
    private static final String OCCLUSION_FN =
            "float taclight_occlusion_at(vec2 uv) { return 1.0; }";

    /**
     * 指令全解析变换(坑80):仅处理本库自有资源,指令清单固定(InlineCoreContract 盘点)。
     * ①include 守卫(#ifndef/#define/#endif 携 TACLIGHT_*_INCLUDED)整行删除;
     * ②TACLIGHT_OCCLUSION_AT 三行块(ifndef/define/endif)→ 单行真函数;
     * ③其余对象式 #define → const 常量(值带 u 后缀 = uint,含小数点 = float,否则 int);
     * ④调用点 TACLIGHT_OCCLUSION_AT( → taclight_occlusion_at(;
     * ⑤任何未识别指令行 = 抛异常(模板被拒 = 零注入,fail-safe)。
     * 产物再全量扫描一遍:任何以 # 开头的行都算实现 bug。
     */
    private static String directiveFree(String src) {
        StringBuilder out = new StringBuilder(src.length() + 64);
        boolean inOcclusionBlock = false;
        for (String line : src.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#")) {
                if (t.matches("#\\s*(ifndef|define|endif).*TACLIGHT_(MATH|CORE)_INCLUDED.*")) {
                    continue; // ① include 守卫
                }
                if (t.matches("#\\s*ifndef\\s+TACLIGHT_OCCLUSION_AT.*")) {
                    inOcclusionBlock = true;
                    continue; // ② 块开
                }
                if (inOcclusionBlock && t.matches("#\\s*define\\s+TACLIGHT_OCCLUSION_AT.*")) {
                    out.append(OCCLUSION_FN).append('\n');
                    continue; // ② 宏定义 → 函数
                }
                if (inOcclusionBlock && t.matches("#\\s*endif.*")) {
                    inOcclusionBlock = false;
                    continue; // ② 块收
                }
                var m = java.util.regex.Pattern
                        .compile("#\\s*define\\s+(TACLIGHT_[A-Za-z_]+)\\s+([^\\s/]+)\\s*(//.*)?")
                        .matcher(t);
                if (m.matches()) {
                    out.append(constFromDefine(m.group(1), m.group(2), m.group(3) == null ? "" : m.group(3)))
                            .append('\n');
                    continue; // ③ 对象式宏 → const
                }
                throw new IllegalStateException("内联资源含未识别指令行(坑80 fail-safe): " + t);
            }
            if (t.startsWith("uniform")) {
                continue; // Iris 标准附件(gbuffer 矩阵/cameraPosition/depthtex1)宿主 composite
                          // 必已声明,重复声明 = C1038 冲突(02:20 实机);SSBO 是 layout 声明不受影响
            }
            out.append(line).append('\n');
        }
        String result = out.toString().replace("TACLIGHT_OCCLUSION_AT(", "taclight_occlusion_at(");
        for (String l : result.split("\r?\n", -1)) {
            if (l.trim().startsWith("#")) {
                throw new IllegalStateException("指令全解析变换后仍有指令行(实现 bug): " + l);
            }
        }
        return result;
    }

    /** 值类型推断:u/U 后缀 = uint,含 . 或 e/E = float,否则 int。注释原样保留。 */
    private static String constFromDefine(String name, String value, String comment) {
        String type;
        if (value.matches(".*[uU]")) type = "uint";
        else if (value.matches(".*[.eE].*")) type = "float";
        else type = "int";
        return "const " + type + " " + name + " = " + value + ";" + (comment.isEmpty() ? "" : " " + comment);
    }

    private static String reqString(JsonObject o, String field) {
        if (!o.has(field) || o.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        String v = o.get(field).getAsString();
        if (v == null || v.isBlank()) throw new IllegalArgumentException("blank field: " + field);
        return v;
    }

    private static String readResource(String path) {
        try (InputStream in = TemplateLibrary.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}

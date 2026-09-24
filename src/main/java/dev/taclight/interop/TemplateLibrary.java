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
        /** 备选锚点(逐字;2026-09-19 新增:运行时/原始文件两种形态,见 PatchExecutor 类注释)。 */
        public List<String> anchors;
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
        /** 注入后必须出现的符号(注后自检;2026-09-19 新增)。空 = 只查 marker/括号。 */
        public List<String> requiredSymbols = List.of();
        /**
         * 已知良好清单(2026-09-19 新增,Lead 硬裁定②):<b>锚点验证过</b>的包名原样
         * (如 {@code ComplementaryReimagined_r5.9.3.zip}),按 {@link PackFingerprint#packMatchKey}
         * 比较。语义 = "这版哈希虽与 packHash 不符,但锚点已离线/实机验证可注入" ⇒ 走锚点通道
         * 并标 known-good。<b>故意不存整文件哈希</b>(F6:原始文件不是运行时 oracle)。
         */
        public List<String> knownGoodPacks = List.of();
    }

    private static volatile String inlineCoreCache;
    private static volatile String inlineCoreForwardCache;

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
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), resourcePath);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 解析并校验模板 JSON;INLINE_CORE 占位符在此替换。任何问题 = empty。 */
    public static Optional<Template> fromJson(String json) {
        return fromJson(json, null);
    }

    private static Optional<Template> fromJson(String json, String resourcePath) {
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
            if (root.has("requiredSymbols") && root.get("requiredSymbols").isJsonArray()) {
                List<String> syms = new ArrayList<>();
                for (var se : root.getAsJsonArray("requiredSymbols")) {
                    if (se.isJsonPrimitive()) syms.add(se.getAsString());
                }
                t.requiredSymbols = List.copyOf(syms);
            }
            if (root.has("knownGoodPacks") && root.get("knownGoodPacks").isJsonArray()) {
                List<String> known = new ArrayList<>();
                for (var ke : root.getAsJsonArray("knownGoodPacks")) {
                    if (ke.isJsonPrimitive()) {
                        String k = ke.getAsString();
                        if (k != null && !k.isBlank()) known.add(k);
                    }
                }
                t.knownGoodPacks = List.copyOf(known);
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
                    if (oo.has("anchors") && oo.get("anchors").isJsonArray()) {
                        List<String> alts = new ArrayList<>();
                        for (var ae : oo.getAsJsonArray("anchors")) {
                            if (ae.isJsonPrimitive()) {
                                String a = ae.getAsString();
                                if (a != null && !a.isBlank() && !a.equals(op.anchor)) alts.add(a);
                            }
                        }
                        op.anchors = List.copyOf(alts);
                    } else {
                        op.anchors = List.of();
                    }
                    if ("insertAtEnd".equals(op.op)) {
                        if (op.content == null) return Optional.empty();
                    } else if (op.anchor == null || op.content == null) {
                        return Optional.empty();
                    }
                    op.content = op.content.replace(INLINE_CORE_PLACEHOLDER,
                            inlineFor(resourcePath));
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
     * <p>gbuffers 前向注入(2026-09-03 Complementary 实机):patchSodium 收到的
     * gbuffers 源同样走 transformInternal AST 解析,<b>函数定义体同样禁活指令 +
     * 复杂控制流风险</b>(SSO 24 步循环+自体胶囊分支/DDA 位运算/ggx 高次幂在
     * gbuffers_terrain AST 下 `missing ';' at '{'` 崩溃,同一内联在 composite
     * 路径零报错)。故内联分两种形态,模板按注入目标选择:
     * {@link #inlineCoreText()} = 完整版(composite/deferred/final 路径);
     * {@link #inlineCoreTextForward()} = 前向精简版(gbuffers 路径):
     * 坐标换算/衰减/软膝/肩部(恒等式 exp 版,无 tanh)/surface 主循环直连体素 DDA
     * (SSO/GGX/绿锥不进前向:voxel 无效(-1)即按可见=1,GGX 高光不计)。
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

    /** 模板占位符替换统一走此函数:前向模板(gbuffers)用精简版,其余用完整版。 */
    public static String inlineFor(String templatePath) {
        if (templatePath != null && templatePath.contains("complementary")) {
            return inlineCoreTextForward();
        }
        return inlineCoreText();
    }

    /** 前向精简版:从 core 真源抽取"坐标换算+衰减+软膝+肩部+DDA+surface"子集,
     *  surface 体重写为直连 DDA(SSO/GGX/绿锥不进前向,voxel 无效即按可见)。
     * 抽取是白名单行级过滤(函数签名锚),core 真源改动若致锚点缺失 = 抛异常 =
     * 模板被拒零注入(InlineCoreContract 同步钉死锚点存在,fail-safe)。 */
    public static String inlineCoreTextForward() {
        String r = inlineCoreForwardCache;
        if (r != null) return r;
        String core = readResource("/shader_patches/inline/taclight_core.glsl");
        if (core == null) {
            throw new IllegalStateException("inline core resources missing — copyInlineCore 未执行?");
        }
        String coreNoInclude = core.lines()
                .filter(l -> !l.trim().startsWith("#include"))
                .collect(Collectors.joining("\n"));
        String slim = slimForwardCore(directiveFree(coreNoInclude));
        String prelude = "// TACLIGHT interop prelude-forward(gbuffers 前向精简版,SSO/GGX 不进前向)\n"
                + "const float TACLIGHT_LIGHT_GAIN = 2.2;\n"
                + "const float TACLIGHT_ATTEN_K = 20.0; // 2026-09-06 用户扫参冻结(主包同值收敛)\n"
                + "const float TACLIGHT_KNEE_GAIN = 2.0;\n"
                + "const float TACLIGHT_VOX_FUZZ = 0.35;\n";
        r = "/* " + PatchExecutor.MARKER + " inline-core-forward (injected by TacLight interop) */\n"
                + prelude + slim + "\n";
        inlineCoreForwardCache = r;
        return r;
    }

    /** 白名单抽取:SSBO 声明 + struct + 坐标五函数 + 衰减/软膝/肩部 + DDA +
     * surface-forward(重写的直连 DDA 版)。丢弃 SSO/GGX/绿锥/debug 函数。 */
    private static String slimForwardCore(String free) {
        List<String> keep = new java.util.ArrayList<>();
        String[] lines = free.split("\r?\n", -1);
        int i = 0;
        while (i < lines.length) {
            String t = lines[i].trim();
            if (t.startsWith("struct TacLightSpot")) {
                int s = i; while (i < lines.length && !lines[i].contains("};")) i++; i++;
                keep.add(String.join("\n",
                        java.util.Arrays.copyOfRange(lines, s, Math.min(i, lines.length))));
                continue;
            }
            if (t.startsWith("layout(std430, binding = 7)")) {
                int s = i; while (i < lines.length && !lines[i].contains("};")) i++; i++;
                keep.add(String.join("\n",
                        java.util.Arrays.copyOfRange(lines, s, Math.min(i, lines.length))));
                continue;
            }
            String fn = null;
            for (String sig : new String[]{
                    "vec3 taclight_world_to_scene(", "vec3 taclight_scene_to_view(",
                    "vec2 taclight_view_to_uv(", "vec3 taclight_depth_to_view(",
                    "vec3 taclight_view_to_world(", "float taclight_attenuation(",
                    "float taclight_soft_knee(", "vec3 taclight_soft_knee3(",
                    "vec3 taclight_shoulder3("}) {
                if (t.contains(sig)) { fn = sig; break; }
            }
            if (fn != null) {
                int s = i; int depth = 0; boolean started = false;
                while (i < lines.length) {
                    for (char c : lines[i].toCharArray()) {
                        if (c == '{') { depth++; started = true; }
                        else if (c == '}') depth--;
                    }
                    i++;
                    if (started && depth == 0) break;
                }
                keep.add(String.join("\n",
                        java.util.Arrays.copyOfRange(lines, s, Math.min(i, lines.length))));
                continue;
            }
            // 体素 DDA 整函数跳过(ivec3/bvec3/位运算在 gbuffers_terrain AST 下
            // `missing ';' at '{'` 三连崩溃 20:54/21:06/21:26;前向遮挡由宿主
            // DoLighting 主管,此处恒可见。锚点上一行注释块 DDA 字样无害(纯注释)。
            if (t.contains("float taclight_vox_transmit(")) {
                int depth = 0; boolean started = false;
                while (i < lines.length) {
                    for (char c : lines[i].toCharArray()) {
                        if (c == '{') { depth++; started = true; }
                        else if (c == '}') depth--;
                    }
                    i++;
                    if (started && depth == 0) break;
                }
                continue;
            }
            // surface 主循环:丢弃,改用下面的 forward 重写版
            if (t.contains("vec3 taclight_surface_lighting(")) {
                int depth = 0; boolean started = false;
                while (i < lines.length) {
                    for (char c : lines[i].toCharArray()) {
                        if (c == '{') { depth++; started = true; }
                        else if (c == '}') depth--;
                    }
                    i++;
                    if (started && depth == 0) break;
                }
                continue;
            }
            i++;
        }
        String joined = String.join("\n", keep);
        for (String need : new String[]{
                "struct TacLightSpot", "layout(std430, binding = 7)",
                "vec3 taclight_world_to_scene(", "vec3 taclight_scene_to_view(",
                "vec3 taclight_view_to_world(", "float taclight_attenuation(",
                "vec3 taclight_soft_knee3(", "vec3 taclight_shoulder3("}) {
            if (!joined.contains(need)) {
                throw new IllegalStateException("前向精简抽取缺锚点(core 真源改动?): " + need);
            }
        }
        // 体素 DDA 不进前向(ivec3/bvec3/位运算 AST 高危):上方白名单循环已整体跳过,
        // 此处只需追加单行恒可见桩(遮挡由宿主 DoLighting 主管)。
        return joined + "\n" + FORWARD_VOX_STUB + FORWARD_SURFACE;
    }

    /** 前向 surface:漫反射单项 + 锥判定 + 距离衰减 + 标量体素 DDA 遮挡。
     * 无 SSO(屏参/depthtex 在 gbuffers 地形 AST 下高危)/无 GGX(高次幂)。
     * 体素 DDA 用纯 float/int 标量步进(Amanatides-Woo,无 ivec3/bvec3/位运算——
     * 完整版三连崩溃 `missing ';' at '{'` 20:54/21:06/21:26 的 AST 高危面;前向
     * 穿墙漏光 2026-09-04 用户实机:恒可见桩 return 1.0 致墙后也亮,宿主 DoLighting
     * 只管太阳阴影不管 SSBO 灯,故 DDA 必须回前向)。分类/衰减与完整版同源
     * (实心深穿一票否决/树叶 0.4/植被 0.75)。掠边软化带已搬(与主线 TACLIGHT_VOX_FUZZ 0.35
 * 同源,穿透<0.35 按比例放行,≥0.35 仍 T=0;修 2026-09-04 墙面阴影破碎硬齿)。 */
    private static final String FORWARD_VOX_STUB =
            "float taclight_vox_fetch(vec3 cellCoords, vec3 dim) {\n"
            + "    if (cellCoords.x < 0.0 || cellCoords.y < 0.0 || cellCoords.z < 0.0) return -1.0;\n"
            + "    if (cellCoords.x >= dim.x || cellCoords.y >= dim.y || cellCoords.z >= dim.z) return -1.0;\n"
            + "    int ix = int(floor(cellCoords.x));\n"
            + "    int iy = int(floor(cellCoords.y));\n"
            + "    int iz = int(floor(cellCoords.z));\n"
            + "    int idx = ix + iy * int(dim.x) + iz * int(dim.x) * int(dim.y);\n"
            + "    int word = idx / 8;\n"
            + "    int slot = idx - word * 8;\n"
            + "    uint w = voxData[word];\n"
            // 2026-09-25 高度感知遮挡:4bit/体素(8 格/uint)。前向路径继续避开位运算(AST 高危),
            // 用 / 与 %(除 16)逐格下移,语义等价于主核的 `>> ((idx&7)*4) & 15u`。
            + "    for (int b = 0; b < 8; b++) { if (b >= slot) break; w = w / 16u; }\n"
            + "    float code = float(w % 16u);\n"
            + "    return code;\n"
            + "}\n"
            + "float taclight_vox_transmit(vec3 worldA, vec3 worldB) {\n"
            + "    if (voxOrigin.w <= 0.0) return -1.0;\n"
            + "    vec3 a = worldA - voxOrigin.xyz;\n"
            + "    vec3 b = worldB - voxOrigin.xyz;\n"
            + "    vec3 dim = vec3(float(voxMeta.x), float(voxMeta.y), float(voxMeta.z));\n"
            + "    if (a.x < 0.0 || a.y < 0.0 || a.z < 0.0) return -1.0;\n"
            + "    if (a.x >= dim.x || a.y >= dim.y || a.z >= dim.z) return -1.0;\n"
            + "    if (b.x < 0.0 || b.y < 0.0 || b.z < 0.0) return -1.0;\n"
            + "    if (b.x >= dim.x || b.y >= dim.y || b.z >= dim.z) return -1.0;\n"
            + "    vec3 dv = b - a;\n"
            + "    float len = length(dv);\n"
            + "    if (len < 0.0001) return 1.0;\n"
            + "    vec3 dir = dv / len;\n"
            + "    float cx = floor(a.x); float cy = floor(a.y); float cz = floor(a.z);\n"
            + "    float lx = floor(b.x - dir.x * 0.001);\n"
            + "    float ly = floor(b.y - dir.y * 0.001);\n"
            + "    float lz = floor(b.z - dir.z * 0.001);\n"
            + "    float sx = dir.x > 0.0 ? 1.0 : (dir.x < 0.0 ? -1.0 : 0.0);\n"
            + "    float sy = dir.y > 0.0 ? 1.0 : (dir.y < 0.0 ? -1.0 : 0.0);\n"
            + "    float sz = dir.z > 0.0 ? 1.0 : (dir.z < 0.0 ? -1.0 : 0.0);\n"
            + "    float ax = abs(dir.x); float ay = abs(dir.y); float az = abs(dir.z);\n"
            + "    float tdx = ax > 0.000000001 ? 1.0 / ax : 1000000000.0;\n"
            + "    float tdy = ay > 0.000000001 ? 1.0 / ay : 1000000000.0;\n"
            + "    float tdz = az > 0.000000001 ? 1.0 / az : 1000000000.0;\n"
            + "    float tmx = ax > 0.000000001 ? (dir.x > 0.0 ? (cx + 1.0 - a.x) : (a.x - cx)) * tdx : 1000000000.0;\n"
            + "    float tmy = ay > 0.000000001 ? (dir.y > 0.0 ? (cy + 1.0 - a.y) : (a.y - cy)) * tdy : 1000000000.0;\n"
            + "    float tmz = az > 0.000000001 ? (dir.z > 0.0 ? (cz + 1.0 - a.z) : (a.z - cz)) * tdz : 1000000000.0;\n"
            + "    float T = 1.0;\n"
            + "    for (int guard = 0; guard < 384; guard++) {\n"
            + "        float tNext = min(tmx, min(tmy, tmz));\n"
            + "        float stepX = 0.0; float stepY = 0.0; float stepZ = 0.0;\n"
            + "        float eps = max(0.000001, abs(tNext) * 0.000001);\n"
            + "        if (abs(tmx - tNext) <= eps) { stepX = sx; tmx += tdx; }\n"
            + "        if (abs(tmy - tNext) <= eps) { stepY = sy; tmy += tdy; }\n"
            + "        if (abs(tmz - tNext) <= eps) { stepZ = sz; tmz += tdz; }\n"
            + "        cx += stepX; cy += stepY; cz += stepZ;\n"
            + "        if (cx < 0.0 || cy < 0.0 || cz < 0.0) return T;\n"
            + "        if (cx >= dim.x || cy >= dim.y || cz >= dim.z) return T;\n"
            + "        if (cx == lx && cy == ly && cz == lz) return T;\n"
            + "        float code = taclight_vox_fetch(vec3(cx + 0.5, cy + 0.5, cz + 0.5), dim);\n"
            + "        float tExit = min(tmx, min(tmy, tmz));\n"
            + "        if (code == 3.0) {\n"
            + "            float penLen = min(tExit, len) - tNext;\n"
            + "            if (penLen < 0.0) penLen = 0.0;\n"
            + "            float f = penLen / 0.35;\n"
            + "            if (f > 1.0) f = 1.0;\n"
            + "            if (f >= 1.0) return 0.0;\n"
            + "            T *= 1.0 - f;\n"
            + "        }\n"
            // 薄板(4..15,2026-09-25 高度感知):射线在该格内的 y 区间与板区间相交 ⇒ 不透明全挡;
            // 从板顶上方掠过 ⇒ 放行。底薄板 4..11(lo=0,hi=(code−3)/8)、顶薄板 12..15(lo=(code−8)/8,hi=1)。
            + "        else if (code >= 4.0) {\n"
            + "            float lo = code >= 12.0 ? (code - 8.0) / 8.0 : 0.0;\n"
            + "            float hi = code >= 12.0 ? 1.0 : (code - 3.0) / 8.0;\n"
            + "            float yA = a.y + dir.y * tNext - cy;\n"
            + "            float yB = a.y + dir.y * tExit - cy;\n"
            + "            float yLo = min(yA, yB);\n"
            + "            float yHi = max(yA, yB);\n"
            + "            bool hit = (yHi - yLo <= 0.000001) ? (yLo >= lo && yLo < hi)\n"
            + "                                              : (min(yHi, hi) - max(yLo, lo) > 0.000001);\n"
            + "            if (hit) return 0.0;\n"
            + "        }\n"
            + "        else if (code == 2.0) T *= 0.40;\n"
            + "        else if (code == 1.0) T *= 0.75;\n"
            + "    }\n"
            + "    return T;\n"
            + "}\n";
    private static final String FORWARD_SURFACE =
            "vec3 taclight_surface_lighting(vec3 fragView, vec3 albedo, vec3 n,\n"
            + "                               float roughness, float metal, vec3 f0) {\n"
            + "    vec3 radiance = vec3(0.0);\n"
            + "    for (int i = 0; i < 8; i++) {\n"
            + "        if (float(i) >= lightCount) { break; }\n"
            + "        TacLightSpot L = lights[i];\n"
            + "        vec3 lightScene = taclight_world_to_scene(L.posRadius.xyz);\n"
            + "        vec3 lightView = taclight_scene_to_view(lightScene);\n"
            + "        vec3 toFrag = fragView - lightView;\n"
            + "        float dist = length(toFrag);\n"
            + "        float radius = L.posRadius.w;\n"
            + "        if (dist < radius && radius > 0.001) {\n"
            + "            vec3 lf = toFrag / max(dist, 0.0001);\n"
            + "            vec3 dirView = mat3(gbufferModelView) * (L.dirType.xyz / max(length(L.dirType.xyz), 0.0001));\n"
            + "            float spot = smoothstep(L.cone.x, L.cone.y, dot(lf, dirView));\n"
            + "            float ndl = dot(n, (vec3(0.0) - lf));\n"
            + "            if (spot > 0.001 && ndl > 0.0) {\n"
            + "                float vt = taclight_vox_transmit(L.posRadius.xyz, taclight_view_to_world(fragView));\n"
            + "                float vis = vt >= 0.0 ? vt : 1.0;\n"
            + "                vec3 lc = L.colorIntensity.rgb * L.colorIntensity.a;\n"
            + "                float attenK = L.cone.z > 0.0 ? L.cone.z : TACLIGHT_ATTEN_K;\n"
            + "                float atten = taclight_attenuation(dist, radius, attenK);\n"
            + "                vec3 contrib = ((albedo * (ndl * (1.0 - metal))) * lc) * (spot * atten * vis);\n"
            + "                radiance = radiance + taclight_soft_knee3(contrib, L.cone.w);\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    return radiance;\n"
            + "}\n";
    private static final String OCCLUSION_FN =
            "float taclight_occlusion_at(vec2 uv) { return 1.0; }";
    private static final String OCCL_TABLE_FN =
            "vec4 taclight_occl_table_row(vec3 rel) { return vec4(1e4); }   // 宿主无表:哨兵=全可见";

    /**
     * 指令全解析变换(坑80):仅处理本库自有资源,指令清单固定(InlineCoreContract 盘点)。
     * ①include 守卫(#ifndef/#define/#endif 携 TACLIGHT_*_INCLUDED)整行删除;
     * ②TACLIGHT_OCCLUSION_AT / TACLIGHT_OCCL_TABLE_AT 三行块(ifndef/define/endif)
     *    → 单行真函数(2026-09-06 方案二:表钩子,宿主无表 = 哨兵全可见);
     * ③其余对象式 #define → const 常量(值带 u 后缀 = uint,含小数点 = float,否则 int);
     * ④调用点 TACLIGHT_OCCLUSION_AT( → taclight_occlusion_at(;
     * ⑤任何未识别指令行 = 抛异常(模板被拒 = 零注入,fail-safe)。
     * 产物再全量扫描一遍:任何以 # 开头的行都算实现 bug。
     */
    private static String directiveFree(String src) {
        StringBuilder out = new StringBuilder(src.length() + 64);
        boolean inOcclusionBlock = false;
        boolean inTableBlock = false;
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
                if (t.matches("#\\s*ifndef\\s+TACLIGHT_OCCL_TABLE_AT.*")) {
                    inTableBlock = true;
                    continue; // ②' 表钩子块开(2026-09-06 方案二)
                }
                if (inTableBlock && t.matches("#\\s*define\\s+TACLIGHT_OCCL_TABLE_AT.*")) {
                    out.append(OCCL_TABLE_FN).append('\n');
                    continue; // ②' 宏定义 → 函数(宿主无表:哨兵=全可见)
                }
                if (inTableBlock && t.matches("#\\s*endif.*")) {
                    inTableBlock = false;
                    continue; // ②' 块收
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
        String result = out.toString()
                .replace("TACLIGHT_OCCLUSION_AT(", "taclight_occlusion_at(")
                .replace("TACLIGHT_OCCL_TABLE_AT(", "taclight_occl_table_row(");
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

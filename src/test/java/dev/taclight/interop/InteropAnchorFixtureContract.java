package dev.taclight.interop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>锚点层离线判据</b>(2026-09-19 interop 闸门换位;Lead 硬裁定:这是"zip 包可注入"唯一
 * 可离线判定的证据)。
 *
 * <p>做什么:</p>
 * <ol>
 *   <li>找 fixture 包根:系统属性 {@code taclight.fixture.complementary} → 用户 r5.9.3 zip
 *       (本机已知路径) → 仓库 {@code run/shaderpacks/ComplementaryReimagined}(目录包)→
 *       都没有则退化为<b>内置合成文本</b>(会大声标注,不静默跳过);</li>
 *   <li><b>离线 include 展开(超集口径)</b>:从 {@code shaders/world0/gbuffers_terrain.fsh}
 *       起递归展开 {@code #include "/..."},得到含 {@code #version}、{@code void DoLighting(}
 *       定义与 {@code DoLighting(} 调用点的文本(条件编译不求解 ⇒ 取超集,只用于"锚点在不在");</li>
 *   <li>对 Complementary 模板的 3 条文件规则跑 {@link PatchExecutor#applyDetailed}
 *       ⇒ 断言 {@code patched != null}(锚点逐字命中 + selectorCount 相符 + 注后自检通过);</li>
 *   <li><b>负控</b>:把 {@code void DoLighting(} 定义从 fixture 里去掉 ⇒ 必须失败,
 *       且失败原因指名该锚点(证明闸门有牙,不是"总能过");</li>
 *   <li><b>备选锚点必要性证据</b>:fixture 里版本行只有一种空格形态;只用双空格(旧模板形态)
 *       会失败、加上单空格备选后成功 ⇒ 证明 2026-09-19 的备选锚点是<b>承重</b>的。</li>
 * </ol>
 *
 * <p><b>如实边界(不得读成"真机已验证")</b>:</p>
 * <ul>
 *   <li>fixture 是<b>离线 include 展开的超集</b>,<b>不是</b> Iris 运行时逐字节文本
 *       (jcpp 预处理/条件编译/规范化未复现);</li>
 *   <li>运行时 {@code #version} 形态由既有实机取证(2026-09-03 dump 双空格)确认;
 *       本契约<b>两种空格都接受</b> ⇒ 该不确定项不再卡注入,但
 *       <b>r5.9.3 的真机运行时文本仍未经真机确认</b>(真机判据 = 日志出现
 *       {@code interop injected family=complementary pack=ComplementaryReimagined_r5.9.3.zip});</li>
 *   <li>r5.9.3 的<b>整文件哈希</b>与模板不符(3/3),故走的是 best-effort 锚点通道
 *       —— 本契约验的就是那条通道的判据。</li>
 * </ul>
 */
public class InteropAnchorFixtureContract {
    private static int checks;
    private static final String TERRAIN = "shaders/program/gbuffers_terrain.glsl";
    private static final String ENTITIES = "shaders/program/gbuffers_entities.glsl";
    private static final String HAND = "shaders/program/gbuffers_hand.glsl";

    public static void main(String[] args) throws Exception {
        Fixture fx = findFixture();
        String label = fx == null ? "synthetic(内置合成;真实包不可得)" : fx.label;
        System.out.println("  FIXTURE = " + label);
        TemplateLibrary.Template t = TemplateLibrary.load(
                "/shader_patches/templates/complementary-r5.9.json").orElse(null);
        check(t != null, "Complementary 模板可加载");
        if (t == null) return;
        check(t.files.size() == 3, "模板三文件规则(terrain/entities/hand)");

        String terrain = fx == null ? synthetic() : expand(fx.root, "shaders/world0/gbuffers_terrain.fsh");
        String entities = fx == null ? synthetic() : expand(fx.root, "shaders/world0/gbuffers_entities.fsh");
        String hand = fx == null ? synthetic() : expand(fx.root, "shaders/world0/gbuffers_hand.fsh");
        if (fx != null) {
            System.out.println("  fixture 字符数 terrain=" + terrain.length()
                    + " entities=" + entities.length() + " hand=" + hand.length()
                    + " sha16(terrain)=" + PackFingerprint.sha256Prefix16(terrain));
            check(terrain.contains("#version") && terrain.contains("void DoLighting("),
                    "离线展开含 #version 与 void DoLighting( 定义(include 链已展开)");
            check(terrain.contains("DoLighting(color, shadowMult, playerPos, viewPos,"),
                    "离线展开含模板 selector/调用点锚(与真包实测一致)");
        }

        // ---- 3 条规则:真闸门必须能过 ----
        checkRule("terrain", t, TERRAIN, terrain);
        checkRule("entities", t, ENTITIES, entities);
        checkRule("hand", t, HAND, hand);

        // ---- 负控:去掉 void DoLighting( 定义 ⇒ 必须失败且指名锚点 ----
        String broken = terrain.replace("void DoLighting(", "void DoLightingBROKEN(");
        PatchExecutor.Result neg = apply(t, TERRAIN, broken);
        check(!neg.ok() && neg.failure != null && neg.failure.contains("void DoLighting("),
                "★ 负控:去掉定义锚 ⇒ 失败且原因指名锚点: " + neg.failure);

        // ---- 备选锚点必要性:只用双空格(旧模板形态)在原始文件口径下必失败 ----
        TemplateLibrary.FileRule rule = ruleFor(t, TERRAIN);
        TemplateLibrary.Op versionOp = versionOp(rule);
        List<PatchExecutor.Op> doubleOnly = new ArrayList<>();
        for (TemplateLibrary.Op o : rule.ops) {
            if (o == versionOp) {
                doubleOnly.add(new PatchExecutor.Op(o.op, o.anchor, o.content)); // 不带备选
            } else {
                doubleOnly.add(new PatchExecutor.Op(o.op, o.anchor, o.content, o.anchors));
            }
        }
        PatchExecutor.Result noAlt = PatchExecutor.applyDetailed(terrain, rule.selector,
                rule.selectorCount, doubleOnly, t.requiredSymbols);
        PatchExecutor.Result withAlt = PatchExecutor.applyDetailed(terrain, rule.selector,
                rule.selectorCount, toOps(rule), t.requiredSymbols);
        boolean fixtureHasDouble = terrain.contains("#version  130");
        boolean fixtureHasSingle = terrain.contains("#version 130");
        check(fixtureHasSingle || fixtureHasDouble, "fixture 版本行存在(单空格或双空格)");
        check(!noAlt.ok() && withAlt.ok(),
                "★ 备选锚点承重:旧形态(仅 " + (fixtureHasDouble ? "双" : "单") + "空格)失败,"
                        + "加备选后成功 ⇒ 失败原因=" + noAlt.failure);

        // ---- 自检项本身在真模板上可判定(缺签名/重复 marker 必须被拒) ----
        PatchExecutor.Result missingSym = PatchExecutor.applyDetailed(terrain, rule.selector,
                rule.selectorCount, toOps(rule), List.of("taclight_definitely_missing_symbol("));
        check(!missingSym.ok() && missingSym.failure.contains("缺必需签名"),
                "自检在真实文本上生效(缺签名被拒): " + missingSym.failure);

        System.out.println("InteropAnchorFixtureContract: ALL PASS (" + checks + " checks) [FIXTURE=" + label + "]");
    }

    private static void checkRule(String what, TemplateLibrary.Template t, String file, String text) {
        TemplateLibrary.FileRule rule = ruleFor(t, file);
        check(rule != null, what + ": 模板含该文件规则");
        if (rule == null) return;
        PatchExecutor.Result res = apply(t, file, text);
        check(res.ok(), "★ " + what + ": 离线 fixture 上锚点全中 ⇒ 可注入(+"
                + res.injectedChars + " chars)"
                + (res.ok() ? "" : " 失败原因=" + res.failure));
        if (res.ok()) {
            int markers = count(res.patched, PatchExecutor.MARKER);
            check(markers >= 2, what + ": 注入后 marker=" + markers
                    + " 个(两个 patch 区标记 + 内联核自带一个;恒等式由 selfCheck 保证)");
        }
    }

    private static PatchExecutor.Result apply(TemplateLibrary.Template t, String file, String text) {
        TemplateLibrary.FileRule rule = ruleFor(t, file);
        if (rule == null) return new PatchExecutor.Result(null, "模板无该文件规则", 0, false);
        return PatchExecutor.applyDetailed(text, rule.selector, rule.selectorCount,
                toOps(rule), t.requiredSymbols);
    }

    private static List<PatchExecutor.Op> toOps(TemplateLibrary.FileRule rule) {
        List<PatchExecutor.Op> ops = new ArrayList<>();
        for (TemplateLibrary.Op o : rule.ops) {
            ops.add(new PatchExecutor.Op(o.op, o.anchor, o.content, o.anchors));
        }
        return ops;
    }

    private static TemplateLibrary.FileRule ruleFor(TemplateLibrary.Template t, String file) {
        for (TemplateLibrary.FileRule r : t.files) {
            if (file.equals(r.file)) return r;
        }
        return null;
    }

    private static TemplateLibrary.Op versionOp(TemplateLibrary.FileRule rule) {
        for (TemplateLibrary.Op o : rule.ops) {
            if (o.anchor != null && o.anchor.contains("#version")) return o;
        }
        return null;
    }

    private static int count(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    // ---------------- fixture 定位与离线 include 展开 ----------------

    private record Fixture(Path root, String label) {}

    private static Fixture findFixture() {
        String prop = System.getProperty("taclight.fixture.complementary");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop);
            if (Files.exists(p)) return new Fixture(p, "sysprop:" + p);
        }
        String[] candidates = {
                "E:\\temp\\mc-test\\.minecraft\\versions\\1.20.1-Forge\\shaderpacks\\ComplementaryReimagined_r5.9.3.zip",
                "run/shaderpacks/ComplementaryReimagined_r5.9.3.zip",
                "run/shaderpacks/ComplementaryReimagined",
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p)) {
                return new Fixture(p, p.toString() + (Files.isRegularFile(p) ? " (zip,r5.9.3 用户实测包)" : " (目录包,dev)"));
            }
        }
        return null;
    }

    /**
     * 离线 include 展开(超集口径):{@code #include "/x/y.glsl"} → 读 {@code shaders/x/y.glsl}
     * 递归展开。去重(同一文件只展开一次)+ 深度上限;条件编译不求解(取超集)。
     */
    private static String expand(Path root, String rel) {
        StringBuilder sb = new StringBuilder();
        expandInto(root, rel, 0, new LinkedHashSet<>(), sb);
        return sb.toString();
    }

    private static void expandInto(Path root, String rel, int depth, Set<String> seen, StringBuilder sb) {
        if (depth > 16 || !seen.add(rel)) return;
        String text = PackFingerprint.readFile(root, rel).orElse(null);
        if (text == null) return;
        for (String line : text.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#include")) {
                int a = t.indexOf('"');
                int b = t.lastIndexOf('"');
                if (a >= 0 && b > a) {
                    String inc = t.substring(a + 1, b);
                    while (inc.startsWith("/")) inc = inc.substring(1);
                    expandInto(root, "shaders/" + inc, depth + 1, seen, sb);
                    continue;
                }
            }
            sb.append(line).append('\n');
        }
    }

    /** 真实包不可得时的合成文本(仅验机制;会大声标注 FIXTURE=synthetic)。 */
    private static String synthetic() {
        return "#version  130\n"
                + "void DoLighting(vec3 color) {}\n"
                + "void main() { DoLighting(color, shadowMult, playerPos, viewPos, lViewPos, geoNormal, normalM, dither,\n"
                + "    centerShadowBias, subsurfaceMode, smoothnessG, highlightMult, emission); }\n";
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

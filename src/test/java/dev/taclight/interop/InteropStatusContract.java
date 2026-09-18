package dev.taclight.interop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code !interop} 诊断工具契约(2026-09-19;Lead 要求"这条是真机验证时要用的诊断工具")。
 *
 * <p>分两层,如实标注:</p>
 * <ul>
 *   <li><b>内容层(纯逻辑,离线可判定)</b>:{@link RuntimePackInjector#formatStatus} 必须输出
 *       五要素 —— 原始包名 / 归一化匹配键 / 包根 / 模板+通道 / 逐文件结果;无模板时必须给"原因"。
 *       这是"命令输出对不对"的可判定部分。</li>
 *   <li><b>接线层(源码文本级,非运行时)</b>:{@code DebugCommandRelay} 里 {@code !interop} 分支
 *       确实调用了 {@code statusReport/registeredTemplates/rawPackName/hashComparison} 并逐行入日志,
 *       且该分支排在其它分支之前不会被前缀吞掉。</li>
 * </ul>
 *
 * <p><b>未验证(必须由真机补)</b>:文件中继 → 客户端 tick → 日志落地这条端到端链路;
 * 本契约只证明"正文五要素齐全 + 接线存在"。</p>
 */
public class InteropStatusContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        contentLayer();
        wiringLayer();
        System.out.println("InteropStatusContract: ALL PASS (" + checks + " checks)");
    }

    private static void contentLayer() {
        String s = RuntimePackInjector.formatStatus(
                "ComplementaryReimagined_r5.9.3.zip", "complementaryreimagined r5.9.3",
                "E:\\...\\shaderpacks\\ComplementaryReimagined_r5.9.3.zip",
                "family=complementary packName=ComplementaryReimagined",
                "known-good:anchors", "已知良好清单命中(锚点验证过;不存整文件哈希)",
                "shaders/program/gbuffers_terrain.glsl: 注入成功 +8419 chars", true);
        check(s.contains("ComplementaryReimagined_r5.9.3.zip"), "要素①:原始包名原样出现");
        check(s.contains("complementaryreimagined r5.9.3"), "要素②:归一化匹配键出现");
        check(s.contains("shaderpacks\\ComplementaryReimagined_r5.9.3.zip"), "要素③:包根出现");
        check(s.contains("family=complementary") && s.contains("packName=ComplementaryReimagined"),
                "要素④:模板(family/packName)出现");
        check(s.contains("known-good:anchors"), "要素④:通道出现(known-good:anchors)");
        check(s.contains("gbuffers_terrain.glsl: 注入成功 +8419 chars"), "要素⑤:逐文件结果出现");
        check(s.contains("注入成功"), "最近一次结局 = 注入成功");
        check(s.split("\n").length >= 7, "输出为多行结构化文本(实际 " + s.split("\n").length + " 行)");
        String none = RuntimePackInjector.formatStatus("OtherPack_r1.zip", "otherpack r1.0", "/x/y.zip",
                null, null, "无候选模板(归一化键 \"otherpack r1.0\" 不匹配任何已登记 packName)", "(尚未有程序源流过)", false);
        check(none.contains("(无)") && none.contains("原因") && none.contains("无候选模板"),
                "无模板分支:给原因(可操作)");
        check(!none.contains("通道"), "无模板分支不输出通道行(避免误导)");
    }

    /** 接线层:源码文本级(非运行时行为验证)。 */
    private static void wiringLayer() throws Exception {
        Path relay = Path.of("src/main/java/dev/taclight/client/DebugCommandRelay.java");
        check(Files.isRegularFile(relay), "找到 DebugCommandRelay 源文件");
        if (!Files.isRegularFile(relay)) return;
        String src = new String(Files.readAllBytes(relay), StandardCharsets.UTF_8);
        // ★ 注释剥离后再定位分支:javadoc 里也提到 "!interop"(用法配方/历史说明),
        // 直接在原文里 indexOf 会落到注释里(2026-09-19 本契约自己踩过一次)。
        String code = stripComments(src);
        int at = code.indexOf("startsWith(\"!interop\")");
        check(at > 0, "存在 !interop 分支(注释剥离后定位)");
        if (at <= 0) return;
        int end = code.indexOf("\n        }", at);
        String branch = end > at ? code.substring(at, end) : code.substring(at);
        check(branch.contains("RuntimePackInjector.statusReport()"), "分支调用 statusReport()(正文)");
        check(branch.contains("registeredTemplates()"), "分支附模板清单");
        check(branch.contains("rawPackName()"), "分支附当前 shaderPack 原始名");
        check(branch.contains("hashComparison("), "分支附哈希对照");
        check(branch.contains("RELAY interop |"), "分支逐行入日志(可被 latest.log 检索)");
        // 前缀吞并检查:!interop 之前不得存在其它 !i* 的 startswith 分支
        String before = code.substring(0, at);
        check(!before.contains("startsWith(\"!i"),
                "!interop 不被更早的 !i* 分支吞掉(无前缀包含)");

        // ★ 生产接线(源码文本级,注释已剥离):matchTemplate 必须走归一化匹配,不得回退精确相等。
        // 补这个检查的原因:纯契约只覆盖 matchesPackName 助手,若生产端改回 name.equals(...)
        // 助手契约仍全绿 ⇒ 这个"最贵的一次教训"就没有守卫。
        Path inj = Path.of("src/main/java/dev/taclight/interop/RuntimePackInjector.java");
        check(Files.isRegularFile(inj), "找到 RuntimePackInjector 源文件");
        if (!Files.isRegularFile(inj)) return;
        String injCode = stripComments(new String(Files.readAllBytes(inj), StandardCharsets.UTF_8));
        int mAt = injCode.indexOf("private static Resolution matchTemplate(");
        check(mAt > 0, "matchTemplate 方法存在");
        if (mAt <= 0) return;
        int mEnd = injCode.indexOf("\n    private static String describeDrift(", mAt);
        String body = mEnd > mAt ? injCode.substring(mAt, mEnd) : injCode.substring(mAt);
        check(body.contains("PackFingerprint.matchesPackName(rawName, t.packName)"),
                "★ matchTemplate 走归一化匹配 PackFingerprint.matchesPackName(改回精确相等即红)");
        check(!body.contains(".equals(t.packName)"),
                "★ matchTemplate 内不得再出现精确相等 .equals(t.packName)(旧 bug 形态)");
        check(body.contains("PackFingerprint.resolvePackRoot(shaderpacks, rawName)"),
                "★ F4:包根解析用原始名 rawName(不得传归一化键)");
    }

    /** 剥离 // 与 /* *\/ 注释(文本级检查前先剥离,避免注释里的旧写法造成假红/假绿)。 */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i = Math.min(s.length(), i + 2);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

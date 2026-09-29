package dev.taclight.client;

import dev.taclight.debug.HudCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code !hud off|on|status} 契约(2026-09-26 task-51 第 2 步;task-62 重写 ⑤/⑥)。
 *
 * <p><b>六条</b>:① 三分支真值表;② {@code !mutates(ACTION_NONE)} 且 {@code !mutates(ACTION_STATUS)}
 * (把"无参/未知参不改状态"写成机器判据 —— 这正是 {@code !light} 曾经出错的地方);
 * ③ {@code off} 的赋值必须落在 <b>{@code !hud} 分支片段内</b>(位置无关的 contains 不算,沿用 task-24/32 教训);
 * ④ STATUS 块内<b>不得出现</b> {@code mc.options.hideGui =}(只读)+ {@code describe} 逐字;
 * ⑤ <b>dev-only · 源码层</b>:{@code !hud} 串在 {@code src/main} 恰好 2 个文件
 * (relay + 纯核心 {@code debug/HudCommand},无第三处);
 * ⑥ <b>dev-only · 构件层(2026-09-26 task-62)</b>:release 件**条目集**断言 ——
 * 枚举 jar 后断言 (a) 无 {@code dev/taclight/client/HudCommand.class} 条目、
 * (b) 无任何 {@code DebugCommandRelay*} 条目、(c) 无任何 {@code .class} 条目含
 * {@code !hud} ASCII 字节。**禁止**再"grep 构建脚本后推论发布件内容"
 * (R9 已证那种推论会静默失效)。<b>2026-09-28 U-1a 增补正控 (d)(e)(f)</b>:
 * 原先 ⑥ 只有 (a)(b)(c) 三条**负断言** + 一条 {@code jar != null} ⇒
 * <b>任意一个"结构上不是发布件"的 jar 都能 ALL GREEN</b>(0 条目 / 只含 3 个文件的 stub 均实测通过)；
 * 外部审计 P1-1 早已指出该病。⇒ 增 (d) 条目总数下限、(e) class 数下限、
 * (f) <b>核心条目必须存在</b>的正向清单。
 * <p>⚠️ 正控只断言<b>结构/关系</b>,<b>不钉任何 sha256 或字节数</b>:
 * 合法重建会让 jar 字节变(timestamp 等),钉字面量必然"永远红/下一轮假红"。
 * 真正的"读旧 jar"风险由 {@code build.gradle} 的 {@code taclightContracts.dependsOn jar} 堵。</p>
 */
public final class HudCommandContract {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final String RELAY = "src/main/java/dev/taclight/client/DebugCommandRelay.java";
    private static final String PURE = "src/main/java/dev/taclight/debug/HudCommand.java";
    private static final String PROP_RELEASE_JAR = "taclight.releaseJar";
    private static final Path LIBS = Path.of("build", "libs");
    private static final Path GRADLE_PROPS = Path.of("gradle.properties");

    // ==== ⑥ 正控阈值(2026-09-28 U-1a)====
    // 定值依据(实件量得,见 docs/evidence/2026-09-28-u1-ship/00-measure-release-jar.txt):
    //   真发布件 = 280 条目(226 文件 + 54 目录) / 145 个 .class / 102 个 assets 条目。
    // 取 200 / 100 —— ⚠️ 报余量**必须写明口径**(reviewer U1-4 指出、`BACKLOG §2.105 ②` 已订正;
    //   本注释原先只写"约 29~31%",那是把 54 个**目录项**算进基数的口径,厚度看着比实际大):
    //     · 条目数 200/280(全部条目,含目录) = 余量 28.6%
    //     · 条目数 200/226(**仅文件项**)      = 余量 11.5%  ← 真实厚度只有一半
    //     · class 数 100/145                  = 余量 31%
    //   下限的职责 = **拦住"结构上不是发布件"的东西**(0 条目 / 3 文件 stub / 截断件,
    //   与下限差 66~200 倍),**不是**"验证内容对不对"(那是负断言 (a)(b)(c) 与 InteropPackagingContract 的事)。
    // ⚠️ 只钉**下界与结构**,不钉 sha256/字节数 —— 合法重建会让 jar 字节变,钉字面量必然恒红。
    //
    // ⚠️⚠️ 【登记式守卫 · 2026-09-28 U-1b】REQUIRED_RELEASE_ENTRIES 是**设计不变量**,
    //   它的任何维护动作**必须经过台账**,不能靠"顺手改个字符串":
    //   若本清单某项因特性增删而消失,**必须**在 `docs\BACKLOG.md` 记一条,并写明
    //   ① 这一项当初**保护的是什么** ② 为什么现在**不再**需要它。
    //   **删一个字符串 ≠ 修好判据** —— 绕过本守卫最省事的做法恰恰就是删名字,
    //   而那正是本守卫存在的理由;把它写成"可以改"等于把守卫自己拆掉。
    //   ✅ **2026-09-28 R12 已按本守卫的要求执行**(不是"顺手删"):
    //      `VERDICT.md §3 B1` 采纳**修法②(结构上移出发布件)**⇒ 整族 `/taclight` 调试命令
    //      物理移入 `dev/taclight/debug/command/**`, `TuneService` 移入 `dev/taclight/debug/tune/**`,
    //      两者随既有 `exclude 'dev/taclight/debug/**'` 一起离开发布件。
    //      ① 这两项当初保护的 = "B1 那条假设的历史形态"(调试命令族曾经**在**发布件里);
    //      ② 现在不再需要 = B1 的裁决从"默认关开关/提权限"改成"结构剔除",
    //         而"它们必须不在发布件里"这一条已由 `InteropPackagingContract` 的
    //         **dev-only 条目集闸门**(前缀 `dev/taclight/debug/`)承接 ⇒ 从**必需清单**移出、
    //         改为**禁止清单**,判据强度是升不是降。
    //      台账留痕:`docs\BACKLOG.md §2.107`(本轮)。
    //   (修法①默认关 dev 开关 / ③改权限等级 都会让这两项继续留在发布件 ⇒ 本清单才需要它们;
    //    两种修法本轮都未被采纳。)
    private static final int MIN_RELEASE_ENTRIES = 200;
    private static final int MIN_RELEASE_CLASSES = 100;

    /** 核心条目正控清单:玩家真正要用到的东西 + 打包元数据,必须在发布件里。 */
    private static final List<String> REQUIRED_RELEASE_ENTRIES = List.of(
            "META-INF/mods.toml",
            "META-INF/MANIFEST.MF",
            "pack.mcmeta",
            "taclight.mixins.json",
            "taclight.refmap.json",
            "dev/taclight/TacLightMod.class",
            "dev/taclight/item/FlashlightItem.class",
            "dev/taclight/registry/ModItems.class",
            "dev/taclight/network/TacLightNetwork.class",
            "dev/taclight/config/TacLightConfig.class",
            "dev/taclight/interop/RuntimePackInjector.class",
            "dev/taclight/client/ClientEvents.class");

    public static void main(String[] args) throws Exception {
        contentLayer();
        wiringLayer();
        releaseJarEntrySetLayer();
        if (!FAILURES.isEmpty()) throw new AssertionError("FAIL " + FAILURES.size() + " 条: " + FAILURES);
        System.out.println("HudCommandContract: ALL PASS (" + checks + " checks)");
    }

    /** ① 三分支真值表 + ② mutates + describe 逐字。 */
    private static void contentLayer() {
        check(HudCommand.action("off") == HudCommand.ACTION_OFF, "action(\"off\") = ACTION_OFF");
        check(HudCommand.action("on") == HudCommand.ACTION_ON, "action(\"on\") = ACTION_ON");
        check(HudCommand.action("status") == HudCommand.ACTION_STATUS, "action(\"status\") = ACTION_STATUS");
        check(HudCommand.action(" OFF ") == HudCommand.ACTION_OFF
                        && HudCommand.action("On") == HudCommand.ACTION_ON,
                "大小写不敏感 + 忽略首尾空白");
        check(HudCommand.action("") == HudCommand.ACTION_NONE
                        && HudCommand.action(null) == HudCommand.ACTION_NONE
                        && HudCommand.action("toggle") == HudCommand.ACTION_NONE
                        && HudCommand.action("offx") == HudCommand.ACTION_NONE,
                "无参 / null / 'toggle' / 未知串 ⇒ ACTION_NONE(不猜;**本命令没有 toggle**)");
        // ② "不改状态"必须机器可判
        check(!HudCommand.mutates(HudCommand.ACTION_NONE) && !HudCommand.mutates(HudCommand.ACTION_STATUS),
                "[★必红] !mutates(ACTION_NONE) 且 !mutates(ACTION_STATUS)(无参/未知与只读都不改状态)");
        check(HudCommand.mutates(HudCommand.ACTION_ON) && HudCommand.mutates(HudCommand.ACTION_OFF),
                "on/off 是幂等置位(会改状态)");
        check(HudCommand.targetHideGui(HudCommand.ACTION_OFF) && !HudCommand.targetHideGui(HudCommand.ACTION_ON),
                "off ⇒ hideGui=true;on ⇒ false");
        check("hud=off(hideGui=true)".equals(HudCommand.describe(true))
                        && "hud=on(hideGui=false)".equals(HudCommand.describe(false)),
                "[逐字] describe 回执 = hud=off(hideGui=true) / hud=on(hideGui=false)");
        check(HudCommand.usage().contains("off") && HudCommand.usage().contains("on")
                        && HudCommand.usage().contains("status"),
                "usage 里三个分支都点名");
        check(HudCommand.usage().contains("!hud"),
                "usage 含 '!hud' 命令串(task-62:该串只随 debug/HudCommand 存在,构件层由 ⑥ 拦)");
        // 纯核心不得提供 toggle 语义(代码行,去注释)
        try {
            String pure = Files.readString(Path.of(PURE), StandardCharsets.UTF_8);
            boolean clean = true;
            for (String line : pure.split("\\R")) {
                String l = line.trim();
                if (l.startsWith("*") || l.startsWith("//") || l.startsWith("/*")) continue;
                if (l.contains("nextState") || l.contains("toggle(")) clean = false;
            }
            check(clean, "HudCommand 代码行不含 toggle/nextState(本命令不提供反转语义)");
        } catch (Exception e) {
            check(false, "读取 " + PURE + " 失败: " + e);
        }
    }

    /** ③④⑤ 接线与 dev-only(源码文本级)。 */
    private static void wiringLayer() throws Exception {
        String relay = Files.readString(Path.of(RELAY), StandardCharsets.UTF_8);
        int start = relay.indexOf("line.startsWith(\"!hud\")");
        check(start > 0, "relay 里有 !hud 分支");
        int end = start < 0 ? -1 : relay.indexOf("\n        if (", start);
        String frag = (start < 0 || end < 0) ? null : relay.substring(start, end);
        check(frag != null, "取到 !hud 分支片段(到下一个同级 if 之前)");
        // ③ 赋值必须在这个片段内(位置无关 contains 不算)
        check(frag != null && frag.contains("mc.options.hideGui = HudCommand.targetHideGui(act);"),
                "[★必红·作用域内] off/on 的赋值落在 !hud 分支片段内"
                        + "(`mc.options.hideGui = HudCommand.targetHideGui(act);`)");
        // ④ STATUS 块只读
        int st = frag == null ? -1 : frag.indexOf("HudCommand.ACTION_STATUS");
        int stEnd = st < 0 ? -1 : frag.indexOf("return;", st);
        String statusBlock = (st < 0 || stEnd < 0) ? null : frag.substring(st, stEnd);
        check(statusBlock != null, "取到 STATUS 块");
        check(statusBlock != null && !statusBlock.contains("mc.options.hideGui ="),
                "[★必红] STATUS 块内不得出现 mc.options.hideGui =(只读,不改状态)");
        check(statusBlock != null && statusBlock.contains("HudCommand.describe(mc.options.hideGui)"),
                "STATUS 回执经 describe(mc.options.hideGui) 读当前值");
        check(frag != null && frag.contains("HudCommand.ACTION_NONE") && frag.contains("HudCommand.usage()"),
                "无参/未知参数 ⇒ 报 usage 且 return(不改状态)");
        // ⑤ dev-only 源码层:命令串恰好 2 个文件(relay + debug 命名空间的纯核心),没有第三处
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    if (Files.readString(p, StandardCharsets.UTF_8).contains("!hud")) hits.add(p.toString());
                } catch (Exception ignored) {
                }
            });
        }
        boolean onlyExpected = hits.size() == 2;
        for (String h : hits) {
            String n = h.replace('\\', '/');
            if (!n.endsWith("client/DebugCommandRelay.java") && !n.endsWith("debug/HudCommand.java")) {
                onlyExpected = false;
            }
        }
        check(onlyExpected, "[dev-only·源码] '!hud' 串在 src/main 恰好 2 个文件"
                + "(relay + debug/HudCommand;实际 " + hits + ")");
    }

    /**
     * ⑥ dev-only 构件层(2026-09-26 task-62):release 件**条目集**断言。
     * jar 选取规则与 {@code InteropPackagingContract} 一致:
     * 显式 {@code -PreleaseJar=<path>} ⇒ 否则 {@code build/libs/taclight-<mod_version>.jar};拿不到 ⇒ 红。
     */
    private static void releaseJarEntrySetLayer() throws Exception {
        Path jar = pickReleaseJar();
        check(jar != null, "[构件层] 拿到 release jar(拿不到 ⇒ 拒绝自证;原因见上)");
        if (jar == null) return;
        List<String> badEntries = new ArrayList<>();
        List<String> tokenHits = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int classCount = 0;
        byte[] token = "!hud".getBytes(StandardCharsets.US_ASCII);
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                names.add(name);
                if (name.endsWith(".class")) classCount++;
                if (name.equals("dev/taclight/client/HudCommand.class")) {
                    badEntries.add("HudCommand 仍在 client 包: " + name);
                }
                if (name.contains("DebugCommandRelay")) {
                    badEntries.add("relay 类泄漏: " + name);
                }
                if (name.endsWith(".class") && containsBytes(z, e, token)) {
                    tokenHits.add(name);
                }
            }
        }
        check(badEntries.isEmpty(), "[★必红·构件] release 件无 client/HudCommand.class 且无 DebugCommandRelay* 条目"
                + (badEntries.isEmpty() ? "" : " ⇒ " + badEntries));
        check(tokenHits.isEmpty(), "[★必红·构件] release 件无任何 .class 条目含 '!hud' ASCII 字节"
                + (tokenHits.isEmpty() ? "" : " ⇒ " + tokenHits));

        // ---- ⑥ 正控(2026-09-28 U-1a):上面三条全是【负断言】,对"空/残/stub jar"恒绿。
        //      下面三条是【正断言】:"该有的在不在",让非发布件露不出 ALL GREEN。----
        System.out.println("[构件层] 实测 entries=" + names.size() + " classes=" + classCount);
        check(names.size() >= MIN_RELEASE_ENTRIES,
                "[正控·构件] 条目总数 >= " + MIN_RELEASE_ENTRIES + "(实测 " + names.size()
                        + " ⇒ 低于下限的 jar 不是发布件;0 条目/3 文件 stub 会被这条拦下)");
        check(classCount >= MIN_RELEASE_CLASSES,
                "[正控·构件] .class 条目数 >= " + MIN_RELEASE_CLASSES + "(实测 " + classCount + ")");

        List<String> missing = new ArrayList<>();
        for (String req : REQUIRED_RELEASE_ENTRIES) {
            if (!names.contains(req)) missing.add(req);
        }
        check(missing.isEmpty(),
                "[正控·构件] 核心条目齐全(应存在 " + REQUIRED_RELEASE_ENTRIES.size() + " 项,缺 " + missing.size()
                        + (missing.isEmpty() ? "" : " ⇒ " + missing) + ")");
    }

    /** 朴素字节搜索(class 常量池为 modified-UTF8,ASCII 片段逐字节可比)。 */
    private static boolean containsBytes(ZipFile z, ZipEntry e, byte[] token) throws IOException {
        byte[] data;
        try (var in = z.getInputStream(e)) {
            data = in.readAllBytes();
        }
        for (int i = 0; i + token.length <= data.length; i++) {
            boolean ok = true;
            for (int j = 0; j < token.length; j++) {
                if (data[i + j] != token[j]) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    /** 与 InteropPackagingContract.pickReleaseJar 同规则(本契约不复用其私有类,规则逐字对齐)。 */
    private static Path pickReleaseJar() {
        String explicit = System.getProperty(PROP_RELEASE_JAR, "").trim();
        if (!explicit.isEmpty()) {
            Path p = Path.of(explicit);
            if (Files.isRegularFile(p)) return p;
            System.out.println("FAIL-CANDIDATE: 显式 -PreleaseJar=" + explicit + " 不存在或不是普通文件");
            return null;
        }
        String version = modVersion();
        if (version == null || version.isEmpty()) {
            System.out.println("FAIL-CANDIDATE: 读不到 gradle.properties 里的 mod_version");
            return null;
        }
        Path expected = LIBS.resolve("taclight-" + version + ".jar");
        if (Files.isRegularFile(expected)) return expected;
        System.out.println("FAIL-CANDIDATE: 期望 " + expected + " 不存在");
        return null;
    }

    private static String modVersion() {
        try {
            for (String line : Files.readAllLines(GRADLE_PROPS, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("mod_version=")) return s.substring("mod_version=".length()).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (cond) {
            System.out.println("PASS: " + what);
        } else {
            FAILURES.add(what);
            System.out.println("FAIL: " + what);
        }
    }

    private HudCommandContract() {}
}

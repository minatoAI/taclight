package dev.taclight.interop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <b>发布包边界反回归契约</b>(2026-09-19,Lead 硬裁定 ③:防"哪天有人顺手把调试通道打进发布包")。
 *
 * <p>为什么需要:2026-09-19 真机诊断发现,qa 在独立实例里写 {@code run\taclight-cmds.txt} 后
 * <b>零 RELAY 行</b>。两个独立原因:① 监听的是 {@code <gameDir>/taclight-cmds.txt}(不是
 * {@code run/} 子目录);② <b>发布 jar 根本不含 {@code DebugCommandRelay}</b> ——
 * {@code build.gradle} 故意把 {@code dev/taclight/client/DebugCommandRelay*.class} 与
 * {@code dev/taclight/debug/**} 剔除(该通道是"任何本地程序可写、写了即驱动客户端命令"的
 * 无守卫写入口,与 modtest-mcp 令牌+审计取向冲突)⇒ 发布包实例里"零 RELAY 行"是<b>预期</b>。
 * 本契约把这条边界钉死,并钉死"路径口径"与"可操作文案"两处修正。</p>
 *
 * <p>四组断言(零真机可判定):</p>
 * <ol>
 *   <li><b>发布 jar 必须不含</b> {@code DebugCommandRelay*.class} 与 {@code dev/taclight/debug/**};
 *       若<b>选不到</b> release jar(未构建 / 只有 dev 变体 / 显式路径不存在)会<b>大声失败</b>
 *       (2026-09-25 起不再"静默跳过";选取规则见下)——否则这条边界断言会假装通过;</li>
 *   <li><b>{@code build/classes} 必须含</b> {@code DebugCommandRelay.class}(证明是"被剔除"
 *       而不是"没编译"——两者对排障含义完全不同);</li>
 *   <li>{@code build.gradle} 的 5 条 exclude 必须在(防误删);</li>
 *   <li>监听路径表达式必须是 {@code FMLPaths.GAMEDIR.get().resolve("taclight-cmds.txt")};
 *       文案里不得再出现"游戏内 !interop"(不存在客户端命令路径,曾误导验收人)。</li>
 * </ol>
 *
 * <p><b>release jar 的选取(2026-09-25 确定化;BACKLOG §2.18⑦ / 待办 ⑯)</b>:旧实现按
 * {@code build/libs} 的 <b>mtime</b> 取"最新 jar"⇒ ① 残留的 devharness/relayonly 测试变体会抢走
 * 选择 = <b>假红</b>;② 把它删掉又会静默换成上一个 release jar = <b>假绿</b>;③ 全组都得记住
 * "跑契约前先把 devtest jar 移走"这个手工步骤 = 流程负债。现在改为:</p>
 * <ol>
 *   <li>显式 {@code -PreleaseJar=<path>}(build.gradle 转发为本 JVM 的系统属性
 *       {@value #PROP_RELEASE_JAR})⇒ 用它;<b>不存在 / 不是 release 口径则红</b>;</li>
 *   <li>否则按<b>名字</b>确定选取 {@code build/libs/taclight-<mod_version>.jar}
 *       (mod_version 读 {@link #GRADLE_PROPS},与 build.gradle 的 jar 产物同源)——
 *       与 mtime、与目录里其它 jar 无关;</li>
 *   <li>选不到 ⇒ <b>红</b>,失败信息里点名"期望哪个文件 + 现有候选 + 两条修法";</li>
 *   <li>选中后校验<b>清单</b>:{@code MixinConfigs} 必须是 release 口径
 *       {@value #RELEASE_MIXIN_CONFIG}(dev 构建是 {@code taclight.dev.mixins.json})
 *       ⇒ "把 dev jar 改名冒充 release" 会被这条点名拒绝;</li>
 *   <li>{@code JAR=} 行照旧打印路径 / 字节数 / sha256,并补印选取方式与清单两个字段
 *       (红的时候一眼看出它选的是哪个 jar)。</li>
 * </ol>
 */
public class InteropPackagingContract {
    private static int checks;
    /** 显式指定 release jar 的开关(build.gradle 把 -PreleaseJar=<path> 转发成该系统属性)。 */
    private static final String PROP_RELEASE_JAR = "taclight.releaseJar";
    /** 版本真源(与 build.gradle 的 jar.archiveVersion 同源)。 */
    private static final Path GRADLE_PROPS = Path.of("gradle.properties");
    /** 发布 jar 所在目录。 */
    private static final Path LIBS = Path.of("build/libs");
    /** release 口径的清单标记;dev 构建是 taclight.dev.mixins.json(改名冒充 release 靠它拦下)。 */
    private static final String RELEASE_MIXIN_CONFIG = "taclight.mixins.json";
    private static final String[] GRADLE_EXCLUDES = {
            "exclude 'dev/taclight/debug/**'",
            "exclude 'dev/taclight/mixin/debug/**'",
            "exclude 'dev/taclight/client/DebugCommandRelay*.class'",
            "exclude 'taclight.debug.mixins.json'",
            "exclude 'taclight.dev.mixins.json'",
    };

    public static void main(String[] args) throws Exception {
        releaseJarBoundary();
        buildClassesPresent();
        gradleExcludes();
        listenPathAndWording();
        System.out.println("InteropPackagingContract: ALL PASS (" + checks + " checks)");
    }

    /** ① 发布 jar 边界 + 正控(证明 jar 不是空/取错)。 */
    private static void releaseJarBoundary() throws IOException {
        Pick pick = pickReleaseJar();
        if (pick.path() == null) {
            // 红:不再"静默跳过"。信息里带期望文件名 + 现有候选 + 两条修法。
            check(false, "★ 选不到 release jar: " + pick.why()
                    + "  ⇒ 修法:① cd taclight && gradlew.bat jar(生成默认 release 产物)"
                    + " ② 或显式 -PreleaseJar=<path>(归档包/实例里的 jar)");
            return;
        }
        Path jar = pick.path();
        String sha = sha256(jar);
        String mixin = manifestAttr(jar, "MixinConfigs");
        String implVer = manifestAttr(jar, "Implementation-Version");
        System.out.println("  JAR = " + jar + "  (" + Files.size(jar) + " bytes, sha256=" + sha + ")");
        System.out.println("  选取 = " + pick.why() + "; 清单 MixinConfigs=" + mixin
                + " Implementation-Version=" + implVer);
        String stale = staleNote(jar);
        if (!stale.isEmpty()) System.out.println("  NOTE " + stale);
        // 清单必须是 release 口径:防"把 dev jar 改名冒充 release"(旧实现按 mtime 会选中它)
        check(RELEASE_MIXIN_CONFIG.equals(mixin),
                "★ 所选 jar 的清单是 release 口径(MixinConfigs 期望 " + RELEASE_MIXIN_CONFIG
                        + ",实际 " + mixin + ")⇒ 选中的是 " + jar);
        List<String> relay = new ArrayList<>();
        List<String> debug = new ArrayList<>();
        boolean hasInterop = false;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("dev/taclight/client/DebugCommandRelay")) relay.add(n);
                if (n.startsWith("dev/taclight/debug/")) debug.add(n);
                if (n.equals("dev/taclight/interop/RuntimePackInjector.class")) hasInterop = true;
            }
        }
        check(hasInterop, "正控:发布 jar 含 dev/taclight/interop/RuntimePackInjector.class(注入逻辑必须发布)");
        check(relay.isEmpty(), "★ 发布 jar 不含 DebugCommandRelay*(实际: " + relay + ");选中 jar = " + jar);
        check(debug.isEmpty(), "★ 发布 jar 不含 dev/taclight/debug/**(实际 " + debug.size()
                + " 条);选中 jar = " + jar);
    }

    /** ② 构建树里必须有该类(剔除 ≠ 没编译)。 */
    private static void buildClassesPresent() {
        Path cls = Path.of("build/classes/java/main/dev/taclight/client/DebugCommandRelay.class");
        check(Files.isRegularFile(cls),
                "★ build/classes 含 DebugCommandRelay.class(证明是打包剔除,不是没编译): " + cls);
        Path debugDir = Path.of("build/classes/java/main/dev/taclight/debug");
        check(Files.isDirectory(debugDir),
                "★ build/classes 含 dev/taclight/debug/ 目录(调试类确实编译了): " + debugDir);
    }

    /** ③ build.gradle 的剔除规则必须在。 */
    private static void gradleExcludes() throws IOException {
        Path g = Path.of("build.gradle");
        check(Files.isRegularFile(g), "找到 build.gradle");
        if (!Files.isRegularFile(g)) return;
        String src = new String(Files.readAllBytes(g), StandardCharsets.UTF_8);
        for (String ex : GRADLE_EXCLUDES) {
            check(src.contains(ex), "★ build.gradle 保留剔除规则: " + ex);
        }
    }

    /** ④ 路径口径 + 文案(注释剥离后判代码;文案是字符串字面量,剥离注释不影响)。 */
    private static void listenPathAndWording() throws IOException {
        Path relay = Path.of("src/main/java/dev/taclight/client/DebugCommandRelay.java");
        check(Files.isRegularFile(relay), "找到 DebugCommandRelay 源文件");
        if (!Files.isRegularFile(relay)) return;
        String full = new String(Files.readAllBytes(relay), StandardCharsets.UTF_8);
        String code = InteropStatusContract.stripComments(full);
        check(code.contains("FMLPaths.GAMEDIR.get()") && code.contains(".resolve(\"taclight-cmds.txt\")"),
                "★ 监听路径表达式 = FMLPaths.GAMEDIR.get().resolve(\"taclight-cmds.txt\")");
        check(!code.contains("resolve(\"run\")") && !code.contains("run/taclight-cmds.txt"),
                "★ 代码里不得把监听路径写成 run/ 子目录(旧 javadoc 口径,已误导过一次真机轮)");
        check(full.contains("<gameDir>/taclight-cmds.txt"),
                "javadoc 写明 <gameDir>/taclight-cmds.txt(并注明 dev 实例 gameDir = run/)");
        check(full.contains("发布 jar 故意剔除") || full.contains("只存在于 dev"),
                "javadoc 写明该通道只在 dev/调试构建存在(发布包零 RELAY 行 = 预期)");

        Path inj = Path.of("src/main/java/dev/taclight/interop/RuntimePackInjector.java");
        check(Files.isRegularFile(inj), "找到 RuntimePackInjector 源文件");
        if (!Files.isRegularFile(inj)) return;
        String injCode = InteropStatusContract.stripComments(
                new String(Files.readAllBytes(inj), StandardCharsets.UTF_8));
        check(!injCode.contains("游戏内 !interop"),
                "★ 聊天文案不得再写\"游戏内 !interop\"(不存在客户端命令路径,曾误导验收人)");
        check(injCode.contains("<gameDir>/taclight-cmds.txt"),
                "★ 聊天文案给出可执行动作:写 <gameDir>/taclight-cmds.txt");
        check(injCode.contains("日志搜 interop"),
                "★ 聊天文案保留人人可用的路径:日志搜 interop(发布包不含文件通道)");
    }

    /** 选取结果:{@code path == null} 表示选不到,{@code why} 说明原因(红色信息会带上它)。 */
    private record Pick(Path path, String why) {}

    /**
     * <b>release jar 的确定化选取</b>(2026-09-25;替换旧 {@code newestJar()} 的 mtime 口径)。
     *
     * <p>规则(优先级自上而下,失败即红):① 显式 {@code -PreleaseJar=<path>} 且存在;
     * ② {@code build/libs/taclight-<mod_version>.jar}(名字确定,与 mtime / 目录里其它 jar 无关);
     * ③ 都拿不到 ⇒ 返回 null + 原因,由调用方红并把候选清单打出来。</p>
     */
    private static Pick pickReleaseJar() throws IOException {
        String explicit = System.getProperty(PROP_RELEASE_JAR, "").trim();
        if (!explicit.isEmpty()) {
            Path p = Path.of(explicit);
            if (!Files.isRegularFile(p)) {
                return new Pick(null, "显式 -PreleaseJar=" + explicit + " 不存在或不是普通文件");
            }
            return new Pick(p, "显式 -PreleaseJar=" + explicit);
        }
        String version = modVersion();
        if (version == null || version.isEmpty()) {
            return new Pick(null, "读不到 " + GRADLE_PROPS + " 里的 mod_version(无法确定 release jar 名)"
                    + ";build/libs 现有候选=" + jarCandidates());
        }
        Path expected = LIBS.resolve("taclight-" + version + ".jar");
        if (Files.isRegularFile(expected)) {
            return new Pick(expected, "按名字确定选取(" + GRADLE_PROPS + " mod_version=" + version + ")");
        }
        return new Pick(null, "期望 " + expected + " 不存在(build/libs 不存在或只有 dev 变体)"
                + ";现有候选=" + jarCandidates());
    }

    /** 读 {@code gradle.properties} 的 {@code mod_version}(= build.gradle 里 jar 的 archiveVersion)。 */
    private static String modVersion() {
        try {
            for (String line : Files.readAllLines(GRADLE_PROPS, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("mod_version=")) return s.substring("mod_version=".length()).trim();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** build/libs 下的 jar 候选(只要文件名,排序 ⇒ 失败信息输出稳定)。 */
    private static List<String> jarCandidates() throws IOException {
        if (!Files.isDirectory(LIBS)) return List.of("(build/libs 不存在:还没构建过)");
        try (var s = Files.list(LIBS)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jar"))
                    .sorted()
                    .toList();
        }
    }

    /** 读 jar 清单里的属性(没有清单或没有该键 ⇒ null)。 */
    private static String manifestAttr(Path jar, String key) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("META-INF/MANIFEST.MF");
            if (e == null) return null;
            String txt;
            try (var in = z.getInputStream(e)) {
                txt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String line : txt.split("\\R")) {
                int i = line.indexOf(':');
                if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(key)) {
                    return line.substring(i + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * 代际提示(<b>只打印,不断言</b>):jar 比 {@code src/main} 里最新文件还旧 ⇒ 提醒"这个 jar 可能不含
     * 最新源码"。不断言的原因:归档 jar / 手动指定的 jar 天然比源码旧,那是合法用法
     * ({@code -PreleaseJar})。用源码而不是 {@code build/classes} 比:契约每次都先编译,
     * class 永远比 jar 新,拿 class 比会天天误报。
     */
    private static String staleNote(Path jar) throws IOException {
        Path srcMain = Path.of("src/main");
        if (!Files.isDirectory(srcMain)) return "";
        long newestSrc;
        try (var s = Files.walk(srcMain)) {
            newestSrc = s.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException ex) {
                            return 0L;
                        }
                    })
                    .max().orElse(0L);
        }
        long jarTime = Files.getLastModifiedTime(jar).toMillis();
        if (newestSrc > jarTime) {
            return "所选 jar 比 src/main 最新文件旧 " + ((newestSrc - jarTime) / 1000)
                    + "s ⇒ 若刚改过源码,先 `gradlew.bat jar` 再跑本契约(jar 可能不含最新改动)";
        }
        return "";
    }

    private static String sha256(Path p) throws IOException {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

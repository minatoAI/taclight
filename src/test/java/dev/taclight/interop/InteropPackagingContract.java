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
 *       若 jar 不可得(未构建)会<b>大声标注</b> {@code JAR=不可得(未构建)} 而非静默跳过;</li>
 *   <li><b>{@code build/classes} 必须含</b> {@code DebugCommandRelay.class}(证明是"被剔除"
 *       而不是"没编译"——两者对排障含义完全不同);</li>
 *   <li>{@code build.gradle} 的 5 条 exclude 必须在(防误删);</li>
 *   <li>监听路径表达式必须是 {@code FMLPaths.GAMEDIR.get().resolve("taclight-cmds.txt")};
 *       文案里不得再出现"游戏内 !interop"(不存在客户端命令路径,曾误导验收人)。</li>
 * </ol>
 */
public class InteropPackagingContract {
    private static int checks;
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
        Path jar = newestJar();
        if (jar == null) {
            System.out.println("  JAR = 不可得(未构建;跳过 jar 内条目断言,其余断言照跑)");
            check(true, "jar 不可得时明确标注(不静默跳过)");
            return;
        }
        String sha = sha256(jar);
        System.out.println("  JAR = " + jar + "  (" + Files.size(jar) + " bytes, sha256=" + sha + ")");
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
        check(relay.isEmpty(), "★ 发布 jar 不含 DebugCommandRelay*(实际: " + relay + ")");
        check(debug.isEmpty(), "★ 发布 jar 不含 dev/taclight/debug/**(实际 " + debug.size() + " 条)");
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

    /** build/libs 下最新的非 sources/javadoc jar。 */
    private static Path newestJar() throws IOException {
        Path dir = Path.of("build/libs");
        if (!Files.isDirectory(dir)) return null;
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".jar") || n.contains("-sources") || n.contains("-javadoc")) continue;
                long t = Files.getLastModifiedTime(p).toMillis();
                if (t > bestTime) {
                    bestTime = t;
                    best = p;
                }
            }
        }
        return best;
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

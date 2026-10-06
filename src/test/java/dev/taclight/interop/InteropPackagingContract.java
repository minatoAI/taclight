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
 * <p><b>五组断言(零真机可判定)</b>:</p>
 * <ol>
 *   <li><b>发布 jar 必须不含</b> {@code DebugCommandRelay*.class} 与 {@code dev/taclight/debug/**},
 *       以及 {@code dev/taclight/devonly/**}(2026-09-28 R12 新增:dev-only 边界的第二命名空间)
 *       与四种"已搬走的旧路径"幽灵守卫(B1 命令族 / B3 LAN 钩子 / N1 按键注入 / N2 构建期工具),
 *       以及 **R12c 新增的"方法级死代码"闸门**({@code GunControl.class} 不得含中继专用方法符号);
 *       若<b>选不到</b> release jar(未构建 / 只有 dev 变体 / 显式路径不存在)会<b>大声失败</b>
 *       (2026-09-25 起不再"静默跳过";选取规则见下)——否则这条边界断言会假装通过;</li>
 *   <li><b>{@code build/classes} 必须含</b> {@code DebugCommandRelay.class} 与 11 个**已搬进 dev-only
 *       命名空间**的类(证明是"被剔除"而不是"没编译"——两者对排障含义完全不同);</li>
 *   <li>{@code build.gradle} 的 6 条 exclude 必须在(防误删);</li>
 *   <li>监听路径表达式必须是 {@code FMLPaths.GAMEDIR.get().resolve("taclight-cmds.txt")};
 *       文案里不得再出现"游戏内 !interop"(不存在客户端命令路径,曾误导验收人);</li>
 *   <li><b>版本一致性(B2 机器闸门,2026-09-28 R12)</b>:{@code mods.toml version} ==
 *       {@code gradle.properties mod_version} == 清单 {@code Implementation-Version},
 *       且 {@code TacLightMod.class} 常量池含该版本串、源码无手写版本字面量。</li>
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
            "exclude 'dev/taclight/devonly/**'",
    };

    /**
     * 2026-09-28 R12:dev-only 面的**禁止前缀/前缀守卫**(B1 命令族 / B3 LAN 钩子 / N1 按键注入 / N2 构建期工具)。
     * 后三项是**已搬走的旧路径** —— 留着当幽灵守卫,防有人"搬回去"或少搬一个类。
     *
     * <p><b>2026-10-06 修正(B1 过度剔除的回归)</b>:原清单里还有 {@code dev/taclight/command/} 与
     * {@code dev/taclight/tune/TuneService} 两项 —— 那是 R12 把**整族** {@code /taclight} 当调试面搬走时
     * 立的,结果把 2026-09-19 就定案为发布面功能的 {@code tune} 一起剔出了发布件
     * (用户实测 {@code /taclight tune} 无此命令)。现在按**功能面**重划:{@code dev/taclight/command/**}
     * 是发布面(禁止前缀撤销),{@code TuneService} 搬回 {@code dev/taclight/tune/**}(撤销);
     * dev-only 子命令改由 {@code dev/taclight/debug/command/DebugCommandChildren} 提供,
     * 仍被 {@code dev/taclight/debug/**} 前缀与下面的 {@code debug.isEmpty()} 闸门覆盖。</p>
     */
    private static final String[] DEV_ONLY_PREFIXES = {
            "dev/taclight/devonly/",
            "dev/taclight/sync/DevLanAuthHook",
            "dev/taclight/channel/KeyInject",
            "dev/taclight/tools/PackPatcherTool",
    };

    /** ② 的正控清单:R12/R12c 搬进 dev-only 命名空间的类必须在构建树里(剔除 ≠ 没编译)。 */
    private static final String[] MOVED_DEV_ONLY_CLASSES = {
            "dev/taclight/debug/command/DebugCommandChildren.class",
            "dev/taclight/debug/command/SceneExecutor.class",
            "dev/taclight/debug/command/ScenePresets.class",
            "dev/taclight/debug/command/CamStore.class",
            "dev/taclight/devonly/KeyInject.class",
            "dev/taclight/devonly/DevLanAuthHook.class",
            "dev/taclight/devonly/tools/PackPatcherTool.class",
            "dev/taclight/devonly/GunRelay.class",
            "dev/taclight/devonly/LightCommand.class",
            "dev/taclight/devonly/BobViewControl.class",
    };

    /**
     * 2026-10-06 发布面回归修复:<b>发布面命令族必须在发布件里</b> —— 反向正控。
     *
     * <p>为什么必须有这条:{@code /taclight tune} 于 2026-09-19 晋升为正式命令并写明"发布包可用",
     * 2026-09-29 R12 把它连同整族调试命令按**目录**搬进 {@code dev/taclight/debug/**},
     * 于是随 {@code exclude 'dev/taclight/debug/**'} 静默离开发布件 —— 而当时的闸门全是
     * **负断言**(debug 面不得在发布件里)+ 一个被"合法化"的必需清单删项,**没有任何一条判据
     * 要求 tune 在发布件里**,所以整族消失无人报警(用户实测才发现)。
     * 现在把"发布面命令族在发布件里"钉成<b>字节码级正断言</b>:</p>
     * <ul>
     *   <li>类条目在:命令类 + 编排类(在 ⇒ 不是"只搬了源码没搬构建");</li>
     *   <li>类字节里含 {@code tune} 与反射挂载目标串(在 ⇒ 命令树真的建出来了,
     *       不是空壳类;钉字节码不受注释影响,见 AGENTS 五 M1)。</li>
     * </ul>
     */
    private static final String[] TUNE_RELEASE_CLASSES = {
            "dev/taclight/command/TacLightCommand.class",
            "dev/taclight/tune/TuneService.class",
    };

    /** 发布面命令族的字节码符号:类条目 → 必须能在其原始字节里搜到的 ASCII 串。 */
    private record TuneSymbol(String entry, String symbol) {}

    private static final TuneSymbol[] TUNE_RELEASE_SYMBOLS = {
            new TuneSymbol("dev/taclight/command/TacLightCommand.class", "tune"),
            new TuneSymbol("dev/taclight/command/TacLightCommand.class", "dev.taclight.debug.command.DebugCommandChildren"),
            new TuneSymbol("dev/taclight/command/TacLightCommand.class", "CMDS RegisterCommandsEvent firing"),
            new TuneSymbol("dev/taclight/tune/TuneService.class", "tune"),
    };


    /**
     * 2026-09-29 R12c(N1 收尾):**发布侧类里"只被 dev-only 调用"的死方法** —— 按**字节码符号**判定。
     * <p>为什么钉字节码而不是源码文本:字节码里**没有注释** ⇒ "javadoc 里提了一嘴方法名"不会污染判据
     * (这是 {@code AGENTS §五} M1「逃生口被文档文字满足」的**镜像**教训:源码级负断言会被自己的注释打脸)。</p>
     */
    private static final String[] DEAD_METHOD_SYMBOLS = { "parseRelayArg", "applyAuto" };

    /**
     * 2026-10-06 预览版收尾:上一轮"剥离调试面"按**目录**剔除,漏掉了散在 channel/client 的
     * 6 个调试类(class 级剔除只覆盖 debug&#47;** 与 devonly&#47;**)。它们随发布件发出但已无触发源,
     * 属于"惰性残留" —— 本条按**类名**把它们钉死在发布件之外。
     * <p>内嵌类($)一并命中,避免"删了外壳留了内部类"。</p>
     */
    private static final String[] REMOVED_DEBUG_CLASSES = {
            "dev/taclight/mixin/GunModelRenderProbeMixin",
            "dev/taclight/client/RenderDocApi",
            "dev/taclight/client/RenderDocGate",
            "dev/taclight/channel/PerfStats",
            "dev/taclight/channel/FrameRecorder",
            "dev/taclight/channel/MotionCapture",
    };

    /** 已删功能的语言键:功能删了、键还留在 lang 里 ⇒ 发布件带孤儿文案。 */
    private static final String[] REMOVED_LANG_KEYS = {
            "key.taclight.debug_toggle",
            "key.taclight.diag_dump",
            "key.taclight.bench",
            "key.taclight.snapshot",
    };

    /** 语言键正控:同一个读取路径必须能找到这个键,否则"没找到"只说明读错了文件。 */
    private static final String PRESENT_LANG_KEY = "key.taclight.flashlight_toggle";

    public static void main(String[] args) throws Exception {
        releaseJarBoundary();
        versionCoherence();
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
        List<String> devOnly = new ArrayList<>();
        List<String> deadDebugClass = new ArrayList<>();
        boolean hasInterop = false;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("dev/taclight/client/DebugCommandRelay")) relay.add(n);
                if (n.startsWith("dev/taclight/debug/")) debug.add(n);
                if (isDevOnlyEntry(n)) devOnly.add(n);
                if (n.equals("dev/taclight/interop/RuntimePackInjector.class")) hasInterop = true;
                if (isRemovedDebugClass(n)) deadDebugClass.add(n);
            }
        }
        check(hasInterop, "正控:发布 jar 含 dev/taclight/interop/RuntimePackInjector.class(注入逻辑必须发布)");
        check(relay.isEmpty(), "★ 发布 jar 不含 DebugCommandRelay*(实际: " + relay + ");选中 jar = " + jar);
        check(debug.isEmpty(), "★ 发布 jar 不含 dev/taclight/debug/**(实际 " + debug.size()
                + " 条);选中 jar = " + jar);
        // 2026-09-28 R12[B1/B3/N1/N2·构件]:这是"结构剔除"口径的机器闸门。
        //   红态自证用的是**归档的旧发布件**(1498EAD7…):在它上面本条必红并逐条点名,不是推演。
        check(devOnly.isEmpty(), "★ R12[B1/B3/N1/N2·构件] 发布 jar 不含 dev-only 面(devonly/** + 已搬走的旧路径)"
                + "(实际 " + devOnly.size() + " 条" + (devOnly.isEmpty() ? "" : " ⇒ " + devOnly)
                + ");选中 jar = " + jar);

        // 2026-10-06 发布面回归修复:**反向**正控 —— 发布面命令族必须在发布件里。
        //   上面全是负断言(debug 面不得在发布件里),单靠它们无法发现"连发布面功能一起被剔走"。
        //   红态自证:拿 0.11.5 发布件(ac7342b 构建)走 -PreleaseJar ⇒ 本条必红并点名两个类。
        List<String> tuneMissing = new ArrayList<>();
        for (String cls : TUNE_RELEASE_CLASSES) {
            if (!zipHasEntry(jar, cls)) tuneMissing.add(cls);
        }
        check(tuneMissing.isEmpty(),
                "★ 发布面回归[构件] 发布 jar 含 /taclight tune 命令族(缺: " + tuneMissing
                        + ");选中 jar = " + jar);
        List<String> tuneNoSymbol = new ArrayList<>();
        for (TuneSymbol ts : TUNE_RELEASE_SYMBOLS) {
            if (!zipEntryContainsAscii(jar, ts.entry(), ts.symbol())) {
                tuneNoSymbol.add(ts.entry() + "#" + ts.symbol());
            }
        }
        check(tuneNoSymbol.isEmpty(),
                "★ 发布面回归[构件] 命令族字节码含 tune/反射挂载目标/可观测标记(缺: " + tuneNoSymbol
                        + ");选中 jar = " + jar);
        // 2026-09-29 R12c(N1 收尾):**方法级**死代码闸门。
        //   背景(VERDICT §4 N1):`GunControl.parseRelayArg` 的类在发布件里、方法却只被 dev-only 中继调用
        //   ⇒ 发布件里是死方法;查"同类还有谁"时发现 `GunControl.applyAuto` 同病,一并搬到 `devonly/GunRelay`。
        //   本闸门比**字节码符号**(不受注释影响);GunControl.class 缺失也算命中(空值 ≠ 通过)。
        //   红态自证:拿改前 jar 走 -PreleaseJar ⇒ 必红并点名符号。
        String[] deadHits = deadMethodHits(jar);
        check(deadHits.length == 0,
                "★ R12c[N1·构件] 发布件 dev/taclight/client/GunControl.class 不含中继专用死方法符号"
                        + "(期望 0 命中,实际 [" + String.join(",", deadHits) + "]);选中 jar = " + jar);

        // 2026-10-06 预览版收尾:调试面**按类名**的构件闸门(补 R12 目录剔除的漏)。
        //   红态自证:拿上一版发布件(89fa02a 构建)走 -PreleaseJar ⇒ 6 个类名逐条点名。
        check(deadDebugClass.isEmpty(),
                "★ 预览版收尾[构件] 发布 jar 不含已删调试类(期望 0 条,实际 " + deadDebugClass.size()
                        + (deadDebugClass.isEmpty() ? "" : " ⇒ " + deadDebugClass) + ");选中 jar = " + jar);

        // 2026-10-06:探针 mixin 也必须从 mixin 配置里消失 —— 类删了但配置留名会让 Mixin 直接报错,
        //   所以这两件事必须同时成立(只删类不改配置 = 启动崩;只改配置不删类 = 残留)。
        check(!zipEntryContainsAscii(jar, RELEASE_MIXIN_CONFIG, "GunModelRenderProbeMixin"),
                "★ 预览版收尾[构件] " + RELEASE_MIXIN_CONFIG + " 不再注册 GunModelRenderProbeMixin");

        // 2026-10-06:孤儿语言键。正控:同一读取函数必须先能找到 flashlight_toggle,
        //   否则本条只能证明"文件读错了",不能证明"键不存在"(AGENTS 一 4:空值 ≠ 否定结论)。
        for (String lang : new String[]{ "assets/taclight/lang/en_us.json", "assets/taclight/lang/zh_cn.json" }) {
            check(zipEntryContainsAscii(jar, lang, PRESENT_LANG_KEY),
                    "正控:jar 内 " + lang + " 含在用的语言键 " + PRESENT_LANG_KEY);
            List<String> orphans = new ArrayList<>();
            for (String k : REMOVED_LANG_KEYS) {
                if (zipEntryContainsAscii(jar, lang, k)) orphans.add(k);
            }
            check(orphans.isEmpty(), "★ 预览版收尾[构件] " + lang + " 不含已删功能的孤儿键(实际 " + orphans + ")");
        }

        // 2026-09-28 U-1b:gradle.properties 的 mod_version 与 jar 清单 Implementation-Version 必须同源。
        //   为什么补这条:implVer 此前**只被打印、从未被 check**(全文件仅 :93 读、:96 打印),
        //   而 modVersion() 早就在手上 —— 两边数据都在,就是没比。纯关系断言,**不钉任何字面量**
        //   (AGENTS 五 第7条:它每轮都会合法变,所以只能断关系,不能断值)。
        //   顺带价值:本条对"jar 是上一轮旧件、而 gradle.properties 的 mod_version 已推进"也敏感。
        // ✅ 2026-09-28 R12:B2 的**另一半**(class 常量对 mods.toml)已由 versionCoherence() 承接 ——
        //   它解析 TacLightMod.class 的常量子节 + 读 jar 内 mods.toml,并附"源码不得写字面量"的复发守卫。
        //   旧注释所说"那是另一张票"现已出票并落地。
        // ⚠️ 任一侧为 null 一律判红,不按"相等"放过(AGENTS 一 4:空值 ≠ 否定结论)。
        String modVer = modVersion();
        check(modVer != null && implVer != null && modVer.equals(implVer),
                "正控·构件: gradle.properties mod_version == 清单 Implementation-Version(实测 mod_version="
                        + modVer + " / Implementation-Version=" + implVer + ")");
    }

    /**
     * ⑤ 版本一致性(B2 的机器闸门,2026-09-28 R12)。**关系断言,不钉任何字面量**
     * ({@code AGENTS §五} 第 7 条:版本每轮都会合法变 ⇒ 只能断关系,不能断值):
     * <ul>
     *   <li>(a) 发布 jar 的 {@code META-INF/mods.toml} version == {@code gradle.properties mod_version}
     *       == 清单 {@code Implementation-Version}(三方同源);</li>
     *   <li>(b) {@code dev/taclight/TacLightMod.class} 常量池含 mod_version 的 ASCII 字节
     *       ⇒ <b>旧码必红</b>:R12 前该常量是手写的 {@code "0.10.0"} 而 {@code mod_version=0.11.0},
     *       本条在**归档的旧发布件**上就是红的(红态自证用的是旧 jar,不是推演);</li>
     *   <li>(c) 源码 {@code TacLightMod.java} 不得再出现带引号的语义化版本字面量
     *       ⇒ 防"下次升版本原样复发"。</li>
     * </ul>
     * ⚠️ 任一侧读不到(null)一律判红,不按"相等"放过({@code AGENTS §一 4}:空值 ≠ 否定结论)。
     */
    private static void versionCoherence() throws IOException {
        Path jar = pickReleaseJar().path();
        check(jar != null, "[B2·构件] 拿到 release jar");
        if (jar == null) return;
        String modVer = modVersion();
        String implVer = manifestAttr(jar, "Implementation-Version");
        String tomlVer = modsTomlVersion(jar);
        check(modVer != null && !modVer.isEmpty(), "读到 gradle.properties mod_version(" + modVer + ")");
        check(modVer != null && modVer.equals(tomlVer),
                "[B2·构件] 发布 jar 的 mods.toml version == mod_version(mods.toml=" + tomlVer
                        + " / mod_version=" + modVer + ")");
        check(modVer != null && modVer.equals(implVer),
                "[B2·构件] 发布 jar 清单 Implementation-Version == mod_version(实际 " + implVer
                        + " / " + modVer + ")");
        boolean inClass = modVer != null && !modVer.isEmpty()
                && zipEntryContainsAscii(jar, "dev/taclight/TacLightMod.class", modVer);
        check(inClass, "[B2·构件·真关系] TacLightMod.class 内含 mod_version 版本串 '" + modVer
                + "'(旧码硬编码 0.10.0 时本条红 —— 这就是 B2 的机器判据)");
        Path src = Path.of("src/main/java/dev/taclight/TacLightMod.java");
        boolean srcThere = Files.isRegularFile(src);
        // ⚠️ 必须**先剥注释再判**:本类与 TacLightMod 的 javadoc 里会引用历史字面量(如 "0.10.0")
        //   ⇒ 不剥注释就会"文档文字满足失败检测器"(AGENTS §五 M1 那条教训的镜像形态)。
        String text = srcThere
                ? InteropStatusContract.stripComments(Files.readString(src, StandardCharsets.UTF_8))
                : null;
        String hard = text == null ? null : findHardCodedVersion(text);
        check(srcThere && hard == null,
                "[B2·源码] TacLightMod.java 的**代码**里无手写语义化版本字面量(单一真源 = gradle.properties mod_version)"
                        + (srcThere ? "" : " ⇒ 源文件读不到: " + src)
                        + (hard == null ? "" : " ⇒ 命中 " + hard));
    }

    /** ② 构建树里必须有该类(剔除 ≠ 没编译)。 */
    private static void buildClassesPresent() {
        Path cls = Path.of("build/classes/java/main/dev/taclight/client/DebugCommandRelay.class");
        check(Files.isRegularFile(cls),
                "★ build/classes 含 DebugCommandRelay.class(证明是打包剔除,不是没编译): " + cls);
        Path debugDir = Path.of("build/classes/java/main/dev/taclight/debug");
        check(Files.isDirectory(debugDir),
                "★ build/classes 含 dev/taclight/debug/ 目录(调试类确实编译了): " + debugDir);
        // 2026-09-28 R12:8 个搬进 dev-only 命名空间的类同样必须编译出来 —— 否则"发布件里没有"
        //   会被"源码被删了"冒充(两种事实对排障含义完全不同)。
        List<String> notCompiled = new ArrayList<>();
        for (String rel : MOVED_DEV_ONLY_CLASSES) {
            if (!Files.isRegularFile(Path.of("build/classes/java/main", rel))) notCompiled.add(rel);
        }
        check(notCompiled.isEmpty(), "★ build/classes 含 R12 搬走的 dev-only 类(应 "
                + MOVED_DEV_ONLY_CLASSES.length + " 个,缺 " + notCompiled.size()
                + (notCompiled.isEmpty() ? "" : " ⇒ " + notCompiled) + ")");
        // 2026-10-06 发布面回归:发布面命令族也必须编译出来 —— 与上面的 dev-only 正控对称,
        //   防"发布件里没有"被"源码被删了"冒充(两种事实对排障含义完全不同)。
        List<String> tuneNotCompiled = new ArrayList<>();
        for (String rel : TUNE_RELEASE_CLASSES) {
            if (!Files.isRegularFile(Path.of("build/classes/java/main", rel))) tuneNotCompiled.add(rel);
        }
        check(tuneNotCompiled.isEmpty(), "★ build/classes 含发布面命令族(应 "
                + TUNE_RELEASE_CLASSES.length + " 个,缺 " + tuneNotCompiled.size()
                + (tuneNotCompiled.isEmpty() ? "" : " ⇒ " + tuneNotCompiled) + ")");
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
                "★ 文案不得再写\"游戏内 !interop\"(不存在客户端命令路径,曾误导验收人)");
        // ★ 2026-10-06:用户可见文案的归属从 interop 移到 ShaderPackDiagLogic(interop 不再发聊天栏,
        // 只因同一次换包连发两条口径不同的消息)。三条"文案口径"检查随之移到新归属,强度不变:
        // 仍钉"给可执行动作(文件通道)""保留人人可用路径(日志)""不得写游戏内命令"。
        Path ui = Path.of("src/main/java/dev/taclight/client/ShaderPackDiagLogic.java");
        check(Files.isRegularFile(ui), "找到 ShaderPackDiagLogic(用户可见文案的归属)");
        if (!Files.isRegularFile(ui)) return;
        String uiCode = InteropStatusContract.stripComments(
                new String(Files.readAllBytes(ui), StandardCharsets.UTF_8));
        check(uiCode.contains("<gameDir>/taclight-cmds.txt"),
                "★ 聊天文案给出可执行动作:写 <gameDir>/taclight-cmds.txt(dev 构建专用,已注明)");
        check(uiCode.contains("日志搜 interop"),
                "★ 聊天文案保留人人可用的路径:日志搜 interop(发布包不含文件通道)");
        check(!uiCode.contains("游戏内 !interop"),
                "★ 用户文案不得写\"游戏内 !interop\"");
        check(!injCode.contains("player.displayClientMessage"),
                "★ interop 不再自己发聊天栏提示(唯一通道 = ClientEvents.checkShaderPackDiag)");
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

    /** R12:jar 条目是否落在 dev-only 禁止清单(大小写敏感;jar 条目名恒为小写路径)。 */
    private static boolean isDevOnlyEntry(String name) {
        for (String p : DEV_ONLY_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * R12c:读发布件里 {@code dev/taclight/client/GunControl.class} 的**原始字节**,返回命中的死方法符号。
     * ⚠️ 类条目缺失 ⇒ 返回 {@code ["<GunControl.class 缺失>"]}(命中非空 ⇒ 判红,不是"静默通过")。
     */
    private static String[] deadMethodHits(Path jar) throws IOException {
        List<String> hit = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("dev/taclight/client/GunControl.class");
            if (e == null) {
                hit.add("<GunControl.class 缺失>");
                return hit.toArray(new String[0]);
            }
            byte[] data;
            try (var in = z.getInputStream(e)) {
                data = in.readAllBytes();
            }
            for (String sym : DEAD_METHOD_SYMBOLS) {
                if (containsAscii(data, sym)) hit.add(sym);
            }
        }
        return hit.toArray(new String[0]);
    }

    /** 发布件条目是否是已删调试类(含内嵌类)。 */
    private static boolean isRemovedDebugClass(String entry) {
        if (!entry.endsWith(".class")) return false;
        for (String fq : REMOVED_DEBUG_CLASSES) {
            String simple = fq.substring(fq.lastIndexOf('/') + 1);
            String pkg = fq.substring(0, fq.lastIndexOf('/') + 1);
            if (!entry.startsWith(pkg)) continue;
            String rest = entry.substring(pkg.length());
            if (rest.equals(simple + ".class") || rest.startsWith(simple + "$")) return true;
        }
        return false;
    }

    /** 朴素 ASCII 子串搜索(class 常量池为 modified-UTF8,ASCII 段逐字节可比)。 */
    private static boolean containsAscii(byte[] data, String s) {
        byte[] t = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + t.length <= data.length; i++) {
            boolean ok = true;
            for (int j = 0; j < t.length; j++) {
                if (data[i + j] != t[j]) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    /** R12:读 jar 内 {@code META-INF/mods.toml} 里本模组自身的 {@code version="…"}。 */
    private static String modsTomlVersion(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("META-INF/mods.toml");
            if (e == null) return null;
            String txt;
            try (var in = z.getInputStream(e)) {
                txt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var m = java.util.regex.Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"").matcher(txt);
            return m.find() ? m.group(1) : null;
        }
    }

    /**
     * R12:jar 内某条目的**原始字节**是否含给定 ASCII 串(class 常量池为 modified-UTF8,
     * ASCII 段逐字节可比)。条目不存在 ⇒ false(调用方按判红处理)。
     */
    /** 条目存在性(与"条目里含某串"分开:空值 ≠ 否定结论,诊断要能分清两种红)。 */
    private static boolean zipHasEntry(Path jar, String entry) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry(entry) != null;
        }
    }

    private static boolean zipEntryContainsAscii(Path jar, String entry, String ascii) throws IOException {        byte[] token = ascii.getBytes(StandardCharsets.US_ASCII);
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) return false;
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
        }
        return false;
    }

    /** R12:源码里带引号的语义化版本字面量(如 {@code "0.10.0"});没有 ⇒ null。 */
    private static String findHardCodedVersion(String src) {
        var m = java.util.regex.Pattern.compile("\"\\d+\\.\\d+\\.\\d+\"").matcher(src);
        return m.find() ? m.group() : null;
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

package dev.taclight.interop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.taclight.TacLightMod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 运行时注入编排(mixin 调用入口,方案C 里程碑2,计划文档 §3)。
 *
 * <p>流程:patchComposite/patchSodium/patchVanilla 的每个程序源文本流经 {@link #patchSource(String)};
 * 首次调用惰性解析(oculus.properties 包名 → 包根 → 候选模板 → 哈希快速通道),按包名缓存;
 * 模板不匹配 / 解析失败 = 原文返回(零注入)。
 * patchComposite 仅在管线(重)构建时被调用,非每帧;oculus.properties 每次调用
 * 重新读取一次(几十次小文件读/reload,可忽略),保证换包/改配置后正确重解析。</p>
 *
 * <p><b>2026-09-19 闸门换位(用户 ComplementaryReimagined_r5.9.3.zip 零注入的修复;Lead 硬裁定)</b>:</p>
 * <ol>
 *   <li><b>包名归一化(只用于匹配键,绝不动路径)</b>:Oculus 写进 oculus.properties 的是
 *       <b>文件名原样</b>({@code ComplementaryReimagined_r5.9.3.zip}),而模板登记的是开发机
 *       <b>目录包名</b>({@code ComplementaryReimagined}) ⇒ 旧 {@code name.equals(t.packName)}
 *       让<b>任何 .zip 包</b>都注入不了(实测用户日志 03:16:17 "暂无注入模板")。
 *       现走 {@link PackFingerprint#matchesPackName}(归一化 + 版本后缀容错);
 *       <b>{@link PackFingerprint#resolvePackRoot} 仍用原始名</b>(F4:磁盘文件名带后缀,
 *       拿归一化键去 resolve 会找不到包根)。</li>
 *   <li><b>闸门换位</b>:名字+整文件哈希降级为<b>快速通道</b>;<b>真闸门 =
 *       {@link PatchExecutor#applyDetailed} 在真实运行时文本上成功</b>
 *       (锚点逐字命中 + selectorCount 相符 + 注后自检:marker 恰 1 次 / 必需签名齐全 /
 *       宿主括号平衡未破坏)。哈希漂移时仍允许注入,但<b>只允许</b>在锚点逐字全中的前提下
 *       —— 锚点不中 = 零注入(不给模糊匹配留口子)。</li>
 *   <li><b>失败可操作</b>:报"哪个文件、哪个算子、试过哪些锚点";{@code !interop} 命令
 *       ({@link #statusReport()})输出原始名→归一化键→包根→模板→逐文件结果。</li>
 *   <li><b>F5</b>:指纹键改为<b>模板自己声明的键</b>({@code t.packHash.keySet()}),
 *       不再用固定 5 文件清单 —— 旧清单含 Complementary 根本没有的 {@code shaders/composite.fsh},
 *       谁照着填进 packHash 就永远匹配不上。</li>
 * </ol>
 *
 * <p><b>2026-10-06 名字闸门降级(用户 e2e 实测:同一份包、只换了文件名就零注入)</b>:</p>
 * <p>实测 {@code ComplementaryReimagined_r5.9.3(1).zip}(浏览器重复下载自动加的后缀)与
 * {@code ComplementaryReimagined_r5.9.3.zip} <b>sha256 完全相同</b>,但旧码在
 * {@code matchesPackName} 不命中时<b>直接返回"无候选模板"</b> ⇒ 内容闸门(哈希/锚点)从未被咨询
 * ⇒ 零注入,并劝用户去选 {@code iterationT 3.2.0 (taclight)}(而发布 jar 不含任何光影包)。
 * 名字匹配是<b>白名单</b>,文件名却由用户/浏览器决定 ⇒ <b>名字降级为排序提示</b>
 * ({@link PackFingerprint#candidateOrder}):全部模板都进内容闸门,谁的内容逐字命中谁生效。
 * 单包解析成本 = 每模板读几个声明文件(一次,按包名缓存),可忽略。</p>
 *
 * <p><b>用户可见提示的归属(2026-10-06)</b>:本类只写日志、不再发聊天栏提示 —— 聊天栏由
 * {@code ClientEvents.checkShaderPackDiag}(每 5s 轮询状态变化)统一负责,避免同一次换包
 * 收到两条口径不同的消息(实测:20:51:47 "暂不支持注入" + 20:51:48 "无 TacLight 注入")。</p>
 */
public final class RuntimePackInjector {

    /** 缓存:原始包名 → 解析结果(含包根缺失等原因)。 */
    private static final Map<String, Resolution> RESOLVED = new ConcurrentHashMap<>();

    /** {@code !interop} 命令读的状态快照(不可变字符串,渲染线程/命令线程皆可读)。 */
    private static volatile String status = "(尚未解析:没有 shader 程序源流经 patchSource)";

    /**
     * 最近一次解析的<b>结构化</b>结果(2026-09-25)。
     *
     * <p>{@link #status} 是给人读的字符串,<b>不能</b>拿来判状态。客户端自检
     * ({@code ShaderPackDiag})必须知道"这个包到底注入了没有",否则只能用"磁盘包里有没有标记"
     * 去猜 —— 而 interop 是运行时内存注入,磁盘上永远没有标记 ⇒ 用户被误报"无注入"
     * (2026-09-19 20:19 用户实测:58 行 injected 日志 + 聊天栏 ✘ 无注入,同一会话)。</p>
     */
    public record Outcome(String rawName, String matchKey, boolean templateMatched,
                          boolean injected, String channel, String detail) {}
    private static volatile Outcome lastOutcome;

    /**
     * <b>粘性成功</b>记录(2026-09-25 真机轮修复)。
     *
     * <p>{@link #lastOutcome} 会被<b>后续任何一个</b>失败 publish 覆盖:同一包对某些程序源
     * 注入成功(+8419 chars ×30),而个别文件"命中模板但该文件注入失败"会把最后一次写成失败 ⇒
     * 客户端自检在注入明明成功的会话里报 {@code INTEROP_FAILED}(2026-09-25 05:13 真机实测,
     * 聊天栏打 ✘ 而日志有 30 行 injected)。所以自检必须读本字段,而不是 {@code lastOutcome}。</p>
     */
    private static volatile Outcome stickyInjected;

    private RuntimePackInjector() {}

    /** 一个候选模板 + 它的匹配依据(哈希快速通道 / 已知良好清单 / 内容 best-effort)。 */
    private static final class Candidate {
        final TemplateLibrary.Template template;
        final boolean fastPath;   // true = 模板声明的哈希键全中
        final boolean knownGood;  // true = 已知良好清单命中(锚点验证过;仍走锚点闸门)
        final String detail;      // 哈希对照 / 漂移说明

        Candidate(TemplateLibrary.Template template, boolean fastPath, boolean knownGood, String detail) {
            this.template = template;
            this.fastPath = fastPath;
            this.knownGood = knownGood;
            this.detail = detail;
        }

        String channel() {
            if (fastPath) return "fast:name+hash";
            return knownGood ? "known-good:anchors" : "best-effort:anchors";
        }
    }

    /** 一次解析的结论(除可变诊断字段外不可变)。 */
    private static final class Resolution {
        final String rawName;
        final String matchKey;
        final String root;
        /** 候选 = <b>模板库全量</b>,按"名字命中优先"排序(名字只排序,不筛选)。 */
        final List<Candidate> candidates;
        /** 候选为空 / 包根缺失时的原因(诊断文案)。 */
        final String reason;
        /** 诊断用:哈希全中的候选,否则第一个候选;可空。 */
        final Candidate probe;
        /** 名字命中的候选数(仅日志用)。 */
        final int namedCount;
        volatile Candidate hit;       // 首个注入成功的候选
        volatile boolean recognized;  // 内容层面认出来了(hash 全中 / selector 命中 / 注入成功)
        volatile boolean loggedNoHit; // "全候选未命中"日志只打一次
        volatile String fileOutcomes = "(尚未有程序源流过)";

        Resolution(String rawName, String matchKey, String root, List<Candidate> candidates,
                   String reason, Candidate probe, int namedCount) {
            this.rawName = rawName;
            this.matchKey = matchKey;
            this.root = root;
            this.candidates = candidates;
            this.reason = reason;
            this.probe = probe;
            this.namedCount = namedCount;
        }
    }

    /** mixin 对每个源文本调用;返回注入后文本或原文。任何异常 = 原文返回(fail-safe,零注入)。 */
    public static String patchSource(String sourceText) {
        try {
            return patchSourceInner(sourceText);
        } catch (Throwable th) {
            TacLightMod.LOGGER.warn("[TacLight] interop: patchSource 异常(原文返回,零注入): {}", th.toString());
            return sourceText;
        }
    }

    private static String patchSourceInner(String sourceText) {
        if (sourceText == null || sourceText.isEmpty()) return sourceText;
        // 内容优先幂等(2026-10-06):源文本已含 marker = 自研/派生包,或同一轮重复流过 ⇒
        // 原样返回。放在模板尝试之前:既省掉无谓扫描,也让"派生包不再被注入一次"改由内容保证,
        // 而不是靠名字不命中(名字已不再是否决权)。
        if (sourceText.contains(PatchExecutor.MARKER)) {
            Resolution cached = RESOLVED.get(currentPackName());
            if (cached != null) {
                cached.recognized = true;
                cached.fileOutcomes = "(源文本已含 marker:自研/派生包或已注入)";
            }
            return sourceText;
        }
        Resolution r = resolve();
        if (r == null || r.candidates.isEmpty()) return sourceText;
        List<String> outcomes = new ArrayList<>();
        boolean anyTarget = false;
        // 命中过的候选先试(顺序稳定);候选是模板库全量 ⇒ 名字不命中也照样进内容闸门。
        for (Candidate c : ordered(r)) {
            for (TemplateLibrary.FileRule rule : c.template.files) {
                PatchExecutor.Result res = PatchExecutor.applyDetailed(sourceText, rule.selector,
                        rule.selectorCount, toOps(rule), c.template.requiredSymbols);
                if (res.ok()) {
                    TacLightMod.LOGGER.info("[TacLight] interop injected family={} pack={} (+{} chars, {})",
                            c.template.familyId, r.rawName, res.injectedChars, c.channel());
                    outcomes.add(rule.file + ": 注入成功 +" + res.injectedChars + " chars");
                    r.hit = c;
                    r.recognized = true;
                    r.fileOutcomes = String.join("; ", outcomes);
                    publish(r, c, true);
                    return res.patched;
                }
                if (res.selectorHit) {
                    anyTarget = true;
                    r.recognized = true;
                }
                outcomes.add(c.template.familyId + "/" + rule.file + ": " + res.failure);
            }
        }
        r.fileOutcomes = String.join("; ", outcomes);
        if (anyTarget) {
            TacLightMod.LOGGER.warn("[TacLight] interop: 包 {} 命中模板但注入失败(零注入)。逐文件原因: {}",
                    r.rawName, r.fileOutcomes);
        } else if (!r.recognized && !r.loggedNoHit) {
            r.loggedNoHit = true;
            TacLightMod.LOGGER.info("[TacLight] interop: 包 {} 与全部 {} 个候选模板的内容锚点均未命中 ⇒ "
                            + "零注入(本包不生效)。逐文件原因: {}",
                    r.rawName, r.candidates.size(), r.fileOutcomes);
        }
        publish(r, r.hit, false);
        return sourceText;
    }

    /** 尝试顺序:已命中的候选最前,其余按名字命中优先。 */
    private static List<Candidate> ordered(Resolution r) {
        List<Candidate> out = new ArrayList<>(r.candidates.size());
        if (r.hit != null) out.add(r.hit);
        for (Candidate c : r.candidates) if (c != r.hit) out.add(c);
        return out;
    }

    private static List<PatchExecutor.Op> toOps(TemplateLibrary.FileRule rule) {
        List<PatchExecutor.Op> ops = new ArrayList<>(rule.ops.size());
        for (TemplateLibrary.Op o : rule.ops) {
            ops.add(new PatchExecutor.Op(o.op, o.anchor, o.content, o.anchors));
        }
        return ops;
    }

    private static Resolution resolve() {
        String name = currentPackName();
        if (name == null) {
            status = "interop: oculus.properties 无 shaderPack= 行(未启用光影)";
            return null;
        }
        Resolution cached = RESOLVED.get(name);
        if (cached != null) return cached;
        synchronized (RESOLVED) {
            cached = RESOLVED.get(name);
            if (cached != null) return cached;
            Resolution r = matchTemplate(name);
            RESOLVED.put(name, r);
            if (r.candidates.isEmpty()) {
                TacLightMod.LOGGER.info("[TacLight] interop: 包 {} 无候选模板(零注入)。原因: {}",
                        name, r.reason);
            } else {
                TacLightMod.LOGGER.info("[TacLight] interop: 包 {} 解析完毕:候选模板 {} 个(名字命中 {}),"
                                + "hash 全中={},诊断模板={}",
                        name, r.candidates.size(), r.namedCount,
                        r.probe != null && r.probe.fastPath,
                        r.probe == null ? "(无)" : r.probe.template.familyId);
            }
            publish(r, null, false);
            return r;
        }
    }

    /**
     * 候选解析:<b>模板库全量</b>入列,按"名字命中优先"排序,再逐候选做哈希快速通道。
     *
     * <p>方法名保持 {@code matchTemplate}(离线契约按名定位本方法体,见
     * {@code InteropStatusContract})。名字只排序、不筛选 —— 见类注释 2026-10-06 一节。</p>
     */
    private static Resolution matchTemplate(String rawName) {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path shaderpacks = gameDir.resolve("shaderpacks");
            // ★ F4:路径解析必须用原始名(带 .zip/版本后缀)
            Path root = PackFingerprint.resolvePackRoot(shaderpacks, rawName).orElse(null);
            String key = PackFingerprint.packMatchKey(rawName);
            if (root == null) {
                return new Resolution(rawName, key, "(未找到)", List.of(),
                        "包根未找到:shaderpacks/" + rawName + " 不存在", null, 0);
            }
            List<TemplateLibrary.Template> all = TemplateLibrary.loadAll();
            if (all.isEmpty()) {
                return new Resolution(rawName, key, root.toString(), List.of(),
                        "模板库为空(未登记任何模板)", null, 0);
            }
            boolean[] nameMatched = new boolean[all.size()];
            List<Candidate> built = new ArrayList<>(all.size());
            for (int i = 0; i < all.size(); i++) {
                TemplateLibrary.Template t = all.get(i);
                nameMatched[i] = PackFingerprint.matchesPackName(rawName, t.packName);
                Map<String, String> fp =
                        PackFingerprint.fingerprint(root, new ArrayList<>(t.packHash.keySet()));
                boolean hashHit = PackFingerprint.matches(fp, t.packHash);
                boolean known = PackFingerprint.isKnownGood(rawName, t.knownGoodPacks);
                built.add(new Candidate(t, hashHit, known, hashHit
                        ? "hash 全中(" + t.packHash.size() + " 键)"
                        : (known ? "已知良好清单命中(锚点验证过;不存整文件哈希) | " : "")
                                + describeDrift(fp, t.packHash)));
            }
            List<Candidate> candidates = new ArrayList<>(all.size());
            int namedCount = 0;
            for (int idx : PackFingerprint.candidateOrder(nameMatched)) {
                candidates.add(built.get(idx));
                if (nameMatched[idx]) namedCount++;
            }
            Candidate probe = null;
            for (Candidate c : candidates) {
                if (c.fastPath) {
                    probe = c;
                    break;
                }
            }
            if (probe == null) probe = candidates.get(0);
            Resolution r = new Resolution(rawName, key, root.toString(), candidates,
                    "(全部候选的锚点均未命中)", probe, namedCount);
            // 哈希全中 = 内容层面已认出这个包(与名字闸门无关)。
            r.recognized = probe.fastPath;
            return r;
        } catch (Throwable th) {
            TacLightMod.LOGGER.warn("[TacLight] interop: 模板解析异常(零注入): {}", th.toString());
            return new Resolution(rawName, PackFingerprint.packMatchKey(rawName), "(异常)", List.of(),
                    "模板解析异常: " + th, null, 0);
        }
    }

    private static String describeDrift(Map<String, String> got, Map<String, String> want) {
        StringBuilder sb = new StringBuilder("哈希漂移(由锚点闸门决定):");
        boolean first = true;
        for (Map.Entry<String, String> e : want.entrySet()) {
            String g = got.get(e.getKey());
            if (e.getValue().equals(g)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(' ').append(e.getKey()).append('=')
                    .append(g == null ? "(缺失)" : g).append("(模板 ").append(e.getValue()).append(')');
        }
        return first ? "哈希一致(快速通道判定异常?)" : sb.toString();
    }

    private static String currentPackName() {
        try {
            Path props = FMLPaths.GAMEDIR.get().resolve("config/oculus.properties");
            if (!Files.isRegularFile(props)) return null;
            return PackFingerprint.packNameFromProperties(Files.readString(props)).orElse(null);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 状态快照(供 {@code !interop} 命令与日志)。 */
    public static String statusReport() {
        return status;
    }

    /** 最近一次解析的<b>结构化</b>结果;从未解析过 ⇒ {@code null}。诊断用。 */
    public static Outcome lastOutcome() {
        return lastOutcome;
    }

    /**
     * 该包<b>成功注入过</b>的结果(粘性:同包后续单文件失败不回退);从未成功 ⇒ {@code null}。
     * <b>客户端自检用这个</b>,不要用 {@link #lastOutcome()}。
     */
    public static Outcome stickyInjectedOutcome() {
        return stickyInjected;
    }

    private static void publish(Resolution r, Candidate c, boolean injected) {
        // 展示用模板:命中者为先,否则用诊断模板(哈希全中/第一个候选),让 !interop 与状态行有据可依。
        Candidate shown = c != null ? c : r.probe;
        String templateLine = shown == null ? null
                : "family=" + shown.template.familyId + " packName=" + shown.template.packName;
        String channel = c != null ? c.channel() : (shown == null ? null : "none:anchors");
        String detail = c != null ? c.detail
                : (shown == null ? r.reason : "全部候选的锚点均未命中 | " + r.fileOutcomes);
        // 结构化快照先行:客户端自检(ShaderPackDiag)读它,不看人读字符串。
        lastOutcome = new Outcome(r.rawName, r.matchKey, r.recognized, injected, channel, detail);
        // 粘性成功:同包后续单文件失败不得把整包翻回失败(2026-09-25 真机实测缺陷)。
        if (injected) stickyInjected = lastOutcome;
        status = formatStatus(r.rawName, r.matchKey, r.root, templateLine, channel, detail,
                r.fileOutcomes, injected);
    }

    /**
     * 状态文本<b>纯格式化</b>(2026-09-19):{@code !interop} 诊断工具的正文。
     * 抽成纯函数以便离线断言"五要素齐全"——原始名 / 归一化匹配键 / 包根 / 模板+通道 /
     * 逐文件结果(+ 无模板时给原因)。真机只差"文件中继把这段打进日志"这一步。
     */
    public static String formatStatus(String rawName, String matchKey, String root,
                                     String templateLine, String channelLine, String detail,
                                     String fileOutcomes, boolean injected) {
        StringBuilder sb = new StringBuilder("interop 状态(最近一次解析):\n");
        sb.append("  shaderPack(原始名) = \"").append(rawName).append("\"\n");
        sb.append("  归一化匹配键      = \"").append(matchKey).append("\"  (仅用于匹配;路径解析用原始名)\n");
        sb.append("  包根              = ").append(root).append('\n');
        if (templateLine == null) {
            sb.append("  模板              = (无)  ⇒ 零注入\n");
            sb.append("  原因              = ").append(detail).append('\n');
        } else {
            sb.append("  模板              = ").append(templateLine).append('\n');
            sb.append("  通道              = ").append(channelLine).append("  (").append(detail).append(")\n");
            sb.append("  逐文件结果        = ").append(fileOutcomes).append('\n');
        }
        sb.append("  最近一次          = ").append(injected ? "注入成功" : "本程序源未注入(见上)");
        return sb.toString();
    }

    /** 诊断用:已登记模板清单。 */
    public static List<String> registeredTemplates() {
        List<String> out = new ArrayList<>();
        for (TemplateLibrary.Template t : TemplateLibrary.loadAll()) {
            out.add(t.familyId + "→" + t.packName + " (hash 键 " + t.packHash.size() + ")");
        }
        return out;
    }

    /** 诊断用:当前 oculus.properties 的 shaderPack 原始值(不解析)。 */
    public static String rawPackName() {
        String n = currentPackName();
        return n == null ? "(无)" : n;
    }

    /** 诊断用:候选模板的哈希对照(不注入)。 */
    public static Map<String, String> hashComparison(String rawName) {
        Map<String, String> out = new LinkedHashMap<>();
        Resolution r = RESOLVED.get(rawName);
        Candidate c = r == null ? null : (r.hit != null ? r.hit : r.probe);
        if (c == null) return out;
        try {
            Path root = PackFingerprint.resolvePackRoot(
                    FMLPaths.GAMEDIR.get().resolve("shaderpacks"), rawName).orElse(null);
            if (root == null) return out;
            Map<String, String> got =
                    PackFingerprint.fingerprint(root, new ArrayList<>(c.template.packHash.keySet()));
            for (Map.Entry<String, String> e : c.template.packHash.entrySet()) {
                out.put(e.getKey(), (got.get(e.getKey()) == null ? "(缺失)" : got.get(e.getKey()))
                        + " vs 模板 " + e.getValue());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}

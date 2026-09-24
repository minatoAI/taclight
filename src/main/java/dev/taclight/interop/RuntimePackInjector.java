package dev.taclight.interop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.taclight.TacLightMod;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 运行时注入编排(mixin 调用入口,方案C 里程碑2,计划文档 §3)。
 *
 * <p>流程:patchComposite/patchSodium/patchVanilla 的每个程序源文本流经 {@link #patchSource(String)};
 * 首次调用惰性解析(oculus.properties 包名 → 包根 → 模板候选 → 哈希快速通道),按包名缓存;
 * 模板不匹配 / 解析失败 = 原文返回(零注入)。不支持包做提示(聊天栏+日志)。
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
 *   <li><b>提示时机</b>:首次 + 每次 reload 各一次(2s 去抖;同一轮管线构建内的多次调用只提示一次)。</li>
 *   <li><b>F5</b>:指纹键改为<b>模板自己声明的键</b>({@code t.packHash.keySet()}),
 *       不再用固定 5 文件清单 —— 旧清单含 Complementary 根本没有的 {@code shaders/composite.fsh},
 *       谁照着填进 packHash 就永远匹配不上。</li>
 * </ol>
 */
public final class RuntimePackInjector {
    /** 提示去抖:同一轮管线构建内 patchSource 会按程序源调用多次(ms 级),reload 间隔远大于此。 */
    private static final long ANNOUNCE_DEBOUNCE_MS = 2000L;

    /** 缓存:原始包名 → 解析结果(含"无模板"的失败原因)。 */
    private static final Map<String, Resolution> RESOLVED = new ConcurrentHashMap<>();
    /** 每包最近一次提示时间(reload 去抖)。 */
    private static final Map<String, Long> LAST_ANNOUNCE = new ConcurrentHashMap<>();

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

    private RuntimePackInjector() {}

    /** 一次解析的结论(除 fileOutcomes 外不可变)。 */
    private static final class Resolution {
        final String rawName;
        final String matchKey;
        final String root;
        final TemplateLibrary.Template template; // null = 无候选模板
        final boolean fastPath;                  // true = 名字+哈希快速通道
        final boolean knownGood;                 // true = 已知良好清单命中(锚点验证过;仍走锚点闸门)
        final String detail;                     // 哈希对照 / 失败原因
        volatile String fileOutcomes = "(尚未有程序源流过)";

        Resolution(String rawName, String matchKey, String root, TemplateLibrary.Template template,
                   boolean fastPath, boolean knownGood, String detail) {
            this.rawName = rawName;
            this.matchKey = matchKey;
            this.root = root;
            this.template = template;
            this.fastPath = fastPath;
            this.knownGood = knownGood;
            this.detail = detail;
        }

        String channel() {
            if (fastPath) return "fast:name+hash";
            return knownGood ? "known-good:anchors" : "best-effort:hash-drift";
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
        Resolution r = resolve();
        if (r == null || r.template == null) return sourceText;
        List<String> outcomes = new ArrayList<>();
        boolean anyTarget = false;
        for (TemplateLibrary.FileRule rule : r.template.files) {
            PatchExecutor.Result res = PatchExecutor.applyDetailed(sourceText, rule.selector,
                    rule.selectorCount, toOps(rule), r.template.requiredSymbols);
            if (res.ok()) {
                TacLightMod.LOGGER.info("[TacLight] interop injected family={} pack={} (+{} chars, {})",
                        r.template.familyId, r.rawName, res.injectedChars, r.channel());
                outcomes.add(rule.file + ": 注入成功 +" + res.injectedChars + " chars");
                r.fileOutcomes = String.join("; ", outcomes);
                publish(r, true);
                return res.patched;
            }
            if (res.selectorHit) anyTarget = true; // 是目标文件但注入不了 ⇒ 必须报因
            outcomes.add(rule.file + ": " + res.failure);
        }
        r.fileOutcomes = String.join("; ", outcomes);
        if (anyTarget) {
            TacLightMod.LOGGER.warn("[TacLight] interop: 包 {} 命中模板 {} 但注入失败(零注入)。逐文件原因: {}",
                    r.rawName, r.template.familyId, r.fileOutcomes);
        }
        publish(r, false);
        return sourceText;
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
            if (r.template == null) {
                announce(name, r.detail);
            } else {
                TacLightMod.LOGGER.info("[TacLight] interop: 包 {} 命中模板 family={} packName={} 通道={} ({})",
                        name, r.template.familyId, r.template.packName, r.channel(), r.detail);
            }
            publish(r, false);
            return r;
        }
    }

    /** 名字(归一化)匹配 + 哈希快速通道;失败也返回 Resolution(带可操作原因),不再返回裸 null。 */
    private static Resolution matchTemplate(String rawName) {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path shaderpacks = gameDir.resolve("shaderpacks");
            // ★ F4:路径解析必须用原始名(带 .zip/版本后缀)
            Path root = PackFingerprint.resolvePackRoot(shaderpacks, rawName).orElse(null);
            String key = PackFingerprint.packMatchKey(rawName);
            if (root == null) {
                return new Resolution(rawName, key, "(未找到)", null, false, false,
                        "包根未找到:shaderpacks/" + rawName + " 不存在");
            }
            List<TemplateLibrary.Template> candidates = new ArrayList<>();
            StringBuilder names = new StringBuilder();
            for (TemplateLibrary.Template t : TemplateLibrary.loadAll()) {
                if (names.length() > 0) names.append(", ");
                names.append(t.familyId).append('→').append(t.packName);
                if (PackFingerprint.matchesPackName(rawName, t.packName)) candidates.add(t);
            }
            if (candidates.isEmpty()) {
                return new Resolution(rawName, key, root.toString(), null, false, false,
                        "无候选模板(归一化键 \"" + key + "\" 不匹配任何已登记 packName;已登记: " + names + ")");
            }
            // 快速通道:名字匹配 + 模板声明的键全中(键 = 模板自己声明的,F5)
            for (TemplateLibrary.Template t : candidates) {
                Map<String, String> fp = PackFingerprint.fingerprint(root, new ArrayList<>(t.packHash.keySet()));
                if (PackFingerprint.matches(fp, t.packHash)) {
                    return new Resolution(rawName, key, root.toString(), t, true, false,
                            "hash 全中(" + t.packHash.size() + " 键)");
                }
            }
            // 哈希漂移:① 已知良好清单(锚点验证过,硬裁定②)⇒ 标 known-good;
            // ② 否则 best-effort。两者都仍由锚点闸门(applyDetailed)决定是否注入。
            TemplateLibrary.Template t = candidates.get(0);
            Map<String, String> fp = PackFingerprint.fingerprint(root, new ArrayList<>(t.packHash.keySet()));
            boolean known = PackFingerprint.isKnownGood(rawName, t.knownGoodPacks);
            return new Resolution(rawName, key, root.toString(), t, false, known,
                    (known ? "已知良好清单命中(锚点验证过;不存整文件哈希) | " : "") + describeDrift(fp, t.packHash));
        } catch (Throwable th) {
            TacLightMod.LOGGER.warn("[TacLight] interop: 模板解析异常(零注入): {}", th.toString());
            return new Resolution(rawName, PackFingerprint.packMatchKey(rawName), "(异常)", null, false, false,
                    "模板解析异常: " + th);
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

    /**
     * 不支持包的提示(计划 §1):日志恒有;聊天栏尽力而为。首次 + 每次 reload(2s 去抖)。
     *
     * <p><b>★ 文案可操作性(2026-09-19 修正)</b>:旧文案写"游戏内 !interop 看详情"——
     * <b>根本不存在客户端命令路径</b>({@code !interop} 只能走 dev 的文件中继),这句把
     * 我们自己的验收人 qa 都误导成"客户端命令路径"⇒ 现改为给出<b>可执行动作</b>:
     * 日志搜 {@code interop} / 写 {@code <gameDir>/taclight-cmds.txt}(并注明该通道仅 dev 构建存在)。
     * 发布 jar 里该文件通道被剔除(build.gradle exclude),故聊天栏必须写清"日志"这条人人可用的路径。</p>
     */
    private static void announce(String packName, String reason) {
        long now = System.currentTimeMillis();
        Long last = LAST_ANNOUNCE.get(packName);
        if (last != null && now - last < ANNOUNCE_DEBOUNCE_MS) return;
        LAST_ANNOUNCE.put(packName, now);
        TacLightMod.LOGGER.info(
                "[TacLight] interop: 包 \"{}\" 暂无注入模板,本包不生效 TacLight 照明(零改动)。原因: {}",
                packName, reason);
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(
                                "[TacLight] 光影包 \"" + packName + "\" 暂不支持锥形照明注入(本包不生效,零改动)。"
                                        + "可做:①日志搜 interop 看原因;"
                                        + "②dev 实例把 !interop 写入 <gameDir>/taclight-cmds.txt 看逐文件详情"),
                                false);
                    }
                } catch (Throwable ignored) {
                    // 提示失败不影响注入安全语义
                }
            });
        } catch (Throwable ignored) {
            // 游戏尚未就绪时仅留日志
        }
    }

    /** 状态快照(供 {@code !interop} 命令与日志)。 */
    public static String statusReport() {
        return status;
    }

    /** 最近一次解析的<b>结构化</b>结果;从未解析过 ⇒ {@code null}。客户端自检用。 */
    public static Outcome lastOutcome() {
        return lastOutcome;
    }

    private static void publish(Resolution r, boolean injected) {
        String templateLine = r.template == null ? null
                : "family=" + r.template.familyId + " packName=" + r.template.packName;
        // 结构化快照先行:客户端自检(ShaderPackDiag)读它,不看人读字符串。
        lastOutcome = new Outcome(r.rawName, r.matchKey, r.template != null, injected,
                r.template == null ? null : r.channel(), r.detail);
        status = formatStatus(r.rawName, r.matchKey, r.root, templateLine,
                r.template == null ? null : r.channel(), r.detail, r.fileOutcomes, injected);
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
        if (r == null || r.template == null) return out;
        try {
            Path root = PackFingerprint.resolvePackRoot(
                    FMLPaths.GAMEDIR.get().resolve("shaderpacks"), rawName).orElse(null);
            if (root == null) return out;
            Map<String, String> got = PackFingerprint.fingerprint(root, new ArrayList<>(r.template.packHash.keySet()));
            for (Map.Entry<String, String> e : r.template.packHash.entrySet()) {
                out.put(e.getKey(), (got.get(e.getKey()) == null ? "(缺失)" : got.get(e.getKey()))
                        + " vs 模板 " + e.getValue());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}

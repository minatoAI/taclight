package dev.taclight.interop;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 包指纹与包文件读取(方案C 里程碑2,计划文档 §3)。零 MC 依赖,契约全量覆盖。
 * 包根三形态:shaderpacks 目录下的目录包 / .zip 包(含嵌套根 mypack/shaders/...)/
 * 绝对路径包(oculus.properties 允许)。指纹 = 关键文件相对路径 → sha256 前 16 位;
 * 匹配规则 = 模板要求的全部键值相等,任一缺失/不符 = false(零注入,保守)。
 */
public final class PackFingerprint {

    private PackFingerprint() {}

    /** 解析 oculus.properties 的 shaderPack= 行;无行/空值 = empty。容忍 CRLF 与首尾空白。 */
    public static Optional<String> packNameFromProperties(String text) {
        if (text == null) return Optional.empty();
        for (String line : text.split("\r?\n")) {
            String l = line.trim();
            if (l.startsWith("shaderPack=")) {
                String v = l.substring("shaderPack=".length()).trim();
                return v.isEmpty() ? Optional.empty() : Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /**
     * 包名<b>匹配键</b>(2026-09-19 interop 包名闸门修复;只用于比较,<b>绝不可用于路径解析</b>)。
     *
     * <p>背景:用户用 {@code ComplementaryReimagined_r5.9.3.zip} 时零注入。Oculus 写进
     * {@code config/oculus.properties} 的 {@code shaderPack=} 是<b>文件名原样</b>(带 {@code .zip}
     * 与版本后缀),而模板登记的是开发机<b>目录包名</b>({@code ComplementaryReimagined} /
     * {@code iterationT 3.2.0}),{@code name.equals(t.packName)} 直接失败 ⇒ 无模板 ⇒ 零注入
     * (任何 {@code .zip} 包都注入不了)。实测:用户日志 2026-09-19 03:16:17
     * {@code 包 "ComplementaryReimagined_r5.9.3.zip" 暂无注入模板}。</p>
     *
     * <p><b>★ F4(2026-09-19 硬裁定 ①)</b>:归一化后的键<b>只能</b>用于模板匹配。
     * {@link #resolvePackRoot} 必须拿<b>原始名</b>({@code ComplementaryReimagined_r5.9.3.zip})
     * —— 磁盘上的文件名带后缀,拿归一化键去 resolve 会找不到包根,名字修好了照样零注入。
     * 调用方:{@code RuntimePackInjector} 用原始名解析包根、用本键匹配模板。</p>
     *
     * <p>规则:trim → 去结尾 {@code .zip}(大小写不敏感,只去一次)→ 连续 {@code [ _-]}
     * 折叠成一个空格 → 去结尾 {@code /} → 小写。</p>
     */
    public static String packMatchKey(String rawName) {
        if (rawName == null) return "";
        String s = rawName.trim();
        if (s.length() >= 4 && s.regionMatches(true, s.length() - 4, ".zip", 0, 4)) {
            s = s.substring(0, s.length() - 4);
        }
        s = s.replaceAll("[ _-]+", " ").trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1).trim();
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 包名匹配(2026-09-19):归一化后<b>精确相等</b>,或"模板名 + 版本后缀"。
     *
     * <p>版本后缀规则 = 余下部分匹配 {@code " ?[a-z]?\d[\w.\- ]*"}:
     * <ul>
     *   <li>{@code ComplementaryReimagined_r5.9.3.zip} → 键 {@code complementaryreimagined r5.9.3}
     *       ⇒ 余 {@code " r5.9.3"} 命中(可选单字母 + 版本号)✓</li>
     *   <li>{@code iterationT-3.2.0.zip} → 键 {@code iterationt 3.2.0} ⇒ 与模板键精确相等 ✓</li>
     *   <li>{@code ComplementaryReimaginedExtra} → 余 {@code "extra"} <b>不</b>命中
     *       (分隔符后必须是"可选单字母+数字")⇒ 同前缀异包不误命中 ✓</li>
     *   <li>{@code iterationT 3.2.0 (taclight)}(本机路线P派生包)→ 余 {@code " (taclight)"}
     *       <b>不</b>命中 ✓(派生包已内联,不该再注入一次)</li>
     * </ul>
     * 残余风险(如实):{@code ComplementaryReimagined_2} 这类"数字后缀但语义不同"的包会被判为
     * 候选——但<b>闸门不在名字</b>:候选仍要过哈希快速通道或"锚点逐字全中 + 注后自检"的真闸门
     * (见 {@code RuntimePackInjector}),锚点不中则零注入。</p>
     */
    public static boolean matchesPackName(String rawName, String templatePackName) {
        String a = packMatchKey(rawName);
        String b = packMatchKey(templatePackName);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (!a.startsWith(b)) return false;
        String rest = a.substring(b.length());
        return rest.matches(" ?[a-z]?\\d[\\w.\\- ]*");
    }

    /**
     * 已知良好清单判定(2026-09-19):{@code rawName} 的匹配键等于清单中任一条目的匹配键。
     * 语义 = "这版哈希虽与模板 packHash 不符,但锚点已离线/实机验证可注入" ⇒ 走锚点通道并标
     * known-good(闸门仍是 {@code PatchExecutor.applyDetailed},不是"进了清单就放行")。
     */
    public static boolean isKnownGood(String rawName, java.util.List<String> knownGoodPacks) {
        if (knownGoodPacks == null || knownGoodPacks.isEmpty()) return false;
        String key = packMatchKey(rawName);
        if (key.isEmpty()) return false;
        for (String k : knownGoodPacks) {
            if (key.equals(packMatchKey(k))) return true;
        }
        return false;
    }

    /** 包根定位:绝对路径优先(存在即用),否则 shaderpacks 目录下按名找。 */
    public static Optional<Path> resolvePackRoot(Path shaderpacksDir, String packName) {
        if (shaderpacksDir == null || packName == null || packName.isBlank()) return Optional.empty();
        Path direct = Path.of(packName);
        if (direct.isAbsolute() && Files.exists(direct)) return Optional.of(direct);
        Path p = shaderpacksDir.resolve(packName);
        if (!Files.exists(p)) return Optional.empty();
        return Optional.of(p);
    }

    /** 读包内相对路径文本;目录/zip 通吃;zip 支持嵌套根尾缀匹配;任何问题 = empty。 */
    public static Optional<String> readFile(Path packRoot, String relPath) {
        try {
            if (Files.isRegularFile(packRoot)) { // zip 包
                try (ZipFile zip = new ZipFile(packRoot.toFile())) {
                    ZipEntry e = zip.getEntry(relPath);
                    if (e == null) {
                        Enumeration<? extends ZipEntry> en = zip.entries();
                        while (en.hasMoreElements()) {
                            ZipEntry cand = en.nextElement();
                            if (cand.getName().equals(relPath)
                                    || cand.getName().endsWith("/" + relPath)) {
                                e = cand;
                                break;
                            }
                        }
                    }
                    if (e == null) return Optional.empty();
                    try (InputStream in = zip.getInputStream(e)) {
                        return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            Path f = packRoot.resolve(relPath);
            if (!Files.isRegularFile(f)) return Optional.empty();
            return Optional.of(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** sha256 前 16 位十六进制(模板 hash 键格式)。 */
    public static String sha256Prefix16(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 摘要前<b>靶向规范化</b>(2026-09-26 task-23):**只对 {@code *.properties}** 剔除
     * {@code java.util.Properties.store()} 自动写的日期注释头(形如
     * {@code #Sat Sep 26 01:34:39 CST 2026})。
     *
     * <p><b>为什么需要</b>:实例里 {@code <gameDir>/patched_shaders/{block,entity,item}.properties}
     * 是 **Iris/Oculus 的补丁输出**(每轮重写)⇒ 每轮多一行新日期头 ⇒ 同一包、同一 mod 两轮指纹不同
     * (审核 R3 实测:9 个包侧 .properties 字节数相同、哈希互异、逐文件只差这一行)⇒ 指纹失去
     * "等价证据"的资格。写入方是第三方(不在我们两个仓里),所以修**比对口径**。</p>
     *
     * <p><b>作用域故意只限 {@code .properties}</b>:GLSL 的 {@code #version}/{@code #define}/{@code #ifdef}
     * 也以 {@code #} 开头 —— 一律剥 {@code ^#} 会擦掉真实内容、制造<b>假稳定性</b>(比指纹不可用更坏)。
     * 契约同时钉"只差日期头 ⇒ 同摘要"与"真实 {@code #} 行/键值变化 ⇒ 摘要仍变",并喂一个
     * {@code .fsh} 文本断言它**不被**规范化。</p>
     *
     * <p>另外:不含日期头的文本**原样返回**(逐字节不变)⇒ 模板里已记录的 hash 不受影响。</p>
     */
    public static String normalizeForDigest(String relPath, String text) {
        if (text == null) return null;
        if (relPath == null
                || !relPath.toLowerCase(java.util.Locale.ROOT).endsWith(".properties")) {
            return text;
        }
        // 只删"日期头那一整行"(含行尾换行);其它字节原样保留(不做 split/join —— 免得改到行尾)
        return text.replaceAll("(?m)^#\\w{3} \\w{3} \\d{2} \\d{2}:\\d{2}:\\d{2} \\w+ \\d{4}\\R?", "");
    }

    /** 指纹:存在且可读的文件才有键(缺失键在 matches() 中判不匹配 = 保守零注入)。 */
    public static Map<String, String> fingerprint(Path packRoot, List<String> relPaths) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (packRoot == null || relPaths == null) return m;
        for (String rel : relPaths) {
            readFile(packRoot, rel).ifPresent(s -> m.put(rel, sha256Prefix16(normalizeForDigest(rel, s))));
        }
        return m;
    }

    /** 模板 hash 全键相等才 match;任一缺失/不符/模板无 hash = false(保守)。 */
    public static boolean matches(Map<String, String> fingerprint, Map<String, String> templateHash) {
        if (fingerprint == null || templateHash == null || templateHash.isEmpty()) return false;
        for (Map.Entry<String, String> e : templateHash.entrySet()) {
            String got = fingerprint.get(e.getKey());
            if (got == null || !got.equals(e.getValue())) return false;
        }
        return true;
    }
}

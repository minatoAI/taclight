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

    /** 指纹:存在且可读的文件才有键(缺失键在 matches() 中判不匹配 = 保守零注入)。 */
    public static Map<String, String> fingerprint(Path packRoot, List<String> relPaths) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (packRoot == null || relPaths == null) return m;
        for (String rel : relPaths) {
            readFile(packRoot, rel).ifPresent(s -> m.put(rel, sha256Prefix16(s)));
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

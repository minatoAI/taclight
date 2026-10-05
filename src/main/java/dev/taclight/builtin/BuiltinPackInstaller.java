package dev.taclight.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 内置光影包安装器(2026-10-05).
 *
 * <p>背景:Iris/Oculus 只从 {@code <gameDir>/shaderpacks} + {@code config/oculus.properties}
 * 的 {@code shaderPack=} 发现包,没有 mod 直接注册包的 API。所以"内置"的真实含义 =
 * JAR 自带包内容 +
 * 首次启动自动安装到 {@code shaderpacks/TacLight-Builtin} +
 * 引导用户在光影界面选中它。不默默替换用户已选的第三方包.</p>
 *
 * <p>单一真源:{@code pack/} 目录。构建期 {@code builtinPackZip} 打固定名 zip
 * 进 generated resources,运行期本类解压安装。run 下的
 * {@code taclight-shaders-dev} 是同份内容的 dev 同步副本(保持曝光锁定,供 A/B 取证确定性),
 * 与玩家面的 {@code TacLight-Builtin}(按配置烘熙曝光)不互相覆盖.</p>
 *
 * <p>本类零 Forge 依赖(只用 {@code java.*}),便于离线契约直接断言。gameDir 与资源流由调用方传入
 * ({@code TacLightMod.clientSetup} 传 {@code FMLPaths.GAMEDIR} 与 {@code getResourceAsStream}).</p>
 */
public final class BuiltinPackInstaller {
    /** 安装目录名(=用户在 oculus.properties 里看到的包名). */
    public static final String BUILTIN_PACK_NAME = "TacLight-Builtin";
    /** JAR 内资源路径(构建期 {@code builtinPackZip} 产物). */
    public static final String RESOURCE_ZIP = "/builtin_shaderpack/builtin-pack.zip";
    /** 版本文件(内容 = mod 版本,跟随发布刷新安装). */
    public static final String VERSION_FILE = "builtin_version.txt";
    /** 曝光 define 所在文件(相对包根). */
    public static final String EXPOSURE_FILE = "shaders/composite2.fsh";
    /** 磁盘标记文件(与 {@code ShaderPackDiag} 同口径). */
    public static final String MARKER_ENTRY = "shaders/shaders.properties";
    public static final String MARKER = "TACLIGHT_PATCH_BEGIN";

    /** 玩家向默认曝光: 自适应开, 目标 0.16, 范围 0.5..4.0, 速率 3.0. */
    public static final boolean DEFAULT_ADAPTIVE = true;
    public static final double DEFAULT_TARGET = 0.16;
    public static final double DEFAULT_MIN = 0.5;
    public static final double DEFAULT_MAX = 4.0;
    public static final double DEFAULT_RATE = 3.0;

    /** 安装参数(来自 TacLightConfig,取不到时用 {@link #defaults}). */
    public record Settings(boolean adaptive, double target, double min, double max, double rate,
                           String version) {}
    /** 安装结果:installed=true 表示本次写了磁盘. */
    public record Outcome(boolean installed, String detail) {}
    /** zip 资源打开方式(null 流 = 资源缺失). */
    public interface ZipSupplier {
        InputStream open() throws IOException;
    }

    private BuiltinPackInstaller() {}

    public static Settings defaults(String version) {
        return new Settings(DEFAULT_ADAPTIVE, DEFAULT_TARGET, DEFAULT_MIN, DEFAULT_MAX, DEFAULT_RATE,
                version == null ? "unknown" : version);
    }

    /**
     * 烘熙曝光 define.行级替换, 缺任一目标行即抛(不装半成品).
     * {@code LOCK_VALUE} 行保持不动(只有 LOCK=1 的取证锁定模式才读它).
     */
    public static String bakeExposure(String text, Settings s) {
        if (text == null) throw new IllegalStateException("bakeExposure: 空文本");
        boolean lock = false;
        boolean target = false;
        boolean min = false;
        boolean max = false;
        boolean rate = false;
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            String rebuilt = null;
            if (t.startsWith("#define")) {
                String[] tok = t.split("\\s+");
                if (tok.length >= 3) {
                    switch (tok[1]) {
                        case "TACLIGHT_EXPOSURE_LOCK" -> {
                            rebuilt = "#define TACLIGHT_EXPOSURE_LOCK " + (s.adaptive() ? "0" : "1")
                                    + "     // baked by BuiltinPackInstaller(adaptive=" + s.adaptive() + ")";
                            lock = true;
                        }
                        case "TACLIGHT_EXPOSURE_TARGET" -> {
                            rebuilt = "#define TACLIGHT_EXPOSURE_TARGET " + s.target();
                            target = true;
                        }
                        case "TACLIGHT_EXPOSURE_MIN" -> {
                            rebuilt = "#define TACLIGHT_EXPOSURE_MIN  " + s.min();
                            min = true;
                        }
                        case "TACLIGHT_EXPOSURE_MAX" -> {
                            rebuilt = "#define TACLIGHT_EXPOSURE_MAX  " + s.max();
                            max = true;
                        }
                        case "TACLIGHT_ADAPT_RATE" -> {
                            rebuilt = "#define TACLIGHT_ADAPT_RATE    " + s.rate();
                            rate = true;
                        }
                        default -> {}
                    }
                }
            }
            out.append(rebuilt != null ? rebuilt : line).append('\n');
        }
        // split(-1) 保留末尾空串导致多一个换行: 原文末尾有换行时排掉多余的一个.
        if (text.endsWith("\n") && out.length() > 0) out.setLength(out.length() - 1);
        if (!lock || !target || !min || !max || !rate) {
            throw new IllegalStateException("bakeExposure: 缺 define(lock=" + lock + " target=" + target
                    + " min=" + min + " max=" + max + " rate=" + rate + ")");
        }
        return out.toString();
    }

    /** 解压到目标目录(防 ZipSlip),返回写入文件数. */
    public static int extractZip(InputStream in, Path target) throws IOException {
        int files = 0;
        try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path dest = target.resolve(e.getName()).normalize();
                if (!dest.startsWith(target.normalize())) {
                    throw new IOException("zip entry 越权: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                    Files.copy(zip, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    files++;
                }
                zip.closeEntry();
            }
        }
        if (files == 0) throw new IOException("zip 为空");
        return files;
    }

    /**
     * 确保内置包安装到位.决策:
     * 版本文件一致 = 跳过; 目录存在但无我方标记 = 冲突不碰;
     * 否则清目录重装 +烘熙曝光 +写版本.不碰用户其他包.
     */
    public static Outcome ensureInstalled(Path gameDir, Settings s, ZipSupplier zip) throws IOException {
        if (gameDir == null || s == null || zip == null) throw new IllegalArgumentException("gameDir/s/zip 不能为空");
        Path target = gameDir.resolve("shaderpacks").resolve(BUILTIN_PACK_NAME);
        Path vf = target.resolve(VERSION_FILE);
        if (Files.isRegularFile(vf)) {
            String v = Files.readString(vf, StandardCharsets.UTF_8).trim();
            if (v.equals(s.version())) return new Outcome(false, "SKIPPED_CURRENT version=" + v);
        }
        if (Files.isDirectory(target) && !Files.isRegularFile(vf) && !isOurPack(target)) {
            return new Outcome(false, "SKIPPED_CONFLICT 目录已被非我方包占用: " + target);
        }
        deleteTree(target);
        Files.createDirectories(target);
        int n;
        try (InputStream in = zip.open()) {
            if (in == null) return new Outcome(false, "SKIPPED_NO_RESOURCE " + RESOURCE_ZIP + " 不在 JAR 内");
            n = extractZip(in, target);
        }
        Path exp = target.resolve(EXPOSURE_FILE);
        if (!Files.isRegularFile(exp)) throw new IllegalStateException("内置包缺 " + EXPOSURE_FILE + "(zip 内容不对)");
        Files.writeString(exp, bakeExposure(Files.readString(exp, StandardCharsets.UTF_8), s),
                StandardCharsets.UTF_8);
        Files.writeString(vf, s.version() + "\n", StandardCharsets.UTF_8);
        return new Outcome(true, "INSTALLED files=" + n + " version=" + s.version()
                + " adaptive=" + s.adaptive() + " target=" + s.target());
    }

    private static boolean isOurPack(Path target) {
        try {
            Path marker = target.resolve(MARKER_ENTRY);
            if (!Files.isRegularFile(marker)) return false;
            return Files.readString(marker, StandardCharsets.UTF_8).contains(MARKER);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

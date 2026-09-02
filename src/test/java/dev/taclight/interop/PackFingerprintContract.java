package dev.taclight.interop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 包指纹契约(方案C 里程碑2,计划文档 §3/§6)。
 * 钉死:oculus.properties 解析 / 包根定位(目录+zip+绝对路径)/ zip 内文件读取
 * (含嵌套根)/ sha256 16 位前缀 / 指纹匹配规则(全键相等,缺失=false=零注入)。
 */
public class PackFingerprintContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        propertiesParse();
        resolveDir();
        resolveZip();
        zipReadNested();
        absolutePath();
        sha256Vector();
        fingerprintMatch();
        System.out.println("PackFingerprintContract: ALL PASS (" + checks + " checks)");
    }

    private static void propertiesParse() {
        check(PackFingerprint.packNameFromProperties(
                "shaderPack=iterationT 3.2.0\nenableShaders=true\n")
                .orElse("").equals("iterationT 3.2.0"), "shaderPack= 行解析");
        check(PackFingerprint.packNameFromProperties(
                "shaderPack=iterationT 3.2.0\r\nother=1\r\n").orElse("").equals("iterationT 3.2.0"),
                "CRLF 容忍");
        check(!PackFingerprint.packNameFromProperties("enableShaders=true").isPresent(),
                "无 shaderPack 行 = empty");
        check(!PackFingerprint.packNameFromProperties("shaderPack=\n").isPresent(),
                "空包名 = empty");
    }

    private static void resolveDir() throws Exception {
        Path root = Files.createTempDirectory("taclight-fp-dir");
        Path pack = root.resolve("iterationT 3.2.0/shaders");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("composite.fsh"), "#version 330\n");
        check(PackFingerprint.resolvePackRoot(root, "iterationT 3.2.0")
                .map(p -> p.getFileName().toString()).orElse("").equals("iterationT 3.2.0"),
                "目录包按名 resolve");
        check(!PackFingerprint.resolvePackRoot(root, "no-such-pack").isPresent(),
                "不存在的包名 = empty");
    }

    private static void resolveZip() throws Exception {
        Path root = Files.createTempDirectory("taclight-fp-zip");
        Path zip = root.resolve("packed.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("shaders/composite.fsh"));
            z.write("#version 330\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("shaders/shaders.properties"));
            z.write("clouds=off\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Path packRoot = PackFingerprint.resolvePackRoot(root, "packed.zip").orElse(null);
        check(packRoot != null && Files.exists(packRoot), "zip 包 resolve 为 zip 文件本身");
        if (packRoot == null) return;
        check(PackFingerprint.readFile(packRoot, "shaders/composite.fsh")
                .orElse("").equals("#version 330\n"), "zip 内文本读取");
        check(!PackFingerprint.readFile(packRoot, "shaders/missing.fsh").isPresent(),
                "zip 内缺失条目 = empty");
    }

    private static void zipReadNested() throws Exception {
        Path root = Files.createTempDirectory("taclight-fp-nested");
        Path zip = root.resolve("nested.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("nested/shaders/composite.fsh"));
            z.write("x".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Path packRoot = PackFingerprint.resolvePackRoot(root, "nested.zip").orElse(null);
        check(packRoot != null && PackFingerprint.readFile(packRoot, "shaders/composite.fsh").isPresent(),
                "zip 嵌套根(mypack/shaders/...)按尾缀匹配");
    }

    private static void absolutePath() throws Exception {
        Path elsewhere = Files.createTempDirectory("taclight-fp-abs");
        Files.createDirectories(elsewhere.resolve("shaders"));
        Path packRoot = PackFingerprint.resolvePackRoot(
                Files.createTempDirectory("taclight-fp-abs-root"),
                elsewhere.toAbsolutePath().toString()).orElse(null);
        check(packRoot != null && packRoot.equals(elsewhere.toAbsolutePath()),
                "绝对路径包名(oculus 允许)直接采用");
    }

    private static void sha256Vector() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        check(PackFingerprint.sha256Prefix16("abc").equals("ba7816bf8f01cfea"),
                "sha256 前 16 位标准向量(abc)");
    }

    private static void fingerprintMatch() throws Exception {
        Path root = Files.createTempDirectory("taclight-fp-match");
        Path pack = root.resolve("p/shaders");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("composite.fsh"), "AAA");
        Files.writeString(pack.resolve("shaders.properties"), "x=1");
        Path packRoot = root.resolve("p");
        List<String> rels = List.of("shaders/composite.fsh", "shaders/shaders.properties");
        Map<String, String> fp = PackFingerprint.fingerprint(packRoot, rels);
        check(fp.size() == 2 && fp.get("shaders/composite.fsh")
                .equals(PackFingerprint.sha256Prefix16("AAA")), "指纹 = 相对路径→前缀");
        check(PackFingerprint.matches(fp, Map.of(
                "shaders/composite.fsh", fp.get("shaders/composite.fsh"),
                "shaders/shaders.properties", fp.get("shaders/shaders.properties"))),
                "全键相等 = match");
        check(!PackFingerprint.matches(fp, Map.of(
                "shaders/composite.fsh", fp.get("shaders/composite.fsh"),
                "shaders/shaders.properties", "0000000000000000")), "任一键值不符 = 不 match(零注入)");
        check(!PackFingerprint.matches(fp, Map.of(
                "shaders/composite.fsh", fp.get("shaders/composite.fsh"),
                "shaders/extra.fsh", "aaaaaaaaaaaaaaaa")), "模板要求的键缺失 = 不 match(保守)");
        Files.deleteIfExists(pack.resolve("shaders.properties"));
        check(PackFingerprint.fingerprint(packRoot, rels).size() == 1, "文件缺失 = 该键缺席(不抛)");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

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
        packNameMatching();
        pathMustUseRawName();
        System.out.println("PackFingerprintContract: ALL PASS (" + checks + " checks)");
    }

    /**
     * 包名层(2026-09-19 用户 zip 包零注入修复):归一化 + 版本后缀容错 + 反例。
     * 正例 = 用户实测的真实形态;反例 = 必须挡住"同前缀异包/派生包"。
     */
    private static void packNameMatching() {
        check(PackFingerprint.packMatchKey("ComplementaryReimagined_r5.9.3.zip")
                        .equals("complementaryreimagined r5.9.3"),
                "匹配键:去 .zip + 折叠分隔符 + 小写 ⇒ " + PackFingerprint.packMatchKey("ComplementaryReimagined_r5.9.3.zip"));
        check(PackFingerprint.matchesPackName("ComplementaryReimagined_r5.9.3.zip", "ComplementaryReimagined"),
                "★ 用户实测形态(带 .zip + 版本后缀)命中 complementary 模板");
        check(PackFingerprint.matchesPackName("ComplementaryReimagined", "ComplementaryReimagined"),
                "目录包(无后缀)精确命中(开发机形态,回归)");
        check(PackFingerprint.matchesPackName("iterationT-3.2.0.zip", "iterationT 3.2.0"),
                "连字符 zip 命中 iterationT 模板(分隔符折叠)");
        check(PackFingerprint.matchesPackName("iterationT 3.2.0.zip", "iterationT 3.2.0"),
                "空格 zip 命中 iterationT 模板");
        check(PackFingerprint.matchesPackName("ITERATIONT 3.2.0", "iterationT 3.2.0"),
                "大小写不敏感");
        check(!PackFingerprint.matchesPackName("ComplementaryReimaginedExtra.zip", "ComplementaryReimagined"),
                "★ 反例:同前缀异包(…Extra)不命中(版本后缀规则要求分隔符+可选单字母+数字)");
        check(!PackFingerprint.matchesPackName("iterationT 3.2.0 (taclight)", "iterationT 3.2.0"),
                "★ 反例:路线P 派生包不命中(已内联,不该二次注入)");
        check(!PackFingerprint.matchesPackName("OtherPack_r1.zip", "ComplementaryReimagined"),
                "反例:无关包不命中");
        check(!PackFingerprint.matchesPackName("", "ComplementaryReimagined")
                        && !PackFingerprint.matchesPackName(null, "ComplementaryReimagined")
                        && !PackFingerprint.matchesPackName("ComplementaryReimagined", ""),
                "空名/null/空模板名 = 不命中");
    }

    /**
     * ★ F4 钉死(硬裁定 ①):归一化键只许用于<b>匹配</b>,路径解析必须用<b>原始名</b>。
     * 同一份输入:匹配成功、但用归一化键 resolve 必须失败 —— 后人"顺手"把名字归一化就会红。
     */
    private static void pathMustUseRawName() throws Exception {
        Path root = Files.createTempDirectory("taclight-fp-rawname");
        Files.writeString(root.resolve("MyPack_r1.0.zip"), "x");
        check(PackFingerprint.resolvePackRoot(root, "MyPack_r1.0.zip").isPresent(),
                "★ F4:路径解析用原始名(带 .zip)⇒ 找到包根");
        check(!PackFingerprint.resolvePackRoot(root, PackFingerprint.packMatchKey("MyPack_r1.0.zip")).isPresent(),
                "★ F4:拿归一化键(" + PackFingerprint.packMatchKey("MyPack_r1.0.zip") + ")去 resolve ⇒ 找不到(故绝不可用于路径)");
        check(PackFingerprint.matchesPackName("MyPack_r1.0.zip", "MyPack"),
                "★ F4:同一对的匹配键命中模板(匹配与路径两条路各自正确)");
        // 已知良好清单(硬裁定②):按匹配键比较,不用整文件哈希
        java.util.List<String> known = java.util.List.of("ComplementaryReimagined_r5.9.3.zip");
        check(PackFingerprint.isKnownGood("ComplementaryReimagined_r5.9.3.zip", known),
                "已知良好清单:原样命中");
        check(PackFingerprint.isKnownGood("complementaryreimagined_r5.9.3.ZIP", known),
                "已知良好清单:大小写/后缀不敏感(走匹配键)");
        check(!PackFingerprint.isKnownGood("ComplementaryReimagined_r5.9.2.zip", known)
                        && !PackFingerprint.isKnownGood("ComplementaryReimagined", known)
                        && !PackFingerprint.isKnownGood("ComplementaryReimagined_r5.9.3.zip", java.util.List.of()),
                "已知良好清单:版本不符/目录名/空清单 = 不命中(清单只覆盖被验证的那一版)");
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

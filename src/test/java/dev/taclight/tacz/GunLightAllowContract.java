package dev.taclight.tacz;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 全枪械 gun_light 许可契约(2026-09-02 配件适配里程碑①,纯 JVM 离线)。
 * 机制(TaCZ 字节码核实):AllowAttachmentTagMatcher.match0 按枪械 id 读
 * allow_attachments/<gunId>.json,getAllowAttachmentTags 返回 null/空集 → 一律 false。
 * 因此契约钉死:
 * 1) 默认包每把枪在我们 gunpack 里都有覆盖文件;
 * 2) 有默认文件的枪:我们是"默认列表 ∪ {#taclight:taclight_laser}"(只增不删,不破坏 TaCZ 原许可);
 * 3) 无默认文件的枪(重武器 m320/minigun/rpg7/taurus943):恰好只含我们的 tag(空集=全禁,新增=纯增量);
 * 4) 不允许出现默认包之外的陈旧文件;
 * 5) 我们自己的 laser tag → taclight:gun_light 链路完整。
 */
public class GunLightAllowContract {
    private static final String OUR_TAG = "#taclight:taclight_laser";
    private static final String JAR = "libs/tacz-1.1.8-hotfix.jar";
    private static final String IDX_PREFIX = "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns/";
    private static final String DEF_ALLOW_PREFIX =
            "assets/tacz/custom/tacz_default_gun/data/tacz/tacz_tags/attachments/allow_attachments/";
    private static final Path OUR_DIR = Path.of(
            "src/main/resources/assets/taclight/gunpack/data/tacz/tacz_tags/attachments/allow_attachments");
    private static final Path OUR_LASER_TAG = Path.of(
            "src/main/resources/assets/taclight/gunpack/data/taclight/tacz_tags/attachments/taclight_laser.json");

    static int failed;

    public static void main(String[] args) throws Exception {
        TreeSet<String> gunIds = new TreeSet<>();
        try (ZipFile zip = new ZipFile(Path.of(JAR).toFile())) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                if (n.startsWith(IDX_PREFIX) && n.endsWith(".json")) {
                    gunIds.add(n.substring(IDX_PREFIX.length(), n.length() - ".json".length()));
                }
            }
            check(!gunIds.isEmpty(), "从 jar 枚举到默认包枪械 id(=" + gunIds.size() + ")");
            List<String> missingDefault = new ArrayList<>();
            for (String id : gunIds) {
                JsonArray ours = JsonParser.parseString(Files.readString(OUR_DIR.resolve(id + ".json"))).getAsJsonArray();
                List<String> oursList = new ArrayList<>();
                ours.forEach(x -> oursList.add(x.getAsString()));
                check(oursList.contains(OUR_TAG), id + ": 含 " + OUR_TAG);
                String defEntry = DEF_ALLOW_PREFIX + id + ".json";
                ZipEntry def = zip.getEntry(defEntry);
                if (def == null) {
                    missingDefault.add(id);
                    check(oursList.size() == 1 && OUR_TAG.equals(oursList.get(0)),
                            id + ": 无默认许可文件 → 恰好只含我们的 tag(空集=全禁,只增不删)");
                } else {
                    JsonArray defArr = JsonParser.parseString(
                            new String(zip.getInputStream(def).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonArray();
                    for (var item : defArr) {
                        check(oursList.contains(item.getAsString()),
                                id + ": 保留默认条目 " + item.getAsString());
                    }
                }
            }
            check(missingDefault.size() == 4 && new TreeSet<>(missingDefault).toString()
                            .equals("[m320, minigun, rpg7, taurus943]"),
                    "无默认许可文件的恰为重武器四件套 " + missingDefault);
            // 陈旧文件守护:目录里的文件必须全部对应真实枪械 id
            TreeSet<String> ourFiles = new TreeSet<>();
            try (var s = Files.list(OUR_DIR)) {
                s.filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> ourFiles.add(p.getFileName().toString().replace(".json", "")));
            }
            check(ourFiles.equals(gunIds), "我们的 allow 文件集合 == 默认包枪械集合(无陈旧/缺漏)");
            // laser tag → 附件链路
            JsonArray laserTag = JsonParser.parseString(Files.readString(OUR_LASER_TAG)).getAsJsonArray();
            check(laserTag.size() == 1 && "taclight:gun_light".equals(laserTag.get(0).getAsString()),
                    "taclight:taclight_laser tag → taclight:gun_light");
        }
        if (failed > 0) {
            throw new AssertionError("GunLightAllowContract FAILED: " + failed);
        }
        System.out.println("GunLightAllowContract: PASS (" + gunIds.size() + " guns)");
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  [FAIL] " + what);
        }
    }
}

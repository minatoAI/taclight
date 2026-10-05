package dev.taclight.builtin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 内置包契约(2026-10-05):曝光烘熙 +路由判定 +安装决策,全离线。
 *
 * <p>未覆盖(必须真机 E2E 补):JAR 内真实 {@code builtin-pack.zip}
 * 存在与内容完整(由构建期产物决定,本契约用合成 zip 验逻辑);
 * Iris 真加载内置包后的画面效果;洞穴亮度进目标区间.</p>
 */
public class BuiltinPackContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        bakeLayer();
        routerLayer();
        installLayer();
        System.out.println("BuiltinPackContract: ALL PASS (" + checks + " checks)");
    }

    private static final String EXPOSURE_SRC =
            "#define TACLIGHT_BLOOM_TH      1.0\n"
            + "#define TACLIGHT_EXPOSURE_LOCK 1     // comment\n"
            + "#define TACLIGHT_EXPOSURE_LOCK_VALUE 1.0\n"
            + "#define TACLIGHT_EXPOSURE_TARGET 0.12 // comment\n"
            + "#define TACLIGHT_EXPOSURE_MIN  0.5\n"
            + "#define TACLIGHT_EXPOSURE_MAX  3.0\n"
            + "#define TACLIGHT_ADAPT_RATE    3.5    // comment\n"
            + "void main() {}\n";

    private static void bakeLayer() {
        BuiltinPackInstaller.Settings s = new BuiltinPackInstaller.Settings(true, 0.16, 0.5, 4.0, 3.0, "9.9.9");
        String baked = BuiltinPackInstaller.bakeExposure(EXPOSURE_SRC, s);
        check(baked.contains("#define TACLIGHT_EXPOSURE_LOCK 0"), "自适应 =解锁(LOCK 0)");
        check(baked.contains("#define TACLIGHT_EXPOSURE_TARGET 0.16"), "目标烘熙为配置值");
        check(baked.contains("#define TACLIGHT_EXPOSURE_MIN  0.5"), "下限烘熙");
        check(baked.contains("#define TACLIGHT_EXPOSURE_MAX  4.0"), "上限放到 4.0(洞穴保底)");
        check(baked.contains("#define TACLIGHT_ADAPT_RATE    3.0"), "速率烘熙");
        check(baked.contains("#define TACLIGHT_EXPOSURE_LOCK_VALUE 1.0"), "LOCK_VALUE 行不动");
        check(baked.contains("void main() {}"), "非 define 行不动");

        BuiltinPackInstaller.Settings locked =
                new BuiltinPackInstaller.Settings(false, 0.12, 0.5, 3.0, 3.5, "9.9.9");
        String bakedLock = BuiltinPackInstaller.bakeExposure(EXPOSURE_SRC, locked);
        check(bakedLock.contains("#define TACLIGHT_EXPOSURE_LOCK 1"), "取证锁定分支(LOCK 1)");

        boolean thrown = false;
        try {
            BuiltinPackInstaller.bakeExposure("void main() {}\n", s);
        } catch (IllegalStateException expected) {
            thrown = true;
        }
        check(thrown, "缺 define 即抛(不装半成品)");
    }

    private static void routerLayer() {
        check(BuiltinPackRouter.isBuiltinName("TacLight-Builtin"), "目录名认出内置");
        check(BuiltinPackRouter.isBuiltinName("TacLight-Builtin.zip"), ".zip 名同样认");
        check(!BuiltinPackRouter.isBuiltinName("ComplementaryReimagined_r5.9.3.zip"), "第三方包不误认");
        check(!BuiltinPackRouter.isBuiltinName(null), "null 不误认");

        check(BuiltinPackRouter.decide(false, null, null, false, false)
                == BuiltinPackRouter.Mode.NO_PACK, "未用包 = NO_PACK");
        check(BuiltinPackRouter.decide(true, "TacLight-Builtin", false, false, false)
                == BuiltinPackRouter.Mode.BUILTIN_ACTIVE, "内置包在用(即使注入未命中也不算失败)");
        check(BuiltinPackRouter.decide(true, "TacLight-Builtin.zip", null, false, false)
                == BuiltinPackRouter.Mode.BUILTIN_ACTIVE, "内置 zip 同样激活");
        check(BuiltinPackRouter.decide(true, "ComplementaryReimagined_r5.9.3.zip", false, true, true)
                == BuiltinPackRouter.Mode.THIRD_PARTY_INJECTED, "第三方注入成功");
        check(BuiltinPackRouter.decide(true, "ComplementaryReimagined_r5.9.3.zip", false, false, true)
                == BuiltinPackRouter.Mode.THIRD_PARTY_FAILED, "命中模板但失败");
        check(BuiltinPackRouter.decide(true, "SomeOtherPack.zip", false, false, false)
                == BuiltinPackRouter.Mode.THIRD_PARTY_GENERIC, "无模板 =通用降级");
        check(BuiltinPackRouter.decide(true, "DerivedPack", true, false, false)
                == BuiltinPackRouter.Mode.PACK_WITH_MARKER, "磁盘带标记的派生包");
        check(BuiltinPackRouter.decide(true, null, null, false, false)
                == BuiltinPackRouter.Mode.UNKNOWN, "空包名 = UNKNOWN");

        check(BuiltinPackRouter.builtinDisabled(BuiltinPackRouter.Mode.THIRD_PARTY_INJECTED),
                "第三方包在用时内置定为禁用");
        check(!BuiltinPackRouter.builtinDisabled(BuiltinPackRouter.Mode.BUILTIN_ACTIVE),
                "内置在用时不禁用");
        check(!BuiltinPackRouter.builtinDisabled(BuiltinPackRouter.Mode.NO_PACK),
                "无包时不谈禁用");
        String line = BuiltinPackRouter.statusLine(BuiltinPackRouter.Mode.THIRD_PARTY_INJECTED, "Comp.zip");
        check(line.contains("builtin-router:") && line.contains("Comp.zip"), "状态行可检索");
    }

    private static void installLayer() throws Exception {
        Path gameDir = Files.createTempDirectory("taclight-builtin-test");
        try {
            BuiltinPackInstaller.Settings s =
                    new BuiltinPackInstaller.Settings(true, 0.16, 0.5, 4.0, 3.0, "9.9.9");
            BuiltinPackInstaller.ZipSupplier zip = () -> new ByteArrayInputStream(syntheticZip());
            BuiltinPackInstaller.Outcome first =
                    BuiltinPackInstaller.ensureInstalled(gameDir, s, zip);
            check(first.installed(), "首次安装写磁盘: " + first.detail());
            Path target = gameDir.resolve("shaderpacks").resolve(BuiltinPackInstaller.BUILTIN_PACK_NAME);
            check(Files.isRegularFile(target.resolve("builtin_version.txt")), "版本文件落盘");
            String baked = Files.readString(target.resolve("shaders/composite2.fsh"),
                    StandardCharsets.UTF_8);
            check(baked.contains("#define TACLIGHT_EXPOSURE_LOCK 0"), "安装后曝光已烘熙");

            BuiltinPackInstaller.Outcome second =
                    BuiltinPackInstaller.ensureInstalled(gameDir, s, zip);
            check(!second.installed() && second.detail().startsWith("SKIPPED_CURRENT"),
                    "版本一致跳过: " + second.detail());

            BuiltinPackInstaller.Outcome noRes =
                    BuiltinPackInstaller.ensureInstalled(gameDir,
                            new BuiltinPackInstaller.Settings(true, 0.16, 0.5, 4.0, 3.0, "9.9.10"),
                            () -> null);
            check(!noRes.installed() && noRes.detail().startsWith("SKIPPED_NO_RESOURCE"),
                    "资源缺失不砸(不覆盖已装包): " + noRes.detail());

            Path foreign = gameDir.resolve("shaderpacks").resolve("ForeignPack");
            Files.createDirectories(foreign);
            Files.writeString(foreign.resolve("note.txt"), "user files", StandardCharsets.UTF_8);
            check(!BuiltinPackInstaller.BUILTIN_PACK_NAME.equals("ForeignPack"), "冲突测试前提");
        } finally {
            deleteTree(gameDir);
        }

        // ZipSlip 必红.
        boolean thrown = false;
        try {
            Path evil = Files.createTempDirectory("taclight-builtin-evil");
            try {
                BuiltinPackInstaller.extractZip(new ByteArrayInputStream(evilZip()), evil);
            } finally {
                deleteTree(evil);
            }
        } catch (IOException expected) {
            thrown = true;
        }
        check(thrown, "ZipSlip 条目被拦");
    }

    private static byte[] syntheticZip() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf, StandardCharsets.UTF_8)) {
            addEntry(zip, "shaders/shaders.properties", "# TACLIGHT_PATCH_BEGIN taclight-shaders\n");
            addEntry(zip, "shaders/composite2.fsh", EXPOSURE_SRC);
            addEntry(zip, "pack.png", "fakepng");
        }
        return buf.toByteArray();
    }

    private static byte[] evilZip() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf, StandardCharsets.UTF_8)) {
            addEntry(zip, "../evil.txt", "evil");
        }
        return buf.toByteArray();
    }

    private static void addEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
    }

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) throw new AssertionError("BuiltinPackContract FAIL: " + msg);
        System.out.println("  [ok] " + msg);
    }
}

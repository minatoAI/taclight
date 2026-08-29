package dev.taclight.client;

import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * 活动光影包自检:读 oculus.properties 的 shaderPack 项,
 * 并从包内容(目录或 zip)中查找我们的注入标记 TACLIGHT_PATCH_BEGIN。
 * 目的:让用户一眼看到"90% 的问题 = 选错包(选了原包而不是派生包)"。
 */
public final class ShaderPackDiag {
    public enum Status { NO_PACK, ORIGINAL_PACK, TACLIGHT_PACK, UNKNOWN }

    private static final String MARKER = "TACLIGHT_PATCH_BEGIN";
    /** 自研配套包的标记所在文件(shaders.properties 第 2 行)。
     *  历史注记:路线 P 时代检查派生包注入文件 shaders/Lib/taclight_lights.glsl,
     *  对自研包(taclight-shaders-dev)必然误报"无注入",v0.10.0 起改查本文件。 */
    private static final String MARKER_ENTRY = "shaders/shaders.properties";

    private ShaderPackDiag() {}

    /** 读 config/oculus.properties 的 shaderPack 项(选中包名)。 */
    public static String activePackName() {
        try {
            Path cfg = Minecraft.getInstance().gameDirectory.toPath().resolve("config/oculus.properties");
            if (!Files.exists(cfg)) return null;
            for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                if (line.startsWith("shaderPack=")) {
                    String v = line.substring("shaderPack=".length()).trim();
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 活动包状态:未激活 / 原包(无注入) / 我们的派生包 / 无法判定。 */
    public static Status activeStatus() {
        try {
            if (!net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()) {
                return Status.NO_PACK;
            }
            String name = activePackName();
            if (name == null) return Status.UNKNOWN;
            Path p = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks").resolve(name);
            if (Files.isDirectory(p)) {
                Path markerFile = p.resolve(MARKER_ENTRY);
                return Files.exists(markerFile) && readContains(markerFile) ? Status.TACLIGHT_PACK : Status.ORIGINAL_PACK;
            }
            if (Files.isRegularFile(p) && name.toLowerCase().endsWith(".zip")) {
                try (ZipFile zf = new ZipFile(p.toFile())) {
                    var entry = zf.getEntry(MARKER_ENTRY);
                    if (entry == null) return Status.ORIGINAL_PACK;
                    byte[] data = zf.getInputStream(entry).readAllBytes();
                    return new String(data, StandardCharsets.UTF_8).contains(MARKER)
                            ? Status.TACLIGHT_PACK : Status.ORIGINAL_PACK;
                }
            }
            return Status.UNKNOWN;
        } catch (Throwable t) {
            return Status.UNKNOWN; // 未装 Oculus 或无 IrisApi 实现
        }
    }

    private static boolean readContains(Path glsl) {
        try {
            return Files.readString(glsl, StandardCharsets.UTF_8).contains(MARKER);
        } catch (Throwable t) {
            return false;
        }
    }
}

package dev.taclight.client;

import dev.taclight.interop.PackFingerprint;
import dev.taclight.interop.RuntimePackInjector;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * 活动光影包自检:读 oculus.properties 的 shaderPack 项,并判定"这个包到底有没有 TacLight 照明"。
 *
 * <p><b>判定与文案在 {@link ShaderPackDiagLogic}(纯类,离线契约覆盖)</b>;本类只负责取输入:
 * ① Iris 是否在用包;② 选中包名;③ 磁盘包内是否含物理标记;④ <b>运行时 interop 注入结果</b>
 * ({@link RuntimePackInjector#lastOutcome()})。</p>
 *
 * <p><b>2026-09-25 修复(结构性误报)</b>:旧实现只看 ③。interop 是运行时内存注入,
 * 磁盘包永远不含标记 ⇒ 第三方包(如 Complementary)注入成功时也报 {@code ORIGINAL_PACK},
 * 聊天栏打 ✘ "无 TacLight 注入"并劝用户换包。现在 ④ 命中同包即判 {@code INTEROP_INJECTED}。</p>
 */
public final class ShaderPackDiag {
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

    /**
     * 活动包状态:未激活 / 原包(无注入) / 我们的包(物理标记) /
     * <b>第三方包但运行时已注入</b> / 命中模板但注入失败 / 无法判定。
     */
    public static ShaderPackDiagLogic.Status activeStatus() {
        try {
            boolean inUse = net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
            String name = activePackName();
            Boolean diskMarker = inUse ? diskMarker(name) : null;
            // 注入判定必须用"粘性成功":同包内个别文件失败不得把整包翻回失败(详见 RuntimePackInjector)。
            RuntimePackInjector.Outcome injectedOutcome = RuntimePackInjector.stickyInjectedOutcome();
            RuntimePackInjector.Outcome lastOutcome = RuntimePackInjector.lastOutcome();
            boolean injected = false;
            boolean matched = false;
            // 只在"同一包"时采信运行时结果,避免切包后串用上一个包的成功结果(假绿)。
            if (inUse && name != null) {
                if (injectedOutcome != null && samePack(name, injectedOutcome.rawName())) {
                    injected = true;
                    matched = injectedOutcome.templateMatched();
                }
                if (lastOutcome != null && samePack(name, lastOutcome.rawName())) {
                    matched = matched || lastOutcome.templateMatched();
                }
            }
            return ShaderPackDiagLogic.decide(inUse, name, diskMarker, injected, matched);
        } catch (Throwable t) {
            return ShaderPackDiagLogic.Status.UNKNOWN; // 未装 Oculus 或无 IrisApi 实现
        }
    }

    /** 原始名与运行时记录名是否同一个包:原始名相等,或归一化匹配键相等。 */
    private static boolean samePack(String active, String recorded) {
        if (active == null || recorded == null) return false;
        if (active.equals(recorded)) return true;
        try {
            return PackFingerprint.packMatchKey(active).equals(PackFingerprint.packMatchKey(recorded));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 磁盘包内是否含我们的标记。TRUE/FALSE=有/无;null=无法判定(包不可读)。 */
    private static Boolean diskMarker(String name) {
        if (name == null) return null;
        try {
            Path p = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks").resolve(name);
            if (Files.isDirectory(p)) {
                Path markerFile = p.resolve(MARKER_ENTRY);
                if (!Files.exists(markerFile)) return Boolean.FALSE;
                return readContains(markerFile) ? Boolean.TRUE : Boolean.FALSE;
            }
            if (Files.isRegularFile(p) && name.toLowerCase().endsWith(".zip")) {
                try (ZipFile zf = new ZipFile(p.toFile())) {
                    var entry = zf.getEntry(MARKER_ENTRY);
                    if (entry == null) return Boolean.FALSE;
                    byte[] data = zf.getInputStream(entry).readAllBytes();
                    return new String(data, StandardCharsets.UTF_8).contains(MARKER)
                            ? Boolean.TRUE : Boolean.FALSE;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
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

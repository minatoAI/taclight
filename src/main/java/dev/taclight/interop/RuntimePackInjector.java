package dev.taclight.interop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.taclight.TacLightMod;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 运行时注入编排(mixin 调用入口,方案C 里程碑2,计划文档 §3)。
 *
 * <p>流程:patchComposite 的每个 composite 族程序源文本流经 {@link #patchSource(String)};
 * 首次调用惰性解析(oculus.properties 包名 → 包根 → 指纹 → 模板匹配),按包名缓存;
 * 模板不匹配 / 解析失败 = 原文返回(零注入)。不支持包做一次性提示(聊天栏+日志)。
 * patchComposite 仅在管线(重)构建时被调用,非每帧;oculus.properties 每次调用
 * 重新读取一次(几十次小文件读/reload,可忽略),保证换包/改配置后正确重解析。
 */
public final class RuntimePackInjector {
    private static final List<String> FINGERPRINT_FILES =
            List.of("shaders/composite.fsh", "shaders/shaders.properties");

    /** 缓存:packName → 该包的解析结果(仅非 null 模板)。 */
    private static final Map<String, TemplateLibrary.Template> RESOLVED = new ConcurrentHashMap<>();
    /** 负缓存:已判定不支持的包(CHM 不收 null value,坑82:null put 炸穿 patchComposite)。 */
    private static final Set<String> UNSUPPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private RuntimePackInjector() {}

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
        TemplateLibrary.Template t = resolve();
        if (t == null) return sourceText;
        for (TemplateLibrary.FileRule rule : t.files) {
            List<PatchExecutor.Op> ops = rule.ops.stream()
                    .map(o -> new PatchExecutor.Op(o.op, o.anchor, o.content))
                    .toList();
            String patched = PatchExecutor.apply(sourceText, rule.selector, rule.selectorCount, ops);
            if (patched != null) {
                TacLightMod.LOGGER.info("[TacLight] interop injected family={} pack={} (+{} chars)",
                        t.familyId, t.packName, patched.length() - sourceText.length());
                return patched;
            }
        }
        return sourceText;
    }

    private static TemplateLibrary.Template resolve() {
        String name = currentPackName();
        if (name == null) return null;
        if (UNSUPPORTED.contains(name)) return null;
        TemplateLibrary.Template cached = RESOLVED.get(name);
        if (cached != null) return cached;
        synchronized (RESOLVED) {
            if (UNSUPPORTED.contains(name)) return null;
            cached = RESOLVED.get(name);
            if (cached != null) return cached;
            TemplateLibrary.Template t = matchTemplate(name);
            if (t == null) {
                UNSUPPORTED.add(name); // 负缓存(坑82:CHM 禁 null value)
                announce(name);
            } else {
                RESOLVED.put(name, t);
            }
            return t;
        }
    }

    private static TemplateLibrary.Template matchTemplate(String name) {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path shaderpacks = gameDir.resolve("shaderpacks");
            Path root = PackFingerprint.resolvePackRoot(shaderpacks, name).orElse(null);
            if (root == null) return null;
            Map<String, String> fp = PackFingerprint.fingerprint(root, FINGERPRINT_FILES);
            for (TemplateLibrary.Template t : TemplateLibrary.loadAll()) {
                if (!name.equals(t.packName)) continue;
                if (PackFingerprint.matches(fp, t.packHash)) return t;
                TacLightMod.LOGGER.warn(
                        "[TacLight] interop: 包 {} 命中模板 {} 但关键文件哈希不符(版本漂移?),不注入",
                        name, t.familyId);
                return null;
            }
            return null; // 无模板 = 不支持
        } catch (Throwable th) {
            TacLightMod.LOGGER.warn("[TacLight] interop: 模板解析异常(零注入): {}", th.toString());
            return null;
        }
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

    /** 不支持包的一次性提示(计划 §1):日志恒有;聊天栏尽力而为(无玩家/时机不对则仅日志)。 */
    private static void announce(String packName) {
        if (!ANNOUNCED.add(packName)) return;
        TacLightMod.LOGGER.info(
                "[TacLight] interop: 包 \"{}\" 暂无注入模板,本包不生效 TacLight 照明(零改动)", packName);
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(
                                "[TacLight] 光影包 \"" + packName + "\" 暂不支持锥形照明注入"),
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
}

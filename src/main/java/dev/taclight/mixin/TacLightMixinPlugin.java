package dev.taclight.mixin;

import dev.taclight.tacz.TaczCompat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * mixin 门控插件:TaCZ 存在时才应用本模组的 mixin(软依赖)。
 * 本类与 TaczCompat 均不引用任何 TaCZ 类型,bootstrap 阶段加载安全。
 * 例外:M5 多人灯同步(Player 前缀公共 mixin)是核心功能,不依赖 TaCZ,一律旁路;
 * interop 注入(oculus. 前缀)按 Oculus 在场门控,也不依赖 TaCZ。
 */
public class TacLightMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("TacLightMixinGate");
    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 旁路 = 核心功能(不依赖 TaCZ):Player 前缀公共 mixin + 位置链快照目标 accessor
        if (mixinClassName.startsWith("dev.taclight.mixin.Player")) return true;
        if (mixinClassName.startsWith("dev.taclight.mixin.LivingEntityLerpAccess")) return true;
        // task-27 killer trap:诊断开关,运行时 -Dtaclight.usingTrap 无 TaCZ 也必须能开
        // (默认关闭零开销;陷阱本身只读日志,不改逻辑)。
        if (mixinClassName.startsWith("dev.taclight.mixin.debug.")) return true;
        // interop 注入(方案C 里程碑2):一律放行,自守卫交给 @Mixin(targets) 字符串解析
        // ——目标类不加载(Oculus 不在场)mixin 就永不应用,无需在此探测。
        // 铁律(2026-09-03 实机取证,坑79):shouldApplyMixin 在配置准备期被调用,此处
        // Class.forName 会把目标类抢先加载,早于本配置完成目标注册 → 类被零 mixin
        // 变换并缓存,静默失效(gate true、零报错、注入器不存在)。任何分支禁止
        // 触碰 mixin 目标类的加载。
        if (mixinClassName.startsWith("dev.taclight.mixin.oculus.")) {
            LOGGER.info("[TacLight] mixin gate {} -> true (self-guarded)", mixinClassName);
            return true;
        }
        return TaczCompat.present();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return List.of(); }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

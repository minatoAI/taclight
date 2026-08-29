package dev.taclight.mixin;

import dev.taclight.tacz.TaczCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * mixin 门控插件:TaCZ 存在时才应用本模组的 mixin(软依赖)。
 * 本类与 TaczCompat 均不引用任何 TaCZ 类型,bootstrap 阶段加载安全。
 * 例外:M5 多人灯同步(Player 前缀公共 mixin)是核心功能,不依赖 TaCZ,一律旁路。
 */
public class TacLightMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("dev.taclight.mixin.Player")) return true;
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

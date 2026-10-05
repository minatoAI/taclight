package dev.taclight.mixin.debug;

import dev.taclight.channel.LightBuffer;
import dev.taclight.channel.SpotlightData;
import dev.taclight.debug.SynthLights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * T11 测试光源夹具注入点(调试混入,产品类零改)。
 *
 * <p>目标:{@code LightBuffer.upload(List, int, VoxelField.Snapshot)} 的 lights 形参
 * (三个 upload 重载最终都汇入这一个)。在 {@code HEAD} 替换该形参,由
 * {@link SynthLights#append(List)} 决定是否追加合成灯。</p>
 *
 * <p><b>默认关 = 逐字节一致</b>:{@code !synth} 未开启时 {@code SynthLights.append} 原样返回入参,
 * 注入器不产生任何可观测差异(不新建 List、不读 world、不改状态)。</p>
 *
 * <p>包名纪律:本类必须留在 {@code dev.taclight.mixin.debug} —— 该前缀在
 * {@code TacLightMixinPlugin.shouldApplyMixin:34} 被无条件放行,且**不与任何游戏包重名**
 * (另一会话曾因 mixin 包名与游戏包重名导致启动崩)。</p>
 */
@Mixin(LightBuffer.class)
public class SynthLightMixin {

    @ModifyVariable(
            method = "upload(Ljava/util/List;ILdev/taclight/channel/VoxelField$Snapshot;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 0)
    private static List<SpotlightData> taclight$synthLights(List<SpotlightData> lights) {
        return SynthLights.append(lights);
    }
}

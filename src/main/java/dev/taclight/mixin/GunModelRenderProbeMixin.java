package dev.taclight.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockGunModel;
import dev.taclight.tacz.TaczClientLightProbe;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * 诊断探针(2026-09-02 TP 无束定位):统计 BedrockGunModel.render 收到的
 * ItemDisplayContext 分布。若远程玩家枪渲染从未以 THIRD_PERSON_RIGHT_HAND 进来,
 * renderLaserBeam 的 TP 门禁就永不放行 —— 此探针给出决定性证据。
 * 每个 context 首次 + 第 100/1000 次各记一条,常态零日志。
 */
@Mixin(value = BedrockGunModel.class, remap = false)
public abstract class GunModelRenderProbeMixin {
    @Shadow(remap = false)
    private List<?> laserBeamPaths;

    private static final Map<String, Integer> SEEN = new java.util.HashMap<>();

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At("HEAD"), require = 1)
    private void taclight$probeCtx(PoseStack poseStack, ItemStack stack, ItemDisplayContext context,
                                   RenderType renderType, int light, int overlay, CallbackInfo ci) {
        String key = String.valueOf(context);
        int n = SEEN.merge(key, 1, Integer::sum);
        if (n == 1 || n == 100 || n == 1000) {
            dev.taclight.TacLightMod.LOGGER.info(
                    "[TacLight] GUN-RENDER ctx={} calls={} ourBeamHolder={} beamPaths={}",
                    context, n,
                    TaczClientLightProbe.isOurBeamHolder(stack),
                    laserBeamPaths == null ? "null" : laserBeamPaths.size());
        }
    }
}

package dev.taclight.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.taclight.client.RenderedEntityTracker;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 里程碑②(2026-09-02):标记"当前正在渲染的实体",供第三人称枪口捕获归属。
 * TaCZ 的 BETWR 渲染链不传实体,唯一可靠的归属点 = LivingEntityRenderer.render
 * 的进出(手部物品渲染发生在其中的 ItemInHandLayer 内)。
 * 注意(2026-09-02 实机定位):必须注入 **基础方法** render(LivingEntity;...) ——
 * 之前注入 render(Entity;...) 桥接方法,但实体渲染的实际路径是
 * "子类桥 → 子类 render(具体类型) → super.render → 基础方法",基础方法的
 * 桥被整体绕过,push/pop 从不执行(实机 BEAM-HEAD entity=none 实锤)。
 *
 * 构建注意(2026-09-04):descriptor 保持 mojmap 描述符写法
 * (Lnet/minecraft/world/entity/LivingEntity;...),AP 在目标类映射段内能找到
 * (m_7392_ LivingEntity 重载,段 51928 行实证)——之前报错是因为
 * annotationProcessor 缺 reobf 映射输入;生产服重映射靠 refmap。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRenderEntityMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void taclight$pushEntity(LivingEntity entity, float entityYaw, float partialTicks,
                                     PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                     CallbackInfo ci) {
        RenderedEntityTracker.push(entity);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void taclight$popEntity(LivingEntity entity, float entityYaw, float partialTicks,
                                    PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                    CallbackInfo ci) {
        RenderedEntityTracker.pop();
    }
}

package dev.taclight.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.functional.BeamRenderer;
import dev.taclight.client.MuzzlePoseCapture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * V4:捕获 TaCZ 激光渲染时的枪模矩阵,得到"枪口"精确姿态(视图空间)。
 * 两个渲染路径(NORMAL/ARAccelerated)分别注入;仅当激光骨节点为 laser_beam* 且
 * 附件是我们的 taclight:gun_light 时捕获。remap=false:TaCZ 类名/方法名即官方名。
 */
@Mixin(value = BeamRenderer.class, remap = false)
public abstract class BeamRendererMixin {
    @Inject(method = "renderLaserBeam",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/util/LaserColorUtil;getLaserColor(Lnet/minecraft/world/item/ItemStack;Lcom/tacz/guns/client/resource/pojo/display/LaserConfig;)I"),
            require = 1)
    private static void taclight$capture(ItemStack attachment, PoseStack poseStack,
                                         ItemDisplayContext context, List<BedrockPart> path,
                                         CallbackInfo callback) {
        capture(attachment, poseStack, context, path);
    }

    @Inject(method = "renderLaserBeamAccelerated",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/compat/ar/ARCompat;renderLaser(Lcom/mojang/blaze3d/vertex/VertexConsumer;FFZLcom/mojang/blaze3d/vertex/PoseStack;I)V"),
            require = 1)
    private static void taclight$captureAccelerated(ItemStack attachment, PoseStack poseStack,
                                                    ItemDisplayContext context, List<BedrockPart> path,
                                                    CallbackInfoReturnable<Boolean> callback) {
        capture(attachment, poseStack, context, path);
    }

    private static void capture(ItemStack attachment, PoseStack poseStack,
                                ItemDisplayContext context, List<BedrockPart> path) {
        if (attachment == null || poseStack == null || context == null || !context.firstPerson()) {
            return;
        }
        String attachmentId = null;
        IAttachment ia = IAttachment.getIAttachmentOrNull(attachment);
        if (ia != null) {
            var id = ia.getAttachmentId(attachment);
            attachmentId = id == null ? null : id.toString();
        }
        boolean ours = "taclight:gun_light".equals(attachmentId);
        String node = (path == null || path.isEmpty()) ? null : path.get(path.size() - 1).name;
        MuzzlePoseCapture.capture(ours, node, poseStack.last().pose(), context.firstPerson());
    }
}

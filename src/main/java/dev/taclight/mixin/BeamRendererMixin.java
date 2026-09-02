package dev.taclight.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
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
 * V4:捕获 TaCZ 激光渲染时的枪模矩阵,得到"枪口"精确姿态。
 * 两条捕获链(2026-09-02 里程碑②):
 * - 第一人称(NORMAL/ARAccelerated):本地玩家枪口,矩阵=手部渲染空间(坑60);
 * - 第三人称:TaCZ 门禁字节码 = !firstPerson && context!=THIRD_PERSON_RIGHT_HAND →
 *   return,即刻意在第三人称渲染激光束;实体归属由 LivingEntityRenderEntityMixin
 *   的"当前渲染实体"标记提供,BEDWR 链不传实体。
 * 参数语义(2026-09-02 字节码实锤):renderLaserBeam 首参在不同调用点分别是枪械 stack
 * (BedrockGunModel)与附件 stack(BedrockAttachmentModel)——our 判定须双语义兼容,
 * 此前只按附件解析导致枪械链 our 恒 false、捕获全死。
 * 仅当节点为激光骨且持灯者是 taclight:gun_light 时捕获。
 * remap=false:TaCZ 类名/方法名即官方名。
 */
@Mixin(value = BeamRenderer.class, remap = false)
public abstract class BeamRendererMixin {

    @Inject(method = "renderLaserBeam", at = @At("HEAD"), require = 1)
    private static void taclight$anchor(ItemStack holder, PoseStack poseStack,
                                        ItemDisplayContext context, List<BedrockPart> path,
                                        CallbackInfo callback) {
        probeHead(holder, context, path, "head");
    }

    @Inject(method = "renderLaserBeam",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/util/LaserColorUtil;getLaserColor(Lnet/minecraft/world/item/ItemStack;Lcom/tacz/guns/client/resource/pojo/display/LaserConfig;)I"),
            require = 1)
    private static void taclight$capture(ItemStack holder, PoseStack poseStack,
                                         ItemDisplayContext context, List<BedrockPart> path,
                                         CallbackInfo callback) {
        capture(holder, poseStack, context, path);
    }

    @Inject(method = "renderLaserBeamAccelerated",
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/compat/ar/ARCompat;renderLaser(Lcom/mojang/blaze3d/vertex/VertexConsumer;FFZLcom/mojang/blaze3d/vertex/PoseStack;I)V"),
            require = 1)
    private static void taclight$captureAccelerated(ItemStack holder, PoseStack poseStack,
                                                    ItemDisplayContext context, List<BedrockPart> path,
                                                    CallbackInfoReturnable<Boolean> callback) {
        capture(holder, poseStack, context, path);
    }

    // ---- 诊断探针(2026-09-02 TP 无束/无捕获定位):无条件记录前几次调用 ----
    private static int probeLeft = 5;

    private static void probeHead(ItemStack holder, ItemDisplayContext context,
                                  List<BedrockPart> path, String via) {
        if (probeLeft <= 0) {
            return;
        }
        probeLeft--;
        var holderEntity = dev.taclight.client.RenderedEntityTracker.current();
        dev.taclight.TacLightMod.LOGGER.info(
                "[TacLight] BEAM-HEAD via={} ctx={} gun={} att={} pathSize={} entity={}",
                via, context,
                dev.taclight.tacz.TaczClientLightProbe.isGunStack(holder),
                dev.taclight.tacz.TaczClientLightProbe.isAttachmentStack(holder),
                path == null ? -1 : path.size(),
                holderEntity == null ? "none" : holderEntity.getName().getString() + "#" + holderEntity.getId());
    }

    private static void capture(ItemStack holder, PoseStack poseStack,
                                ItemDisplayContext context, List<BedrockPart> path) {
        boolean fp = context.firstPerson();
        boolean tp = context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        if (holder == null || poseStack == null || context == null || (!fp && !tp)) {
            return;
        }
        boolean ours = dev.taclight.tacz.TaczClientLightProbe.isOurBeamHolder(holder);
        String node = (path == null || path.isEmpty()) ? null : path.get(path.size() - 1).name;
        int entityId = 0;
        if (!fp) {
            var holderEntity = dev.taclight.client.RenderedEntityTracker.current();
            if (holderEntity == null) {
                return; // 不在实体渲染栈内(展示框/掉落物等),无法归属,放弃
            }
            entityId = holderEntity.getId();
        }
        if (!fp) {
            // TP 直接捕获(2026-09-02 标定):深处矩阵平移 = 束起点(枪口,视空间);
            // 束向 = 该矩阵 +Z 列归一(束沿骨局部 +Z 拉伸,字节码 stringVertex z=0..length)。
            // 早期成对差值法退役:激光模块侧轨安装,根→束起点向量不沿枪管(实测与
            // look 夹角 73°,+Z 列 28.8°)。
            var tip = poseStack.last().pose();
            float[] dir = dev.taclight.pose.MuzzlePoseMath.normalizeBeamDelta(
                    tip.m02(), tip.m12(), tip.m22());
            if (dir == null) {
                return;
            }
            MuzzlePoseCapture.captureTp(ours, node,
                    tip.m30(), tip.m31(), tip.m32(),
                    dir[0], dir[1], dir[2], entityId);
            return;
        }
        MuzzlePoseCapture.capture(ours, node, poseStack.last().pose(), fp, entityId);
    }
}

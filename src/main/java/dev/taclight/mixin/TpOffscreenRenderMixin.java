package dev.taclight.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v3 屏外不剔除渲染(2026-09-03):对"距离内+开枪灯的远程玩家"强制
 * EntityRenderDispatcher.shouldRender=true,让真实渲染链(TaCZ 枪模动画 +
 * BeamRendererMixin 捕获钩子)在屏外照常执行 —— 屏外捕获不断,入场即无交接差。
 *
 * <p>严格门禁(性能与行为安全):本人/枪灯关/超距/总开关 off 一律放行原值,零行为变化。
 * 渲染结果本身由 GPU 视锥裁剪消化,屏外顶点不产生可见像素;实体的 nameTag 等
 * 附带渲染同样走正常路径,屏外被裁剪。
 *
 * 构建注意(2026-09-04):源码保持 mojmap 名写法(shouldRender),AP 在目标类
 * 映射段内能找到(m_114397_,EntityRenderDispatcher 段 50781 行实证)——
 * 之前报错是因为 annotationProcessor 缺 reobf 映射输入;生产服重映射靠 refmap。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class TpOffscreenRenderMixin {

    @Inject(method = "shouldRender",
            at = @At("HEAD"),
            cancellable = true)
    private void taclight$keepGunLightPlayer(Entity entity, Frustum frustum,
                                            double x, double y, double z,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof Player player)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (player.getId() == mc.player.getId()) {
            return;
        }
        boolean gun;
        try {
            gun = dev.taclight.sync.PlayerLightAccess.gunLight(player);
        } catch (Throwable t) {
            return;
        }
        if (!gun) {
            return;
        }
        double maxDist;
        try {
            maxDist = dev.taclight.config.TacLightConfig.REMOTE_LIGHT_MAX_DIST.get();
        } catch (Throwable t) {
            return;
        }
        var cam = mc.gameRenderer.getMainCamera();
        double dx = player.getX() - cam.getPosition().x;
        double dy = player.getY() - cam.getPosition().y;
        double dz = player.getZ() - cam.getPosition().z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dev.taclight.client.TpOffscreenRenderGate.keepFor(false, true, dist, maxDist)) {
            cir.setReturnValue(true);
        }
    }
}

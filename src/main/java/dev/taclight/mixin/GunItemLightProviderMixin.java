package dev.taclight.mixin;

import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.item.ModernKineticGunItem;
import net.irisshaders.iris.api.v0.item.IrisItemLightProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;

/**
 * B 计划:给 TaCZ 统一枪物品(X)动态添加 IrisItemLightProvider 接口。
 * 手持枪械时由 Iris 回调(每帧):枪上装有 taclight:gun_light → 15 光强。
 * 效果:满载 light-value 的枪触发 iterationT 内置 FLASHLIGHT(锥形+遮挡)。
 * 接口注入是 mixin 的合法用途(不触碰 TaCZ 私有内部),全部走官方 API。
 */
@Mixin(value = ModernKineticGunItem.class, remap = false)
public abstract class GunItemLightProviderMixin implements IrisItemLightProvider {
    @Override
    public int getLightEmission(Player player, ItemStack stack) {
        // 2026-09-04 用户体感:15→5 暖底基本消失→10 折中(与手电一致);锥形主光走 SSBO 不受影响。
        return hasOurLight(stack) ? 10 : 0;
    }

    @Override
    public Vector3f getLightColor(Player player, ItemStack stack) {
        return new Vector3f(1.0f, 0.96f, 0.88f); // warm tactical white
    }

    private static boolean hasOurLight(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return false;
        // 决议顺序同 TaczClientLightProbe(2026-09-02 类型门修复):gated 安装件 →
        // 原始 NBT(读门关闭的枪唯一可见,ak47 实测)→ 内置件。
        String id = dev.taclight.tacz.GunLaserReader.resolveLaserId(
                attachmentId(gun.getAttachment(stack, AttachmentType.LASER)),
                attachmentId(dev.taclight.tacz.TaczClientLightProbe.rawInstalledAttachment(stack, AttachmentType.LASER)),
                attachmentId(gun.getBuiltinAttachment(stack, AttachmentType.LASER)));
        return dev.taclight.tacz.GunLaserReader.OUR_ATTACHMENT_ID.equals(id);
    }

    private static String attachmentId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        IAttachment ia = IAttachment.getIAttachmentOrNull(stack);
        if (ia == null) return null;
        var id = ia.getAttachmentId(stack);
        return id == null ? null : id.toString();
    }
}

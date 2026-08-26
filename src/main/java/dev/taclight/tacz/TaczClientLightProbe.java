package dev.taclight.tacz;

import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端探针:读取玩家主手枪械的 LASER 槽"生效附件"。
 * 顺序(经旧项目 G1 证据确认):显式附件优先,内置附件兜底。
 * 仅当 TaCZ 存在时由调用方触发加载。
 */
public final class TaczClientLightProbe {
    public record Result(String gunId, String laserAttachmentId) {}

    public static Result probe(Player player) {
        ItemStack held = player.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(held);
        if (gun == null) return null;

        ResourceLocation gunId = gun.getGunId(held);
        if (gunId == null) return null;

        ItemStack laser = gun.getAttachment(held, AttachmentType.LASER);
        if (laser.isEmpty()) {
            laser = gun.getBuiltinAttachment(held, AttachmentType.LASER);
        }

        String laserId = null;
        if (!laser.isEmpty()) {
            IAttachment ia = IAttachment.getIAttachmentOrNull(laser);
            if (ia != null) {
                ResourceLocation lid = ia.getAttachmentId(laser);
                laserId = lid == null ? null : lid.toString();
            }
        }
        return new Result(gunId.toString(), laserId);
    }

    private TaczClientLightProbe() {}
}

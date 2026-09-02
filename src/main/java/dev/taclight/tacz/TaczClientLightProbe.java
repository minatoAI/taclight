package dev.taclight.tacz;

import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端探针:读取玩家主手枪械的 LASER 槽"生效附件"。
 * 决议顺序(2026-09-02 类型门修复,GunLaserReader.resolveLaserId):显式附件 →
 * 原始 NBT(TaCZ 读门对 allow_attachment_types 缺 laser 的枪一律拒显,ak47 实测)→
 * 内置附件兜底。仅当 TaCZ 存在时由调用方触发加载。
 */
public final class TaczClientLightProbe {
    public record Result(String gunId, String laserAttachmentId) {}

    public static Result probe(Player player) {
        ItemStack held = player.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(held);
        if (gun == null) return null;

        ResourceLocation gunId = gun.getGunId(held);
        if (gunId == null) return null;

        String laserId = GunLaserReader.resolveLaserId(
                attachmentId(gun.getAttachment(held, AttachmentType.LASER)),
                attachmentId(rawInstalledAttachment(held, AttachmentType.LASER)),
                attachmentId(gun.getBuiltinAttachment(held, AttachmentType.LASER)));
        return new Result(gunId.toString(), laserId);
    }

    /**
     * 原始 NBT 读(绕过 allowAttachmentType 读门):TaCZ 把安装件存在枪根 tag 的
     * "Attachment<类型名>" 子 compound(= GunItemDataAccessor.GUN_ATTACHMENT_BASE 公开
     * 常量 + 类型名,与 installAttachment/getAttachment 同一拼接配方)。返回重构的附件
     * ItemStack,槽空/无 tag 为 EMPTY。
     */
    public static ItemStack rawInstalledAttachment(ItemStack gunStack, AttachmentType type) {
        var root = gunStack.getTag();
        if (root == null) return ItemStack.EMPTY;
        String key = GunLaserReader.attachmentNbtKey(type.name());
        if (!root.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) return ItemStack.EMPTY;
        return ItemStack.of(root.getCompound(key));
    }

    private static String attachmentId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        IAttachment ia = IAttachment.getIAttachmentOrNull(stack);
        if (ia == null) return null;
        ResourceLocation id = ia.getAttachmentId(stack);
        return id == null ? null : id.toString();
    }

    /**
     * renderLaserBeam 首参双语义判定(2026-09-02 字节码实锤):BedrockGunModel 传枪械
     * stack、BedrockAttachmentModel 传附件 stack。附件 stack 直读 id;枪械 stack 走
     * resolveLaserId 链(gated → 原始 NBT → 内置)。均不是 → false。
     */
    public static boolean isOurBeamHolder(ItemStack holder) {
        return GunLaserReader.OUR_ATTACHMENT_ID.equals(beamHolderLaserId(holder));
    }

    /** 诊断:stack 是否为枪械(IGun 可解析)。 */
    public static boolean isGunStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && IGun.getIGunOrNull(stack) != null;
    }

    /** 诊断:stack 是否为附件(IAttachment 可解析)。 */
    public static boolean isAttachmentStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && IAttachment.getIAttachmentOrNull(stack) != null;
    }

    private static String beamHolderLaserId(ItemStack holder) {
        if (holder == null || holder.isEmpty()) return null;
        IAttachment ia = IAttachment.getIAttachmentOrNull(holder);
        if (ia != null) {
            return attachmentId(holder);
        }
        IGun gun = IGun.getIGunOrNull(holder);
        if (gun == null) return null;
        return GunLaserReader.resolveLaserId(
                attachmentId(gun.getAttachment(holder, AttachmentType.LASER)),
                attachmentId(rawInstalledAttachment(holder, AttachmentType.LASER)),
                attachmentId(gun.getBuiltinAttachment(holder, AttachmentType.LASER)));
    }

    private TaczClientLightProbe() {}
}

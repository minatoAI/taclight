package dev.taclight.tacz;

/**
 * 枪灯状态判定核心(纯逻辑,无 Minecraft 依赖,契约可测)。
 * 灯 = 枪上安装了 taclight:gun_light 附件。
 */
public final class GunLaserReader {
    public static final String OUR_ATTACHMENT_ID = "taclight:gun_light";

    public enum Status { NONE, OTHER_LASER, OUR_LIGHT }

    /**
     * @param gunId              当前主手枪械 id(tacz:hk416d),无枪为 null
     * @param laserAttachmentId  该枪 LASER 槽生效附件 id,无附件为 null
     */
    public static Status classify(String gunId, String laserAttachmentId) {
        if (gunId == null || gunId.isEmpty()) return Status.NONE;
        if (laserAttachmentId == null || laserAttachmentId.isEmpty()) return Status.NONE;
        return OUR_ATTACHMENT_ID.equals(laserAttachmentId) ? Status.OUR_LIGHT : Status.OTHER_LASER;
    }

    /**
     * LASER 槽生效附件决议(2026-09-02 类型门修复)。
     * TaCZ 读门:枪械 data JSON 的 allow_attachment_types 不含 laser 时(实机 ak47 探针:
     * allowed=true 但 slot=EMPTY),getAttachment/getBuiltinAttachment 一律 EMPTY——但经
     * 白名单 installAttachment 写入的枪根 NBT 仍在。决议顺序:gated 安装件 → 原始 NBT
     * (类型门关闭时的唯一可见安装件)→ 内置件。空串视同无。
     */
    public static String resolveLaserId(String gatedId, String rawNbtId, String builtinId) {
        if (gatedId != null && !gatedId.isEmpty()) return gatedId;
        if (rawNbtId != null && !rawNbtId.isEmpty()) return rawNbtId;
        return builtinId;
    }

    /**
     * TaCZ 枪根 NBT 附件槽 key:配方 "Attachment\u0001" + 类型名。前缀 = 公开常量
     * GunItemDataAccessor.GUN_ATTACHMENT_BASE(installAttachment/getAttachment 同一 indy 配方,
     * 2026-09-02 字节码实锤)。
     */
    public static String attachmentNbtKey(String attachmentTypeName) {
        return "Attachment" + attachmentTypeName;
    }

    private GunLaserReader() {}
}

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

    private GunLaserReader() {}
}

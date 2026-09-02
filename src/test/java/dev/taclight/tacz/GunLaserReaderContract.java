package dev.taclight.tacz;

/** GunLaserReader 契约测试(纯 JVM,零 MC 依赖;由 taclightContracts 任务运行)。 */
public class GunLaserReaderContract {
    public static void main(String[] args) {
        check(GunLaserReader.Status.NONE,
                GunLaserReader.classify(null, "taclight:gun_light"), "无枪(枪id null)→ NONE");
        check(GunLaserReader.Status.NONE,
                GunLaserReader.classify("tacz:hk416d", null), "有枪无激光 → NONE");
        check(GunLaserReader.Status.NONE,
                GunLaserReader.classify("tacz:hk416d", ""), "空激光id → NONE");
        check(GunLaserReader.Status.OTHER_LASER,
                GunLaserReader.classify("tacz:hk416d", "tacz:laser_lopro"), "他人激光 → OTHER_LASER(不点亮)");
        check(GunLaserReader.Status.OUR_LIGHT,
                GunLaserReader.classify("tacz:hk416d", "taclight:gun_light"), "我们的附件 → OUR_LIGHT");
        check(GunLaserReader.Status.OUR_LIGHT,
                GunLaserReader.classify("tacz:m4a1", "taclight:gun_light"), "其他枪+我们的灯 → OUR_LIGHT");

        // ---- LASER 槽生效附件决议(2026-09-02 类型门修复;实机 KIT-INSTALL 探针定位) ----
        // TaCZ 读门:枪械 data JSON 的 allow_attachment_types 不含 laser 时(ak47 实测),
        // getAttachment/getBuiltinAttachment 一律 EMPTY,但白名单 installAttachment 的
        // 写入(枪根 NBT AttachmentLASER)仍在 → 原始 NBT 兜底读先于内置件。
        checkS(GunLaserReader.resolveLaserId("tacz:laser_a", "taclight:gun_light", null),
                "tacz:laser_a", "gated 安装件优先(类型门开启,如 hk416d)");
        checkS(GunLaserReader.resolveLaserId(null, "taclight:gun_light", "tacz:laser_b"),
                "taclight:gun_light", "类型门关闭(gated null)→ 原始 NBT 兜底,先于内置件");
        checkS(GunLaserReader.resolveLaserId("", "taclight:gun_light", "tacz:laser_b"),
                "taclight:gun_light", "gated 空串视同无 → 原始 NBT 兜底");
        checkS(GunLaserReader.resolveLaserId(null, null, "tacz:laser_b"),
                "tacz:laser_b", "无安装件 → 内置件兜底(旧语义保持)");
        checkS(GunLaserReader.resolveLaserId(null, null, null), null, "全空 → null(不点亮)");
        checkS(GunLaserReader.resolveLaserId("taclight:gun_light", "tacz:laser_x", "tacz:laser_b"),
                "taclight:gun_light", "安装件即灯时直接生效");
        checkS(GunLaserReader.attachmentNbtKey("LASER"), "AttachmentLASER",
                "枪根 NBT key 配方 = GUN_ATTACHMENT_BASE(\"Attachment\") + 类型名(字节码实锤)");
        checkS(GunLaserReader.attachmentNbtKey("SCOPE"), "AttachmentSCOPE", "配方按类型名泛化");

        System.out.println("GunLaserReaderContract: ALL PASS (14 checks)");
    }

    private static void check(GunLaserReader.Status expect, GunLaserReader.Status actual, String what) {
        if (expect != actual) {
            throw new AssertionError("FAIL " + what + ": expected " + expect + " but got " + actual);
        }
        System.out.println("  PASS " + what);
    }

    private static void checkS(String expect, String actual, String what) {
        if (expect == null ? actual != null : !expect.equals(actual)) {
            throw new AssertionError("FAIL " + what + ": expected " + expect + " but got " + actual);
        }
        System.out.println("  PASS " + what);
    }
}

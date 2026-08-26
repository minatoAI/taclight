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
        System.out.println("GunLaserReaderContract: ALL PASS (6 checks)");
    }

    private static void check(GunLaserReader.Status expect, GunLaserReader.Status actual, String what) {
        if (expect != actual) {
            throw new AssertionError("FAIL " + what + ": expected " + expect + " but got " + actual);
        }
        System.out.println("  PASS " + what);
    }
}

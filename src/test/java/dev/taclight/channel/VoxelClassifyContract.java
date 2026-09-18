package dev.taclight.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * 形状分档契约(2026-09-18 雪地方格阵列根因轮)。纯 JVM,零 MC 依赖。
 *
 * <p>钉死三件事:</p>
 * <ol>
 *   <li><b>根因①</b>——C 口径分档(Lead 裁定):守卫 1 {@code coll} 空 ⇒ EMPTY;
 *       守卫 2 {@code coll maxY > 1.0} ⇒ SOLID;其余按 {@code occ} 实心占比
 *       (≤0.25 EMPTY / 0.25~0.9 VEG / ≥0.9 SOLID)。逐例覆盖任务表;</li>
 *   <li><b>根因②③</b>——位置相关档必须"按坐标重新探测、不被 state 级缓存冻结":
 *       同一 key(等价同一 BlockState)在两个坐标下形状不同时必须返回不同码,
 *       且坐标 x/y/z 如实透传;</li>
 *   <li><b>共面回归</b>——{@code OAK_STAIRS[half=top]} 的两盒共面(面都落在 z=0.5),
 *       旧的"中心列严格包含"判据在此判 EMPTY(=漏光,危险方向);占比口径下必须是 VEG。</li>
 * </ol>
 *
 * <p><b>夹具出处</b>:AABB 数值<b>不是手写猜的</b>,取自 {@code VoxelRealRegistryContract}
 * 在真 1.20.1 registry 上打印的 {@code getCollisionShape/getOcclusionShape} 实测值
 * (见该契约输出的 MEASURED 行)。纯契约用同一组数值,从而"合成夹具"与"真方块"同源。</p>
 */
public class VoxelClassifyContract {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        // ================= 1. 占比阈值与常量 =================
        check(VoxelClassifier.THIN_MAX_FRACTION == 0.25 && VoxelClassifier.FULL_MIN_FRACTION == 0.9
                        && VoxelClassifier.TALL_TOP_Y == 1.0,
                "阈值常量 = 0.25 / 0.9 / 守卫 2 = 1.0(改动必须同步本契约与交付说明)");
        check(VoxelClassifier.codeForSolidFraction(0.25) == VoxelField.CODE_EMPTY,
                "占比 0.25 ⇒ EMPTY(含等号)");
        check(VoxelClassifier.codeForSolidFraction(0.2500001) == VoxelField.CODE_VEG,
                "占比略大于 0.25 ⇒ VEG");
        check(VoxelClassifier.codeForSolidFraction(0.9) == VoxelField.CODE_SOLID,
                "占比 0.9 ⇒ SOLID(含等号)");
        check(VoxelClassifier.codeForSolidFraction(0.8999999) == VoxelField.CODE_VEG,
                "占比略小于 0.9 ⇒ VEG");
        check(VoxelClassifier.codeForSolidFraction(0.0) == VoxelField.CODE_EMPTY
                        && VoxelClassifier.codeForSolidFraction(Double.NaN) == VoxelField.CODE_EMPTY,
                "占比 0 / NaN ⇒ EMPTY(宁可漏挡不可假遮挡)");

        // ================= 2. 体积与顶高工具 =================
        check(VoxelClassifier.solidFraction(box(0, 0, 0, 1, 1, 1), 1) == 1.0, "满方块占比 = 1.0");
        check(Math.abs(VoxelClassifier.solidFraction(
                boxes(box(0, 0, 0, 1, 0.5, 1), box(0, 0.5, 0, 1, 1, 0.5)), 2) - 0.75) < 1e-9,
                "楼梯(bottom)两盒占比 = 0.5 + 0.25 = 0.75");
        check(VoxelClassifier.solidFraction(new double[0], 0) == 0.0, "空盒列表占比 = 0");
        check(VoxelClassifier.maxTopY(new double[0], 0) == Double.NEGATIVE_INFINITY,
                "空盒列表 maxTopY = -Infinity(守卫 2 自然不成立)");
        check(VoxelClassifier.maxTopY(box(0.375, 0, 0.375, 0.625, 1.5, 0.625), 1) == 1.5,
                "栅栏碰撞柱 maxTopY = 1.5");

        // ================= 3. 守卫顺序(两条守卫各自防一个方向) =================
        check(shapeCode(new double[0], 0, box(0, 0, 0, 1, 0.5, 1), 1) == VoxelField.CODE_EMPTY,
                "守卫 1:coll 空 + occ 0.5 ⇒ EMPTY(占比 0.5 也不管)");
        check(shapeCode(box(0, 0, 0, 1, 0.5, 1), box(0, 0, 0, 1, 0.5, 1)) == VoxelField.CODE_VEG,
                "守卫 1 不越界:coll 非空(0.5)+ occ 0.5 ⇒ VEG(不得因 occ 低就当 EMPTY)");
        check(shapeCode(new double[0], 0, box(0, 0, 0, 1, 0.5, 1), 1) == VoxelField.CODE_EMPTY,
                "守卫 1:绊线(coll 空 / occ 0.5)⇒ EMPTY(先于占比,否则会被判 VEG)");
        check(shapeCode(new double[0], 0, box(0, 0, 0, 1, 1, 1), 1) == VoxelField.CODE_EMPTY,
                "守卫 1:蛛网(coll 空 / occ 满格)⇒ EMPTY(先于占比,否则会被判 SOLID)");
        check(shapeCode(box(0.375, 0, 0.375, 0.625, 1.5, 0.625),
                box(0.375, 0, 0.375, 0.625, 1, 0.625)) == VoxelField.CODE_SOLID,
                "守卫 2:栅栏(coll maxY 1.5 / occ 占比 0.0625)⇒ SOLID(先于占比,否则会漏光)");
        check(shapeCode(box(0.25, 0, 0.25, 0.75, 1.5, 0.75),
                box(0.25, 0, 0.25, 0.75, 1, 0.75)) == VoxelField.CODE_SOLID,
                "守卫 2:墙(coll maxY 1.5 / occ 占比 0.25)⇒ SOLID(先于占比)");

        // ================= 4. 雪层(阵列主因;实测 coll 比 occ 矮一层) =================
        check(shapeCode(new double[0], 0, box(0, 0, 0, 1, 0.125, 1), 1) == VoxelField.CODE_EMPTY,
                "雪 layers=1(coll 空 / occ 0.125)⇒ EMPTY");
        check(shapeCode(box(0, 0, 0, 1, 0.125, 1), box(0, 0, 0, 1, 0.25, 1)) == VoxelField.CODE_EMPTY,
                "雪 layers=2(coll 0.125 / occ 0.25)⇒ EMPTY");
        check(shapeCode(box(0, 0, 0, 1, 0.25, 1), box(0, 0, 0, 1, 0.375, 1)) == VoxelField.CODE_VEG,
                "雪 layers=3(coll 0.25 / occ 0.375)⇒ VEG(旧口径用 coll 会误判 EMPTY)");
        check(shapeCode(box(0, 0, 0, 1, 0.875, 1), box(0, 0, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "雪 layers=8(coll 0.875 / occ 1.0)⇒ SOLID(旧口径用 coll 会误判 VEG)");
        check(shapeCode(box(0, 0, 0, 1, 1, 1), box(0, 0, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "雪块 snow_block ⇒ SOLID");

        // ================= 5. 薄片档 =================
        check(shapeCode(box(0, 0, 0, 1, 0.0625, 1), box(0, 0, 0, 1, 0.0625, 1)) == VoxelField.CODE_EMPTY,
                "地毯 white_carpet(0.0625)⇒ EMPTY");
        check(shapeCode(box(0.0625, 0, 0.0625, 0.9375, 0.09375, 0.9375),
                box(0.0625, 0, 0.0625, 0.9375, 0.09375, 0.9375)) == VoxelField.CODE_EMPTY,
                "睡莲 lily_pad(0.0718)⇒ EMPTY");
        check(shapeCode(box(0, 0, 0, 1, 0.1875, 1), box(0, 0, 0, 1, 0.1875, 1)) == VoxelField.CODE_EMPTY,
                "活板门 half=bottom open=false(0.1875)⇒ EMPTY");
        check(shapeCode(box(0, 0.8125, 0, 1, 1, 1), box(0, 0.8125, 0, 1, 1, 1)) == VoxelField.CODE_EMPTY,
                "活板门 half=top open=false(0.1875)⇒ EMPTY(旧中心列口径会误判 SOLID)");

        // ================= 6. 中低档:半砖 / 楼梯(含共面回归) =================
        check(shapeCode(box(0, 0, 0, 1, 0.5, 1), box(0, 0, 0, 1, 0.5, 1)) == VoxelField.CODE_VEG,
                "半砖 type=bottom(0.5)⇒ VEG");
        check(shapeCode(box(0, 0.5, 0, 1, 1, 1), box(0, 0.5, 0, 1, 1, 1)) == VoxelField.CODE_VEG,
                "半砖 type=top(0.5)⇒ VEG(旧中心列口径会误判 SOLID)");
        check(shapeCode(box(0, 0, 0, 1, 1, 1), box(0, 0, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "半砖 type=double(1.0)⇒ SOLID");
        check(shapeCode(boxes(box(0, 0, 0, 1, 0.5, 1), box(0, 0.5, 0, 1, 1, 0.5)),
                boxes(box(0, 0, 0, 1, 0.5, 1), box(0, 0.5, 0, 1, 1, 0.5))) == VoxelField.CODE_VEG,
                "楼梯 half=bottom(0.75)⇒ VEG");
        check(shapeCode(boxes(box(0, 0, 0, 1, 1, 0.5), box(0, 0.5, 0.5, 1, 1, 1)),
                boxes(box(0, 0, 0, 1, 1, 0.5), box(0, 0.5, 0.5, 1, 1, 1))) == VoxelField.CODE_VEG,
                "共面回归:楼梯 half=top 两盒面都落在 z=0.5,占比 0.75 ⇒ VEG(旧中心列判据在此判 EMPTY=漏光)");

        // ================= 7. 实心档 =================
        check(shapeCode(box(0, 0, 0, 1, 1, 1), box(0, 0, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "满方块 stone / oak_log ⇒ SOLID");
        check(shapeCode(box(0, 0, 0, 1, 0.9375, 1), box(0, 0, 0, 1, 0.9375, 1)) == VoxelField.CODE_SOLID,
                "耕地 farmland / 土径 dirt_path(0.9375 ≥ 0.9)⇒ SOLID");

        // ================= 8. 根因②③:位置相关档按坐标重算、不被 state 级缓存冻结 =================
        List<int[]> calls = new ArrayList<>();
        VoxelClassifier.ShapeProbe<String, String> probe = (lv, st, x, y, z) -> {
            calls.add(new int[]{x, y, z});
            // 同一 key(等价同一 BlockState)在两个坐标下形状不同
            return x == 1
                    ? new VoxelClassifier.ShapeBoxes(new double[0], 0, box(0, 0, 0, 1, 0.125, 1), 1)
                    : new VoxelClassifier.ShapeBoxes(box(0, 0, 0, 1, 1, 1), 1, box(0, 0, 0, 1, 1, 1), 1);
        };
        int atSnow = VoxelClassifier.classifyByShape(probe, "L", "minecraft:snow", 1, 70, 1);
        int atFull = VoxelClassifier.classifyByShape(probe, "L", "minecraft:snow", 2, 70, 1);
        check(atSnow == VoxelField.CODE_EMPTY, "同一 state 坐标 (1,70,1) 形状=雪 1 层 ⇒ EMPTY");
        check(atFull == VoxelField.CODE_SOLID, "同一 state 坐标 (2,70,1) 形状=满方块 ⇒ SOLID");
        check(atSnow != atFull,
                "根因②:同一 BlockState 在不同坐标判定不同 ⇒ 不得返回同一码(旧代码 CURSOR 恒为原点 ⇒ 恒同码)");
        check(calls.size() == 2,
                "根因③:位置相关档每次调用都重新探测(共 2 次),结果不被 state 级缓存冻结");
        check(calls.get(0)[0] == 1 && calls.get(0)[1] == 70 && calls.get(0)[2] == 1
                        && calls.get(1)[0] == 2 && calls.get(1)[1] == 70 && calls.get(1)[2] == 1,
                "坐标 x/y/z 如实透传给探针(不得恒为世界原点 (0,0,0))");
        int again = VoxelClassifier.classifyByShape(probe, "L", "minecraft:snow", 2, 70, 1);
        check(again == atFull && calls.size() == 3, "同坐标重复调用 ⇒ 同码且仍重新探测(无反例缓存)");

        // 收集式断言:一次跑出全部失败项(变异实验要看"雪层断言"确实在红名单里,而不是被首条中断掩盖)
        if (!FAILURES.isEmpty()) {
            throw new AssertionError("FAIL " + FAILURES.size() + " 条: " + FAILURES);
        }
        System.out.println("VoxelClassifyContract: ALL PASS (" + checks + " checks)");
    }

    private static double[] box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static double[] boxes(double[]... parts) {
        double[] out = new double[parts.length * VoxelClassifier.BOX_STRIDE];
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, i * VoxelClassifier.BOX_STRIDE, VoxelClassifier.BOX_STRIDE);
        }
        return out;
    }

    private static int shapeCode(double[] coll, double[] occ) {
        return shapeCode(coll, coll.length / VoxelClassifier.BOX_STRIDE, occ, occ.length / VoxelClassifier.BOX_STRIDE);
    }

    private static int shapeCode(double[] coll, int collN, double[] occ, int occN) {
        return VoxelClassifier.codeForShapes(coll, collN, occ, occN);
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (cond) {
            System.out.println("  PASS " + what);
        } else {
            FAILURES.add(what);
            System.out.println("  FAIL " + what);
        }
    }
}

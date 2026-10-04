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
        check(shapeCode(box(0, 0, 0, 1, 0.5, 1), box(0, 0, 0, 1, 0.5, 1)) == VoxelField.slabBottomCode(4),
                "守卫 1 不越界:coll 非空(0.5)+ occ 0.5 ⇒ 底薄板码 7(不得因 occ 低就当 EMPTY)");
        check(shapeCode(new double[0], 0, box(0, 0, 0, 1, 0.5, 1), 1) == VoxelField.CODE_EMPTY,
                "守卫 1:绊线(coll 空 / occ 顶 0.5 > 1/8)⇒ EMPTY(例外档只收贴地最薄一档)");
        check(shapeCode(new double[0], 0, box(0, 0, 0, 1, 1, 1), 1) == VoxelField.CODE_EMPTY,
                "守卫 1:蛛网(coll 空 / occ 满格)⇒ EMPTY(先于占比,否则会被判 SOLID)");
        check(shapeCode(new double[0], 0, box(0, 0, 0, 1, 0.125, 1), 1) == VoxelField.slabBottomCode(1),
                "守卫 1 例外:雪 1 层(coll 空 / occ 顶 1/8)⇒ 薄板码 4(2026-09-25 细雪层穿光根因)");
        // 2026-10-03 R18:守卫 2 改读 occ ⇒ 栅栏/墙不再判整格实心(旧式读 coll 顶 1.5 才判 SOLID)。
        // 用户实测依据:栅栏影子像一个满方块、栅栏门中间的洞透不过光(BACKLOG §2.132/§2.133)。
        check(shapeCode(box(0.375, 0, 0.375, 0.625, 1.5, 0.625),
                box(0.375, 0, 0.375, 0.625, 1, 0.625)) == VoxelField.CODE_VEG,
                "守卫 2 改读 occ:栅栏(coll maxY 1.5 / occ 顶 1.0 占比 0.0625)⇒ VEG(不再是整格实心)");
        check(shapeCode(box(0.25, 0, 0.25, 0.75, 1.5, 0.75),
                box(0.25, 0, 0.25, 0.75, 1, 0.75)) == VoxelField.CODE_VEG,
                "守卫 2 改读 occ:墙(coll maxY 1.5 / occ 顶 1.0 占比 0.25)⇒ VEG(不再是整格实心)");
        check(shapeCode(box(0, 0, 0, 1, 1.5, 1),
                box(0, 0, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "守卫 2 改读 occ 不越界:occ 占满(占比 1.0)仍 ⇒ SOLID(实心判据没有丢)");

        // ================= 4. 雪层(2026-09-25 高度感知:按真实高度出薄板码) =================
        check(shapeCode(box(0, 0, 0, 1, 0.125, 1), box(0, 0, 0, 1, 0.25, 1)) == VoxelField.slabBottomCode(2),
                "雪 layers=2(coll 0.125 / occ 0.25)⇒ 薄板码 5(旧口径 EMPTY=完全不遮挡)");
        check(shapeCode(box(0, 0, 0, 1, 0.25, 1), box(0, 0, 0, 1, 0.375, 1)) == VoxelField.slabBottomCode(3),
                "雪 layers=3(coll 0.25 / occ 0.375)⇒ 薄板码 6(旧口径 VEG=整格仅 25% 衰减)");
        check(shapeCode(box(0, 0, 0, 1, 0.875, 1), box(0, 0, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "雪 layers=8(coll 0.875 / occ 1.0)⇒ SOLID");
        check(shapeCode(box(0, 0, 0, 1, 1, 1), box(0, 0, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "雪块 snow_block ⇒ SOLID");

        // ================= 5. 薄片档(占满 XZ 足印的薄板 ⇒ 薄板码,按真实高度遮挡) =================
        check(shapeCode(box(0, 0, 0, 1, 0.0625, 1), box(0, 0, 0, 1, 0.0625, 1)) == VoxelField.slabBottomCode(1),
                "地毯 white_carpet(0.0625 ⇒ 量化到 1/8 档)⇒ 薄板码 4");
        check(shapeCode(box(0.0625, 0, 0.0625, 0.9375, 0.09375, 0.9375),
                box(0.0625, 0, 0.0625, 0.9375, 0.09375, 0.9375)) == VoxelField.CODE_VEG,
                "睡莲 lily_pad(不占满足印 ⇒ VEG;旧口径 EMPTY=完全不挡)");
        check(shapeCode(box(0, 0, 0, 1, 0.1875, 1), box(0, 0, 0, 1, 0.1875, 1)) == VoxelField.slabBottomCode(2),
                "活板门 half=bottom open=false(0.1875 ⇒ 量化 1/4)⇒ 薄板码 5");
        check(shapeCode(box(0, 0.8125, 0, 1, 1, 1), box(0, 0.8125, 0, 1, 1, 1)) == VoxelField.slabTopCode(7),
                "活板门 half=top open=false(0.8125)⇒ 顶薄板码 15(旧中心列口径会误判 SOLID)");

        // ================= 6. 中低档:半砖 / 楼梯(含共面回归) =================
        check(shapeCode(box(0, 0, 0, 1, 0.5, 1), box(0, 0, 0, 1, 0.5, 1)) == VoxelField.slabBottomCode(4),
                "半砖 type=bottom(0.5)⇒ 底薄板码 7");
        check(shapeCode(box(0, 0.5, 0, 1, 1, 1), box(0, 0.5, 0, 1, 1, 1)) == VoxelField.slabTopCode(4),
                "半砖 type=top(0.5)⇒ 顶薄板码 12(旧中心列口径会误判 SOLID)");
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
        check(atSnow == VoxelField.slabBottomCode(1),
                "同一 state 坐标 (1,70,1) 形状=雪 1 层(coll 空/occ 1/8)⇒ 薄板码 4");
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

        // ================= 9. 位置无关"类别"纯判定(2026-09-25 classify 快路径) =================
        // 背景:VoxelGrid 的快路径把"逐格 getKey().toString() + 两次字符串哈希"折叠成
        // "每方块一次"的类别查表。本组钉住**折叠前的那张判定表**的语义(顺序 + 不早返回)。
        // 红对照:把 CAT_VEG/CAT_LEAF 的先后调换(或让 CAT_OTHER 也返回码)⇒ 本组必红。
        check(VoxelClassifier.leafVegCategory(true, true) == VoxelClassifier.CAT_LEAF,
                "两张表都命中 ⇒ 树叶(顺序 = 旧代码 if/else:树叶先于软植被)");
        check(VoxelClassifier.leafVegCategory(true, false) == VoxelClassifier.CAT_LEAF,
                "只命中树叶表 ⇒ 树叶");
        check(VoxelClassifier.leafVegCategory(false, true) == VoxelClassifier.CAT_VEG,
                "只命中软植被表 ⇒ 软植被");
        check(VoxelClassifier.leafVegCategory(false, false) == VoxelClassifier.CAT_OTHER,
                "两表都不命中 ⇒ 其它(必须继续走流体/形状,不得早返回)");
        check(VoxelClassifier.CAT_LEAF != VoxelClassifier.CAT_VEG
                        && VoxelClassifier.CAT_VEG != VoxelClassifier.CAT_OTHER
                        && VoxelClassifier.CAT_LEAF != VoxelClassifier.CAT_OTHER,
                "三个类别值互不相同");
        check(VoxelClassifier.codeForBlockCategory(VoxelClassifier.CAT_LEAF) == VoxelField.CODE_LEAF
                        && VoxelClassifier.codeForBlockCategory(VoxelClassifier.CAT_VEG) == VoxelField.CODE_VEG,
                "树叶/软植被 ⇒ 对应的位置无关码(纯 block-id 判定 ⇒ 按方块身份缓存才安全)");
        check(VoxelClassifier.codeForBlockCategory(VoxelClassifier.CAT_OTHER)
                        == VoxelClassifier.CODE_FALLTHROUGH,
                "其它 ⇒ 哨兵 CODE_FALLTHROUGH(不早返回;否则等于重演\"非空气非树叶非软植被 ⇒ 默认实心\"兜底)");
        check(VoxelClassifier.CODE_FALLTHROUGH < 0
                        && VoxelClassifier.CODE_FALLTHROUGH != VoxelField.CODE_EMPTY
                        && VoxelClassifier.CODE_FALLTHROUGH != VoxelField.CODE_VEG
                        && VoxelClassifier.CODE_FALLTHROUGH != VoxelField.CODE_LEAF
                        && VoxelClassifier.CODE_FALLTHROUGH != VoxelField.CODE_SOLID
                        && !VoxelField.isSlab(VoxelClassifier.CODE_FALLTHROUGH),
                "哨兵在 VoxelField 合法码(0..15)之外,不会与任何真实码混淆");

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

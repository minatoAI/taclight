package dev.taclight.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * 形状分档契约(2026-09-18 雪地菱形阵列根因轮)。纯 JVM,零 MC 依赖。
 *
 * <p>钉死两件事:</p>
 * <ol>
 *   <li><b>根因①</b>——分档表:{@code codeForTopHeight} 的 0.25/0.9 两条阈值边界,
 *       以及 雪层 / 地毯 / 半砖(bottom) / 楼梯(bottom) / 满方块 / 耕地 / 栅栏·墙
 *       各自一例,断言分类码与批准的表一致;</li>
 *   <li><b>根因②③</b>——位置相关档必须"按坐标重新探测、不被 state 级缓存冻结"：
 *       同一 key(等价于同一 BlockState)在两个坐标下形状不同时,必须返回不同码;
 *       且坐标 x/y/z 如实透传(旧代码 {@code CURSOR} 从不 set ⇒ 永远在世界原点求值)。</li>
 * </ol>
 *
 * <p><b>形状夹具的出处(1.20.1 官方映射 jar,{@code javap} 实测转录,非记忆)</b>:</p>
 * <pre>
 *   SnowLayerBlock   SHAPE_BY_LAYER[i] = box(0,0,0,16,2i,16)/16   ⇒ 1 层 0.125 / 2 层 0.25 / 3 层 0.375
 *   CarpetBlock      box(0,0,0,16,1,16)/16                        ⇒ 0.0625
 *   SlabBlock        BOTTOM_AABB = box(0,0,0,16,8,16)/16          ⇒ 0.5
 *                    TOP_AABB    = box(0,8,0,16,16,16)/16         ⇒ 1.0
 *   StairBlock       BOTTOM_AABB(同 SlabBlock) ∪ OCTET_*(0.5³ 角块,y 上移 0.5)
 *                                                                 ⇒ 中心列 0.5 / max(Y) 1.0
 *   FarmBlock        box(0,0,0,16,15,16)/16                       ⇒ 0.9375
 *   FenceBlock/Wall  CrossCollisionBlock 碰撞高 24/16             ⇒ 1.5(中心柱严格包含格中心)
 *   满方块            box(0,0,0,1,1,1)                             ⇒ 1.0
 *   梯子(贴边薄板)    形状不含格中心列                              ⇒ 0(透明)
 *   火把             box(7/16,0,7/16,9/16,0.625,9/16)             ⇒ 0.625
 * </pre>
 */
public class VoxelClassifyContract {
    private static int checks;

    public static void main(String[] args) {
        // ================= 1. 阈值边界(分档表的两个分界) =================
        check(VoxelClassifier.codeForTopHeight(0.25) == VoxelField.CODE_EMPTY,
                "边界 h=0.25(雪 2 层)⇒ CODE_EMPTY(≤0.25 透光)");
        check(VoxelClassifier.codeForTopHeight(0.2500001) == VoxelField.CODE_VEG,
                "边界 h 略大于 0.25 ⇒ CODE_VEG");
        check(VoxelClassifier.codeForTopHeight(0.9) == VoxelField.CODE_SOLID,
                "边界 h=0.9 ⇒ CODE_SOLID(≥0.9 实心)");
        check(VoxelClassifier.codeForTopHeight(0.8999999) == VoxelField.CODE_VEG,
                "边界 h 略小于 0.9 ⇒ CODE_VEG");
        check(VoxelClassifier.codeForTopHeight(0.0) == VoxelField.CODE_EMPTY,
                "h=0(无碰撞形状/空形状)⇒ CODE_EMPTY");
        check(VoxelClassifier.codeForTopHeight(Double.NaN) == VoxelField.CODE_EMPTY,
                "h=NaN(异常输入)⇒ CODE_EMPTY(宁可漏挡不可假遮挡)");
        check(VoxelClassifier.THIN_MAX_Y == 0.25 && VoxelClassifier.FULL_MIN_Y == 0.9,
                "阈值常量 = 批准方案 0.25 / 0.9(改动必须同步本契约与交付说明)");

        // ================= 2. 雪层(阵列主因) =================
        check(tierOf(box(0, 0, 0, 1, 0.125, 1)) == VoxelField.CODE_EMPTY,
                "雪层 layers=1(顶高 2/16)⇒ CODE_EMPTY(旧兜底 = 整格实心 ⇒ 方格阵列)");
        check(tierOf(box(0, 0, 0, 1, 0.25, 1)) == VoxelField.CODE_EMPTY,
                "雪层 layers=2(顶高 4/16)⇒ CODE_EMPTY");
        check(tierOf(box(0, 0, 0, 1, 0.375, 1)) == VoxelField.CODE_VEG,
                "雪层 layers=3(顶高 6/16,>0.25)⇒ CODE_VEG");
        check(tierOf(box(0, 0, 0, 1, 1.0, 1)) == VoxelField.CODE_SOLID,
                "雪层 layers=8(满格 16/16)⇒ CODE_SOLID");

        // ================= 3. 地毯 / 绊线 =================
        check(tierOf(box(0, 0, 0, 1, 0.0625, 1)) == VoxelField.CODE_EMPTY,
                "地毯(顶高 1/16)⇒ CODE_EMPTY");
        check(tierOf() == VoxelField.CODE_EMPTY,
                "绊线(无碰撞形状,0 个 AABB)⇒ CODE_EMPTY");

        // ================= 4. 半砖 / 楼梯(部分高度档) =================
        check(tierOf(box(0, 0, 0, 1, 0.5, 1)) == VoxelField.CODE_VEG,
                "半砖 type=bottom(顶高 8/16)⇒ CODE_VEG");
        check(tierOf(box(0, 0.5, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "半砖 type=top(中心列顶高 16/16)⇒ CODE_SOLID(保守;已知残留)");
        // 楼梯 bottom:下半满铺 + 0.5³ 角块(y 0.5..1)在四角之一。格中心恰是角块顶点 ⇒ 必须严格不等号
        check(tierOf(box(0, 0, 0, 1, 0.5, 1), box(0, 0.5, 0, 0.5, 1, 0.5)) == VoxelField.CODE_VEG,
                "楼梯 half=bottom(角块 NNN:格中心落在角块顶点)⇒ CODE_VEG");
        check(tierOf(box(0, 0, 0, 1, 0.5, 1), box(0.5, 0.5, 0.5, 1, 1, 1)) == VoxelField.CODE_VEG,
                "楼梯 half=bottom(角块 PPP:另一方位)⇒ CODE_VEG");
        check(VoxelClassifier.centerColumnTopY(box(0, 0, 0, 1, 0.5, 1), 1) == 0.5
                        && VoxelClassifier.centerColumnTopY(box(0, 0, 0, 1, 1, 1), 1) == 1.0,
                "中心列顶高:下半砖 0.5 / 满方块 1.0(严格包含格中心 (0.5,0.5))");
        check(tierOf(box(0, 0.5, 0, 1, 1, 1), box(0, 0, 0, 0.5, 0.5, 0.5)) == VoxelField.CODE_SOLID,
                "楼梯 half=top(上半满铺)⇒ CODE_SOLID(保守;已知残留)");

        // ================= 5. 满方块 / 耕地 / 土径 =================
        check(tierOf(box(0, 0, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "满方块(顶高 16/16)⇒ CODE_SOLID");
        check(tierOf(box(0, 0, 0, 1, 0.9375, 1)) == VoxelField.CODE_SOLID,
                "耕地/土径(顶高 15/16 = 0.9375 ≥ 0.9)⇒ CODE_SOLID");

        // ================= 6. 栅栏 / 墙(非满但高 ⇒ 保守实心) =================
        check(tierOf(box(0.375, 0, 0.375, 0.625, 1.5, 0.625)) == VoxelField.CODE_SOLID,
                "栅栏(中心柱 1.5 格高)⇒ CODE_SOLID(保守;任务表第 4 行,已知残留)");
        check(tierOf(box(0.3125, 0, 0.3125, 0.6875, 1.5, 0.6875)) == VoxelField.CODE_SOLID,
                "墙(中心柱 1.5 格高)⇒ CODE_SOLID(保守;已知残留)");

        // ================= 7. 其它非满方块(对照,防"一刀切") =================
        check(tierOf(box(0, 0, 0.8125, 1, 1, 1)) == VoxelField.CODE_EMPTY,
                "梯子(贴边薄板,形状不含格中心列)⇒ CODE_EMPTY");
        check(tierOf(box(0.4375, 0, 0.4375, 0.5625, 0.625, 0.5625)) == VoxelField.CODE_VEG,
                "火把(顶高 10/16,中心列命中)⇒ CODE_VEG");
        check(tierOf(box(0, 0, 0, 1, 0.5, 1), box(0, 0.5, 0, 1, 1, 1)) == VoxelField.CODE_SOLID,
                "复合形状(下半 + 上半 = 满列):中心列取最高盒 1.0 ⇒ CODE_SOLID");
        check(tierOf(box(0.375, 0, 0.375, 0.625, 1.5, 0.625), box(0, 0, 0, 1, 0.0625, 1))
                        == VoxelField.CODE_SOLID,
                "复合形状(栅栏柱 + 地毯):取中心列最高盒 1.5 ⇒ CODE_SOLID(不被薄片拉低)");

        // ================= 8. 根因②③:位置相关档按坐标重算、不被 state 级缓存冻结 =================
        List<int[]> calls = new ArrayList<>();
        // 等价于"同一个 BlockState(同 key)":在 (1,70,1) 处是 1 层雪,在 (2,70,1) 处是满方块
        VoxelClassifier.ShapeProbe<String, String> probe = (lv, st, x, y, z) -> {
            calls.add(new int[]{x, y, z});
            return x == 1 ? 0.125 : 1.0;
        };
        int atSnow = VoxelClassifier.classifyByShape(probe, "L", "minecraft:snow", 1, 70, 1);
        int atFull = VoxelClassifier.classifyByShape(probe, "L", "minecraft:snow", 2, 70, 1);
        check(atSnow == VoxelField.CODE_EMPTY, "同一 state 坐标 (1,70,1) 形状=1 层雪 ⇒ CODE_EMPTY");
        check(atFull == VoxelField.CODE_SOLID, "同一 state 坐标 (2,70,1) 形状=满方块 ⇒ CODE_SOLID");
        check(atSnow != atFull,
                "根因②:同一 BlockState 在不同坐标判定不同 ⇒ 不得返回同一码(旧代码 CURSOR 恒为原点⇒恒同码)");
        check(calls.size() == 2,
                "根因③:位置相关档每次调用都重新探测(共 2 次),结果不被 state 级缓存冻结");
        check(calls.get(0)[0] == 1 && calls.get(0)[1] == 70 && calls.get(0)[2] == 1
                        && calls.get(1)[0] == 2 && calls.get(1)[1] == 70 && calls.get(1)[2] == 1,
                "坐标 x/y/z 如实透传给探针(不得恒为世界原点 (0,0,0))");
        int again = VoxelClassifier.classifyByShape(probe, "L", "minecraft:snow", 2, 70, 1);
        check(again == atFull && calls.size() == 3,
                "同坐标重复调用 ⇒ 同码且仍重新探测(无反例缓存)");

        System.out.println("VoxelClassifyContract: ALL PASS (" + checks + " checks)");
    }

    /** 单盒夹具。 */
    private static double[] box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** 多盒夹具拼接(平坦数组,步长 BOX_STRIDE)。 */
    private static double[] boxes(double[]... parts) {
        double[] out = new double[parts.length * VoxelClassifier.BOX_STRIDE];
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, i * VoxelClassifier.BOX_STRIDE, VoxelClassifier.BOX_STRIDE);
        }
        return out;
    }

    private static int tierOf(double[]... parts) {
        double[] flat = boxes(parts);
        return VoxelClassifier.codeForTopHeight(
                VoxelClassifier.centerColumnTopY(flat, parts.length));
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

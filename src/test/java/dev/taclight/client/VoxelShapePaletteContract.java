package dev.taclight.client;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>形状调色板的设计尺寸闸门</b>（2026-10-03 R19）。
 *
 * <p><b>为什么有这个契约</b>：形状调色板（把每格从"16 档类别"升级成"指向一组盒的索引"）的
 * 两个关键尺寸——<b>能表达多少种不同形状</b>与<b>一条形状最多几个盒</b>——决定了 SSBO 容量与
 * 步长。这两个数<b>推理不出来</b>，只能对真实注册表穷举实测。本契约把它变成一次机器可判的闸门：
 * 容量或上限设小了就红，而不是等到真机上发现装不下。</p>
 *
 * <p><b>"需要调色板"的精确口径</b>（不用猜，写成可判定的条件）：某状态的遮挡形
 * <b>不是</b>"单一盒且占满 XZ 足印、且从 y=0 起或到 y=1 止"时，16 档基础码<b>无法精确表达</b>它。
 * 理由：基础码里能带形状的只有"满足印水平板"（4..15）与"整格"（3），此外只剩
 * 整格均匀衰减（VEG/LEAF）与完全不挡（EMPTY）——都丢掉了水平足印。</p>
 *
 * <p><b>形状的规范化</b>：盒坐标按 1/16 格量化（原版形状全部落在 1/16 网格上），
 * 每盒写成 6 个整数、按字典序排序后拼接 ⇒ <b>同一组盒必然得到同一个 key</b>，
 * 与盒的顺序无关。这个 key 就是调色板的去重键。</p>
 */
public final class VoxelShapePaletteContract {

    /** 调色板容量：码 16..255 共 240 条（0..15 留给既有基础码）。
     *  <b>注意这是"每帧局部"容量，不是全局容量</b>：调色板每帧按当前盒里实际出现的形状重建，
     *  盒是 128³、装不下全局的 676 种。真实场景一个盒里通常几十种；万一超了，
     *  溢出的格退回基础码（降级到今天的行为），不会崩、不会错位。 */
    public static final int PALETTE_CAPACITY = 240;
    /** 一条形状允许的最大盒数（决定 SSBO 步长）。按实测盒数分布定：8 盒覆盖 669/676 种形状。 */
    public static final int MAX_BOXES_PER_SHAPE = 8;
    /** 步长选择必须覆盖的形状占比下限（设计闸门）。 */
    public static final double MIN_STRIDE_COVERAGE = 0.95;

    private static int checks = 0;
    private static int fails = 0;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(Bootstrap.class != null, "真 registry bootstrap 完成");

        BlockPos p = new BlockPos(0, 64, 0);
        Map<String, int[]> byShape = new HashMap<>();      // key -> {状态数, 盒数}
        Map<String, String> sample = new HashMap<>();      // key -> 代表状态
        Map<String, Integer> byBlock = new TreeMap<>();    // 方块 id -> 需要调色板的状态数
        int total = 0, occEmpty = 0, baseExpressible = 0, need = 0, maxBoxes = 0;

        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            for (BlockState st : block.getStateDefinition().getPossibleStates()) {
                total++;
                List<AABB> boxes = st.getOcclusionShape(EmptyBlockGetter.INSTANCE, p).toAabbs();
                if (boxes.isEmpty()) { occEmpty++; continue; }
                if (isBaseExpressible(boxes)) { baseExpressible++; continue; }
                need++;
                byBlock.merge(id, 1, Integer::sum);
                String key = canonical(boxes);
                maxBoxes = Math.max(maxBoxes, boxes.size());
                byShape.computeIfAbsent(key, k -> new int[]{0, boxes.size()})[0]++;
                sample.putIfAbsent(key, id + st.getValues());
            }
        }

        System.out.println(String.format(Locale.ROOT,
                "[shape-palette] total=%d  occ空=%d  16码已能精确表达=%d  **需要调色板=%d (%.1f%%)**",
                total, occEmpty, baseExpressible, need, 100.0 * need / total));
        System.out.println(String.format(Locale.ROOT,
                "[shape-palette] 不同形状数=%d（容量 %d，余量 %d）  单形状最大盒数=%d（上限 %d）",
                byShape.size(), PALETTE_CAPACITY, PALETTE_CAPACITY - byShape.size(),
                maxBoxes, MAX_BOXES_PER_SHAPE));

        TreeMap<Integer, Integer> dist = new TreeMap<>();
        for (int[] v : byShape.values()) dist.merge(v[1], 1, Integer::sum);
        StringBuilder d = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : dist.entrySet()) {
            d.append(e.getKey()).append("盒×").append(e.getValue()).append("种  ");
        }
        System.out.println("[shape-palette] 形状按盒数分布: " + d);

        List<Map.Entry<String, int[]>> top = new ArrayList<>(byShape.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        System.out.println("[shape-palette] 覆盖状态最多的前 10 种形状:");
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            Map.Entry<String, int[]> e = top.get(i);
            System.out.println("    " + e.getValue()[0] + " 状态  " + e.getValue()[1] + " 盒  "
                    + e.getKey() + "   例:" + sample.get(e.getKey()));
        }

        List<Map.Entry<String, Integer>> fam = new ArrayList<>(byBlock.entrySet());
        fam.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        System.out.println("[shape-palette] 需要调色板的方块=" + fam.size() + " 种，状态数前 15:");
        for (int i = 0; i < Math.min(15, fam.size()); i++) {
            System.out.println("    " + fam.get(i).getValue() + "  " + fam.get(i).getKey());
        }

        System.out.println("[shape-palette] ---- 设计尺寸闸门 ----");
        int withinStride = 0;
        for (int[] v : byShape.values()) if (v[1] <= MAX_BOXES_PER_SHAPE) withinStride++;
        double coverage = (double) withinStride / byShape.size();
        System.out.println(String.format(Locale.ROOT,
                "[shape-palette] 步长 %d 盒可覆盖形状 %d/%d = %.2f%%（下限 %.0f%%）；"
                        + "超步长的形状 %d 种退回基础码",
                MAX_BOXES_PER_SHAPE, withinStride, byShape.size(), coverage * 100.0,
                MIN_STRIDE_COVERAGE * 100.0, byShape.size() - withinStride));
        check(coverage >= MIN_STRIDE_COVERAGE,
                String.format(Locale.ROOT, "步长 %d 盒的形状覆盖率 %.2f%% ≥ %.0f%%",
                        MAX_BOXES_PER_SHAPE, coverage * 100.0, MIN_STRIDE_COVERAGE * 100.0));
        check(maxBoxes <= 16,
                "全局单形状最大盒数 " + maxBoxes + " ≤ 16（硬上限，超过说明解析有问题）");
        check(need > 0, "确实存在 16 码表达不了的状态（否则调色板无意义）: " + need + " 个");

        System.out.println("[shape-palette] checks=" + checks + " fails=" + fails);
        if (fails > 0) {
            throw new AssertionError("VoxelShapePaletteContract FAILED: " + fails + "/" + checks);
        }
        System.out.println("VoxelShapePaletteContract: ALL PASS (" + checks + " checks)");
    }

    /** 16 档基础码能否<b>精确</b>表达这组盒：单一盒 + 占满 XZ 足印 + 从 y=0 起或到 y=1 止。 */
    private static boolean isBaseExpressible(List<AABB> boxes) {
        if (boxes.size() != 1) return false;
        AABB b = boxes.get(0);
        boolean fullXZ = q(b.minX) == 0 && q(b.minZ) == 0 && q(b.maxX) == 16 && q(b.maxZ) == 16;
        if (!fullXZ) return false;
        return q(b.minY) == 0 || q(b.maxY) == 16;
    }

    /** 规范化：盒按 1/16 量化，每盒 6 个整数，行内字典序排序后拼接。 */
    private static String canonical(List<AABB> boxes) {
        List<String> rows = new ArrayList<>(boxes.size());
        for (AABB b : boxes) {
            rows.add(q(b.minX) + " " + q(b.minY) + " " + q(b.minZ) + " "
                    + q(b.maxX) + " " + q(b.maxY) + " " + q(b.maxZ));
        }
        rows.sort(null);
        return String.join(" ; ", rows);
    }

    private static int q(double v) { return (int) Math.round(v * 16.0); }

    private static void check(boolean ok, String what) {
        checks++;
        if (ok) {
            System.out.println("  PASS " + what);
        } else {
            fails++;
            System.out.println("  FAIL " + what);
        }
    }

    private VoxelShapePaletteContract() {}
}

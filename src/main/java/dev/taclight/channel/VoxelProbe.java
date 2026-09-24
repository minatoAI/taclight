package dev.taclight.channel;

import java.util.List;
import java.util.Locale;

/**
 * 体素遮挡的<b>游戏内单元诊断</b>纯逻辑(2026-09-25 细雪层穿光轮)。
 *
 * <p><b>为什么有这个类</b>:细雪层穿光这一轮暴露的调试缺口是——我们能在离线契约里量到
 * "雪 1-2 层 ⇒ CODE_EMPTY"(真 registry 实测),却<b>没法在真实场景里问一句</b>
 * "这条光路的这一格到底被判成什么"。只能靠截图 A/B 猜,而截图受动画噪声支配(全图 imgdiff
 * 已被证伪:同状态 1.4s 漂移 14204)。于是把判据做成<b>可程序化提问</b>的探针。</p>
 *
 * <p><b>并排两面(关键)</b>:同一格同时给出
 * <b>live</b> = {@code VoxelGrid.classify} 此刻对真实世界的判定,与
 * <b>grid</b> = 已经打包上传到 SSBO 的值。两者不一致 ⇒ 问题在打包/上传/盒范围;
 * 两者一致但仍漏光 ⇒ 问题在分类口径或着色器透射表。没有这层区分就只能猜。</p>
 *
 * <p>纯 JVM(零 MC 依赖)⇒ 可被 {@code VoxelProbeContract} 直接断言。</p>
 */
public final class VoxelProbe {
    /** 盒外(占用未知)。 */
    public static final int OUT = -1;
    /** {@code !voxray} 最多打印多少格(避免 128 格长射线刷屏)。 */
    public static final int DEFAULT_MAX_CELLS = 24;

    private VoxelProbe() {}

    /** 码 → 可读名(与 {@link VoxelField} 常量、shader 透射表同源;薄板码带高度区间)。 */
    public static String codeName(int code) {
        return switch (code) {
            case VoxelField.CODE_EMPTY -> "EMPTY";
            case VoxelField.CODE_VEG -> "VEG";
            case VoxelField.CODE_LEAF -> "LEAF";
            case VoxelField.CODE_SOLID -> "SOLID";
            default -> code < 0 ? "OUT"
                    : (VoxelField.isSlab(code)
                            ? String.format(Locale.ROOT, "SLAB[%.3f..%.3f]",
                                    VoxelField.slabLow(code), VoxelField.slabHigh(code))
                            : ("CODE" + code));
        };
    }

    /** 单格探针一行:live/grid 并排,不一致时显式标注 MISMATCH。 */
    public static String cellReport(int x, int y, int z, int liveCode, int gridCode) {
        return String.format(Locale.ROOT, "VOXPROBE (%d,%d,%d) live=%s grid=%s%s",
                x, y, z, codeName(liveCode), codeName(gridCode),
                liveCode == gridCode ? "" : " MISMATCH(判定与已上传网格不一致)");
    }

    /** 盒扫描的一行:格坐标 + 方块标识(含属性) + live/grid 两面的码。 */
    public record Row(int x, int y, int z, String block, int liveCode, int gridCode) { }

    /**
     * 盒扫描报告:先给计数摘要与"非空气却判透光"的<b>按方块归类</b>,再<b>优先</b>列出漏光格
     * (live=EMPTY 的非空格),最后才是其余格。
     *
     * <p><b>为什么漏光格优先</b>:2026-09-25 首轮真机发现——按坐标顺序打印时,60 行上限会被
     * 地下石头/雪块占满,而真正想看的"薄雪层被判成空气"那些格反而被截掉。诊断工具必须让
     * <b>签名行先出现</b>,否则等于没印。第一轮实测 {@code nonAir=416 EMPTY=78}(9³ 盒子)。</p>
     */
    public static String scanReport(List<Row> leaky, List<Row> rest,
                                    int nonAir, int emptyNonAir,
                                    int veg, int leaf, int slab, int solid) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "VOXSCAN nonAir=%d EMPTY=%d VEG=%d LEAF=%d SLAB=%d SOLID=%d | 非空气却被判透光(EMPTY,完全不遮挡)=%d",
                nonAir, emptyNonAir, veg, leaf, slab, solid, emptyNonAir));
        if (!leaky.isEmpty()) {
            java.util.LinkedHashMap<String, Integer> byBlock = new java.util.LinkedHashMap<>();
            for (Row r : leaky) {
                byBlock.merge(r.block(), 1, Integer::sum);
            }
            StringBuilder agg = new StringBuilder();
            int kinds = 0;
            for (java.util.Map.Entry<String, Integer> e : byBlock.entrySet()) {
                if (kinds++ >= 6) { agg.append(", ..."); break; }
                if (agg.length() > 0) agg.append(", ");
                agg.append(e.getKey()).append('×').append(e.getValue());
            }
            sb.append("\n  漏光格按方块归类: ").append(agg);
        }
        int printed = 0;
        for (Row r : leaky) {
            sb.append(rowLine(r));
            printed++;
        }
        for (Row r : rest) {
            if (printed >= nonAir) break;
            sb.append(rowLine(r));
            printed++;
        }
        if (printed < nonAir) {
            sb.append("\n  ...TRUNCATED(").append(nonAir - printed).append(" 格未列出)");
        }
        return sb.toString();
    }

    private static String rowLine(Row r) {
        return String.format(Locale.ROOT, "\n  (%d,%d,%d) block=%s live=%s grid=%s%s",
                r.x(), r.y(), r.z(), r.block(),
                codeName(r.liveCode()), codeName(r.gridCode()),
                r.liveCode() == r.gridCode() ? "" : " MISMATCH");
    }

    /**
     * 射线报告:逐格列出 live/grid 码与该格穿透长度,并给出两种口径下累积透射率
     * ({@code liveT} 用真实世界判定、{@code gridT} 用已上传网格 = 着色器实际看到的)。
     * 两端格豁免与 GLSL/{@link VoxelDda#traceIntermediate} 同一遍历。
     */
    public static String rayReport(double ax, double ay, double az,
                                   double bx, double by, double bz,
                                   int maxCells,
                                   VoxelDda.Classifier live,
                                   VoxelDda.Classifier grid) {
        List<VoxelDda.Visited> visited = VoxelDda.traceVisited(ax, ay, az, bx, by, bz);
        double liveT = VoxelDda.transmit(ax, ay, az, bx, by, bz, live);
        double gridT = VoxelDda.transmit(ax, ay, az, bx, by, bz, grid);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "VOXRAY a=(%.2f,%.2f,%.2f) b=(%.2f,%.2f,%.2f) cells=%d liveT=%.3f gridT=%.3f",
                ax, ay, az, bx, by, bz, visited.size(), liveT, gridT));
        int shown = Math.min(visited.size(), Math.max(maxCells, 0));
        for (int i = 0; i < shown; i++) {
            VoxelDda.Visited v = visited.get(i);
            sb.append(String.format(Locale.ROOT, "\n  [%d] (%d,%d,%d) live=%s grid=%s pen=%.3f",
                    i, v.cell().x(), v.cell().y(), v.cell().z(),
                    codeName(live.code(v.cell())), codeName(grid.code(v.cell())), v.penetration()));
        }
        if (visited.size() > shown) {
            sb.append("\n  ...TRUNCATED(").append(visited.size() - shown).append(" 格未列出)");
        }
        if (visited.isEmpty()) {
            sb.append("\n  (无中间格:两端同格或相邻)");
        }
        return sb.toString();
    }
}

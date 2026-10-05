package dev.taclight.channel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 薄板命中距离契约（2026-09-30 R13：用户真机实测"薄雪层阴影异常延长"根因轮）。
 *
 * <p><b>为什么补这条</b>：现役契约里有 {@link VoxelDda}（逐采样路径 {@code taclight_vox_transmit} 的
 * Java 模型，雪层语义被 {@code VoxelDdaContract} 36 项守住），但**没有"建表路径"
 * {@code taclight_vox_hit_dist} 的模型** ⇒ 没有任何判据能拦住本次缺陷：
 * 该函数对**薄板**（码 4..15）命中时返回 {@code tNext}（**进入该格的距离**），
 * 而逐采样路径判的是"是否真的穿过板面"。对满方块两者等价（入格≈表面），
 * 对 1/8 格厚的雪层则可差近一整格 ⇒ 表里 {@code dHit} 系统性偏小 ⇒
 * {@code taclight_occl_vis_from_hit} 提前判遮挡 ⇒ **阴影朝光源方向被拉长**
 * （离线数值实验：中位 +0.88 格 / 最差 +1.36 格；76.6% 方向超 0.5 格）。
 * 证据与档案：{@code docs/evidence/2026-09-30-snow-shadow/}｜台账 {@code BACKLOG §2.123/§2.124}。
 *
 * <p><b>本契约三层断言</b>：
 * <ol>
 *   <li><b>源码级守卫</b>：建表函数薄板分支**不得**再直接返回入格距离，必须计算板面穿越距离；
 *       同时断言逐采样路径的薄板语义**未被顺带改掉**（命中仍 {@code T=0}）。</li>
 *   <li><b>数值正例/反例（本契约自带的模型）</b>：同一条 DDA 的**旧公式**与**新公式**分别与
 *       **几何解析参考**（射线∩AABB）比：
 *       ① 满方块场景 —— 两者都不得有大偏差（阳性对照；残差上限 = FUZZ/2 软化带）；
 *       ② 薄雪层场景 —— **旧公式必须被判为大偏差**（证明模型真能复现该缺陷 = 反例自证），
 *           **新公式偏差必须 ≈0**（证明修法在模型里真成立）。</li>
 *   <li><b>第二条独立参考</b>：抽样方向再用暴力细步（O(1) 按格查表）复核解析参考，
 *       两者不吻合则该方向作废（防止参考本身坏掉还被当结论）。</li>
 * </ol>
 *
 * <p>纯 JVM、零 MC 依赖；不读任何运行期状态。
 */
public class SlabHitDistContract {
    private static int checks;

    private static final int SX = 8;
    private static final int SY = 4;
    private static final int SZ = 8;
    private static final double FUZZ = 0.35;      // 与 GLSL TACLIGHT_VOX_FUZZ 同值
    private static final int CODE_AIR = 0;
    private static final int CODE_VEG = 1;
    private static final int CODE_LEAF = 2;
    private static final int CODE_SOLID = 3;
    private static final int CODE_SNOW1 = 4;      // 雪 1 层 = 底薄板 hi = (4-3)/8 = 0.125

    public static void main(String[] args) throws Exception {
        String core = Files.readString(Path.of("pack/shaders/lib/taclight_core.glsl"));

        // ---------- 1) 源码级守卫（建表路径） ----------
        String tableFn = between(core, "float taclight_vox_hit_dist(", "float taclight_occl_vis_from_hit(");
        check(!tableFn.isEmpty(), "能定位建表函数 taclight_vox_hit_dist（源码结构未变）");
        check(!tableFn.contains("if (hit) return tNext;"),
                "建表函数薄板分支不再直接返回'入格距离'（R13 缺陷的原始写法）");
        check(tableFn.contains("float tHit = tNext;"), "建表函数薄板分支引入 tHit 作为返回量");
        check(tableFn.contains("(yA - hi) / (-dir.y)"),
                "底薄板：自上方进入且向下 ⇒ 返回穿越 y=cell.y+hi 的距离");
        check(tableFn.contains("(lo - yA) / dir.y"),
                "顶薄板：自下方进入且向上 ⇒ 返回穿越 y=cell.y+lo 的距离");

        // ---------- 2) 逐采样路径语义未被顺带改掉 ----------
        String txFn = between(core, "float taclight_vox_transmit(", "float taclight_vox_hit_dist(");
        check(!txFn.isEmpty(), "能定位逐采样函数 taclight_vox_transmit");
        check(txFn.contains("taclight_vox_box_fraction(") && !txFn.contains("if (hit) return 0.0;"),
                "逐采样路径薄板改用共享盒规则(R20:band=min(FUZZ,板厚),GB 旧\"相交即 T=0\"已由 R20 有意取代 —— "
                        + "R13 当时钉的\"语义未漂移\"是为了隔离建表路径的改动,不是永久冻结该写法)");

        // ---------- 3) 数值：解析参考 + 暴力参考 ----------
        int[][] solidOnly = solidScene(false);
        int[][] withSnow = solidScene(true);
        double[] light = {4.5, 3.5, 4.5};

        List<double[]> dirs = directions(36, 7);
        Stats posOld = new Stats();
        Stats slabOld = new Stats();          // 首碰**雪板**的方向（缺陷域）
        Stats slabNew = new Stats();
        Stats groundOld = new Stats();        // 首碰**地面实心**的方向（含 FUZZ 软化带，设计内）
        int refMismatch = 0;
        int grazeSkipped = 0;
        int i = 0;
        for (double[] d : dirs) {
            double[] solid = analyticHit(solidOnly, light, d, 24.0);
            if (solid == null) continue;
            if (i % 5 == 0) {                       // 抽样交叉核解析参考
                double brute = bruteHit(solidOnly, light, d, 24.0);
                if (Math.abs(brute - solid[0]) > 3e-3) refMismatch++;
            }
            i++;
            // 掠边豁免（**设计内**，不是缺陷）：逐采样/建表两条路径对**实心格**都按
            // 「穿透长度 ≥ FUZZ(0.35) 才算命中」软化（GLSL 注释自陈"掠边得部分透射"），
            // 而几何解析参考会如实报出"擦到盒角"的零穿透命中 ⇒ 两者在掠边方向**本就不该相等**。
            // 这类方向必须排除在"必须一致"之外；同时下面断言 grazeSkipped>0，证明排除规则真生效过。
            if (solid[1] < FUZZ) { grazeSkipped++; continue; }
            // 阳性对照：满方块场景
            addIfHit(posOld, solid[0], dda(solidOnly, light, d, 24.0, false));

            // 反例：薄雪层场景。⚠️ 该场景**并非所有方向首碰都是雪板** ⇒ 按"首碰落在哪个盒"分组，
            // 否则地面实心路径的 FUZZ 软化带（设计内）会被误判成缺陷。
            double[] snow = analyticHit(withSnow, light, d, 24.0);
            if (snow == null) continue;
            double sOld = dda(withSnow, light, d, 24.0, false);
            double sNew = dda(withSnow, light, d, 24.0, true);
            if (sOld < 0 || sNew < 0) continue;
            if (hitsSlab(withSnow, light, d, snow[0])) {
                slabOld.add(snow[0] - sOld);
                slabNew.add(snow[0] - sNew);
            } else if (snow[1] >= FUZZ) {
                groundOld.add(snow[0] - sOld);
            }
        }

        check(refMismatch == 0,
                "两套独立参考（解析 ∩ / 暴力细步）在抽样方向上一致（不一致数=" + refMismatch + "）");
        check(grazeSkipped > 0,
                "掠边豁免确实生效过（跳过 " + grazeSkipped + " 个穿透<FUZZ 的方向 —— 两条路径对实心格按 FUZZ 软化是设计内语义，不该拿来做等值断言）");
        check(posOld.count() >= 40, "阳性对照方向数充足（实测 " + posOld.count() + "）");
        check(posOld.big() == 0,
                "阳性对照：满方块场景无 |偏差|>0.5 格 的方向（实测 " + posOld.big() + "/" + posOld.count()
                        + "，max|偏差|=" + fmt(posOld.maxAbs()) + "）");
        check(groundOld.count() > 0 && groundOld.big() == 0,
                "薄雪层场景中首碰地面的方向同样无大偏差（" + groundOld.count() + " 个）");
        check(slabOld.count() >= 30, "缺陷域（首碰雪板）方向数充足（实测 " + slabOld.count() + "）");
        check(slabOld.big() * 100 >= slabOld.count() * 40,
                "反例自证：**旧公式**在首碰雪板的方向上大偏差占比 ≥40%（实测 " + slabOld.big() + "/"
                        + slabOld.count() + "，中位 " + fmt(slabOld.median()) + " 格，最大 "
                        + fmt(slabOld.maxAbs()) + " 格）——本模型确实能复现该缺陷");
        check(slabNew.maxAbs() < 1e-6,
                "修法自证：**新公式**在首碰雪板的方向上偏差 ≈0（实测 max|偏差|=" + fmt(slabNew.maxAbs())
                        + "，方向数 " + slabNew.count() + "）");

        System.out.println("SlabHitDistContract: ALL PASS (" + checks + " checks)");
    }

    // ------------------------------------------------------------------
    // 数值模型（本契约自带；与 GLSL 逐行对应，唯一差别 = 薄板命中时返回什么）
    // ------------------------------------------------------------------

    /** 直线 DDA；{@code fixed=false} 复刻 R13 缺陷写法，{@code true} 为修后写法。返回首碰距离或 -1。 */
    private static double dda(int[][] code, double[] o, double[] d, double maxDist, boolean fixed) {
        int[] cell = {(int) Math.floor(o[0]), (int) Math.floor(o[1]), (int) Math.floor(o[2])};
        for (int k = 0; k < 3; k++) if (cell[k] < 0 || cell[k] >= dim(k)) return -1;
        int[] istep = new int[3];
        double[] tdelta = new double[3];
        double[] tmax = new double[3];
        for (int k = 0; k < 3; k++) {
            istep[k] = d[k] > 0 ? 1 : (d[k] < 0 ? -1 : 0);
            tdelta[k] = Math.abs(d[k]) > 1e-9 ? 1.0 / Math.abs(d[k]) : 1e9;
            if (Math.abs(d[k]) > 1e-9) {
                double f = d[k] > 0 ? (cell[k] + 1.0 - o[k]) : (o[k] - cell[k]);
                tmax[k] = f * tdelta[k];
            } else tmax[k] = 1e9;
        }
        double trans = 1.0;
        for (int guard = 0; guard < 384; guard++) {
            double tnext = Math.min(tmax[0], Math.min(tmax[1], tmax[2]));
            if (tnext > maxDist) return maxDist;
            double tieEps = Math.max(1e-6, Math.abs(tnext) * 1e-5);
            boolean[] tied = new boolean[3];
            for (int k = 0; k < 3; k++) tied[k] = Math.abs(tmax[k] - tnext) <= tieEps;
            for (int k = 0; k < 3; k++) if (tied[k]) { cell[k] += istep[k]; tmax[k] += tdelta[k]; }
            for (int k = 0; k < 3; k++) if (cell[k] < 0 || cell[k] >= dim(k)) return maxDist;
            double texit = Math.min(tmax[0], Math.min(tmax[1], tmax[2]));
            int c = airAt(code, cell);
            if (c == CODE_SOLID) {
                double pen = Math.max(0.0, Math.min(texit, maxDist) - tnext);
                double f = Math.min(1.0, Math.max(0.0, pen / FUZZ));
                if (f >= 1.0) return tnext;
                trans *= 1.0 - f;
            } else if (c >= 4) {
                double lo = c >= 12 ? (c - 8.0) / 8.0 : 0.0;
                double hi = c >= 12 ? 1.0 : (c - 3.0) / 8.0;
                double yA = o[1] + d[1] * tnext - cell[1];
                double yB = o[1] + d[1] * texit - cell[1];
                double yLo = Math.min(yA, yB);
                double yHi = Math.max(yA, yB);
                boolean hit = (yHi - yLo <= 1e-6) ? (yLo >= lo && yLo < hi)
                        : (Math.min(yHi, hi) - Math.max(yLo, lo) > 1e-6);
                if (hit) {
                    if (!fixed) return tnext;                       // ← R13 缺陷写法
                    double tHit = tnext;
                    if (c >= 12) {
                        if (d[1] > 0.0 && yA < lo) tHit = tnext + (lo - yA) / d[1];
                    } else {
                        if (d[1] < 0.0 && yA > hi) tHit = tnext + (yA - hi) / (-d[1]);
                    }
                    return tHit;
                }
            } else if (c == CODE_LEAF) trans *= 0.40;
            else if (c == CODE_VEG) trans *= 0.75;
            if (trans <= 0.45) return tnext;
        }
        return maxDist;
    }

    private static int dim(int axis) {
        return axis == 0 ? SX : axis == 1 ? SY : SZ;
    }

    private static int airAt(int[][] code, int[] cell) {
        if (cell[0] < 0 || cell[0] >= SX || cell[1] < 0 || cell[1] >= SY || cell[2] < 0 || cell[2] >= SZ) {
            return CODE_AIR;
        }
        return code[cell[0]][cell[1] * SZ + cell[2]];
    }

    /**
     * 参考 1：解析射线∩所有遮挡盒（盒都在自己那一格内）。
     *
     * @return {@code {首碰距离, 该次相交在盒内的穿透长度}}；无命中返回 {@code null}。
     *         穿透长度用于识别**掠边**命中（&lt; FUZZ 时两条代码路径按设计**不**相等）。
     */
    private static double[] analyticHit(int[][] code, double[] o, double[] d, double maxDist) {
        double best = -1;
        double bestPen = 0;
        for (int x = 0; x < SX; x++) {
            for (int y = 0; y < SY; y++) {
                for (int z = 0; z < SZ; z++) {
                    int c = code[x][y * SZ + z];
                    double[] box = boxOf(c, x, y, z);
                    if (box == null) continue;
                    double[] r = rayBox(o, d, box);
                    if (r == null) continue;
                    double t = r[0] > 0 ? r[0] : r[1];
                    if (t < 0 || t > maxDist) continue;
                    if (best < 0 || t < best) {
                        best = t;
                        bestPen = Math.min(r[1], maxDist) - Math.max(r[0], 0.0);
                    }
                }
            }
        }
        return best < 0 ? null : new double[] {best, bestPen};
    }

    /** 参考 2：暴力细步（O(1) 按格判定）。 */
    private static double bruteHit(int[][] code, double[] o, double[] d, double maxDist) {
        double step = 2e-3;
        for (double t = 0; t <= maxDist; t += step) {
            double[] p = {o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t};
            int[] cell = {(int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])};
            double[] box = boxOf(airAt(code, cell), cell[0], cell[1], cell[2]);
            if (box == null) continue;
            if (p[0] >= box[0] && p[0] <= box[3] && p[1] >= box[1] && p[1] <= box[4]
                    && p[2] >= box[2] && p[2] <= box[5]) return t;
        }
        return -1;
    }

    private static double[] boxOf(int c, int x, int y, int z) {
        if (c == CODE_SOLID) return new double[] {x, y, z, x + 1.0, y + 1.0, z + 1.0};
        if (c >= 4) {
            double lo = c >= 12 ? (c - 8.0) / 8.0 : 0.0;
            double hi = c >= 12 ? 1.0 : (c - 3.0) / 8.0;
            return new double[] {x, y + lo, z, x + 1.0, y + hi, z + 1.0};
        }
        return null;
    }

    private static double[] rayBox(double[] o, double[] d, double[] b) {
        double tmin = 0.0;
        double tmax = Double.MAX_VALUE;
        for (int k = 0; k < 3; k++) {
            double lo = b[k];
            double hi = b[3 + k];
            if (Math.abs(d[k]) < 1e-12) {
                if (o[k] < lo || o[k] > hi) return null;
            } else {
                double t1 = (lo - o[k]) / d[k];
                double t2 = (hi - o[k]) / d[k];
                if (t1 > t2) { double s = t1; t1 = t2; t2 = s; }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return null;
            }
        }
        return new double[] {tmin, tmax};
    }

    /**
     * y=0 整层实心地面；{@code snow=true} 时**只在 x &lt; SX/2 的半边**铺雪 1 层。
     *
     * <p>为什么只铺半边：若雪层铺满整层，则任何向下射线都先碰雪板 ⇒ "首碰地面"那组**恒为空**，
     * 该组的断言就永远不能被证伪（`§一 4`：空集不得当通过）。铺半边后两组都非空，
     * 才能同时验证"缺陷域（雪板）必须归零"与"实心地面的 FUZZ 软化带属设计内"。
     */
    private static int[][] solidScene(boolean snow) {
        int[][] code = new int[SX][SY * SZ];
        for (int x = 0; x < SX; x++) {
            for (int z = 0; z < SZ; z++) {
                code[x][0 * SZ + z] = CODE_SOLID;
                if (snow && x < SX / 2) code[x][1 * SZ + z] = CODE_SNOW1;
            }
        }
        return code;
    }

    private static List<double[]> directions(int nAz, int nEl) {
        List<double[]> out = new ArrayList<>();
        for (int a = 0; a < nAz; a++) {
            double az = 2 * Math.PI * a / nAz;
            for (int e = 0; e < nEl; e++) {
                double el = Math.toRadians(-85.0 + 90.0 * e / (nEl - 1));
                out.add(new double[] {Math.cos(el) * Math.cos(az), Math.sin(el), Math.cos(el) * Math.sin(az)});
            }
        }
        return out;
    }

    private static void addIfHit(Stats s, double ref, double got) {
        if (ref >= 0 && got >= 0) s.add(ref - got);
    }

    /** 首碰点是否落在**薄板**盒内（用于把"缺陷域"与"实心地面"分开断言）。 */
    private static boolean hitsSlab(int[][] code, double[] o, double[] d, double t) {
        for (double eps = 1e-4; eps < 0.5; eps += 1e-4) {
            double s = t + eps;
            double[] p = {o[0] + d[0] * s, o[1] + d[1] * s, o[2] + d[2] * s};
            int[] cell = {(int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])};
            int c = airAt(code, cell);
            if (c != CODE_AIR) return c >= 4;
        }
        return false;
    }

    private static String between(String text, String from, String to) {
        int i = text.indexOf(from);
        if (i < 0) return "";
        int j = text.indexOf(to, i);
        return j > i ? text.substring(i, j) : text.substring(i);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static final class Stats {
        private final List<Double> xs = new ArrayList<>();
        private int count;

        void add(double v) { xs.add(v); count++; }

        int count() { return count; }
        int big() { int n = 0; for (double v : xs) if (Math.abs(v) > 0.5) n++; return n; }
        double maxAbs() { double m = 0; for (double v : xs) m = Math.max(m, Math.abs(v)); return m; }
        double median() {
            List<Double> c = new ArrayList<>(xs);
            java.util.Collections.sort(c);
            return c.isEmpty() ? 0 : c.get(c.size() / 2);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

package dev.taclight.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * 体素 DDA 的纯 JVM 几何 oracle。运行时由 GLSL 执行同一遍历，本类用于把边/角 crossing、
 * 端点豁免和材质透射语义钉成可重复契约，防止 shader 改动重新引入擦边侧邻格或硬边翻转。
 *
 * <p><b>2026-09-25 高度感知遮挡</b>：新增薄板码(4..15)的判定——{@link Visited} 现在携带
 * 射线在该格内的 y 区间，薄板格只在"y 区间与板区间相交"时遮挡；从板顶上方掠过 ⇒ 放行。
 * 这与 GLSL {@code taclight_vox_transmit} 必须逐位同源(契约对拍)。</p>
 */
public final class VoxelDda {
    /**
     * 实心格穿透软化带宽(方块):≥ 带宽严格 T=0,以下按比例放行。与 GLSL TACLIGHT_VOX_FUZZ 同源。
     * 0.20 = 2026-09-02 实机标定:0.08 不足以抑制墙柱硬影条纹在 bob 视差下的节律闪烁
     * (四臂 s0001 vs s0002,条纹边缘 |bob| 相关 0.13 残留),0.20 ≈ 视差 4-8×、
     * ≈ 20% 条纹周期;墙后遮挡内部(穿透≥1 格)仍一票否决。
     * 0.35 = 2026-09-02 体感轮:用户实测步行条纹放大仍在、跳跃前进(原版 bob 离地
     * 衰减)即不明显 → 步频 bob × 硬影缘残留闪烁,加宽半影压边缘时间对比度。
     */
    public static final double FUZZ_BLOCKS = 0.35;

    /**
     * 盒相交的数值容差(方块):剔除"擦着盒面"的退化相交。
     *
     * <p><b>2026-10-03 R20 语义变更(用户实测:半砖影子比满方块凸出去一点)</b>:本常量
     * 原注释自陈"薄板不透明,不承担半影软化职责"——那个口径正是缺陷本身:实心格有
     * FUZZ 软化(穿透 &lt;0.35 按比例放行),薄板却"任何 y 区间相交即 T=0" ⇒
     * <b>薄板比满方块更硬</b> ⇒ 同一足印下半砖/雪层的影子比满方块向外多出最多 0.35 格。
     * 现改为<b>薄板与形状盒共用同一条软化规则</b>(见 {@link #boxFraction}):
     * {@code band = min(FUZZ, 盒最薄边长)},故 0.5 厚的半砖 band=0.35 与满方块逐位一致,
     * 1/8 厚的雪层 band=0.125(穿满整个板厚仍 T=0,不重演"细雪层穿光")。
     * 本常量现在只做退化相交的剔除。</p>
     */
    public static final double SLAB_TOUCH_EPS = 1.0e-6;

    public record Cell(int x, int y, int z) { }

    /**
     * 一次真实穿入的体素访问:cell、射线进出该格的参数 {@code [entry,exit]}(世界长度,
     * 方向已归一 ⇒ t 即距离)、格内穿透长度、格内 y 区间(0..1)。
     * {@code [entry,exit]} 是盒相交判据的原生输入({@link #boxFraction})。
     */
    record Visited(Cell cell, double entry, double exit, double penetration,
                   double localYLo, double localYHi) { }

    @FunctionalInterface
    public interface Classifier {
        int code(Cell cell);
    }

    /**
     * 调色板码 → 格内盒列表(2026-10-03 R21):平铺 6 个 0..1 浮点坐标/盒;
     * {@code null} = 该码没有盒(走基础码语义)。没有它,{@code PAL[..]} 码在 Java oracle
     * 里会既不落基础码分支也不落盒分支 ⇒ 被当成全透射,诊断报出的数与着色器不一致。
     */
    @FunctionalInterface
    public interface ShapeLookup {
        float[] boxesFor(Cell cell, int code);
    }

    private VoxelDda() { }

    /** 返回起点格与终点格之间真正穿入的体素；两端格均豁免。 */
    public static List<Cell> traceIntermediate(
            double ax, double ay, double az,
            double bx, double by, double bz) {
        List<Cell> cells = new ArrayList<>();
        for (Visited visited : traceVisited(ax, ay, az, bx, by, bz)) {
            cells.add(visited.cell());
        }
        return List.copyOf(cells);
    }

    /**
     * 与 GLSL 相同的材质透射：实心格按穿透长度软化(≥{@link #FUZZ_BLOCKS} 仍一票否决，
     * 掠边按比例放行——硬 0/1 边界在 bob 亚像素移动下会翻转成条纹，实机 09-02 根因轮)；
     * 树叶 ×0.40、软植被 ×0.75 按整格计(植被体积填充,与穿透深度无关)；
     * <b>薄板(4..15)</b>按高度带与"盒内穿透长度"软化——<b>与实心格同一条规则</b>，
     * 只是带宽换成 {@code min(FUZZ, 板厚)}（2026-10-03 R20，见 {@link #boxFraction}）。
     */
    public static double transmit(
            double ax, double ay, double az,
            double bx, double by, double bz,
            Classifier classifier) {
        return transmit(ax, ay, az, bx, by, bz, classifier, null);
    }

    /** 带形状调色板的透射率(2026-10-03 R21);{@code shapes == null} 时与单项版逐位一致。 */
    public static double transmit(
            double ax, double ay, double az,
            double bx, double by, double bz,
            Classifier classifier, ShapeLookup shapes) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) return 1.0;
        double nx = dx / len, ny = dy / len, nz = dz / len;
        double transmission = 1.0;
        for (Visited visited : traceVisited(ax, ay, az, bx, by, bz)) {
            int code = classifier.code(visited.cell());
            if (code >= VoxelField.CODE_PALETTE_BASE) {
                // 形状调色板:逐盒走与薄板同一条盒规则;取不到盒 ⇒ 保守按整格(宁可误挡)
                float[] boxes = shapes == null ? null : shapes.boxesFor(visited.cell(), code);
                if (boxes == null) {
                    double f = boxFraction(ax, ay, az, nx, ny, nz, visited, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
                    if (f <= 0.0) return 0.0;
                    transmission *= f;
                    continue;
                }
                for (int i = 0; i + 5 < boxes.length; i += 6) {
                    double f = boxFraction(ax, ay, az, nx, ny, nz, visited,
                            boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], boxes[i + 4], boxes[i + 5]);
                    if (f <= 0.0) return 0.0;
                    transmission *= f;
                }
            } else if (code == VoxelField.CODE_SOLID) {
                double factor = Math.min(visited.penetration() / FUZZ_BLOCKS, 1.0);
                if (factor >= 1.0) return 0.0;
                transmission *= 1.0 - factor;
            } else if (code == VoxelField.CODE_LEAF) {
                transmission *= 0.40;
            } else if (code == VoxelField.CODE_VEG) {
                transmission *= 0.75;
            } else if (VoxelField.isSlab(code)) {
                double factor = boxFraction(ax, ay, az, nx, ny, nz, visited,
                        0.0, VoxelField.slabLow(code), 0.0,
                        1.0, VoxelField.slabHigh(code), 1.0);
                if (factor <= 0.0) return 0.0;
                transmission *= factor;
            }
        }
        return transmission;
    }

    /**
     * <b>单盒遮挡比</b>（2026-10-03 R20;GLSL {@code taclight_vox_box_fraction} 的逐行镜像）:
     * 射线在该格内的 {@code [entry, exit]} 段与格内盒 {@code [xLo,yLo,zLo]..[xHi,yHi,zHi]}
     * (格内 0..1 局部坐标)的相交长度,按 {@code band = min(FUZZ, 盒最薄边长)} 软化
     * ⇒ 返回透射倍数 ∈ [0,1]。
     *
     * <p>1.0 = 未相交(掠过,完全放行);0.0 = 盒内穿透长度 ≥ band(全挡)。
     * 取盒的<b>最薄</b>边长作带宽是刻意的:满方块 1.0 ⇒ band=FUZZ(与旧实心格逐位一致);
     * 半砖 0.5 ⇒ band=0.35(与满方块一致 ⇒ 影子不再外凸);雪 1 层 0.125 ⇒ band=0.125
     * (穿满整个板厚才算全挡 ⇒ 细雪层不会又变透明,掠边得半影)。</p>
     *
     * <p>盒与格子分离写入是为了让"薄板"(x/z 占满、只带 y 高度带)与"形状盒"(任意小盒,
     * 形状调色板用)共用同一条判据 —— 两者只在盒的取值上不同,规则本身不许分叉。</p>
     */
    static double boxFraction(double ax, double ay, double az,
                              double dirX, double dirY, double dirZ, Visited v,
                              double xLo, double yLo, double zLo,
                              double xHi, double yHi, double zHi) {
        double loX = v.cell().x() + xLo, loY = v.cell().y() + yLo, loZ = v.cell().z() + zLo;
        double hiX = v.cell().x() + xHi, hiY = v.cell().y() + yHi, hiZ = v.cell().z() + zHi;
        double tn = v.entry(), tf = v.exit();
        if (Math.abs(dirX) > 1e-9) {
            double t1 = (loX - ax) / dirX, t2 = (hiX - ax) / dirX;
            tn = Math.max(tn, Math.min(t1, t2));
            tf = Math.min(tf, Math.max(t1, t2));
        } else if (ax < loX || ax > hiX) {
            return 1.0;
        }
        if (Math.abs(dirY) > 1e-9) {
            double t1 = (loY - ay) / dirY, t2 = (hiY - ay) / dirY;
            tn = Math.max(tn, Math.min(t1, t2));
            tf = Math.min(tf, Math.max(t1, t2));
        } else if (ay < loY || ay > hiY) {
            return 1.0;
        }
        if (Math.abs(dirZ) > 1e-9) {
            double t1 = (loZ - az) / dirZ, t2 = (hiZ - az) / dirZ;
            tn = Math.max(tn, Math.min(t1, t2));
            tf = Math.min(tf, Math.max(t1, t2));
        } else if (az < loZ || az > hiZ) {
            return 1.0;
        }
        double path = tf - tn;
        if (path <= 0.0) return 1.0;
        return soften(path, Math.min(hiX - loX, Math.min(hiY - loY, hiZ - loZ)));
    }

    /** 穿透长度 → 透射倍数:band = min(FUZZ, 盒最薄边长),不足 band 按比例放行。 */
    private static double soften(double path, double thinnest) {
        double band = Math.min(FUZZ_BLOCKS, Math.max(thinnest, 1e-3));
        return 1.0 - Math.min(Math.max(path, 0.0) / band, 1.0);
    }

    /**
     * 真正穿入的体素 + 每格穿透长度 + 每格 y 区间。2026-09-25 起改为包内可见(package-private),
     * 供同包的 {@link VoxelProbe} 做"逐格 live/grid 码"诊断复用同一遍历
     * (诊断必须走与生产同一条 DDA,否则探针报的就不是渲染看到的东西)。
     */
    static List<Visited> traceVisited(
            double ax, double ay, double az,
            double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) return List.of();
        double dirX = dx / len;
        double dirY = dy / len;
        double dirZ = dz / len;
        int cx = floor(ax), cy = floor(ay), cz = floor(az);
        int lastX = floor(bx - dirX * 1e-3);
        int lastY = floor(by - dirY * 1e-3);
        int lastZ = floor(bz - dirZ * 1e-3);
        int stepX = sign(dirX), stepY = sign(dirY), stepZ = sign(dirZ);
        double deltaX = delta(dirX), deltaY = delta(dirY), deltaZ = delta(dirZ);
        double maxX = firstCrossing(ax, cx, dirX, deltaX);
        double maxY = firstCrossing(ay, cy, dirY, deltaY);
        double maxZ = firstCrossing(az, cz, dirZ, deltaZ);
        List<Visited> visited = new ArrayList<>();
        for (int guard = 0; guard < VoxelField.MAX_DIM * 3; guard++) {
            double entry = Math.min(maxX, Math.min(maxY, maxZ));
            double tieEps = Math.max(1e-6, Math.abs(entry) * 1e-6);
            boolean tiedX = Math.abs(maxX - entry) <= tieEps;
            boolean tiedY = Math.abs(maxY - entry) <= tieEps;
            boolean tiedZ = Math.abs(maxZ - entry) <= tieEps;
            if (tiedX) {
                cx += stepX;
                maxX += deltaX;
            }
            if (tiedY) {
                cy += stepY;
                maxY += deltaY;
            }
            if (tiedZ) {
                cz += stepZ;
                maxZ += deltaZ;
            }
            if (cx == lastX && cy == lastY && cz == lastZ) break;
            double exit = Math.min(maxX, Math.min(maxY, maxZ));
            double penetration = Math.max(0.0, Math.min(exit, len) - entry);
            double yEntry = ay + dirY * entry - cy;
            double yExit = ay + dirY * exit - cy;
            visited.add(new Visited(new Cell(cx, cy, cz), entry, exit, penetration,
                    Math.min(yEntry, yExit), Math.max(yEntry, yExit)));
        }
        return List.copyOf(visited);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int sign(double value) {
        return value > 0.0 ? 1 : (value < 0.0 ? -1 : 0);
    }

    private static double delta(double dir) {
        return Math.abs(dir) > 1e-9 ? 1.0 / Math.abs(dir) : 1e9;
    }

    private static double firstCrossing(double point, int cell, double dir, double delta) {
        if (Math.abs(dir) <= 1e-9) return 1e9;
        return (dir > 0.0 ? cell + 1.0 - point : point - cell) * delta;
    }
}

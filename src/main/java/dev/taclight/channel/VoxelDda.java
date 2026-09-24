package dev.taclight.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * 体素 DDA 的纯 JVM 几何 oracle。运行时由 GLSL 执行同一遍历，本类用于把边/角 crossing、
 * 端点豁免和材质透射语义钉成可重复契约，防止 shader 改动重新引入擦边侧邻格或硬边翻转。
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

    public record Cell(int x, int y, int z) { }

    /** 一次真实穿入的体素访问:cell 与射线在该格内的穿透长度(方块单位)。 */
    record Visited(Cell cell, double penetration) { }

    @FunctionalInterface
    public interface Classifier {
        int code(Cell cell);
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
     * 树叶 ×0.40、软植被 ×0.75 按整格计(植被体积填充,与穿透深度无关)。
     */
    public static double transmit(
            double ax, double ay, double az,
            double bx, double by, double bz,
            Classifier classifier) {
        double transmission = 1.0;
        for (Visited visited : traceVisited(ax, ay, az, bx, by, bz)) {
            int code = classifier.code(visited.cell());
            if (code == VoxelField.CODE_SOLID) {
                double factor = Math.min(visited.penetration() / FUZZ_BLOCKS, 1.0);
                if (factor >= 1.0) return 0.0;
                transmission *= 1.0 - factor;
            } else if (code == VoxelField.CODE_LEAF) {
                transmission *= 0.40;
            } else if (code == VoxelField.CODE_VEG) {
                transmission *= 0.75;
            }
        }
        return transmission;
    }

    /**
     * 真正穿入的体素 + 每格穿透长度。2026-09-25 起改为包内可见(package-private),
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
            visited.add(new Visited(new Cell(cx, cy, cz), penetration));
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

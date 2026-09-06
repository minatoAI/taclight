package dev.taclight.channel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 方案二契约(2026-09-06,用户批准的性能立项):遮挡距离表。
 *
 * 背景:体积光的逐采样×逐灯体素 DDA 在光池内(in-pool)是成本主体(@4K +20.6ms,
 * 占开灯开销 2/3,evidence/2026-09-06-inpool-perf/)。方案二 = 每帧按方向预计算
 * 一次"最远无遮挡距离"D(dir)(composite pass 写 colortex8 左上 512×256 equirect),
 * composite1 每采样一次查表代替 DDA 走格。
 *
 * 本契约三层钉:
 *   ① JVM oracle:hitDist 镜像 GLSL taclight_vox_hit_dist(起点格豁免/tie 全轴/
 *      穿透软化/植被累积),并与既有契约硬化过的 VoxelDda.transmit 互证——
 *      性质:对空气采样点,t < D ⟺ transmit(lamp→P) > SOFT_FLOOR。
 *   ② 表级性质:512×256 全表构建后,深影必遮挡(零漏光)/全清晰必可见(零假挡)/
 *      硬判定不一致率钉上限/近场零不一致/往返取 texel 恒等/植被双向偏差显式钉。
 *   ③ 源码钉:GLSL(core/adapter/composite/composite1)与 Java(flags/旋钮/中继/
 *      uploader/knob.ps1)的关键片段,含 GLSL 16u ↔ Java 1<<4 位镜像。
 */
public class OcclTableContract {
    static int checks;

    // ---- 与 GLSL 同源常数(改动必须两侧同步,下方源码钉负责抓失配)----
    static final double FUZZ = VoxelDda.FUZZ_BLOCKS;          // 0.35
    static final double SOFT_FLOOR = 0.45;
    static final int TABLE_X = 512, TABLE_Y = 256;
    static final double DIST_SCALE = 128.0;
    static final double MAX_DIST = 96.0;

    public static void main(String[] args) throws Exception {
        hitDistBasics();
        oracleAgainstVoxelDda();
        tableLevelProperties();
        visSemantics();
        foliageSemantics();
        knobBehavior();
        glslPins();
        javaPins();
        System.out.println("OcclTableContract: ALL PASS (" + checks + " checks)");
    }

    // =====================================================================
    // ① JVM oracle:GLSL taclight_vox_hit_dist 的逐行镜像
    // =====================================================================

    /** 场景 = byte[x][y][z],值即 VoxelField 分类码。 */
    static byte[][][] scene(int nx, int ny, int nz) {
        byte[][][] g = new byte[nx][ny][nz];
        return g;
    }

    static int dimX(byte[][][] g) { return g.length; }
    static int dimY(byte[][][] g) { return g[0].length; }
    static int dimZ(byte[][][] g) { return g[0][0].length; }

    static int codeAt(byte[][][] g, int x, int y, int z) { return g[x][y][z]; }

    static double hitDist(byte[][][] g, double ax, double ay, double az,
                          double dx, double dy, double dz, double maxDist) {
        if (g == null) return -1.0;
        int nx = dimX(g), ny = dimY(g), nz = dimZ(g);
        if (ax < 0 || ay < 0 || az < 0 || ax >= nx || ay >= ny || az >= nz) return -1.0;
        int cx = (int) Math.floor(ax), cy = (int) Math.floor(ay), cz = (int) Math.floor(az);
        int sx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int sy = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int sz = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double dX = Math.abs(dx) > 1e-9 ? 1.0 / Math.abs(dx) : 1e9;
        double dY = Math.abs(dy) > 1e-9 ? 1.0 / Math.abs(dy) : 1e9;
        double dZ = Math.abs(dz) > 1e-9 ? 1.0 / Math.abs(dz) : 1e9;
        double mX = Math.abs(dx) > 1e-9 ? (dx > 0 ? (cx + 1.0 - ax) : (ax - cx)) * dX : 1e9;
        double mY = Math.abs(dy) > 1e-9 ? (dy > 0 ? (cy + 1.0 - ay) : (ay - cy)) * dY : 1e9;
        double mZ = Math.abs(dz) > 1e-9 ? (dz > 0 ? (cz + 1.0 - az) : (az - cz)) * dZ : 1e9;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) return maxDist;   // 与 GLSL 一致:零向量不设防(调用方保证归一)
        double T = 1.0;
        for (int guard = 0; guard < 384; guard++) {
            double tNext = Math.min(mX, Math.min(mY, mZ));
            if (tNext > maxDist) return maxDist;
            double tieEps = Math.max(1e-6, Math.abs(tNext) * 1e-5);
            boolean tX = Math.abs(mX - tNext) <= tieEps;
            boolean tY = Math.abs(mY - tNext) <= tieEps;
            boolean tZ = Math.abs(mZ - tNext) <= tieEps;
            if (tX) { cx += sx; mX += dX; }
            if (tY) { cy += sy; mY += dY; }
            if (tZ) { cz += sz; mZ += dZ; }
            if (cx < 0 || cy < 0 || cz < 0 || cx >= nx || cy >= ny || cz >= nz) return maxDist;
            int code = codeAt(g, cx, cy, cz);
            if (code == VoxelField.CODE_SOLID) {
                double tExit = Math.min(mX, Math.min(mY, mZ));
                double pen = Math.max(0.0, Math.min(tExit, maxDist) - tNext);
                double f = Math.min(pen / FUZZ, 1.0);
                if (f >= 1.0) return tNext;
                T *= 1.0 - f;
            } else if (code == VoxelField.CODE_LEAF) {
                T *= 0.40;
            } else if (code == VoxelField.CODE_VEG) {
                T *= 0.75;
            }
            if (T <= SOFT_FLOOR) return tNext;
        }
        return maxDist;
    }

    static double transmit(byte[][][] g,
                           double ax, double ay, double az, double bx, double by, double bz) {
        return VoxelDda.transmit(ax, ay, az, bx, by, bz, (c) -> {
            if (c.x() < 0 || c.y() < 0 || c.z() < 0
                    || c.x() >= dimX(g) || c.y() >= dimY(g) || c.z() >= dimZ(g)) {
                return VoxelField.CODE_EMPTY;
            }
            return codeAt(g, c.x(), c.y(), c.z());
        });
    }

    // ---- equirect 镜像(与 adapter 严格互逆)----

    static double[] tableDir(int tx, int ty) {
        double lon = (tx + 0.5) / TABLE_X * 2.0 * Math.PI - Math.PI;
        double lat = (ty + 0.5) / TABLE_Y * Math.PI - Math.PI / 2.0;
        double cl = Math.cos(lat);
        return new double[]{cl * Math.cos(lon), Math.sin(lat), cl * Math.sin(lon)};
    }

    static int[] dirTexel(double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double u = Math.atan2(dz / len, dx / len) / (2.0 * Math.PI) + 0.5;
        double v = Math.asin(Math.max(-1.0, Math.min(1.0, dy / len))) / Math.PI + 0.5;
        int tx = (int) Math.floor(Math.min(0.9999, Math.max(0.0, u)) * TABLE_X);
        int ty = (int) Math.floor(Math.min(0.9999, Math.max(0.0, v)) * TABLE_Y);
        return new int[]{tx, ty};
    }

    static double visFromHit(double dHit, double dist) {
        return Math.max(0.0, Math.min(1.0, (dHit - dist) / FUZZ + 0.5));
    }

    // =====================================================================
    // 基础行为
    // =====================================================================

    static void hitDistBasics() {
        // S1:24³ 空场 + x=10 整面实心墙;灯 (2.5,12.5,12.5),+x 方向
        byte[][][] g = scene(24, 24, 24);
        for (int y = 0; y < 24; y++) for (int z = 0; z < 24; z++) g[10][y][z] = VoxelField.CODE_SOLID;
        double lampX = 2.5, lampY = 12.5, lampZ = 12.5;
        double d = hitDist(g, lampX, lampY, lampZ, 1, 0, 0, MAX_DIST);
        check(close(d, 7.5), "实心墙入格时间 = 墙面距离 7.5(10−2.5)");
        // 深墙后零漏光:任意墙后空气点全部判遮挡(方向必须归一——GLSL 前置条件)
        for (int i = 0; i < 200; i++) {
            double t = 8.6 + 0.05 * i;
            double px = lampX + t, py = lampY + (((i * 37) % 17) - 8) * 0.4, pz = lampZ + (((i * 53) % 19) - 9) * 0.4;
            if (px >= 24 || Math.floor(px) == 10) continue;
            double vx = px - lampX, vy = py - lampY, vz = pz - lampZ;
            double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double dHit = hitDist(g, lampX, lampY, lampZ, vx / vl, vy / vl, vz / vl, MAX_DIST);
            check(dHit <= t, "墙后空气点 (t=" + fmt(t) + ") 命中距离必须 ≤ 采样距离");
        }
        // 无遮挡方向 → maxDist 哨兵;栅格无效 → -1;灯出格 → -1
        byte[][][] open = scene(24, 24, 24);
        check(close(hitDist(open, 3.5, 3.5, 3.5, 1, 0, 0, MAX_DIST), MAX_DIST),
                "全空场景沿 +x 无命中 → maxDist 哨兵");
        check(close(hitDist(null, 3.5, 3.5, 3.5, 1, 0, 0, MAX_DIST), -1.0),
                "栅格无效(null)→ -1(消费侧回退可见)");
        check(close(hitDist(open, -0.5, 3.5, 3.5, 1, 0, 0, MAX_DIST), -1.0),
                "灯在栅格外 → -1(不假遮挡)");
    }

    /** oracle 互证:hitDist 与契约硬化过的 VoxelDda.transmit 必须满足
     *  t < D ⟺ transmit(lamp→P) > SOFT_FLOOR(P 限空气点,双端豁免才可比)。 */
    static void oracleAgainstVoxelDda() {
        Random rnd = new Random(20260906L);
        double agree = 0, total = 0;
        for (int sceneIdx = 0; sceneIdx < 3; sceneIdx++) {
            byte[][][] g = scene(24, 24, 24);
            // 随机撒实心块与植被簇(确定性种子)
            for (int k = 0; k < 40; k++) {
                int bx = 4 + rnd.nextInt(16), by = 4 + rnd.nextInt(16), bz = 4 + rnd.nextInt(16);
                int code = k % 5 == 0 ? VoxelField.CODE_LEAF
                        : (k % 7 == 0 ? VoxelField.CODE_VEG : VoxelField.CODE_SOLID);
                int w = 1 + rnd.nextInt(3);
                for (int ix = 0; ix < w; ix++)
                    for (int iy = 0; iy < w; iy++)
                        for (int iz = 0; iz < w; iz++)
                            if (bx + ix < 24 && by + iy < 24 && bz + iz < 24)
                                g[bx + ix][by + iy][bz + iz] = (byte) code;
            }
            double lampX = 2.5, lampY = 12.5, lampZ = 12.5;
            g[(int) lampX][(int) lampY][(int) lampZ] = 0;   // 灯格保持空气
            for (int i = 0; i < 800; i++) {
                double dx = rnd.nextDouble() - 0.5, dy = rnd.nextDouble() - 0.5, dz = rnd.nextDouble() - 0.5;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 0.2) continue;
                dx /= len; dy /= len; dz /= len;
                double t = 1.0 + rnd.nextDouble() * 15.0;
                double px = lampX + dx * t, py = lampY + dy * t, pz = lampZ + dz * t;
                // 采样点必须离任何格边界 ≥0.02 且在栅格内(端点豁免才严格可比)
                if (px < 0.5 || py < 0.5 || pz < 0.5 || px > 23.5 || py > 23.5 || pz > 23.5) continue;
                if (nearBoundary(px) || nearBoundary(py) || nearBoundary(pz)) continue;
                if (codeAt(g, (int) px, (int) py, (int) pz) != VoxelField.CODE_EMPTY) continue;
                double dHit = hitDist(g, lampX, lampY, lampZ, dx, dy, dz, MAX_DIST);
                double tr = transmit(g, lampX, lampY, lampZ, px, py, pz);
                boolean ddaVisible = tr > SOFT_FLOOR + 1e-12;
                boolean tableVisible = dHit > t + 1e-9;
                total += 1.0;
                if (ddaVisible == tableVisible) agree += 1.0;
            }
        }
        check(total >= 1000, "互证样本量充足(实测 " + (int) total + ")");
        check(agree == total, "hitDist 与 VoxelDda.transmit 全量一致(不一致 "
                + fmt(total - agree) + "/" + fmt(total) + ")");
    }

    static boolean nearBoundary(double v) {
        double f = v - Math.floor(v);
        return f < 0.02 || f > 0.98;
    }

    // =====================================================================
    // ② 表级性质(全 512×256 表构建后的行为)
    // =====================================================================

    static double[][] buildTable(byte[][][] g, double lampX, double lampY, double lampZ) {
        double[][] table = new double[TABLE_X][TABLE_Y];
        for (int tx = 0; tx < TABLE_X; tx++) {
            for (int ty = 0; ty < TABLE_Y; ty++) {
                double[] dir = tableDir(tx, ty);
                double dHit = hitDist(g, lampX, lampY, lampZ, dir[0], dir[1], dir[2], MAX_DIST);
                if (dHit < 0.0) dHit = 1e4;   // 与 composite.fsh 构建侧同款哨兵
                table[tx][ty] = Math.min(1.0, dHit / DIST_SCALE);
            }
        }
        return table;
    }

    static double tableVis(double[][] table, double dx, double dy, double dz, double t) {
        int[] texel = dirTexel(dx, dy, dz);
        return visFromHit(table[texel[0]][texel[1]] * DIST_SCALE, t);
    }

    static void tableLevelProperties() {
        // S2 混合场景:门洞墙 + 柱 + 植被(确定性)
        byte[][][] g = scene(24, 24, 24);
        for (int y = 0; y < 24; y++)
            for (int z = 0; z < 24; z++)
                if (!(y >= 10 && y <= 14 && z >= 10 && z <= 14)) g[10][y][z] = VoxelField.CODE_SOLID;
        for (int y = 8; y <= 11; y++) for (int z = 4; z <= 7; z++) g[16][y][z] = VoxelField.CODE_SOLID;
        for (int y = 15; y <= 20; y++) for (int z = 16; z <= 21; z++) g[5][y][z] = VoxelField.CODE_LEAF;
        double lampX = 2.5, lampY = 12.5, lampZ = 12.5;
        double[][] table = buildTable(g, lampX, lampY, lampZ);

        // 往返恒等:随机 texel → dir → texel 必须原样(构建/消费同一 texel,错位=系统性影子位移)
        Random rnd = new Random(20260907L);
        for (int i = 0; i < 5000; i++) {
            int tx = rnd.nextInt(TABLE_X), ty = rnd.nextInt(TABLE_Y);
            double[] dir = tableDir(tx, ty);
            int[] back = dirTexel(dir[0], dir[1], dir[2]);
            check(back[0] == tx && back[1] == ty,
                    "equirect 往返恒等 texel(" + tx + "," + ty + ")→(" + back[0] + "," + back[1] + ")");
        }

        // 深影必遮挡(零漏光):4 角全 0 透射的采样点,查表必须 ≤0.003
        // 全清晰必可见(零假挡):4 角透射全 > SOFT_FLOOR 的采样点,查表必须 > 0.5
        int leakCandidates = 0, clearCandidates = 0, leaks = 0, falseBlocks = 0;
        int hardMismatch = 0, hardTotal = 0, nearMismatch = 0, nearTotal = 0;
        for (int i = 0; i < 20000; i++) {
            double t = 1.0 + rnd.nextDouble() * 18.0;
            double dx = rnd.nextDouble() - 0.5, dy = rnd.nextDouble() * 0.4 - 0.2, dz = rnd.nextDouble() - 0.5;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.2) continue;
            dx /= len; dy /= len; dz /= len;
            double px = lampX + dx * t, py = lampY + dy * t, pz = lampZ + dz * t;
            if (px < 0.5 || py < 0.5 || pz < 0.5 || px > 23.5 || py > 23.5 || pz > 23.5) continue;
            if (nearBoundary(px) || nearBoundary(py) || nearBoundary(pz)) continue;
            if (codeAt(g, (int) px, (int) py, (int) pz) != VoxelField.CODE_EMPTY) continue;
            double tv = tableVis(table, dx, dy, dz, t);
            double tr = transmit(g, lampX, lampY, lampZ, px, py, pz);
            boolean hardTable = tv > 0.003, hardDda = tr > 0.003;
            hardTotal++;
            if (hardTable != hardDda) {
                hardMismatch++;
                if (t < 4.0) nearMismatch++;
            }
            if (t < 4.0) nearTotal++;
            // 角点探针(±半 texel 的 lon/lat 角)——深影/全清晰只在这两个保守集合里断言
            double[] corners = cornerTransmits(g, lampX, lampY, lampZ, dx, dy, dz, t);
            boolean allBlocked = corners[0] <= 0.0 && corners[1] <= 0.0 && corners[2] <= 0.0 && corners[3] <= 0.0;
            boolean allClear = corners[0] > SOFT_FLOOR && corners[1] > SOFT_FLOOR
                    && corners[2] > SOFT_FLOOR && corners[3] > SOFT_FLOOR;
            if (allBlocked) {
                leakCandidates++;
                if (tv > 0.003) leaks++;
            }
            if (allClear && tr > SOFT_FLOOR) {
                clearCandidates++;
                if (tv <= 0.5) falseBlocks++;
            }
        }
        check(leakCandidates > 300, "深影样本量充足(实测 " + leakCandidates + ")");
        check(leaks == 0, "深影(4 角全挡)零漏光(实测漏 " + leaks + ")");
        check(clearCandidates > 300, "全清晰样本量充足(实测 " + clearCandidates + ")");
        check((double) falseBlocks / Math.max(clearCandidates, 1) <= 0.02,
                "假挡率 ≤2%(NEAREST 重采样的固有角度歧义带,保守方向不漏光;实测挡 "
                        + falseBlocks + "/" + clearCandidates + ")");
        check(hardTotal >= 8000, "不一致率样本量充足(实测 " + hardTotal + ")");
        check((double) hardMismatch / hardTotal <= 0.025,
                "硬判定不一致率 ≤2.5%(实测 " + hardMismatch + "/" + hardTotal
                        + " = " + fmt(100.0 * hardMismatch / hardTotal) + "%)");
        check(nearMismatch == 0, "近场(t<4)零不一致(实测 " + nearMismatch + "/" + nearTotal + ")");
    }

    /** 采样方向的表 texel 的 4 个 lon/lat 角方向,各自 lamp→(同 t 端点) 的 DDA 透射。 */
    static double[] cornerTransmits(byte[][][] g, double lx, double ly, double lz,
                                    double dx, double dy, double dz, double t) {
        int[] texel = dirTexel(dx, dy, dz);
        double halfLon = Math.PI / TABLE_X, halfLat = Math.PI / (2.0 * TABLE_Y);
        double lon0 = (texel[0] + 0.5) / TABLE_X * 2.0 * Math.PI - Math.PI;
        double lat0 = (texel[1] + 0.5) / TABLE_Y * Math.PI - Math.PI / 2.0;
        double[] out = new double[4];
        int k = 0;
        for (int sLon = -1; sLon <= 1; sLon += 2)
            for (int sLat = -1; sLat <= 1; sLat += 2) {
                double lon = lon0 + sLon * halfLon, lat = lat0 + sLat * halfLat;
                double cl = Math.cos(lat);
                double cx = cl * Math.cos(lon), cy = Math.sin(lat), cz = cl * Math.sin(lon);
                out[k++] = transmit(g, lx, ly, lz,
                        lx + cx * t, ly + cy * t, lz + cz * t);
            }
        return out;
    }

    // =====================================================================
    // vis 语义与植被
    // =====================================================================

    static void visSemantics() {
        double dHit = 7.5;
        check(close(visFromHit(dHit, 7.0), 1.0), "命中带前(t ≤ D−FUZZ/2)全可见");
        check(close(visFromHit(dHit, 7.5), 0.5), "命中距离处半透(FUZZ 软带中点)");
        check(close(visFromHit(dHit, 8.0), 0.0), "命中带后(t ≥ D+FUZZ/2)全遮挡");
        check(close(visFromHit(1e4 * DIST_SCALE / DIST_SCALE, 96.0), 1.0),
                "构建侧哨兵(1e4→归一 1.0→128m)在半径内恒可见");
    }

    static void foliageSemantics() {
        // 树叶墙(x=10 起):第一层叶子即 0.4 ≤ 0.45 → D = 叶墙入格 7.5
        // (SOFT_FLOOR 0.45 < 单层树叶透射 0.4 不成立——单层即触发,
        //  表对植被一致偏暗(0 vs 旧 0.4/0.16)= 保守方向,不存在偏透漏光)
        byte[][][] g = scene(24, 24, 24);
        for (int y = 0; y < 24; y++) for (int z = 0; z < 24; z++) {
            g[10][y][z] = VoxelField.CODE_LEAF;
            g[11][y][z] = VoxelField.CODE_LEAF;
        }
        double d = hitDist(g, 2.5, 12.5, 12.5, 1, 0, 0, MAX_DIST);
        check(close(d, 7.5), "双层树叶:第一层即累积 ≤SOFT_FLOOR(D=叶墙入格 7.5)");
        double tv = visFromHit(d, 10.0);
        double oldT = transmit(g, 2.5, 12.5, 12.5, 12.5, 12.5, 12.5);
        check(tv <= 0.02, "叶墙后查表判遮挡(≤0.02,实测 " + fmt(tv) + ")");
        check(oldT > 0.003 && oldT < 0.2,
                "旧行为双层叶墙后仅部分透射(T=" + fmt(oldT) + ",表偏暗=保守方向)");
        // 单层树叶同样触发(0.4 ≤ 0.45)——显式钉死"单层也判遮挡"
        byte[][][] g1 = scene(24, 24, 24);
        for (int y = 0; y < 24; y++) for (int z = 0; z < 24; z++) g1[10][y][z] = VoxelField.CODE_LEAF;
        double d1 = hitDist(g1, 2.5, 12.5, 12.5, 1, 0, 0, MAX_DIST);
        check(close(d1, 7.5), "单层树叶即触发(0.4 ≤ 0.45)→ D=叶墙入格(表一致偏暗)");
    }

    // =====================================================================
    // ③ 源码钉(GLSL / Java / 工具)
    // =====================================================================

    static void glslPins() throws Exception {
        String core = Files.readString(Path.of("pack/shaders/lib/taclight_core.glsl"));
        check(core.contains("#define TACLIGHT_FLAG_OCCL_TABLE") && core.contains("16u"),
                "GLSL:core 定义 bit4=16u(位3已被时序探针占用)");
        check(core.contains("float taclight_vox_hit_dist(vec3 worldA, vec3 dir, float maxDist)"),
                "GLSL:core 定义方向行走 hit_dist(与 vox_transmit 同源遍历)");
        check(core.contains("#define TACLIGHT_OCCL_SOFT_FLOOR 0.45"), "GLSL:core 植被累积遮挡阈值 0.45");
        check(core.contains("#ifndef TACLIGHT_OCCL_TABLE_AT"), "GLSL:core 表行取数走适配层宏(核心零 colortex)");
        check(core.contains("float taclight_occl_table_vis(uint lampIdx, vec3 relWorld)"),
                "GLSL:core 查表 vis 消费入口");
        check(core.contains("clamp((dHit - dist) / TACLIGHT_VOX_FUZZ + 0.5, 0.0, 1.0)"),
                "GLSL:core 查表 vis 与 DDA 同宽 FUZZ 软带");
        check(!core.contains("colortex8"), "GLSL:core 禁止 colortex 字面量(布局知识归适配层,ShaderCore 分层契约)");

        String adapter = Files.readString(Path.of("pack/shaders/lib/taclight_adapter.glsl"));
        check(adapter.contains("uniform sampler2D colortex8"), "GLSL:adapter 声明表缓冲 colortex8");
        check(adapter.contains("#define TACLIGHT_OCCL_TABLE_AT(rel) taclight_occl_table_row(rel)"),
                "GLSL:adapter 注入表行取数宏(core 未适配包回退全哨兵)");
        check(adapter.contains("texelFetch(colortex8"), "GLSL:adapter NEAREST texelFetch(无接缝滤波问题)");
        check(adapter.contains("#define TACLIGHT_OCCL_TABLE_SIZE_X 512.0")
                && adapter.contains("#define TACLIGHT_OCCL_TABLE_SIZE_Y 256.0"),
                "GLSL:adapter 表区 512×256(854×480 的 B 端视口也放得下)");
        check(adapter.contains("#define TACLIGHT_OCCL_DIST_SCALE 128.0"), "GLSL:adapter D 归一尺度 128");
        check(adapter.contains("vec3 taclight_occl_table_dir(vec2 uv01)"),
                "GLSL:adapter 构建侧方向编码(与消费侧互逆)");

        String comp = Files.readString(Path.of("pack/shaders/composite.fsh"));
        check(comp.contains("/* DRAWBUFFERS:08 */"), "GLSL:composite MRT 输出 colortex0+colortex8");
        check(comp.contains("layout(location = 1) out vec4 taclightOcclOut"),
                "GLSL:composite location=1 表输出(与 composite3 MRT 同款家规)");
        check(comp.contains("const int colortex8Format = RGBA16"), "GLSL:composite 声明 colortex8 格式(家规:注释块常量)");
        check(comp.contains("taclight_build_occl_table();"), "GLSL:composite main 顶部建表(先于任何 DBG 早退)");
        check(comp.contains("taclight_vox_hit_dist(L.posRadius.xyz, tdir, 96.0)"),
                "GLSL:composite 表构建按方向走 hit_dist(maxDist=96=VL_MAX/半径上限)");
        check(comp.contains("if (dHit < 0.0) dHit = 1e4;"), "GLSL:composite 栅格无效→哨兵(回退可见,不假遮挡)");
        check(comp.contains("TACLIGHT_OCCL_TABLE_SIZE_X && tpx.y < TACLIGHT_OCCL_TABLE_SIZE_Y"),
                "GLSL:composite 表区越界保护(表区外写哨兵)");

        String comp1 = Files.readString(Path.of("pack/shaders/composite1.fsh"));
        check(comp1.contains("taclight_occl_table_vis(i, spWorld - L.posRadius.xyz)"),
                "GLSL:composite1 表模式查表(世界域灯→采样,与 vox_transmit 同坐标)");
        check(comp1.contains("taclight_vox_transmit(L.posRadius.xyz, spWorld)"),
                "GLSL:composite1 保留原逐采样 DDA 回退(off 路径/灯4..7/小视口)");
        check(comp1.contains("occlTableOn && i < 4u"), "GLSL:composite1 仅前 4 灯走表(每 texel 4 通道)");
        check(comp1.contains("(flags & TACLIGHT_FLAG_OCCL_TABLE) != 0u"), "GLSL:composite1 表模式门 = 头部 bit4");
        check(comp1.contains("viewWidth >= TACLIGHT_OCCL_TABLE_SIZE_X"),
                "GLSL:composite1 视口 < 表区时回退(防越界取数)");
        check(comp1.contains("#define TACLIGHT_VL_STEPS 64"),
                "GLSL:composite1 步数回 64(查表化把 DDA 预算买回,采样质量恢复 09-05 定案)");
    }

    static void javaPins() throws Exception {
        check(SpotlightBufferLayout.FLAG_OCCL_TABLE == 16, "Java:FLAG_OCCL_TABLE = 16(与 GLSL 16u 逐位镜像)");

        String layout = Files.readString(Path.of(
                "src/main/java/dev/taclight/channel/SpotlightBufferLayout.java"));
        check(layout.contains("FLAG_OCCL_TABLE = 1 << 4"), "Java:布局类 bit4 常量(1<<4)");

        String tune = Files.readString(Path.of(
                "src/main/java/dev/taclight/channel/LightTuneOverride.java"));
        check(tune.contains("private static volatile boolean occlActive = true;"),
                "Java:!occl 默认开(性能定案,重启回默认)");
        check(tune.contains("public static String configureOccl(String arg)"), "Java:configureOccl 入口存在");
        check(tune.contains("public static boolean occlTable()"), "Java:onFrame 读取口存在");

        String uploader = Files.readString(Path.of(
                "src/main/java/dev/taclight/channel/ClientSpotlightUploader.java"));
        check(uploader.contains("LightTuneOverride.occlTable() && voxelGrid != null"),
                "Java:仅体素栅格有效时置位(无效=原逐采样 -1→可见 回退,语义逐位一致)");
        check(uploader.contains("extraFlags |= SpotlightBufferLayout.FLAG_OCCL_TABLE"),
                "Java:头部 flags OR bit4");

        String relay = Files.readString(Path.of(
                "src/main/java/dev/taclight/client/DebugCommandRelay.java"));
        check(relay.contains("startsWith(\"!occl\")"), "Java:中继 !occl 分发");
        check(relay.contains("configureOccl(arg)"), "Java:中继接 configureOccl");

        String knob = Files.readString(Path.of("tools/knob.ps1"));
        check(knob.contains("|cone|occl|scat|"), "工具:knob.ps1 白名单含 occl(裸词自动补 !)");
        check(knob.contains("!occl"), "工具:knob.ps1 帮助文本含 !occl");
    }

    // ---- !occl 旋钮行为(内存覆盖层语义,与 beamonly 同族)----

    static void knobBehavior() {
        check(LightTuneOverride.occlTable(), "旋钮:默认开(初始态)");
        String s1 = LightTuneOverride.configureOccl("");
        check(s1.contains("occl=on"), "旋钮:无参=status(on)");
        String s2 = LightTuneOverride.configureOccl("off");
        check(s2.contains("occl=off") && !LightTuneOverride.occlTable(), "旋钮:off 关闭并回显");
        String s3 = LightTuneOverride.configureOccl("on");
        check(s3.contains("occl=on") && LightTuneOverride.occlTable(), "旋钮:on 开启并回显");
        String s4 = LightTuneOverride.configureOccl("wat");
        check(s4.startsWith("bad arg"), "旋钮:坏参数报错不变更状态");
        LightTuneOverride.configureOccl("on");
    }

    static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 1000.0) / 1000.0);
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
    }
}

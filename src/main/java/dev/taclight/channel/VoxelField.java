package dev.taclight.channel;

import java.util.List;

/**
 * 体素遮挡栅格纯逻辑(2026-09-01 深夜④,DDA 立项:用户实测墙后地面漏光,
 * 满足 AGENTS §4 条件项"实机真见漏光才立项")。
 *
 * <p>1 格 = 1 体素(直接对齐原版方块网格,零重采样误差);每体素 2bit 分类码
 * 与 pack/shaders/block.properties 同源:0 空 / 1 软植被(SSO 系数 0.25)/
 * 2 树叶(0.60)/ 3 实心(1.0)。数据由模组每 tick 采样填充、经 SSBO 尾段
 * (SpotlightBufferLayout.OFF_VOX_*)上传,GLSL 侧做 Amanatides-Woo DDA 光线
 * 步进根治屏幕空间 SSO 的"视锥外遮挡者不投影"漏光。</p>
 *
 * <p>2026-09-18 雪地方格阵列根因轮:码 0 的语义从"空气/流体"扩为
 * <b>"空气 / 流体 / 无碰撞形方块(绊线/铁轨/蛛网) / 遮挡形实心占比 ≤ 0.25 的薄片
 * (雪 1-2 层、地毯、活板门等)"</b>,码 1 兼收"占比 0.25~0.9 的部分高度方块"
 * (半砖 bottom/top、楼梯 bottom/top、雪 3-7 层等)。
 * 2bit 格式本身未动(4 码不变);C 口径(守卫 1 coll 空⇒EMPTY / 守卫 2 coll maxY&gt;1.0⇒SOLID /
 * 其余按 <b>occ 遮挡形占比</b>分档)见 {@link VoxelClassifier},判据更正见
 * {@code docs/判据更正-2026-09-18-体素分档顶高口径.md}。</p>
 *
 * <p>纯 JVM 可测(契约 = VoxelFieldContract + VoxelClassifyContract);方块采样与节流在
 * dev.taclight.client.VoxelGrid。本类自研,零第三方照搬(红线 1)。</p>
 */
public final class VoxelField {
    /** 栅格单轴最大格数(SSBO 容量与 DDA 步数上限同源;128³×4bit = 1MB)。 */
    public static final int MAX_DIM = 128;
    /** 每体素位数(2026-09-25 高度感知遮挡:2bit → 4bit,16 码)。 */
    public static final int BITS_PER_VOXEL = 4;
    /** 每个 uint 装几个体素。 */
    public static final int VOXELS_PER_UINT = 32 / BITS_PER_VOXEL;
    /** 每轴 128 格、4bit/体素时的 uint 总数(= SpotlightBufferLayout.VOX_MAX_UINTS)。 */
    public static final int VOX_MAX_UINTS = MAX_DIM * MAX_DIM * MAX_DIM / VOXELS_PER_UINT;

    public static final int CODE_EMPTY = 0;
    public static final int CODE_VEG = 1;
    public static final int CODE_LEAF = 2;
    public static final int CODE_SOLID = 3;
    /** 底薄板码基:4..11 = 占满 XZ 足印、从 y=0 起、顶高 = (code−3)/8。 */
    public static final int CODE_SLAB_BOTTOM_BASE = 4;
    /** 顶薄板码基:12..15 = 占满 XZ 足印、到 y=1 止、底高 = (code−8)/8。 */
    public static final int CODE_SLAB_TOP_BASE = 12;

    /** 底薄板码:{@code eighths} ∈ 1..7(顶高 = eighths/8)。 */
    public static int slabBottomCode(int eighths) {
        return CODE_SLAB_BOTTOM_BASE + (eighths - 1);
    }

    /** 顶薄板码:{@code eighths} ∈ 4..7(底高 = eighths/8)。 */
    public static int slabTopCode(int eighths) {
        return CODE_SLAB_TOP_BASE + (eighths - 4);
    }

    /** 是否薄板码(4..15;0..3 为 空/植被/树叶/实心 四个基础码)。 */
    public static boolean isSlab(int code) {
        return code >= CODE_SLAB_BOTTOM_BASE;
    }

    /** 薄板占据的格内 y 区间下端(0..1)。 */
    public static double slabLow(int code) {
        return code >= CODE_SLAB_TOP_BASE ? (code - CODE_SLAB_TOP_BASE + 4) / 8.0 : 0.0;
    }

    /** 薄板占据的格内 y 区间上端(0..1)。 */
    public static double slabHigh(int code) {
        return code >= CODE_SLAB_TOP_BASE ? 1.0 : (code - CODE_SLAB_BOTTOM_BASE + 1) / 8.0;
    }

    /** 栅格盒:origin = 方块角点世界坐标(整数格),dims = 各轴格数(≤MAX_DIM)。 */
    public static final class Box {
        public final int ox, oy, oz, dx, dy, dz;

        public Box(int ox, int oy, int oz, int dx, int dy, int dz) {
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.dx = dx; this.dy = dy; this.dz = dz;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Box b)) return false;
            return ox == b.ox && oy == b.oy && oz == b.oz && dx == b.dx && dy == b.dy && dz == b.dz;
        }
        @Override public int hashCode() {
            return (ox * 73856093) ^ (oy * 19349663) ^ (oz * 83492791) ^ (dx * 31 + dy * 7 + dz);
        }
        @Override public String toString() {
            return "(" + ox + "," + oy + "," + oz + ")+" + dx + "x" + dy + "x" + dz;
        }
    }

    /** 上传快照(data 为复用缓冲的只读视图,仅渲染线程消费)。 */
    public record Snapshot(float ox, float oy, float oz, int dx, int dy, int dz, int[] data, long version) {
        /** 实际占用 uint 数(上传只传有数据区)。 */
        public int usedUints() { return (dx * dy * dz + VOXELS_PER_UINT - 1) / VOXELS_PER_UINT; }
    }

    private VoxelField() {}

    /**
     * 灯集合 → 覆盖盒:∪ [pos−r, pos+r],floor/ceil 对齐方块格。
     * 任一轴跨度 &gt; MAX_DIM 时以并集中心钳到 MAX_DIM——被截断在外的灯由
     * GLSL 端点出界检查回退 SSO(不会假遮挡,只会退化为旧行为)。
     */
    public static Box boxFor(List<SpotlightData> lights) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (SpotlightData l : lights) {
            float r = l.radius();
            minX = Math.min(minX, l.posX() - r); maxX = Math.max(maxX, l.posX() + r);
            minY = Math.min(minY, l.posY() - r); maxY = Math.max(maxY, l.posY() + r);
            minZ = Math.min(minZ, l.posZ() - r); maxZ = Math.max(maxZ, l.posZ() + r);
        }
        int ox = floorI(minX), oy = floorI(minY), oz = floorI(minZ);
        int dx = ceilI(maxX) - ox, dy = ceilI(maxY) - oy, dz = ceilI(maxZ) - oz;
        if (dx > MAX_DIM) { dx = MAX_DIM; ox = floorI((minX + maxX) * 0.5 - MAX_DIM * 0.5); }
        if (dy > MAX_DIM) { dy = MAX_DIM; oy = floorI((minY + maxY) * 0.5 - MAX_DIM * 0.5); }
        if (dz > MAX_DIM) { dz = MAX_DIM; oz = floorI((minZ + maxZ) * 0.5 - MAX_DIM * 0.5); }
        return new Box(ox, oy, oz, dx, dy, dz);
    }

    /** 线性体素索引(GLSL 侧镜像:x + y*dx + z*dx*dy)。局部坐标,调用方保证界内。 */
    public static int voxelIndex(Box b, int x, int y, int z) {
        return x + y * b.dx + z * b.dx * b.dy;
    }

    /** 4bit 打包:第 idx 个体素占 uint[idx&gt;&gt;3] 的 (idx&amp;7)×4 位,写覆盖旧码。 */
    public static void pack(Box b, int x, int y, int z, int code, int[] data) {
        int idx = voxelIndex(b, x, y, z);
        int sh = (idx & (VOXELS_PER_UINT - 1)) * BITS_PER_VOXEL;
        int i = idx / VOXELS_PER_UINT;
        data[i] = (data[i] & ~(15 << sh)) | ((code & 15) << sh);
    }

    public static int unpack(Box b, int x, int y, int z, int[] data) {
        int idx = voxelIndex(b, x, y, z);
        return (data[idx / VOXELS_PER_UINT] >> ((idx & (VOXELS_PER_UINT - 1)) * BITS_PER_VOXEL)) & 15;
    }

    /** 出实心步进(沿 −dir 回退的单步距离)。 */
    public static final double DESOLIDIFY_STEP = 0.05;
    /** 出实心最大回退(超过仍无出路 = 原样 fail-safe,行为与今日一致)。 */
    public static final double DESOLIDIFY_MAX = 2.0;

    /**
     * 灯头出实心钳制(2026-09-05 贴墙穿墙根因:持枪贴墙时枪口灯位被推进墙体素格,
     * DDA 起点格豁免跳过灯所在墙格 → 整墙对该灯透明,光照到墙后)。
     * 灯位落实心格 → 沿 −dir 退到首个非实心格,恢复"DDA 起点格必为空气"前提。
     * 纯函数(JVM 可测,无 MC 依赖);盒外/格内非实心/零方向/2m 内无出路 →
     * 原样返回(fail-safe 零回归)。注意:只认 CODE_SOLID,树叶/植被格不管
     * (单格透射误差小,保持本次 scope 最小)。
     */
    public static double[] clampOutOfSolid(Box b, int[] data,
                                           double x, double y, double z,
                                           double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1e-6)) return new double[]{x, y, z};
        double nx = dx / len, ny = dy / len, nz = dz / len;
        if (codeAt(b, data, x, y, z) != CODE_SOLID) return new double[]{x, y, z};
        for (double d = DESOLIDIFY_STEP; d <= DESOLIDIFY_MAX + 1e-9; d += DESOLIDIFY_STEP) {
            double px = x - nx * d, py = y - ny * d, pz = z - nz * d;
            int c = codeAt(b, data, px, py, pz);
            if (c < 0) return new double[]{x, y, z}; // 出盒:占用未知,原样 fail-safe
            if (c != CODE_SOLID) return new double[]{px, py, pz};
        }
        return new double[]{x, y, z};
    }

    /** 世界坐标 → 体素分类码;−1 = 盒外(占用未知)。 */
    private static int codeAt(Box b, int[] data, double x, double y, double z) {
        int lx = (int) Math.floor(x) - b.ox;
        int ly = (int) Math.floor(y) - b.oy;
        int lz = (int) Math.floor(z) - b.oz;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= b.dx || ly >= b.dy || lz >= b.dz) return -1;
        return unpack(b, lx, ly, lz, data);
    }

    private static int floorI(double v) { return (int) Math.floor(v); }
    private static int ceilI(double v) { return (int) Math.ceil(v); }
}

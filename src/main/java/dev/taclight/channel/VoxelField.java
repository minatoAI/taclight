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
        return boxFor(lights, 1.0f);
    }

    /** 盒收缩系数下限(0.1 = 只覆盖射程的 10%,防退化盒)。 */
    public static final float MIN_BOX_FRACTION = 0.1f;

    /**
     * 灯集合 → 覆盖盒,半径按 {@code fraction} 收缩(2026-09-25 性能轮,取证见
     * {@code docs/evidence/2026-09-25-voxel-box/})。
     *
     * <p><b>为什么缩是安全的(有上界的取舍,不是拍脑袋)</b>:灯自身的衰减
     * ({@code taclight_core.glsl:191-197},K=20)在 <b>0.5r 处只剩 12.5%、0.8r 处 2.6%、
     * 1.0r 处恰好 0</b> ⇒ 盒外那段本来就没多少光,而"盒外 ⇒ 回退 SSO"只会退化为旧行为,
     * 漏判的亮度上界 = 该距离的衰减值。盒心仍是灯位 ⇒ 灯永远在盒内。</p>
     *
     * <p>NaN / ≤0 / &gt;1 一律回 1.0(保持旧行为);低于 {@link #MIN_BOX_FRACTION} 钳到下限。</p>
     */
    public static Box boxFor(List<SpotlightData> lights, float fraction) {
        float f = fraction;
        if (!(f > 0f) || f > 1f) f = 1.0f;
        if (f < MIN_BOX_FRACTION) f = MIN_BOX_FRACTION;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (SpotlightData l : lights) {
            float r = l.radius() * f;
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

    /** 锥盒额外余量(格):①覆盖灯位起点格 ②"灯落墙内沿 −dir 回退"通道必须留在盒内
     *  (clampOutOfSolid 的 DESOLIDIFY_MAX=2.0;余量 3 足够),否则那条 2026-09-05 的修复会静默失效。 */
    public static final float CONE_BOX_MARGIN = 3.0f;

    /** cosOuter 低于此值视为锥过宽(≈±78°,tanθ 爆炸且几乎无收益)⇒ 该灯退回球盒(安全侧)。 */
    public static final float CONE_MIN_COS_OUTER = 0.2f;

    /**
     * 锥形盒是否默认启用(2026-09-25 性能轮定案:启用)。
     *
     * <p>真机同实例同姿势对照(证据 {@code docs/evidence/2026-09-25-voxel-box/},夜里锁时间):
     * 重建 7.36 ms → 0.80–1.40 ms、CPU 体素相位 2.48 → 0.27 ms、p1Low 36.0–37.4 → 49.0–49.9;
     * 而画面差异(远景区 meanDiff 0.93)<b>落在两组同状态控制对(0.58 / 1.08)之间</b>⇒ 与抖动不可区分。
     * 对照:均匀缩盒(0.8r)在同一场景是 5.6 倍抖动且整片远景变亮 ⇒ 已否。</p>
     */
    public static final boolean DEFAULT_CONE_BOX = true;

    /**
     * 灯集合 → 覆盖盒:每灯取<b>光锥</b>的轴对齐包围盒(2026-09-25 性能轮第二步)。
     *
     * <p><b>为什么锥盒对被照到的地方是"无损"的</b>:光只能照到锥内(锥外 spot=0)⇒ 任何被照到的
     * 片元都在锥内 ⇒ 灯→片元整条射线都在锥内(凸集)⇒ 该射线的遮挡体也都在锥内 ⇒
     * <b>遮挡判定与全尺寸球盒逐格一致</b>;盒外只可能是"本来就照不到"的方向(身后/侧后方)。
     * 这正是<b>均匀缩盒做不到</b>的:均匀缩会把"光能照到的远处"一起切掉——真机实测整片远景地面
     * 变亮(热图见 {@code docs/evidence/2026-09-25-voxel-box/})。</p>
     *
     * <p>几何:锥 = {pos + t·dir + u : t∈[0,L], |u| ≤ t·tanθ}(θ = 外锥半角,L = 灯半径)。
     * 沿轴 i 的极值 = {@code pos_i + L·max(0, dir_i ± tanθ)}——因 |u_i| ≤ |u| ≤ t·tanθ,
     * 该界必然包含整个锥。方向退化 / 锥过宽 / cosOuter 非法 ⇒ <b>该灯退回球盒</b>(不缩,安全侧)。</p>
     */
    public static Box boxForCones(List<SpotlightData> lights, float margin) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (SpotlightData l : lights) {
            double dx = l.dirX(), dy = l.dirY(), dz = l.dirZ();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double c = l.cosOuter();
            double r = l.radius();
            // 该灯的"球盒"(灯的有效影响 = 锥 ∩ 半径球:衰减在 r 处恰好归零)
            double sx0 = l.posX() - r, sx1 = l.posX() + r;
            double sy0 = l.posY() - r, sy1 = l.posY() + r;
            double sz0 = l.posZ() - r, sz1 = l.posZ() + r;
            double c0x, c1x, c0y, c1y, c0z, c1z;
            if (len < 1e-6 || !(c > CONE_MIN_COS_OUTER) || c > 1.0) {
                // 退化/过宽:该灯直接用球盒
                c0x = sx0; c1x = sx1; c0y = sy0; c1y = sy1; c0z = sz0; c1z = sz1;
            } else {
                double tan = Math.sqrt(Math.max(0.0, 1.0 - c * c)) / c;
                double L = r;
                double nx = dx / len, ny = dy / len, nz = dz / len;
                // 精确锥 AABB(2026-09-25 晚修正):垂直于光线的张开量在轴 i 上的分量是
                // tan·sqrt(1-n_i^2),**不是** tan。原式(d_i ± tan)把整份张开加到每根轴上,
                // 包括"沿着光线"的那根轴(那里分量恰好为 0)⇒ 45° 时沿轴被算成 2L 而非 L,
                // 盒反而比球盒还大(真机实测 82 > 76,重建 10.40ms,比优化前更差)。
                // 修正后:沿轴方向只剩 n_i 那一段,与几何一致。
                double tx = tan * Math.sqrt(Math.max(0.0, 1.0 - nx * nx));
                double ty = tan * Math.sqrt(Math.max(0.0, 1.0 - ny * ny));
                double tz = tan * Math.sqrt(Math.max(0.0, 1.0 - nz * nz));
                c0x = l.posX() + L * Math.min(0.0, nx - tx) - margin;
                c1x = l.posX() + L * Math.max(0.0, nx + tx) + margin;
                c0y = l.posY() + L * Math.min(0.0, ny - ty) - margin;
                c1y = l.posY() + L * Math.max(0.0, ny + ty) + margin;
                c0z = l.posZ() + L * Math.min(0.0, nz - tz) - margin;
                c1z = l.posZ() + L * Math.max(0.0, nz + tz) + margin;
            }
            // 2026-09-25 晚(重场景轮实测):锥的 AABB 在宽锥下会比球盒还大(45° 实测 82³ vs 球盒 76³,
            // 重建 10.4ms vs 7.3ms ⇒ 反而比优化前更差)。与球盒求交:
            //   - 包含性不变:有效区 = 锥 ∩ 球 ⊂ 锥 ⊂ 锥AABB,且 ⊂ 球 ⊂ 球盒 ⇒ ⊂ 交集;
            //   - 且**永不比球盒更大**(最坏退化为优化前的行为,不会倒退)。
            double ix0 = Math.max(c0x, sx0), ix1 = Math.min(c1x, sx1);
            double iy0 = Math.max(c0y, sy0), iy1 = Math.min(c1y, sy1);
            double iz0 = Math.max(c0z, sz0), iz1 = Math.min(c1z, sz1);
            if (ix0 > ix1) { ix0 = sx0; ix1 = sx1; }   // 退化保护:回该灯球盒
            if (iy0 > iy1) { iy0 = sy0; iy1 = sy1; }
            if (iz0 > iz1) { iz0 = sz0; iz1 = sz1; }
            minX = Math.min(minX, ix0); maxX = Math.max(maxX, ix1);
            minY = Math.min(minY, iy0); maxY = Math.max(maxY, iy1);
            minZ = Math.min(minZ, iz0); maxZ = Math.max(maxZ, iz1);
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) return new Box(0, 0, 0, 1, 1, 1);
        int ox = floorI(minX), oy = floorI(minY), oz = floorI(minZ);
        int dx = ceilI(maxX) - ox, dy = ceilI(maxY) - oy, dz = ceilI(maxZ) - oz;
        if (dx < 1) dx = 1;
        if (dy < 1) dy = 1;
        if (dz < 1) dz = 1;
        if (dx > MAX_DIM) { dx = MAX_DIM; ox = floorI((minX + maxX) * 0.5 - MAX_DIM * 0.5); }
        if (dy > MAX_DIM) { dy = MAX_DIM; oy = floorI((minY + maxY) * 0.5 - MAX_DIM * 0.5); }
        if (dz > MAX_DIM) { dz = MAX_DIM; oz = floorI((minZ + maxZ) * 0.5 - MAX_DIM * 0.5); }
        return new Box(ox, oy, oz, dx, dy, dz);
    }

    /** 盒原点一次位移超过这么多格就立即重建(瞬移/极速移动兜底,不参与转动节流)。 */
    public static final float MAX_BOX_SHIFT_BLOCKS = 4.0f;

    /**
     * 重建决策(纯函数,2026-09-25 转动节流;契约见 {@code VoxelFieldContract})。
     *
     * <p>背景:重建门原来是"tick 变了 <b>或</b> 盒变了"。转动时箱形几乎每帧都变 ⇒ <b>逐帧重建</b>
     * (真机实测 60 次/秒 vs 静止 20 次/秒)。但"箱形滞后一点"的代价是<b>有上界</b>的:
     * 被照到的片元若落在旧盒外,只是回退到旧的屏幕空间遮挡,而那个方向的光本来就弱。</p>
     *
     * <p>所以加一层:<b>盒变了也要方向转过 {@code maxLagDeg} 才重建</b>(tick 门控仍是无条件安全网,
     * 所以任何情况下的滞后都 ≤1 tick = 50 ms)。</p>
     *
     * <p><b>注意</b>{@code lightShiftBlocks} 必须是<b>灯位置</b>的位移,<b>不能</b>用盒原点位移:
     * 转动本身就会让盒原点移动好几格(实测把瞬移兜底挂在盒原点上时,兜底每 2 帧就触发,
     * 节流形同虚设:转动重建率被顶回 36/s)。</p>
     */
    public static boolean shouldRebuild(boolean tickDue, boolean boxChanged, float dirDeg,
                                        float lightShiftBlocks, float maxLagDeg, float maxShiftBlocks) {
        if (tickDue) return true;                  // 50ms 安全网:世界/位置/一切变化的兜底
        if (!boxChanged) return false;
        if (lightShiftBlocks >= maxShiftBlocks) return true;   // 瞬移:立刻跟上
        return dirDeg >= maxLagDeg;                // 转动:滞后满 maxLagDeg 才重建
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

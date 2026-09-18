package dev.taclight.channel;

/**
 * 体素遮挡"按真实形状分档"的纯逻辑核心(2026-09-18 雪地菱形阵列根因轮)。
 *
 * <p><b>为什么有这个类</b>：用户真机实测 v0.10.0 在雪地上光斑呈规则菱形方格阵列。
 * 根因 = DDA 把"非满方块"当整格实心遮挡：{@code VoxelGrid.classify()} 的兜底分支
 * 对"非空气 / 非树叶 / 非软植被 / 非流体"一律回 {@code CODE_SOLID}，而
 * {@code minecraft:snow}(雪层)四个条件全不命中 ⇒ 整格实心；着色器
 * {@code taclight_core.glsl} 对实心格"穿入 ≥ {@code TACLIGHT_VOX_FUZZ}=0.35 格 ⇒ T=0"
 * ⇒ 一整格厚的假遮挡 ⇒ 光斑被切成方格阵列。同罪者不止雪：地毯 / 绊线 / 半砖 /
 * 楼梯 / 耕地 / 土径 / 栅栏 / 墙 / 活板门等凡"非满且不在两张 ID 表里"的都在内。</p>
 *
 * <p>本类只做纯数据判定(零 MC 依赖、JVM 可测)：输入 = 碰撞形与遮挡形的盒列表，
 * 输出 = 2bit 分类码。方块形状采样在 {@code dev.taclight.client.VoxelGrid}，经
 * {@link ShapeProbe} 注入，从而让"同一 state 在不同坐标判定不同"可在无 MC 运行时下断言。</p>
 *
 * <p><b>分档口径(C 口径，Lead 2026-09-18 裁定；不扩 3bit，格式/pack/GLSL 解码一律不动)</b>：</p>
 * <ol>
 *   <li><b>守卫 1</b>：{@code coll} 为空(无碰撞形：绊线/铁轨/蛛网/空气/流体) ⇒
 *       {@code CODE_EMPTY}。这些方块 {@code occ} 非空(蛛网 occ 甚至是满格)，若先按占比分档
 *       会把蛛网判成实心 ⇒ 守卫必须在占比之前。</li>
 *   <li><b>守卫 2</b>：{@code coll} 最大 {@code maxY > 1.0}(栅栏/墙碰撞柱 1.5) ⇒
 *       {@code CODE_SOLID}(保守，任务表第 4 行)。它们 {@code occ} 只有 1.0 格高(占比
 *       0.0625/0.25) ⇒ 不设此守卫会把栅栏/墙判成透光(漏光)。</li>
 *   <li>其余按 <b>{@code occ}(遮挡形)实心体积占比</b>分档：占比 ≤ 0.25 ⇒ {@code CODE_EMPTY}；
 *       0.25 &lt; 占比 &lt; 0.9 ⇒ {@code CODE_VEG}；占比 ≥ 0.9 ⇒ {@code CODE_SOLID}。</li>
 * </ol>
 *
 * <p><b>为什么占比用 occ 而不是 coll(判据更正 ②)</b>：我们算的是"光被挡多少"，
 * 遮挡形才是这件事的真源。原版雪层的 <b>{@code coll} 故意比 {@code occ}/{@code vis}
 * 矮一层</b>(玩法决定：让玩家能走上雪层)⇒ 拿 coll 当遮挡必然"影子偏小"：
 * 实测 coll 雪 3 层=0.25(⇒EMPTY)、8 层=0.875(⇒VEG，偏透)；而 occ 雪 1/2 层=0.125/0.25
 * (⇒EMPTY)、3..7 层=0.375..0.875(⇒VEG)、8 层=1.0(⇒SOLID)——正好是任务表。</p>
 *
 * <p><b>为什么不再用"中心列顶高"(判据更正 ①)</b>：中心列严格包含判据在两盒<b>共面</b>处
 * 会同时排除两个盒：实测 {@code OAK_STAIRS[half=top]} = {@code [0,0,0→1,1,0.5] ∪
 * [0,0.5,0.5→1,1,1]}，两盒的面都恰好落在 z=0.5 ⇒ h=0 ⇒ <b>EMPTY = 漏光</b>(危险方向)。
 * <b>占比口径下没有共面问题</b>：体积是两盒体积之和(0.75 ⇒ VEG)，与面落在哪里无关、
 * 也与朝向无关。后来者若要重写"中心柱/中心列"这类点采样判据，请先复现这一例。</p>
 *
 * <p><b>已知残留(如实)</b>：① {@code CODE_VEG} 对部分高度方块是"整格同系数 0.25 遮挡/格"
 * 的近似(半砖 0.5 格高与楼梯 0.75 占比同码)；② 半砖 {@code type=top} 与楼梯
 * {@code half=top} 现按占比落 {@code CODE_VEG}(表第 2 行)，若真机 after 截图显示
 * "顶部半砖仍透"再考虑第二阶段(扩 3bit 或按位置细分)。</p>
 *
 * <p>纯 JVM 断言：{@code VoxelClassifyContract}(合成夹具) + {@code VoxelRealRegistryContract}
 * (真 registry 的 {@code Blocks.*}，直接驱动生产 {@code VoxelGrid.classify})。</p>
 */
public final class VoxelClassifier {
    /** 占比薄片阈值：≤ 此值 ⇒ 透光(雪 1-2 层 0.125/0.25、地毯 0.0625、活板门 0.1875)。 */
    public static final double THIN_MAX_FRACTION = 0.25;
    /** 占比满档阈值：≥ 此值 ⇒ 实心(耕地/土径 0.9375、满方块 1.0、雪 8 层 1.0)。 */
    public static final double FULL_MIN_FRACTION = 0.9;
    /** 守卫 2 阈值：{@code coll} 最高盒顶 > 此值 ⇒ 实心(栅栏/墙碰撞柱 1.5)。 */
    public static final double TALL_TOP_Y = 1.0;
    /** 平坦 AABB 数组的步长：[minX, minY, minZ, maxX, maxY, maxZ]。 */
    public static final int BOX_STRIDE = 6;

    /**
     * C 口径总规则(守卫顺序写死，勿调换——两条守卫各自防一个漏光/误挡方向)：
     * <ol>
     *   <li>{@code coll} 空 ⇒ EMPTY；</li>
     *   <li>{@code coll} 最高盒顶 &gt; 1.0 ⇒ SOLID；</li>
     *   <li>否则按 {@code occ} 实心占比分档。</li>
     * </ol>
     */
    public static int codeForShapes(double[] coll, int collCount, double[] occ, int occCount) {
        if (collCount <= 0) return VoxelField.CODE_EMPTY;
        if (maxTopY(coll, collCount) > TALL_TOP_Y) return VoxelField.CODE_SOLID;
        return codeForSolidFraction(solidFraction(occ, occCount));
    }

    /** 占比分档：≤0.25 ⇒ EMPTY；≥0.9 ⇒ SOLID；其间 ⇒ VEG。{@code NaN} ⇒ EMPTY(宁可漏挡不可假遮挡)。 */
    public static int codeForSolidFraction(double fraction) {
        if (!(fraction > THIN_MAX_FRACTION)) return VoxelField.CODE_EMPTY;
        if (fraction >= FULL_MIN_FRACTION) return VoxelField.CODE_SOLID;
        return VoxelField.CODE_VEG;
    }

    /**
     * 盒列表在单位格内的实心体积占比 = Σ (maxX−minX)(maxY−minY)(maxZ−minZ) / 1。
     * 盒为方块局部 0..1 坐标(原版 {@code getShape/getOcclusionShape/getCollisionShape} 即此域)。
     */
    public static double solidFraction(double[] boxes, int boxCount) {
        double v = 0.0;
        for (int i = 0; i < boxCount; i++) {
            int o = i * BOX_STRIDE;
            double dx = boxes[o + 3] - boxes[o];
            double dy = boxes[o + 4] - boxes[o + 1];
            double dz = boxes[o + 5] - boxes[o + 2];
            if (dx > 0 && dy > 0 && dz > 0) v += dx * dy * dz;
        }
        return v;
    }

    /** 盒列表的最大 {@code maxY}；无盒 ⇒ {@code -Infinity}(守卫 2 自然不成立)。 */
    public static double maxTopY(double[] boxes, int boxCount) {
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < boxCount; i++) {
            double maxY = boxes[i * BOX_STRIDE + 4];
            if (maxY > top) top = maxY;
        }
        return top;
    }

    /** 一对形状的平铺盒(生产侧用可复用暂存数组填充，避免逐格分配)。 */
    public static final class ShapeBoxes {
        public final double[] coll;
        public final int collCount;
        public final double[] occ;
        public final int occCount;

        public ShapeBoxes(double[] coll, int collCount, double[] occ, int occCount) {
            this.coll = coll;
            this.collCount = collCount;
            this.occ = occ;
            this.occCount = occCount;
        }
    }

    /**
     * 位置相关形状探针：返回 {@code (level, state)} 在方块坐标 {@code (x,y,z)} 处的
     * 碰撞形与遮挡形。生产实现必须把坐标如实透传给 {@code CURSOR}，否则又会退回
     * "永远在世界原点求值"的根因②。
     */
    @FunctionalInterface
    public interface ShapeProbe<L, S> {
        ShapeBoxes boxes(L level, S state, int x, int y, int z);
    }

    /**
     * 位置相关分类：<b>每次</b>都按传入坐标重新探测、<b>不做任何 state 级缓存</b>。
     * 这是根因②(坐标没用上)与根因③(只以 BlockState 为键的缓存把首次求值冻结)的合并处置：
     * 形状可随位置变化(栅栏连接 / 楼梯朝向 / 模组方块)⇒ 位置相关结论不得入 state 级缓存。
     */
    public static <L, S> int classifyByShape(ShapeProbe<L, S> probe, L level, S state, int x, int y, int z) {
        ShapeBoxes b = probe.boxes(level, state, x, y, z);
        return codeForShapes(b.coll, b.collCount, b.occ, b.occCount);
    }

    private VoxelClassifier() {}
}

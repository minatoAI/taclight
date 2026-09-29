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
 * <p><b>分档口径(2026-09-25 高度感知口径;取代 2026-09-18 的 C 口径）</b>:</p>
 * <ol>
 *   <li><b>守卫 1</b>：{@code coll} 为空(无碰撞形：绊线/铁轨/蛛网/空气/流体) ⇒ {@code CODE_EMPTY}。</li>
 *   <li><b>守卫 2</b>：{@code coll} 最大 {@code maxY > 1.0}(栅栏/墙碰撞柱 1.5) ⇒ {@code CODE_SOLID}。</li>
 *   <li>{@code occ} 实心体积占比 ≥ 0.9 ⇒ {@code CODE_SOLID}。</li>
 *   <li><b>薄板</b>(占满 XZ 足印的单一盒)⇒ <b>薄板码</b>：底薄板 {@code 4..11}(顶高 k/8)、
 *       顶薄板 {@code 12..15}(底高 k/8)。雪层 1..7 层分别落 4..10。</li>
 *   <li>其余 ⇒ {@code CODE_VEG}。</li>
 * </ol>
 *
 * <p><b>为什么必须改成高度感知(C 口径的根因)</b>：C 口径按<b>体积占比</b>分档，
 * {@code ≤0.25 ⇒ EMPTY} 这条"薄片透光"规则把 <b>雪层 1–2 层(占比 0.125/0.25)判成完全不遮挡</b>
 * ——2026-09-25 真机探针实测：一个 9³ 盒子里 <b>116 格非空气却判透光</b>，其中
 * {@code snow{layers=1}} 占 60 格(`docs/evidence/2026-09-25-voxel-probe/`)。
 * 体积占比<b>丢掉了"板在格内的哪个高度"</b>这一必要信息：1/8 格高的雪层与"格内均匀 12.5% 填充"
 * 在占比上无法区分，而两者对光的意义完全不同。薄板码把高度带进着色器，由 DDA 用射线在该格的
 * y 区间与板区间求交决定挡不挡。</p>
 *
 * <p><b>为什么不再用"中心列顶高"(判据更正 ①)</b>：中心列严格包含判据在两盒<b>共面</b>处
 * 会同时排除两个盒：实测 {@code OAK_STAIRS[half=top]} 两盒面都落在 z=0.5 ⇒ h=0 ⇒ <b>EMPTY = 漏光</b>。
 * 占比口径无此问题(体积是两盒之和)；薄板判据要求"单一盒 + 占满 XZ 足印"，楼梯(两盒)自然落 VEG。</p>
 *
 * <p><b>已知残留(如实)</b>：① 高度按 1/8 格量化(雪 1 层 0.125 与地毯 0.0625 都落"顶高 1/8")；
 * ② {@code CODE_VEG} 仍是"整格同系数 0.25 遮挡/格"的近似(楼梯/半砖按整格算)；
 * ③ 非薄板的薄小方块(睡莲等)落 VEG 而非 EMPTY —— 从"完全不挡"变成"每格衰减 25%"，方向更保守。</p>
 *
 * <p>纯 JVM 断言：{@code VoxelClassifyContract}(合成夹具) + {@code VoxelRealRegistryContract}
 * (真 registry 的 {@code Blocks.*}，直接驱动生产 {@code VoxelGrid.classify})。</p>
 */
public final class VoxelClassifier {
    /**
     * 历史口径(C 口径)的"薄片透光"阈值。<b>2026-09-25 高度感知口径已不再使用</b>
     * (薄片改由薄板码按真实高度遮挡);保留常量只为对照旧判据与历史契约。
     */
    public static final double THIN_MAX_FRACTION = 0.25;
    /** 足印/贴面判定容差(盒是否占满 XZ 足印、是否贴 y=0 或 y=1)。 */
    public static final double FOOTPRINT_EPS = 1e-6;
    /**
     * "无碰撞形却仍是贴地薄板"的顶高上限(守卫 1 的例外档)。
     * 雪 1 层(coll 空 / occ 顶 0.125)是用户实测的漏光主角;绊线(occ 顶 0.5)、蛛网(occ 满格)
     * 不在此列 ⇒ 仍按无碰撞形判透光。
     */
    public static final double NOCOLL_SLAB_MAX_TOP = 0.125;
    /** 占比满档阈值：≥ 此值 ⇒ 实心(耕地/土径 0.9375、满方块 1.0、雪 8 层 1.0)。 */
    public static final double FULL_MIN_FRACTION = 0.9;
    /** 守卫 2 阈值：{@code coll} 最高盒顶 > 此值 ⇒ 实心(栅栏/墙碰撞柱 1.5)。 */
    public static final double TALL_TOP_Y = 1.0;
    /** 平坦 AABB 数组的步长：[minX, minY, minZ, maxX, maxY, maxZ]。 */
    public static final int BOX_STRIDE = 6;

    // ==================================================================
    // 位置无关档的"按方块身份缓存"类别(2026-09-25 classify 快路径)
    // ------------------------------------------------------------------
    // 动机:`VoxelGrid.classify` 原来对**每个非空气格**都做
    // `BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()`(每格新建 String)
    // + 两次 HashSet<String> 查找。但"是否树叶 / 是否软植被"只取决于**方块注册 ID**
    // (与 state、与坐标都无关)⇒ 可以按 `Block` 身份缓存,每格只做一次 O(1) 查表。
    //
    // 语义边界(三条,写死在纯函数里,别看走眼):
    //  ① 顺序 = 旧代码的 if/else 顺序:树叶表先于软植被表(同一方块同时在两表 ⇒ LEAF);
    //  ② **只**缓存这两档;空气是 **state 谓词**(`BlockState.isAir()` → `Block.isAir(state)`,
    //     模组可按状态覆写)、流体也是 state 谓词(水logged 的同种方块 fluid state 不同)
    //     ⇒ 二者都不得按方块冻结,必须留在 `VoxelGrid` 里逐 state 判;
    //  ③ 其余(CAT_OTHER)是"**不早返回**"的哨兵:必须继续走"流体(state)→ 形状(坐标)"。
    //     这一条是根因③的同族防线 —— 位置相关结论不得按方块/状态冻结首次求值。
    // ==================================================================

    /** 分类类别:命中 LEAF_IDS(树叶档)。 */
    public static final int CAT_LEAF = 1;
    /** 分类类别:命中 VEG_IDS(软植被档)。 */
    public static final int CAT_VEG = 2;
    /** 分类类别:两张表都不命中 ⇒ 不早返回(继续走流体/形状判定)。 */
    public static final int CAT_OTHER = 3;
    /**
     * {@link #codeForBlockCategory} 的"不早返回"哨兵(取值必须在 {@code VoxelField}
     * 合法码 0..15 之外,避免与任何真实码混淆)。
     */
    public static final int CODE_FALLTHROUGH = -1;

    /**
     * 位置无关"类别"的纯判定(零 MC 依赖):顺序即语义 —— 树叶先于软植被
     * (与旧代码 {@code if (LEAF_IDS.contains(key)) … else if (VEG_IDS.contains(key))} 逐字一致)。
     * 入参 = 该方块注册 ID 是否命中两张表;调用方负责"每方块只算一次"。
     */
    public static int leafVegCategory(boolean inLeafIds, boolean inVegIds) {
        if (inLeafIds) return CAT_LEAF;
        if (inVegIds) return CAT_VEG;
        return CAT_OTHER;
    }

    /**
     * 位置无关类别 ⇒ 分类码;{@link #CAT_OTHER} ⇒ {@link #CODE_FALLTHROUGH}(不早返回)。
     *
     * <p>把"哪几档可以按方块冻结"收敛成一条可断言的规则:只有 LEAF/VEG 能直接出码,
     * 其余必须回到逐 state / 逐坐标的判定。若有人图省事让 CAT_OTHER 也返回一个码,
     * 就等于"非空气/非树叶/非软植被 ⇒ 默认实心"(旧兜底)重演 —— 雪地方格阵列的根因。</p>
     */
    public static int codeForBlockCategory(int category) {
        if (category == CAT_LEAF) return VoxelField.CODE_LEAF;
        if (category == CAT_VEG) return VoxelField.CODE_VEG;
        return CODE_FALLTHROUGH;
    }

    /**
     * C 口径总规则(守卫顺序写死，勿调换——两条守卫各自防一个漏光/误挡方向)：
     * <ol>
     *   <li>{@code coll} 空 ⇒ EMPTY；</li>
     *   <li>{@code coll} 最高盒顶 &gt; 1.0 ⇒ SOLID；</li>
     *   <li>否则按 {@code occ} 实心占比分档。</li>
     * </ol>
     */
    public static int codeForShapes(double[] coll, int collCount, double[] occ, int occCount) {
        if (collCount <= 0) {
            // 守卫 1:无碰撞形默认透光(绊线/铁轨/蛛网/空气/流体)…
            // …但"贴地薄板"必须例外:雪 1 层**无碰撞形**(原版玩法:让玩家能走上雪层),
            // 却有真实遮挡形 occ=[0,0.125] —— 若一并判 EMPTY,就是用户报的"细雪层穿光"根因
            // (真 registry 实测:coll=[] / occ 0.125)。判据用形状而非方块 ID:
            // 占满 XZ 足印的单一盒 + 顶高 ≤ 1/8。绊线 occ 顶 0.5、蛛网 occ 满格 ⇒ 仍 EMPTY。
            int thin = slabCodeFor(occ, occCount);
            if (VoxelField.isSlab(thin) && VoxelField.slabHigh(thin) <= NOCOLL_SLAB_MAX_TOP) return thin;
            return VoxelField.CODE_EMPTY;
        }
        if (maxTopY(coll, collCount) > TALL_TOP_Y) return VoxelField.CODE_SOLID;
        double fraction = solidFraction(occ, occCount);
        if (fraction >= FULL_MIN_FRACTION) return VoxelField.CODE_SOLID;
        int slab = slabCodeFor(occ, occCount);
        if (slab >= 0) return slab;
        if (!(fraction > 0.0)) return VoxelField.CODE_EMPTY;
        return VoxelField.CODE_VEG;
    }

    /**
     * 占满 XZ 足印的<b>单一</b>盒 ⇒ 薄板码(含高度档);不是薄板 ⇒ -1。
     *
     * <p>底薄板 = 从 {@code y=0} 起、顶高 {@code maxY} ⇒ 码 4..11(顶高 = (code−3)/8);
     * 顶薄板 = 到 {@code y=1} 止、底高 {@code minY} ⇒ 码 12..15(底高 = (code−8)/8)。
     * 高度按 1/8 格量化(与 {@link VoxelField#slabHigh} 同源)。</p>
     *
     * <p>要求"单一盒"是刻意的:楼梯/多盒形状落 VEG(整格近似),避免把复杂形状误判成薄板;
     * 要求"占满 XZ 足印"是因为薄板判据只带高度、不带水平形状——不占满足印的盒若按薄板处理
     * 会在水平方向假遮挡。</p>
     */
    public static int slabCodeFor(double[] boxes, int boxCount) {
        if (boxCount != 1) return -1;
        double minX = boxes[0], minY = boxes[1], minZ = boxes[2];
        double maxX = boxes[3], maxY = boxes[4], maxZ = boxes[5];
        if (minX > FOOTPRINT_EPS || minZ > FOOTPRINT_EPS) return -1;
        if (maxX < 1.0 - FOOTPRINT_EPS || maxZ < 1.0 - FOOTPRINT_EPS) return -1;
        if (minY <= FOOTPRINT_EPS) {
            int eighths = (int) Math.round(maxY * 8.0);
            if (eighths < 1) eighths = 1;
            if (eighths >= 8) return VoxelField.CODE_SOLID;
            return VoxelField.slabBottomCode(eighths);
        }
        if (maxY >= 1.0 - FOOTPRINT_EPS) {
            int eighths = (int) Math.round(minY * 8.0);
            if (eighths < 4) eighths = 4;
            if (eighths > 7) eighths = 7;
            return VoxelField.slabTopCode(eighths);
        }
        return -1;
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

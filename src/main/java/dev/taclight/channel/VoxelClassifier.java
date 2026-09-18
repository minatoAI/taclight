package dev.taclight.channel;

/**
 * 体素遮挡"按真实形状分档"的纯逻辑核心(2026-09-18 雪地菱形阵列根因轮)。
 *
 * <p><b>为什么有这个类</b>：用户真机实测 v0.10.0 在雪地上光斑呈规则菱形方格阵列。
 * 根因 = DDA 把"非满方块"当整格实心遮挡：{@code VoxelGrid.classify()} 的兜底分支
 * 对"非空气 / 非树叶 / 非软植被 / 非流体"一律回 {@code CODE_SOLID}，而
 * {@code minecraft:snow}(雪层，1/8 格高、非满碰撞形状)四个条件全不命中 ⇒ 整格实心；
 * 着色器 {@code taclight_core.glsl} 对实心格"穿入 ≥ {@code TACLIGHT_VOX_FUZZ}=0.35 格
 * ⇒ T=0"⇒ 一整格厚的假遮挡 ⇒ 光斑被切成方格阵列。同罪者不止雪：地毯 / 绊线 / 半砖 /
 * 楼梯 / 耕地 / 土径 / 栅栏 / 墙 / 活板门等凡"非满且不在两张 ID 表里"的都在内。</p>
 *
 * <p>本类只做纯数据判定(零 MC 依赖、JVM 可测)：输入 = 碰撞形"顶高"，输出 = 2bit 分类码。
 * 方块形状采样({@code state.getCollisionShape(level, CURSOR)})留在
 * {@code dev.taclight.client.VoxelGrid}，经 {@link ShapeProbe} 注入，从而让
 * "同一 state 在不同坐标判定不同"这件事可以在无 MC 运行时下被断言。</p>
 *
 * <p><b>分档表</b>(第一步方案；不扩 3bit —— 打包格式 / {@code VOX_MAX_UINTS} /
 * pack / GLSL 解码一律不动，留作第二阶段)：</p>
 * <pre>
 *   h ≤ 0.25          → CODE_EMPTY   雪(≤2 层)/地毯/绊线等薄片 ⇒ 阵列主因直接消除
 *   0.25 &lt; h &lt; 0.9  → CODE_VEG     半砖(bottom)/楼梯(bottom)/部分雪 ⇒ 复用软档
 *   h ≥ 0.9           → CODE_SOLID   满方块(含耕地/土径 15/16)
 *   非满但高(>1 格)   → CODE_SOLID   栅栏/墙(保守)；如实记为已知残留
 * </pre>
 * <p>注意：软档对 1/8 格高的雪(1-2 层)是"整格同系数"近似——薄片档(h≤0.25)直接判透光，
 * 所以 1-2 层雪走的是 EMPTY；3-7 层雪(0.375~0.875)走软档 0.25/格，仍不精确，如实记录。</p>
 *
 * <p><b>顶高 h 的取值口径(对任务书 "shape.max(Y)" 的 1 处修正，已上报 Lead)</b>：
 * 任务书原文建议 {@code getCollisionShape(level, CURSOR).max(Direction.Axis.Y)}。实测
 * 1.20.1 官方映射下 {@code StairBlock} 的碰撞形 = {@code BOTTOM_AABB} ∪ (0.5³ 角块上移 0.5)，
 * 其 {@code max(Y)} = 1.0 ⇒ 按字面 max(Y) 楼梯会被判 SOLID，与任务书表格第 2 行
 * "楼梯 → 中低档"相矛盾；而耕地 15/16 = 0.9375 又必须落 SOLID。单个标量无法同时满足，
 * 故这里取 <b>中心列顶高</b> {@code hCenter} = "严格包含格中心 (0.5, 0.5) 的 AABB 的最高 y"：
 * <ul>
 *   <li>楼梯(bottom) / 半砖(bottom)：中心列只有下半 ⇒ 0.5 → VEG(与表格第 2 行一致)</li>
 *   <li>耕地 / 土径：0.9375 → SOLID，满方块 1.0 → SOLID(与表格第 3 行一致)</li>
 *   <li>栅栏 / 墙：中心柱 1.5(CrossCollisionBlock 碰撞高 24/16) → SOLID(表格第 4 行)</li>
 *   <li>雪 1 层 2/16 = 0.125、地毯 1/16 = 0.0625 → EMPTY(表格第 1 行)</li>
 *   <li>无碰撞形状(绊线/铁轨/植物) ⇒ 0 → EMPTY</li>
 * </ul>
 * 严格不等号({@code <} / {@code >})是必需的：楼梯角块的 x/z 边界恰好落在格中心，
 * 用 {@code ≤} 会把角块算进来 ⇒ 角块顶到 1.0 ⇒ 又回 SOLID。两种角块方位各有一条断言钉住
 * ({@code OcclusionTierContract})。</p>
 */
public final class VoxelClassifier {
    /** 薄片阈值：顶高 ≤ 此值 ⇒ 透光。雪 1 层 = 2/16、地毯 = 1/16、绊线 = 无形状。 */
    public static final double THIN_MAX_Y = 0.25;
    /** 满档阈值：顶高 ≥ 此值 ⇒ 实心。耕地 / 土径 = 15/16 = 0.9375 属此档。 */
    public static final double FULL_MIN_Y = 0.9;
    /** 格中心采样坐标(严格包含判定的探针点)。 */
    public static final double CELL_MID = 0.5;
    /** 平坦 AABB 数组的步长：[minX, minY, minZ, maxX, maxY, maxZ]。 */
    public static final int BOX_STRIDE = 6;

    /**
     * 顶高 → 分类码(分档表)。{@code NaN}/0/无形状都落 EMPTY(薄片 ⇒ 透光，宁可漏挡不可假遮挡)。
     * 这是旧 {@code VoxelGrid.classify()} 兜底分支"非空即实心"的替代点。 */
    public static int codeForTopHeight(double topY) {
        if (!(topY > THIN_MAX_Y)) return VoxelField.CODE_EMPTY;
        if (topY >= FULL_MIN_Y) return VoxelField.CODE_SOLID;
        return VoxelField.CODE_VEG;
    }

    /**
     * 中心列顶高：遍历 {@code boxCount} 个 AABB(平坦数组 {@code boxes}，步长 {@link #BOX_STRIDE}，
     * 方块局部坐标 0..1)，取"严格包含格中心 (0.5, 0.5)"的那些 AABB 的最高 {@code maxY}；
     * 一个都不包含 ⇒ 0.0(无形状/形状避开了中心列)。
     * 严格不等号的理由见类注释(楼梯角块边界恰好落在格中心)。
     */
    public static double centerColumnTopY(double[] boxes, int boxCount) {
        double top = 0.0;
        for (int i = 0; i < boxCount; i++) {
            int o = i * BOX_STRIDE;
            double minX = boxes[o], minZ = boxes[o + 2];
            double maxX = boxes[o + 3], maxY = boxes[o + 4], maxZ = boxes[o + 5];
            if (minX < CELL_MID && maxX > CELL_MID && minZ < CELL_MID && maxZ > CELL_MID && maxY > top) {
                top = maxY;
            }
        }
        return top;
    }

    /**
     * 位置相关形状探针：返回 {@code (level, state)} 在方块坐标 {@code (x,y,z)} 处的碰撞形中心列顶高。
     * 生产实现 = {@code CURSOR.set(x,y,z); centerColumnTopY(state.getCollisionShape(level, CURSOR))}
     * ——坐标必须如实透传到 {@code CURSOR}，否则又会退回"永远在世界原点求值"的根因②。
     */
    @FunctionalInterface
    public interface ShapeProbe<L, S> {
        double topY(L level, S state, int x, int y, int z);
    }

    /**
     * 位置相关分类：<b>每次</b>都按传入坐标重新探测、<b>不做任何 state 级缓存</b>。
     * 这是根因②(坐标没用上)与根因③(只以 BlockState 为键的缓存把首次求值冻结)的合并处置：
     * 形状可随位置变化(栅栏连接 / 楼梯朝向 / 模组方块)⇒ 位置相关结论不得入 state 级缓存；
     * 位置无关档(空气/树叶/软植被/流体)仍由 {@code VoxelGrid} 按 state 缓存。
     */
    public static <L, S> int classifyByShape(ShapeProbe<L, S> probe, L level, S state, int x, int y, int z) {
        return codeForTopHeight(probe.topY(level, state, x, y, z));
    }

    private VoxelClassifier() {}
}

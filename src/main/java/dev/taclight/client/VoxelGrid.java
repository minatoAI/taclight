package dev.taclight.client;

import dev.taclight.channel.SpotlightData;
import dev.taclight.channel.VoxelClassifier;
import dev.taclight.channel.VoxelField;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Set;

/**
 * 体素遮挡栅格世界侧(2026-09-01 深夜④,DDA 立项 = 用户实测墙后地面漏光):
 * 灯作用范围内的方块 → 2bit 分类码,每 tick 填充(渲染线程;节流 = tick/盒变化),
 * 供 LightBuffer 经 SSBO 尾段上传,GLSL 做 Amanatides-Woo DDA 步进。
 *
 * <p>分类与 pack/shaders/block.properties 同源(SSO 消费的 colortex3.a 语义):
 * 2001 软植被 / 2002 树叶 / 其余实心;空气与流体(水/熔岩,gbuffers_water 不写
 * colortex3)透光。性能护栏:chunk section hasOnlyAir 整段跳过 + <b>位置无关档</b>的
 * BlockState 分类身份缓存(位置相关档的缓存策略见下);实测构建耗时经
 * {@code !voxel status} 观察。</p>
 *
 * <p><b>2026-09-18 雪地方格阵列根因轮</b>(用户真机实测 v0.10.0):旧兜底"非空气/
 * 非树叶/非软植被/非流体 ⇒ 整格实心"把雪层(1/8 格高)等非满方块当整格遮挡 ⇒ 光斑
 * 成规则菱形阵列。两条根因的处置:
 * <ol>
 *   <li><b>①</b> 新增"形状分档"——非两张 ID 表的方块不再默认实心,改由
 *       {@link VoxelClassifier}(真实碰撞形顶高 h → EMPTY/VEG/SOLID)判定;</li>
 *   <li><b>②</b> {@code x/y/z} 与 {@code CURSOR.set(...)} 必须用上——旧代码的 CURSOR
 *       从未 set ⇒ {@code isSolidRender} 永远在世界原点求值;</li>
 *   <li><b>③</b> 缓存策略(显式二选一,取任务书推荐的 (i)):<b>位置无关档
 *       (空气 / 树叶 / 软植被 ID / 流体)按 state 身份走 {@code CLASS_CACHE};
 *       形状分档是位置相关的,一律不入 state 级缓存,改以 {@code VoxelShape} <b>实例</b>为键
 *       走 {@code SHAPE_CACHE}</b> —— 位置相关性已被形状本身编码(同实例必同码),
 *       既不会重演根因③,也免掉逐格 {@code toAabbs()} 分配(实测该方法每次调用都新建 ArrayList)。
 *       仍未验证的只有:fence/wall 这类"每次新形状实例"的位置相关方块在极端场景下的
 *       命中率与构建耗时增幅(需真机 {@code !voxel status} 的 lastBuildMs 对比)。</li>
 * </ol></p>
 *
 * <p>运行时开关(客户端本地,DebugCommandRelay):{@code !voxel <on|off|status>},
 * 默认 on;off = 上传无效位,GLSL 逐光线回退屏幕空间 SSO(A/B 对照与一键回退)。</p>
 */
public final class VoxelGrid {
    private static volatile boolean enabled = true;

    private static final int[] DATA = new int[VoxelField.VOX_MAX_UINTS];
    private static VoxelField.Snapshot snap;
    private static long version;
    private static long lastTick = Long.MIN_VALUE;
    private static Level lastLevel;
    private static VoxelField.Box lastBox;
    private static float lastBuildMs;
    private static int builds;

    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    /**
     * 位置无关档的 state 级分类缓存(仅 空气 / 树叶 ID / 软植被 ID / 流体 会写入)。
     * <b>缓存策略(显式选择,取任务书推荐 (i) 的两个子项,合起来用)</b>:
     * <ol>
     *   <li>位置无关档按 {@code BlockState} 身份缓存;</li>
     *   <li>位置相关(形状)档<b>绝不</b>写本缓存,改由 {@link #SHAPE_CACHE} 以
     *       {@code VoxelShape} <b>实例</b>为键缓存 —— 位置相关性已被形状本身编码,
     *       不会重演"按 state 固化首次求值"的根因③。</li>
     * </ol>
     * 渲染线程单线程访问。
     */
    private static final java.util.IdentityHashMap<BlockState, Integer> CLASS_CACHE = new java.util.IdentityHashMap<>();
    /**
     * 位置相关档的"形状身份"缓存:键 = {@code getCollisionShape} 解析出的 {@code VoxelShape} 实例。
     *
     * <p>为什么以形状为键是安全的(而不是"看起来修了"):分类码是形状(盒列表)的<b>纯函数</b>,
     * 而形状实例已编码全部位置相关性(栅栏/墙的连接、楼梯朝向、模组方块随坐标变化的形状,
     * 差异必然体现为不同实例)⇒ 同一实例必然同码,不存在"按 BlockState 冻结首次位置求值"。
     * 同时省掉位置无关方块(雪层/地毯/半砖/台阶/满方块返回 Block 级静态实例)的逐格
     * {@code toAabbs()} 分配 —— 该方法每次调用都 {@code Lists.newArrayList()}。
     * 位置相关方块(fence/wall 的 {@code Shapes.or} 每次新实例)不命中 ⇒ 每格重算(正确,只是慢),
     * 由 4096 上限清空兜底。渲染线程单线程访问。</p>
     */
    private static final java.util.IdentityHashMap<VoxelShape, Integer> SHAPE_CACHE = new java.util.IdentityHashMap<>();
    /** 形状分档的 AABB 暂存(渲染线程单线程;避免逐格分配;不足时按需扩容)。 */
    private static double[] SHAPE_SCRATCH = new double[VoxelClassifier.BOX_STRIDE * 8];

    // 与 pack/shaders/block.properties 的 2001/2002 分类同源(ASCII 清单,镜像维护)
    // 注:2001/2002 之外的原版方块不再"默认实心" —— 由形状分档判定(见 classify);
    // 清单只作位置无关档(交叉模型植被/树叶)的快速判定 + SSO 回退路径镜像。
    private static final Set<String> VEG_IDS = Set.of(
            "minecraft:grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
            "minecraft:dead_bush", "minecraft:sweet_berry_bush", "minecraft:sugar_cane",
            "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots",
            "minecraft:nether_wart", "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
            "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
            "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower",
            "minecraft:lily_of_the_valley", "minecraft:wither_rose", "minecraft:torchflower",
            "minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony",
            "minecraft:pink_petals", "minecraft:vine", "minecraft:glow_lichen", "minecraft:hanging_roots",
            "minecraft:weeping_vines", "minecraft:weeping_vines_plant", "minecraft:twisting_vines",
            "minecraft:twisting_vines_plant", "minecraft:cave_vines", "minecraft:cave_vines_plant",
            "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass", "minecraft:tall_seagrass");
    private static final Set<String> LEAF_IDS = Set.of(
            "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
            "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves",
            "minecraft:mangrove_leaves", "minecraft:cherry_leaves", "minecraft:azalea_leaves",
            "minecraft:flowering_azalea_leaves");

    private VoxelGrid() {}

    public static boolean enabled() { return enabled; }

    /** !voxel 旋钮:on/off 切换(切换即作废快照),空串/status 返回状态。 */
    public static String configure(String arg) {
        if ("on".equals(arg)) { enabled = true; snap = null; }
        else if ("off".equals(arg)) { enabled = false; }
        return status();
    }

    public static String status() {
        if (!enabled) return "voxel=off(fallback SSO)";
        if (snap == null) return "voxel=on grid=null";
        return String.format(
                "voxel=on box=(%.0f,%.0f,%.0f)+%dx%dx%d builds=%d lastBuildMs=%.2f",
                snap.ox(), snap.oy(), snap.oz(), snap.dx(), snap.dy(), snap.dz(), builds, lastBuildMs);
    }

    /**
     * 每渲染帧调用(与灯收集同点)。返回当前快照;禁用/无灯/无世界 → null
     * (LightBuffer 写无效位,GLSL 回退 SSO)。重建节流:同 tick 且盒未变时复用。
     */
    public static VoxelField.Snapshot update(Minecraft mc, List<SpotlightData> lights) {
        if (!enabled || lights.isEmpty() || mc.level == null) return null;
        Level level = mc.level;
        VoxelField.Box box = VoxelField.boxFor(lights);
        long tick = level.getGameTime();
        if (snap != null && level == lastLevel && tick == lastTick && box.equals(lastBox)) return snap;
        long t0 = System.nanoTime();
        java.util.Arrays.fill(DATA, 0);
        fill(level, box);
        lastBuildMs = (System.nanoTime() - t0) / 1e6f;
        builds++;
        version++;
        snap = new VoxelField.Snapshot(box.ox, box.oy, box.oz, box.dx, box.dy, box.dz, DATA, version);
        lastTick = tick;
        lastLevel = level;
        lastBox = box;
        return snap;
    }

    /** 逐 chunk-section 填充:纯天空段整段跳过,其余段逐块分类打包。 */
    private static void fill(Level level, VoxelField.Box box) {
        int x1 = box.ox + box.dx - 1, y1 = box.oy + box.dy - 1, z1 = box.oz + box.dz - 1;
        int minSecY = level.getMinSection();
        int maxSecY = (level.getMaxBuildHeight() - 1) >> 4;
        for (int cz = box.oz >> 4; cz <= z1 >> 4; cz++) {
            for (int cx = box.ox >> 4; cx <= x1 >> 4; cx++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                int sy0 = Math.max(box.oy >> 4, minSecY);
                int sy1 = Math.min(y1 >> 4, maxSecY);
                for (int sy = sy0; sy <= sy1; sy++) {
                    LevelChunkSection[] secs = chunk.getSections();
                    int idx = sy - minSecY;
                    if (idx < 0 || idx >= secs.length) continue;
                    LevelChunkSection sec = secs[idx];
                    if (sec.hasOnlyAir()) continue;
                    int bx0 = Math.max(box.ox, cx << 4), bx1 = Math.min(x1, (cx << 4) + 15);
                    int by0 = Math.max(box.oy, sy << 4), by1 = Math.min(y1, (sy << 4) + 15);
                    int bz0 = Math.max(box.oz, cz << 4), bz1 = Math.min(z1, (cz << 4) + 15);
                    for (int wy = by0; wy <= by1; wy++) {
                        for (int wz = bz0; wz <= bz1; wz++) {
                            for (int wx = bx0; wx <= bx1; wx++) {
                                int code = classify(level, sec.getBlockState(wx & 15, wy & 15, wz & 15), wx, wy, wz);
                                if (code != VoxelField.CODE_EMPTY) {
                                    VoxelField.pack(box, wx - box.ox, wy - box.oy, wz - box.oz, code, DATA);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 分类(2026-09-18 雪地方格阵列根因轮重写)。
     *
     * <p>两级:<b>位置无关档</b>(空气 / 树叶 ID / 软植被 ID / 流体,与
     * block.properties 的 2001/2002 同源)按 state 身份缓存;<b>位置相关档</b>
     * (其余所有方块,含雪层/地毯/半砖/楼梯/栅栏/模组方块)按当前格真实碰撞形分档,
     * 且不入 {@link #CLASS_CACHE}(见该字段注释的缓存策略)。</p>
     *
     * <p>根因②:形状查询前必须 {@code CURSOR.set(x, y, z)};旧代码从不 set ⇒
     * {@code isSolidRender} 永远在世界原点求值。{@code VoxelGridWiringContract}
     * 以源码文本钉住"set 先于 getCollisionShape、形状分支不含 CODE_SOLID、形状分支不写
     * CLASS_CACHE(改走 SHAPE_CACHE 且键 = 形状实例)"。</p>
     */
    private static int classify(Level level, BlockState state, int x, int y, int z) {
        Integer cached = CLASS_CACHE.get(state);
        if (cached != null) return cached;
        int code;
        if (state.isAir()) {
            code = VoxelField.CODE_EMPTY;
        } else {
            String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (LEAF_IDS.contains(key)) code = VoxelField.CODE_LEAF;
            else if (VEG_IDS.contains(key)) code = VoxelField.CODE_VEG;
            else if (!state.getFluidState().isEmpty()) code = VoxelField.CODE_EMPTY; // 水/熔岩:与 SSO 一致透光
            else {
                // 位置相关档:必须按当前格 set CURSOR(根因②)。
                CURSOR.set(x, y, z);
                VoxelShape shape = state.getCollisionShape(level, CURSOR);
                // 缓存键 = 形状实例(位置相关性已编码在 shape 中,根因③不成立);命中即免逐格 toAabbs 分配。
                Integer shapeHit = SHAPE_CACHE.get(shape);
                if (shapeHit != null) return shapeHit;
                List<AABB> boxes = shape.toAabbs();
                int n = boxes.size();
                if (SHAPE_SCRATCH.length < n * VoxelClassifier.BOX_STRIDE) {
                    SHAPE_SCRATCH = new double[n * VoxelClassifier.BOX_STRIDE];
                }
                for (int i = 0; i < n; i++) {
                    AABB b = boxes.get(i);
                    int o = i * VoxelClassifier.BOX_STRIDE;
                    SHAPE_SCRATCH[o] = b.minX;
                    SHAPE_SCRATCH[o + 1] = b.minY;
                    SHAPE_SCRATCH[o + 2] = b.minZ;
                    SHAPE_SCRATCH[o + 3] = b.maxX;
                    SHAPE_SCRATCH[o + 4] = b.maxY;
                    SHAPE_SCRATCH[o + 5] = b.maxZ;
                }
                int shapeCode = VoxelClassifier.codeForTopHeight(VoxelClassifier.centerColumnTopY(SHAPE_SCRATCH, n));
                if (SHAPE_CACHE.size() > 4096) SHAPE_CACHE.clear();
                SHAPE_CACHE.put(shape, shapeCode);
                return shapeCode;
            }
        }
        CLASS_CACHE.put(state, code);
        if (CLASS_CACHE.size() > 4096) CLASS_CACHE.clear(); // 防极端模组包状态爆炸
        return code;
    }
}

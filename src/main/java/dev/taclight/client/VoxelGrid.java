package dev.taclight.client;

import dev.taclight.channel.BoundedIdentityCache;
import dev.taclight.channel.SpotlightData;
import dev.taclight.channel.VoxelClassifier;
import dev.taclight.channel.VoxelDda;
import dev.taclight.channel.VoxelField;
import dev.taclight.channel.VoxelProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
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
 * 2001 中低档 0.25 / 2002 树叶 0.6 / 2003 薄片档 0.0(2026-09-18 新增)/ 其余实心 1.0;
 * 空气与流体(水/熔岩,gbuffers_water 不写 colortex3)透光。性能护栏:chunk section
 * hasOnlyAir 整段跳过 + <b>位置无关档</b>的 BlockState 分类身份缓存(位置相关档的缓存策略见下);
 * 实测构建耗时经 {@code !voxel status} 观察。</p>
 *
 * <p><b>2026-09-18 雪地方格阵列根因轮</b>(用户真机实测 v0.10.0):旧兜底"非空气/
 * 非树叶/非软植被/非流体 ⇒ 整格实心"把雪层(1/8 格高)等非满方块当整格遮挡 ⇒ 光斑
 * 成规则菱形阵列。两条根因的处置:
 * <ol>
 *   <li><b>①</b> 新增"形状分档"——非两张 ID 表的方块不再默认实心,改由
 *       {@link VoxelClassifier} 判定(C 口径:守卫 1 {@code coll} 空⇒EMPTY /
 *       守卫 2 {@code coll maxY>1.0}⇒SOLID / 其余按 <b>{@code occ} 遮挡形实心占比</b>
 *       分档 0.25/0.9);</li>
 *   <li><b>②</b> {@code x/y/z} 与 {@code CURSOR.set(...)} 必须用上——旧代码的 CURSOR
 *       从未 set ⇒ {@code isSolidRender} 永远在世界原点求值;</li>
 *   <li><b>③</b> 缓存策略(显式二选一,取任务书推荐的 (i)):<b>位置无关档
 *       (空气 / 树叶 / 软植被 ID / 流体)按 state 身份走 {@code CLASS_CACHE};
 *       形状分档是位置相关的,一律不入 state 级缓存,改以 {@code VoxelShape} <b>实例</b>为键
 *       走<b>有界</b> {@code SHAPE_CACHE}({@link BoundedIdentityCache},cap
 *       {@link #SHAPE_CACHE_CAP})</b> —— 位置相关性已被形状本身编码(同实例必同码),
 *       既不会重演根因③,也免掉逐格 {@code toAabbs()} 分配(实测该方法每次调用都新建 ArrayList);
 *       容量上限防"fence/wall 每次新实例 ⇒ 身份键缓存无限增长"的内存泄漏。
 *       仍未验证的只有:fence/wall 这类位置相关方块在极端场景下的命中率与构建耗时增幅
 *       (需真机 {@code !voxel status} 的 lastBuildMs 对比)。</li>
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
     * 位置相关档的"形状身份"缓存:<b>主键 = {@code getOcclusionShape} 的 {@code VoxelShape} 实例</b>
     * (occ 是分档占比的真源,Lead 复核项 ③),值里再存 {@code getCollisionShape} 实例做一致性校验
     * —— 因为守卫 1/2 读的是 coll(空 / maxY&gt;1.0),只按 occ 命中在"模组方块返回常量 occ 而
     * coll 随位置变化"时会误命中。校验成本 = 一次引用比较。
     *
     * <p>为什么以形状实例为键是安全的(而不是"看起来修了"):分类码是 (coll, occ) 盒列表的
     * <b>纯函数</b>,而形状实例已编码全部位置相关性(栅栏/墙的连接、楼梯朝向、模组方块随坐标
     * 变化的形状,差异必然体现为不同实例)⇒ 同一对实例必然同码,不存在"按 BlockState 冻结首次
     * 位置求值"的根因③。同时省掉位置无关方块(雪层/地毯/半砖/台阶/满方块返回 Block 级静态实例)
     * 的逐格 {@code toAabbs()} 分配 —— 实测该方法每次调用都 {@code Lists.newArrayList()}。</p>
     *
     * <p><b>有界性(Lead 复核项 ①)</b>:位置相关方块(fence/wall 的 {@code Shapes.or})每次解析
     * 都产生<b>新实例</b> ⇒ 无界身份键缓存会随帧数持续增长 = 内存泄漏。故用
     * {@link BoundedIdentityCache}:容量 {@link #SHAPE_CACHE_CAP},写满即整体清空
     * (不区分冷热)。命中率下降只影响速度,不影响正确性(缓存不承载语义)。
     * 有界性由 {@code BoundedIdentityCacheContract} 断言;此处仅断言接线。</p>
     */
    private static final int SHAPE_CACHE_CAP = 4096;
    private static final BoundedIdentityCache<VoxelShape, ShapeCode> SHAPE_CACHE =
            new BoundedIdentityCache<>(SHAPE_CACHE_CAP);

    /** 形状分档缓存值:碰撞形实例(校验用)+ 分类码。仅未命中时分配一次。 */
    private record ShapeCode(VoxelShape coll, int code) {}

    /** 碰撞形 AABB 暂存(渲染线程单线程;避免逐格分配;不足时按需扩容)。 */
    private static double[] COLL_SCRATCH = new double[VoxelClassifier.BOX_STRIDE * 8];
    /** 遮挡形 AABB 暂存(占比真源)。 */
    private static double[] OCC_SCRATCH = new double[VoxelClassifier.BOX_STRIDE * 8];

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
     * {@code !voxprobe x y z}:同一格的"分类器此刻判定(live)"与"已上传网格实际值(grid)"并排。
     *
     * <p>2026-09-25 细雪层穿光轮补的调试缺口:此前只能靠截图 A/B 猜,没法在真实场景里直接问
     * "这条光路的这一格到底被判成什么"。两者不一致 ⇒ 打包/上传/盒范围的问题;
     * 两者一致但仍漏光 ⇒ 分类口径或着色器透射表的问题。</p>
     */
    public static String probe(Minecraft mc, int x, int y, int z) {
        return VoxelProbe.cellReport(x, y, z, liveCode(mc, x, y, z), gridCode(x, y, z));
    }

    /**
     * {@code !voxray ...}:沿 A→B 逐格列出 live/grid 码与穿透长度,并给两种口径的累积透射率。
     * {@code liveT} = 真实世界判定下的透射、{@code gridT} = 已上传网格(着色器实际看到的)下的透射。
     */
    public static String ray(Minecraft mc, double ax, double ay, double az,
                             double bx, double by, double bz, int maxCells) {
        return VoxelProbe.rayReport(ax, ay, az, bx, by, bz, maxCells,
                cell -> liveCode(mc, cell.x(), cell.y(), cell.z()),
                cell -> gridCode(cell.x(), cell.y(), cell.z()));
    }

    /** 现场对真实世界求分类码(与逐格填充同一条 {@link #classify},不做 state 级缓存污染)。 */
    private static int liveCode(Minecraft mc, int x, int y, int z) {
        if (mc.level == null) return VoxelField.CODE_EMPTY;
        return classify(mc.level, mc.level.getBlockState(CURSOR.set(x, y, z)), x, y, z);
    }

    /** 盒扫描上限(体积与打印行数),防止误输入把日志刷爆。 */
    public static final int SCAN_MAX_VOLUME = 4096;
    public static final int SCAN_MAX_ROWS = 60;

    /**
     * {@code !voxprobe x1 y1 z1 x2 y2 z2}:盒扫描——逐格列"非空气"格的
     * (方块 / 现场判定 / 已上传网格),并统计<b>非空气却被判透光</b>的格数。
     * 这一类漏光的签名就是后者 &gt; 0(方块对光完全不存在)。
     */
    public static String scan(Minecraft mc, int x1, int y1, int z1, int x2, int y2, int z2) {
        if (mc.level == null) return "VOXSCAN level=null";
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2);
        int ay = Math.min(y1, y2), by = Math.max(y1, y2);
        int az = Math.min(z1, z2), bz = Math.max(z1, z2);
        long volume = (long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1);
        if (volume > SCAN_MAX_VOLUME) {
            return "VOXSCAN 体积 " + volume + " 超过上限 " + SCAN_MAX_VOLUME + "(请缩小范围)";
        }
        java.util.List<VoxelProbe.Row> leaky = new java.util.ArrayList<>();
        java.util.List<VoxelProbe.Row> rest = new java.util.ArrayList<>();
        int nonAir = 0, emptyNonAir = 0, veg = 0, leaf = 0, slab = 0, solid = 0;
        for (int y = ay; y <= by; y++) {
            for (int z = az; z <= bz; z++) {
                for (int x = ax; x <= bx; x++) {
                    BlockState state = mc.level.getBlockState(CURSOR.set(x, y, z));
                    if (state.isAir()) continue;
                    nonAir++;
                    int live = classify(mc.level, state, x, y, z);
                    int grid = gridCode(x, y, z);
                    boolean isLeaky = live == VoxelField.CODE_EMPTY;
                    if (isLeaky) emptyNonAir++;
                    else if (live == VoxelField.CODE_VEG) veg++;
                    else if (live == VoxelField.CODE_LEAF) leaf++;
                    else if (VoxelField.isSlab(live)) slab++;
                    else solid++;
                    // 漏光签名行优先(见 VoxelProbe.scanReport 注释:首轮实测被地下石头挤掉了)
                    java.util.List<VoxelProbe.Row> bucket = isLeaky ? leaky : rest;
                    if (bucket.size() < SCAN_MAX_ROWS) {
                        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        String props = state.getValues().toString();
                        bucket.add(new VoxelProbe.Row(x, y, z,
                                props.equals("{}") ? id : id + props, live, grid));
                    }
                }
            }
        }
        return VoxelProbe.scanReport(leaky, rest, nonAir, emptyNonAir, veg, leaf, slab, solid);
    }

    /** 已上传网格里的码;无快照或盒外 ⇒ -1(OUT,着色器按"占用未知"处理)。 */
    public static int gridCode(int x, int y, int z) {
        VoxelField.Snapshot s = snap;
        if (s == null) return -1;
        int ox = (int) s.ox(), oy = (int) s.oy(), oz = (int) s.oz();
        int lx = x - ox, ly = y - oy, lz = z - oz;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= s.dx() || ly >= s.dy() || lz >= s.dz()) return -1;
        return VoxelField.unpack(new VoxelField.Box(ox, oy, oz, s.dx(), s.dy(), s.dz()), lx, ly, lz, s.data());
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
     * 分类(2026-09-18 雪地方格阵列根因轮重写;C 口径)。
     *
     * <p>两级:<b>位置无关档</b>(空气 / 树叶 ID / 软植被 ID / 流体,与
     * block.properties 的 2001/2002 同源)按 state 身份缓存;<b>位置相关档</b>
     * (其余所有方块,含雪层/地毯/半砖/楼梯/栅栏/模组方块)按当前格真实
     * <b>碰撞形 + 遮挡形</b>分档,且不入 {@link #CLASS_CACHE}(见该字段注释的缓存策略)。</p>
     *
     * <p>根因②:形状查询前必须 {@code CURSOR.set(x, y, z)};旧代码从不 set ⇒
     * {@code isSolidRender} 永远在世界原点求值。{@code VoxelGridWiringContract}
     * 以源码文本钉住"set 先于两个形状查询、形状分支不含 CODE_SOLID、形状分支不写
     * CLASS_CACHE(改走有界 SHAPE_CACHE 且主键 = occ 实例)"。</p>
     *
     * <p><b>可见性</b>:包私有 + 形参类型 {@link BlockGetter}(而非 Level),以便
     * {@code VoxelRealRegistryContract} 用真 registry 的 {@code Blocks.*} +
     * {@code EmptyBlockGetter.INSTANCE} <b>直接驱动本生产方法</b>(而不是在测试里复制一份
     * 分档逻辑)——"测的路径 = 生产路径"。</p>
     */
    static int classify(BlockGetter level, BlockState state, int x, int y, int z) {
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
                VoxelShape coll = state.getCollisionShape(level, CURSOR);
                VoxelShape occ = state.getOcclusionShape(level, CURSOR);
                // 缓存主键 = occ 实例(占比真源);命中还需 coll 身份一致(守卫 1/2 读 coll)。
                ShapeCode hit = SHAPE_CACHE.get(occ);
                if (hit != null && hit.coll() == coll) return hit.code();
                List<AABB> collBoxes = coll.toAabbs();
                List<AABB> occBoxes = occ.toAabbs();
                int collN = collBoxes.size();
                int occN = occBoxes.size();
                if (COLL_SCRATCH.length < collN * VoxelClassifier.BOX_STRIDE) {
                    COLL_SCRATCH = new double[collN * VoxelClassifier.BOX_STRIDE];
                }
                if (OCC_SCRATCH.length < occN * VoxelClassifier.BOX_STRIDE) {
                    OCC_SCRATCH = new double[occN * VoxelClassifier.BOX_STRIDE];
                }
                fill(collBoxes, COLL_SCRATCH);
                fill(occBoxes, OCC_SCRATCH);
                int shapeCode = VoxelClassifier.codeForShapes(COLL_SCRATCH, collN, OCC_SCRATCH, occN);
                SHAPE_CACHE.put(occ, new ShapeCode(coll, shapeCode));
                return shapeCode;
            }
        }
        CLASS_CACHE.put(state, code);
        if (CLASS_CACHE.size() > 4096) CLASS_CACHE.clear(); // 防极端模组包状态爆炸
        return code;
    }

    /** 把盒列表平铺进暂存数组(容量由调用方保证)。 */
    private static void fill(List<AABB> boxes, double[] scratch) {
        for (int i = 0; i < boxes.size(); i++) {
            AABB b = boxes.get(i);
            int o = i * VoxelClassifier.BOX_STRIDE;
            scratch[o] = b.minX;
            scratch[o + 1] = b.minY;
            scratch[o + 2] = b.minZ;
            scratch[o + 3] = b.maxX;
            scratch[o + 4] = b.maxY;
            scratch[o + 5] = b.maxZ;
        }
    }
}

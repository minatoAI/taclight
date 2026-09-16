package dev.taclight.client;

import dev.taclight.channel.SpotlightData;
import dev.taclight.channel.VoxelField;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

/**
 * 体素遮挡栅格世界侧(2026-09-01 深夜④,DDA 立项 = 用户实测墙后地面漏光):
 * 灯作用范围内的方块 → 2bit 分类码,每 tick 填充(渲染线程;节流 = tick/盒变化),
 * 供 LightBuffer 经 SSBO 尾段上传,GLSL 做 Amanatides-Woo DDA 步进。
 *
 * <p>分类与 pack/shaders/block.properties 同源(SSO 消费的 colortex3.a 语义):
 * 2001 软植被 / 2002 树叶 / 其余实心;空气与流体(水/熔岩,gbuffers_water 不写
 * colortex3)透光。性能护栏:chunk section hasOnlyAir 整段跳过 + BlockState 分类
 * 身份缓存;实测构建耗时经 {@code !voxel status} 观察。</p>
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
    private static final java.util.IdentityHashMap<BlockState, Integer> CLASS_CACHE = new java.util.IdentityHashMap<>();

    // 与 pack/shaders/block.properties 的 2001/2002 分类同源(ASCII 清单,镜像维护)
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

    /** 分类:block.properties 语义镜像(实心/树叶/软植被/空气与流体透光)。 */
    private static int classify(Level level, BlockState state, int x, int y, int z) {
        Integer cached = CLASS_CACHE.get(state);
        if (cached != null) return cached;
        int code;
        if (state.isAir()) {
            code = VoxelField.CODE_EMPTY;
        } else if (state.isSolidRender(level, CURSOR)) {
            code = VoxelField.CODE_SOLID;
        } else {
            String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (LEAF_IDS.contains(key)) code = VoxelField.CODE_LEAF;
            else if (VEG_IDS.contains(key)) code = VoxelField.CODE_VEG;
            else if (!state.getFluidState().isEmpty()) code = VoxelField.CODE_EMPTY; // 水/熔岩:与 SSO 一致透光
            else code = VoxelField.CODE_SOLID; // 默认实心(block.properties "everything else")
        }
        CLASS_CACHE.put(state, code);
        if (CLASS_CACHE.size() > 4096) CLASS_CACHE.clear(); // 防极端模组包状态爆炸
        return code;
    }
}

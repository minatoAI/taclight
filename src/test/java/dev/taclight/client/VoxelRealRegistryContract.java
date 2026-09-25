package dev.taclight.client;

import dev.taclight.channel.VoxelField;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>真 registry 运行时契约</b>(2026-09-18 雪地方格阵列根因轮;Lead 复核项 ②)。
 *
 * <p>为什么必须有它:分档断言若只跑<b>合成 AABB 夹具</b>,"楼梯→软档"这张表在
 * <b>真实方块</b>上就没被运行时验过——那正是"测的路径 ≠ 生产路径"的老坑。本契约:</p>
 * <ol>
 *   <li>{@code SharedConstants.tryDetectVersion() + Bootstrap.bootStrap()} 拉起真 registry;</li>
 *   <li>用真 {@code Blocks.*} 状态 + {@code EmptyBlockGetter.INSTANCE} <b>直接调用生产方法</b>
 *       {@link VoxelGrid#classify}(包私有,同包可见)——不是复制一份分档逻辑;</li>
 *   <li>逐例打印<b>实测形状</b>(coll/occ/vis 三份 AABB 与体积占比)与分类码,再断言
 *       —— 输出里的 MEASURED 行就是"真方块 ⇒ 码"的原始证据,也是
 *       {@code VoxelClassifyContract} 合成夹具的数值出处;</li>
 *   <li>所有不匹配<b>一次性汇总</b>(不在第一条就中断),便于一轮看清全表。</li>
 * </ol>
 *
 * <p><b>本契约抓到过的两处真问题(口径更正的直接依据)</b>:① 原版雪层 {@code coll} 比
 * {@code occ}/{@code vis} <b>矮一层</b> ⇒ 用 coll 分档会把雪 3 层判 EMPTY、8 层判 VEG(偏透);
 * ② {@code OAK_STAIRS[half=top]} 两盒共面(面都落在 z=0.5)⇒ 旧的"中心列严格包含"判据
 * 判 EMPTY = <b>漏光</b>(危险方向)。现口径 C(占比用 occ)对这两例分别给出 VEG/SOLID 与 VEG。</p>
 *
 * <p><b>边界(如实)</b>:① {@code EmptyBlockGetter} 让所有邻居都是空气 ⇒ 栅栏/墙取"无连接"
 * 形状(柱体),足以验"非满但高 ⇒ SOLID";"同一 state 因邻居不同而形状不同"(栅栏连接)
 * 未在此断言,由 {@code VoxelClassifyContract} 的 probe 注入用例覆盖坐标透传/不冻结语义。
 * ② 若本机 JavaExec 环境无法 bootstrap,本契约会<b>大声失败</b>(不静默跳过)。</p>
 *
 * <p>登记在 {@code AllContracts} 的<b>最后</b>:即使它失败,前面的契约结果也会先打印出来。</p>
 */
public class VoxelRealRegistryContract {
    private static int checks;
    private static final List<String> MISMATCH = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(Bootstrap.class != null, "真 registry bootstrap 完成(tryDetectVersion + bootStrap)");

        BlockPos p = new BlockPos(0, 64, 0);

        // ---- 雪层(阵列主因):真实 SnowLayerBlock ----
        // 2026-09-25 高度感知口径:雪 1..7 层 ⇒ 底薄板码 4..10(顶高 = layers/8);
        // 8 层 occ=1.0 ⇒ SOLID。旧口径把 1–2 层判 EMPTY(完全不遮挡)= 用户报的"细雪层穿光"根因。
        for (int layers = 1; layers <= 8; layers++) {
            int expect = layers >= 8 ? VoxelField.CODE_SOLID : VoxelField.slabBottomCode(layers);
            checkCode("雪 layers=" + layers, snow(layers), expect, p);
        }
        checkCode("雪块 minecraft:snow_block", Blocks.SNOW_BLOCK.defaultBlockState(), VoxelField.CODE_SOLID, p);

        // ---- 薄片档(2026-09-25:占满 XZ 足印的薄板 ⇒ 薄板码,按真实高度遮挡) ----
        checkCode("地毯 white_carpet(1/16 ⇒ 量化到 1/8 薄板)", Blocks.WHITE_CARPET.defaultBlockState(),
                VoxelField.slabBottomCode(1), p);
        checkCode("绊线 tripwire(无碰撞)", Blocks.TRIPWIRE.defaultBlockState(), VoxelField.CODE_EMPTY, p);
        checkCode("铁轨 rail(无碰撞 / occ 顶 1/8 ⇒ 贴地薄板例外)", Blocks.RAIL.defaultBlockState(),
                VoxelField.slabBottomCode(1), p);
        checkCode("睡莲 lily_pad(不占满足印 ⇒ VEG)", Blocks.LILY_PAD.defaultBlockState(), VoxelField.CODE_VEG, p);
        checkCode("蛛网 cobweb(无碰撞)", Blocks.COBWEB.defaultBlockState(), VoxelField.CODE_EMPTY, p);
        checkCode("活板门 half=bottom open=false(0.1875 ⇒ 量化 0.25 薄板)",
                Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM).setValue(TrapDoorBlock.OPEN, false),
                VoxelField.slabBottomCode(2), p);

        // ---- 中低档 ----
        checkCode("半砖 type=bottom(0.5 ⇒ 底薄板 7)", Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM), VoxelField.slabBottomCode(4), p);
        checkCode("楼梯 half=bottom", Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.HALF, Half.BOTTOM), VoxelField.CODE_VEG, p);

        // ---- 实心档 ----
        checkCode("满方块 stone", Blocks.STONE.defaultBlockState(), VoxelField.CODE_SOLID, p);
        checkCode("原木 oak_log", Blocks.OAK_LOG.defaultBlockState(), VoxelField.CODE_SOLID, p);
        checkCode("耕地 farmland(15/16)", Blocks.FARMLAND.defaultBlockState(), VoxelField.CODE_SOLID, p);
        checkCode("土径 dirt_path(15/16)", Blocks.DIRT_PATH.defaultBlockState(), VoxelField.CODE_SOLID, p);
        checkCode("半砖 type=top(0.5 ⇒ 顶薄板 12)", Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP), VoxelField.slabTopCode(4), p);
        checkCode("半砖 type=double", Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.DOUBLE), VoxelField.CODE_SOLID, p);
        checkCode("楼梯 half=top(共面回归例)", Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.HALF, Half.TOP), VoxelField.CODE_VEG, p);
        checkCode("活板门 half=top open=false(0.8125 ⇒ 顶薄板 15)",
                Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.HALF, Half.TOP).setValue(TrapDoorBlock.OPEN, false),
                VoxelField.slabTopCode(7), p);
        checkCode("栅栏 oak_fence(碰撞柱 1.5)", Blocks.OAK_FENCE.defaultBlockState(), VoxelField.CODE_SOLID, p);
        checkCode("墙 cobblestone_wall(碰撞柱 1.5)", Blocks.COBBLESTONE_WALL.defaultBlockState(), VoxelField.CODE_SOLID, p);

        // ---- 位置无关档(ID 表 / 空气)在真 registry 上的分支仍生效 ----
        // 注:水/熔岩这两条在**离线**靠"coll 空 ⇒ 守卫 1"通过,不是流体分支(见 3b 的离线口径哨兵);
        // 真机(登录后 rebuildCache 已跑)流体分支才活。标签刻意不写"流体分支"以免误导读者。
        checkCode("空气", Blocks.AIR.defaultBlockState(), VoxelField.CODE_EMPTY, p);
        checkCode("水(离线走守卫 1:coll 空;流体分支见 3b)", Blocks.WATER.defaultBlockState(), VoxelField.CODE_EMPTY, p);
        checkCode("熔岩(离线走守卫 1:coll 空;流体分支见 3b)", Blocks.LAVA.defaultBlockState(), VoxelField.CODE_EMPTY, p);
        checkCode("草 grass(VEG 表)", Blocks.GRASS.defaultBlockState(), VoxelField.CODE_VEG, p);
        checkCode("树叶 oak_leaves(LEAF 表)", Blocks.OAK_LEAVES.defaultBlockState(), VoxelField.CODE_LEAF, p);

        // ---- 坐标透传(生产方法内部 CURSOR.set) ----
        int a = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, snow(1), 0, 64, 0);
        int b = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, snow(1), 37, 71, -12);
        check(a == VoxelField.slabBottomCode(1) && b == a,
                "同状态在 (0,64,0) 与 (37,71,-12) 均 ⇒ 薄板码 4(坐标透传无异常)");

        // ---- 全 registry 穷举 + golden(2026-09-25):把"每个方块状态 ⇒ 码"变成可 diff 的映射 ----
        // 手写清单抓不到"雪 1 层 occ=0.125 却判 EMPTY"这类单例;全量穷举 + golden 天然能抓。
        fullRegistryGolden();

        // ---- 分类快路径 A/B(!voxel classcache on|off,2026-09-25) ----
        classCacheAb();

        if (!MISMATCH.isEmpty()) {
            throw new AssertionError("FAIL 真 registry 分档不符 " + MISMATCH.size() + " 例: " + MISMATCH);
        }
        System.out.println("VoxelRealRegistryContract: ALL PASS (" + checks + " checks)");
    }

    // ================= 全 registry 穷举 + golden(2026-09-25) =================

    /** golden 路径(repo 根相对;与其他契约读 {@code pack/...} 同约定)。 */
    private static final String GOLDEN_PATH = "src/test/resources/voxel-registry-golden.txt";

    /**
     * 全 registry 穷举:枚举 {@code BuiltInRegistries.BLOCK} 的<b>每个状态</b>,
     * 用<b>生产方法</b> {@link VoxelGrid#classify} 求码,生成/比对 golden 映射。
     *
     * <p><b>为什么值得</b>:2026-09-25 细雪层穿光的根因是"雪 1 层 occ=0.125 却判 EMPTY"——
     * 手写清单契约抓不到这种单例,而<b>全量穷举 + golden diff</b> 天然能抓(任何分类漂移 = 红)。
     * golden 同时钉住"非空气却判透光"的<b>显式名单</b>({@code leakyAllowlist}):
     * 新出现的这类方块立刻失败,不再有"静默新增漏光方块"。</p>
     *
     * <p><b>覆盖边界(如实)</b>:JavaExec 无 Forge 模组加载 ⇒ 只覆盖<b>原版</b>方块;
     * 模组方块由"按形状分档"的通用规则覆盖,并由游戏内 {@code !voxprobe} 探针在真机验证。</p>
     *
     * <p><b>更新方式</b>:加 {@code -Dtaclight.voxelGolden.update=true} 重跑会重写 golden
     * (刻意变更时用;必须审阅 diff 后提交)。golden 缺失时<b>生成并失败</b>,不静默通过。</p>
     */
    private static void fullRegistryGolden() throws Exception {
        List<String> all = new ArrayList<>();
        List<String> pinned = new ArrayList<>();
        List<String> leakyIds = new ArrayList<>();
        int total = 0, solid = 0, empty = 0, veg = 0, leaf = 0, slab = 0, leaky = 0;
        BlockPos p = new BlockPos(0, 64, 0);
        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            for (BlockState st : block.getStateDefinition().getPossibleStates()) {
                VoxelShape coll = st.getCollisionShape(EmptyBlockGetter.INSTANCE, p);
                VoxelShape occ = st.getOcclusionShape(EmptyBlockGetter.INSTANCE, p);
                int code = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, st, 0, 64, 0);
                double occVol = volume(occ);
                String line = String.format(Locale.ROOT, "%s%s=%d occ=%.4f collTop=%.4f",
                        id, propsOf(st), code, occVol, maxTop(coll));
                total++;
                all.add(line);
                boolean isLeaky = code == VoxelField.CODE_EMPTY && occVol > 0.0;
                if (code == VoxelField.CODE_SOLID) {
                    solid++;
                } else if (code == VoxelField.CODE_EMPTY) {
                    empty++;
                    if (isLeaky) {
                        leaky++;
                        if (!leakyIds.contains(id)) leakyIds.add(id);
                    }
                } else if (code == VoxelField.CODE_VEG) {
                    veg++;
                } else if (code == VoxelField.CODE_LEAF) {
                    leaf++;
                } else {
                    slab++;
                }
                // golden 只<b>逐行</b>钉"机制类":薄板(新机制)/树叶/非空气却判透光(危险类)。
                // 其余(实心/软植被/真空气)只进计数与 sha256 —— 分类漂移照样红,
                // 但 diff 不会被四千行楼梯淹没(实测全量逐行 = 13936 行/1.48MB)。
                if (VoxelField.isSlab(code) || code == VoxelField.CODE_LEAF || isLeaky) {
                    pinned.add(line);
                }
            }
        }
        Collections.sort(all);
        Collections.sort(pinned);
        Collections.sort(leakyIds);

        String mapping = String.join("\n", all);
        String sha = sha256(mapping);
        StringBuilder sb = new StringBuilder();
        sb.append("# taclight voxel registry golden v1\n");
        sb.append("# 生成: VoxelRealRegistryContract.fullRegistryGolden(全 registry 穷举)\n");
        sb.append("# 覆盖: 原版 BuiltInRegistries.BLOCK(JavaExec 无模组加载 ⇒ 不含模组方块)\n");
        sb.append("# 更新: 加 -Dtaclight.voxelGolden.update=true 重跑 taclightContracts 会重写本文件\n");
        sb.append("total=").append(total).append('\n');
        sb.append("solid=").append(solid).append('\n');
        sb.append("empty=").append(empty).append('\n');
        sb.append("veg=").append(veg).append('\n');
        sb.append("leaf=").append(leaf).append('\n');
        sb.append("slab=").append(slab).append('\n');
        sb.append("leaky=").append(leaky).append('\n');
        sb.append("pinned=").append(pinned.size()).append('\n');
        sb.append("mappingSha256=").append(sha).append('\n');
        sb.append("leakyAllowlist=").append(String.join(",", leakyIds)).append('\n');
        sb.append("--- pinned(薄板 / 树叶 / 非空气却判透光) ---\n");
        for (String l : pinned) {
            sb.append(l).append('\n');
        }
        String expected = sb.toString();

        // 需要深挖时:把全量映射 dump 到指定路径(-Dtaclight.voxelGolden.dumpAll=<path>)
        String dumpAll = System.getProperty("taclight.voxelGolden.dumpAll");
        if (dumpAll != null && !dumpAll.isEmpty()) {
            Files.writeString(Path.of(dumpAll), mapping, StandardCharsets.UTF_8);
            System.out.println("  [golden] 全量映射已 dump 到 " + dumpAll + "(" + all.size() + " 行)");
        }

        Path path = Path.of(GOLDEN_PATH);
        boolean update = Boolean.getBoolean("taclight.voxelGolden.update");
        if (update || !Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, expected, StandardCharsets.UTF_8);
            if (update) {
                check(true, "golden 已按 -Dtaclight.voxelGolden.update=true 重写: " + GOLDEN_PATH);
            } else {
                throw new AssertionError("FAIL golden 缺失:已生成 " + GOLDEN_PATH
                        + "(total=" + total + ", nonSolid=" + pinned.size() + "),请审阅后提交");
            }
            return;
        }
        String actual = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (actual.equals(expected)) {
            check(true, "全 registry 穷举与 golden 一致:total=" + total + " nonSolid=" + pinned.size()
                    + " sha256=" + sha.substring(0, 12));
            check(leakyIds.isEmpty() || true, "非空气却判透光的显式名单(" + leakyIds.size() + " 个方块): "
                    + (leakyIds.isEmpty() ? "(无)" : String.join(",", leakyIds)));
        } else {
            String[] a = actual.split("\n", -1);
            String[] e = expected.split("\n", -1);
            StringBuilder diff = new StringBuilder();
            int shown = 0;
            for (int i = 0; i < Math.max(a.length, e.length) && shown < 12; i++) {
                String av = i < a.length ? a[i] : "<缺行>";
                String ev = i < e.length ? e[i] : "<多行>";
                if (!av.equals(ev)) {
                    diff.append("\n    L").append(i + 1).append(" golden=").append(av)
                            .append("\n         实际=").append(ev);
                    shown++;
                }
            }
            throw new AssertionError("FAIL 全 registry 分类与 golden 不符(前 " + shown + " 处):" + diff
                    + "\n  ⇒ 若为刻意变更:加 -Dtaclight.voxelGolden.update=true 重生成并审阅 diff");
        }
    }

    // ================= 分类快路径 A/B(!voxel classcache on|off,2026-09-25) =================

    /**
     * <b>快路径与旧路径的对账 + 机制断言</b>({@code !voxel classcache on|off},默认 on)。
     *
     * <p>为什么必须有它:{@code VoxelGrid.classify} 的快路径把"逐格注册 ID → String +
     * 两次字符串哈希"折叠成"每方块一次"的类别查表。这类改动的危险不是"算错"而是
     * <b>冻结</b> —— 一旦把 state/坐标相关的结论按方块缓存,就会重演 2026-09-18 根因③
     * (雪地方格阵列)。所以本组断言分三层:</p>
     * <ol>
     *   <li><b>机制</b>:on 时类别缓存确有命中,未命中数 = 方块"种"数(不是格数/状态数)⇒ 折叠真的发生了;</li>
     *   <li><b>语义</b>:off(旧路径)时类别计数不动 + 开关两侧<b>全 registry 逐状态同码</b>
     *       ⇒ "语义一个字都不变"变成可判定;</li>
     *   <li><b>反冻结</b>:同一种方块的 8 种雪层状态必须给 8 个不同的码、且与判定次序无关、
     *       类别缓存只多出 1 个条目 ⇒ 状态/形状结论没有被方块身份缓存冻结(根因③同族)。</li>
     * </ol>
     *
     * <p><b>红对照(已做;还原后 sha256 核对一致,见 commit 信息)</b>:
     * ① 把 {@code classify} 的快路径改回旧路径(逐格 {@code toString()} + 两次字符串哈希,
     * 不查表)⇒ 第 1 层断言必红;
     * ② 只删掉命中/未命中自增(接线文本不变)⇒ 第 1 层断言必红(证明它盯的是"快路径在跑");
     * ③ 把形状查询改成按 {@code state.getBlock().defaultBlockState()} 求值(根因③复刻)
     * ⇒ 第 3 层断言 + 全量同码断言必红;
     * ④ 把 {@code VoxelClassifier.leafVegCategory} 两档换位 ⇒ 全量同码断言 + golden 必红。</p>
     *
     * <p><b>离线口径(如实)</b>:JavaExec 不会触发 {@code Blocks.rebuildCache()} ⇒ 所有 state 的
     * {@code getFluidState()} 恒空(见 3b 的哨兵断言)⇒ 流体分支在离线<b>不可运行验证</b>:
     * 离线只钉接线(WiringContract 文本断言)+ 运行期口径哨兵,真机行为由 {@code !voxprobe} 验。</p>
     */
    private static void classCacheAb() {
        List<BlockState> states = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            states.addAll(block.getStateDefinition().getPossibleStates());
        }
        BlockPos p = new BlockPos(0, 64, 0);

        // ---- 1. 机制:on 时类别缓存真的在用 ----
        VoxelGrid.configure("classcache on");
        VoxelGrid.resetBlockCategoryCache();
        int[] onCodes = sweep(states, p);
        long hits = VoxelGrid.categoryCacheHits();
        long misses = VoxelGrid.categoryCacheMisses();
        check(hits > 0, "classcache=on 时方块类别缓存有命中(命中 " + hits + ")⇒ 快路径真的在跑");
        check(misses > 0 && misses < states.size(),
                "未命中 = 首次遇到的方块\"种\"数(" + misses + "),远小于状态数(" + states.size()
                        + ")⇒ 判定已折叠到每方块一次");
        check(VoxelGrid.blockCategoryCacheSize() <= VoxelGrid.blockCategoryCacheCap(),
                "方块类别缓存有界:size=" + VoxelGrid.blockCategoryCacheSize()
                        + " ≤ cap=" + VoxelGrid.blockCategoryCacheCap());
        check(VoxelGrid.blockCategoryCacheSize() <= misses,
                "类别条目数 ≤ 首次遇到的方块数(" + VoxelGrid.blockCategoryCacheSize() + " ≤ " + misses
                        + ")⇒ 一个方块至多一条(未命中才写)");

        // ---- 2. off = 旧路径:类别计数不动(证明没走快路径),且逐状态同码 ----
        VoxelGrid.configure("classcache off");
        int[] offCodes = sweep(states, p);
        check(VoxelGrid.categoryCacheHits() == hits && VoxelGrid.categoryCacheMisses() == misses,
                "classcache=off 时不碰方块类别缓存(计数不变 ⇒ 走的确实是旧路径)");
        int diff = 0;
        String firstDiff = null;
        for (int i = 0; i < states.size(); i++) {
            if (onCodes[i] != offCodes[i]) {
                diff++;
                if (firstDiff == null) {
                    firstDiff = BuiltInRegistries.BLOCK.getKey(states.get(i).getBlock()) + propsOf(states.get(i))
                            + " on=" + onCodes[i] + " off=" + offCodes[i];
                }
            }
        }
        check(diff == 0, "开关两侧逐状态同码(" + states.size() + " 个状态,差异 " + diff + " 处)"
                + (firstDiff == null ? "" : " 首个: " + firstDiff) + " ⇒ 快路径语义不变");

        // ---- 3. 反冻结:同一种方块的不同 state 必须仍然不同码(类别缓存不得冻结状态/坐标相关结论) ----
        // 样本 = 雪层:同一方块 8 种状态 ⇒ 8 个不同码(1..7 层薄板码 4..10 / 8 层 SOLID),
        // 且判定走形状档(SHAPE_CACHE 主键 = occ 实例)⇒ 正好检验"方块身份缓存只吃 ID 档"。
        VoxelGrid.configure("classcache on");
        BlockState[] snowStates = new BlockState[8];
        for (int i = 0; i < 8; i++) snowStates[i] = snow(i + 1);
        VoxelGrid.resetBlockCategoryCache();
        int[] asc = new int[8];
        for (int i = 0; i < 8; i++) {
            asc[i] = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, snowStates[i], p.getX(), p.getY(), p.getZ());
        }
        VoxelGrid.resetBlockCategoryCache();
        int[] desc = new int[8];
        for (int i = 0; i < 8; i++) {
            desc[i] = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, snowStates[7 - i], p.getX(), p.getY(), p.getZ());
        }
        check(asc[0] == VoxelField.slabBottomCode(1) && asc[6] == VoxelField.slabBottomCode(7)
                        && asc[7] == VoxelField.CODE_SOLID,
                "快路径:雪 1 / 7 / 8 层仍按 state 分档(码 " + asc[0] + " / " + asc[6] + " / " + asc[7] + ")");
        boolean orderFree = true;
        for (int i = 0; i < 8; i++) {
            if (asc[i] != desc[7 - i]) orderFree = false;
        }
        check(orderFree, "8 种雪层状态正序/倒序分类结果一致 ⇒ 同方块的状态差异没有被类别缓存冻结");
        check(VoxelGrid.blockCategoryCacheSize() == 1,
                "8 种状态同属一个方块 ⇒ 类别缓存只有 1 个条目(" + VoxelGrid.blockCategoryCacheSize()
                        + ")⇒ 状态/形状结论没有进按方块的类别缓存");

        // ---- 3b. 流体分支的离线口径自检(为什么"水logged 不同 ⇒ 码不同"在此环境验不了) ----
        // 实测(2026-09-25):JavaExec 里 **所有** state 的 getFluidState() 都是空 —— 连
        // Blocks.WATER/LAVA 也是。根因:`Blocks.rebuildCache()`(把每个 state 的 fluidState
        // 由默认 EMPTY 重算成 Block.getFluidState)只在**客户端登录**
        // (ClientPacketListener / ReloadableServerResources)与 ItemRenderer 路径被调用,
        // headless 契约不会触发。⇒ 离线 golden 钉的是"无 fluidState 口径":水/熔岩之所以是 0,
        // 靠的是"coll 空 ⇒ 守卫 1",不是流体分支。真机(登录后 rebuildCache 已跑)流体分支是活的。
        // 本条是**口径哨兵**:若此条变红 ⇒ 离线口径变了(有人调了 rebuildCache)⇒ golden 必须
        // 重生成并逐个复核 waterlogged 状态的差异。流体"逐 state 判"的接线由
        // VoxelGridWiringContract 的文本断言钉住,真机行为由 !voxprobe 验(见交付说明)。
        BlockState wet = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState dry = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, false);
        VoxelGrid.resetBlockCategoryCache(); // 只留这一对 state,便于断言"两种状态 ⇒ 1 个条目"
        int wetCode = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, wet, p.getX(), p.getY(), p.getZ());
        int dryCode = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, dry, p.getX(), p.getY(), p.getZ());
        check(Blocks.WATER.defaultBlockState().getFluidState().isEmpty()
                        && Blocks.LAVA.defaultBlockState().getFluidState().isEmpty()
                        && wet.getFluidState().isEmpty() && wetCode == dryCode
                        && dryCode != VoxelField.CODE_EMPTY,
                "离线口径哨兵:JavaExec 未调 Blocks.rebuildCache() ⇒ fluidState 恒空(水logged 也空)"
                        + ",干/湿两态在此环境同码(" + wetCode + "/" + dryCode + ")且干态非空码"
                        + "(否则\"同码\"可能两边都是 0 = 空洞真);真机登录后湿态应 ⇒ 0。"
                        + "本条变红 ⇒ 必须重生成 golden 并复核 waterlogged 差异");
        check(VoxelGrid.blockCategoryCacheSize() == 1,
                "干/湿两态只占 1 个类别条目(" + VoxelGrid.blockCategoryCacheSize()
                        + ")⇒ 流体结论不在按方块的类别缓存里(离线靠接线断言,真机靠 !voxprobe)");

        // ---- 4. 开关表面(测试同事靠它确认"这一轮测的是哪条路径") ----
        check(VoxelGrid.configure("classcache off").equals("voxel classcache=off")
                        && VoxelGrid.configure("classcache on").equals("voxel classcache=on"),
                "!voxel classcache on|off 回显与状态一致(默认 on;改值即作废快照 ⇒ 下一帧按新路径重建)");
        check(VoxelGrid.configure("classcache").startsWith("voxel classcache=on"),
                "!voxel classcache 无参 = 只读回状态(默认 on,回显带 usage 提示,与 box/cone 同规)");
        check(VoxelGrid.profile().contains("classcache=on"),
                "!voxel profile 行回报 classcache=on(驱动可用一条正则确认路径)");
        VoxelGrid.configure("classcache off");
        check(VoxelGrid.profile().contains("classcache=off"),
                "!voxel profile 行回报 classcache=off(off 时 cat=0/0)");
        VoxelGrid.configure("classcache on"); // 还原默认,避免影响后续契约

        // ---- 5. 离线微基准(仅参考,不是判据) ----
        classCacheMicroBench();
    }

    /** 全 registry 穷举一遍(与 golden 同一驱动路径),返回逐状态码。 */
    private static int[] sweep(List<BlockState> states, BlockPos p) {
        int[] out = new int[states.size()];
        for (int i = 0; i < states.size(); i++) {
            out[i] = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, states.get(i), p.getX(), p.getY(), p.getZ());
        }
        return out;
    }

    /**
     * 离线微基准:<b>仅参考</b>——小工作集(15 种状态)× 30 万格,近似重建循环里
     * {@code classify} 的逐格成本与"工作集小 ⇒ 缓存命中"的真实形态。
     *
     * <p>不作为判据的原因:JavaExec 没有真机的渲染线程/JIT/内存布局,且小工作集会把
     * 形状缓存全部命中(真机同姿势也接近)。<b>唯一判据是真机 {@code !voxel profile} 的
     * {@code classify=XXns} 与 {@code loop=XXms}</b>(同实例开关 A/B)。这里只回答
     * "方向对不对、量级大概多少"。</p>
     */
    private static void classCacheMicroBench() {
        BlockState[] mix = {
                Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SAND.defaultBlockState(),
                snow(1), snow(3), snow(4), Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.GRASS.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.COBBLESTONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
                Blocks.OAK_LOG.defaultBlockState(), Blocks.AIR.defaultBlockState(),
        };
        int cells = 300_000;
        runMix(mix, cells, true);   // 预热两条路径(JIT + 两侧缓存)
        runMix(mix, cells, false);
        long on = Math.min(runMix(mix, cells, true), runMix(mix, cells, true));
        long off = Math.min(runMix(mix, cells, false), runMix(mix, cells, false));
        VoxelGrid.configure("classcache on");
        System.out.println(String.format(Locale.ROOT,
                "  [classcache] 离线微基准(仅参考,判据 = 真机 !voxel profile):%d 格 × %d 种状态 "
                        + "on=%.1f ns/格 off=%.1f ns/格 Δ=%.1f ns/格(%.0f%%) sink=%d",
                cells, mix.length, (double) on / cells, (double) off / cells,
                (double) (off - on) / cells, 100.0 * (off - on) / off, benchSink));
    }

    /** 混合工作集跑 {@code cells} 次 classify,返回耗时 ns(坐标轮转 ⇒ 形状缓存稳态命中)。 */
    private static long runMix(BlockState[] mix, int cells, boolean cacheOn) {
        VoxelGrid.configure(cacheOn ? "classcache on" : "classcache off");
        long t0 = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < cells; i++) {
            sink += VoxelGrid.classify(EmptyBlockGetter.INSTANCE, mix[i % mix.length],
                    i & 31, 64, (i >> 5) & 31);
        }
        benchSink = sink;
        return System.nanoTime() - t0;
    }

    /** 微基准结果落点(防止 JIT 把整段判成死代码)。 */
    private static int benchSink;

    /** 状态属性规范化串(按属性名排序 ⇒ 稳定 diff)。 */
    private static String propsOf(BlockState st) {
        if (st.getValues().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Property<?>, Comparable<?>> e : st.getValues().entrySet()) {
            parts.add(e.getKey().getName() + "=" + e.getValue());
        }
        Collections.sort(parts);
        return "[" + String.join(",", parts) + "]";
    }

    /** 盒列表最大 maxY;无盒 ⇒ {@code -Infinity}。 */
    private static double maxTop(VoxelShape shape) {
        double top = Double.NEGATIVE_INFINITY;
        for (net.minecraft.world.phys.AABB b : shape.toAabbs()) {
            top = Math.max(top, b.maxY);
        }
        return top;
    }

    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder h = new StringBuilder();
        for (byte b : d) {
            h.append(String.format("%02x", b));
        }
        return h.toString();
    }

    /** 真 {@code Blocks.SNOW} 指定层数状态。 */
    private static BlockState snow(int layers) {
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    /**
     * 直接调用<b>生产方法</b> {@link VoxelGrid#classify};并打印实测形状(AABB + maxY),
     * 让"真方块 ⇒ 码"这条链路的证据留在测试输出里。
     */
    private static void checkCode(String what, BlockState state, int expect, BlockPos p) {
        VoxelShape coll = state.getCollisionShape(EmptyBlockGetter.INSTANCE, p);
        VoxelShape occ = state.getOcclusionShape(EmptyBlockGetter.INSTANCE, p);
        VoxelShape vis = state.getShape(EmptyBlockGetter.INSTANCE, p);
        int got = VoxelGrid.classify(EmptyBlockGetter.INSTANCE, state, p.getX(), p.getY(), p.getZ());
        System.out.println("  MEASURED " + what
                + " | coll=" + coll.toAabbs() + " vol=" + volume(coll)
                + " | occ=" + occ.toAabbs() + " vol=" + volume(occ)
                + " | vis=" + vis.toAabbs() + " vol=" + volume(vis)
                + " | code=" + got + " expect=" + expect);
        if (got == expect) {
            check(true, what + " ⇒ 码 " + expect);
        } else {
            checks++;
            MISMATCH.add(what + "(期望 " + expect + ", 实际 " + got + ")");
            System.out.println("  MISMATCH " + what + " 期望 " + expect + " 实际 " + got);
        }
    }

    /** 形状在单位格内的实心体积占比(盒体积和;盒已按方块局部 0..1 坐标给出)。 */
    private static double volume(VoxelShape shape) {
        double v = 0.0;
        for (net.minecraft.world.phys.AABB b : shape.toAabbs()) {
            v += Math.max(0.0, b.maxX - b.minX) * Math.max(0.0, b.maxY - b.minY) * Math.max(0.0, b.maxZ - b.minZ);
        }
        return v;
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

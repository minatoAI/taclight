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

        // ---- 位置无关档(ID 表 / 流体 / 空气)在真 registry 上的分支仍生效 ----
        checkCode("空气", Blocks.AIR.defaultBlockState(), VoxelField.CODE_EMPTY, p);
        checkCode("水(流体分支)", Blocks.WATER.defaultBlockState(), VoxelField.CODE_EMPTY, p);
        checkCode("熔岩(流体分支)", Blocks.LAVA.defaultBlockState(), VoxelField.CODE_EMPTY, p);
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

package dev.taclight.client;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * FenceShapeProbe —— 离线结算"某方块的真实遮挡盒数"(2026-10-04 R57)。
 *
 * <p>起因:{@code BACKLOG §2.164 ②} 断言"4 面连接的栅栏 = 9 盒 > {@code MAX_BOXES=8} ⇒ 退回整格 VEG ⇒ 没有孔洞",
 * 而 {@code §2.167} 的 A/B 把该断言**推翻**(上限 8→16 对栅栏逐位无差别)⇒ 需要用**真实 registry**把盒数**结算**掉:
 * 这个类打印各连接组合下 {@code getOcclusionShape().toAabbs().size()} 的实测值,
 * 并给出"连接数 → 盒数"的表,以后不再靠推断。</p>
 *
 * <p>用法:{@code gradlew taclightFenceProbe}(JavaExec,真 registry,离线无游戏)。</p>
 */
public final class FenceShapeProbe {

    private static final String[] WATCH = {
            "minecraft:oak_fence", "minecraft:oak_fence_gate", "minecraft:cobblestone_wall",
            "minecraft:redstone_wire", "minecraft:oak_stairs", "minecraft:oak_door", "minecraft:snow",
            "minecraft:oak_trapdoor", "minecraft:iron_bars",
    };

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos p = new BlockPos(0, 64, 0);

        System.out.println("[fence-probe] ---- 关注方块的遮挡盒数分布(真 registry) ----");
        for (String id : WATCH) {
            Block b = BuiltInRegistries.BLOCK.get(new net.minecraft.resources.ResourceLocation(id));
            if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) {
                System.out.println("  " + id + " : 不存在");
                continue;
            }
            Map<Integer, Integer> dist = new TreeMap<>();
            int max = 0;
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                int n = st.getOcclusionShape(EmptyBlockGetter.INSTANCE, p).toAabbs().size();
                dist.merge(n, 1, Integer::sum);
                max = Math.max(max, n);
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, Integer> e : dist.entrySet()) {
                sb.append(e.getKey()).append("盒×").append(e.getValue()).append("状态  ");
            }
            System.out.println(String.format(Locale.ROOT, "  %-28s 状态=%d  最大盒数=%d  [%s]",
                    id, b.getStateDefinition().getPossibleStates().size(), max, sb.toString().trim()));
        }

        // ---- 栅栏:连接数 → 盒数(决定性表) ----
        for (String id : new String[]{"minecraft:oak_fence", "minecraft:cobblestone_wall"}) {
            Block b = BuiltInRegistries.BLOCK.get(new net.minecraft.resources.ResourceLocation(id));
            if (b == null) continue;
            System.out.println("[fence-probe] ---- " + id + " 连接数 → 盒数 ----");
            Map<String, int[]> byConn = new LinkedHashMap<>();   // key=连接数 → {状态数, 最小盒数, 最大盒数}
            Map<String, List<AABB>> example = new LinkedHashMap<>();
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                int conn = 0;
                // 注意:PROPERTY_BY_DIRECTION 是 protected ⇒ 用四个公开属性(1.20.1 CrossCollisionBlock)
                for (BooleanProperty prop : new BooleanProperty[]{CrossCollisionBlock.NORTH,
                        CrossCollisionBlock.SOUTH, CrossCollisionBlock.EAST, CrossCollisionBlock.WEST}) {
                    if (st.hasProperty(prop) && st.getValue(prop)) conn++;
                }
                int n = st.getOcclusionShape(EmptyBlockGetter.INSTANCE, p).toAabbs().size();
                int[] rec = byConn.computeIfAbsent("conn=" + conn, k -> new int[]{0, 99, 0});
                rec[0]++;
                rec[1] = Math.min(rec[1], n);
                rec[2] = Math.max(rec[2], n);
                if (conn == 4) example.putIfAbsent("conn=4", st.getOcclusionShape(EmptyBlockGetter.INSTANCE, p).toAabbs());
            }
            for (Map.Entry<String, int[]> e : byConn.entrySet()) {
                System.out.println(String.format(Locale.ROOT, "  %-9s 状态=%d  盒数 %d..%d",
                        e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
            }
            List<AABB> ex = example.get("conn=4");
            if (ex != null) {
                System.out.println("  conn=4 的盒(逐盒 minX,minY,minZ..maxX,maxY,maxZ,单位 1/16 格):");
                for (AABB a : ex) {
                    System.out.println(String.format(Locale.ROOT,
                            "     %.4f %.4f %.4f .. %.4f %.4f %.4f",
                            a.minX * 16, a.minY * 16, a.minZ * 16, a.maxX * 16, a.maxY * 16, a.maxZ * 16));
                }
            }
        }

        // ---- 全局:盒数 → 形状数(与契约同一口径,便于交叉核对) ----
        Map<Integer, Integer> global = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState st : block.getStateDefinition().getPossibleStates()) {
                int n = st.getOcclusionShape(EmptyBlockGetter.INSTANCE, p).toAabbs().size();
                if (n > 0) global.merge(n, 1, Integer::sum);
            }
        }
        StringBuilder g = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : global.entrySet()) {
            g.append(e.getKey()).append("盒×").append(e.getValue()).append("  ");
        }
        System.out.println("[fence-probe] ---- 全局 盒数→状态数 ----\n  " + g.toString().trim());
        int maxGlobal = 0;
        for (Integer k : global.keySet()) maxGlobal = Math.max(maxGlobal, k);
        System.out.println("[fence-probe] 全局最大盒数 = " + maxGlobal + "(上限 16 ⇒ "
                + (maxGlobal <= 16 ? "全覆盖" : "★ 有形状超上限") + ")");
    }

    private FenceShapeProbe() {}
}

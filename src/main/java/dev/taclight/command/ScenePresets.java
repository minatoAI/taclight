package dev.taclight.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 布景计划(纯数据,无 Minecraft 依赖,契约可测 ScenePlanContract)。
 * 场景区契约:X=2000,Z=0,平台方块层 y=120(顶面 y=121,与 CamStore 内置机位一致)。
 * 预设规格 = docs/调试环境搭建计划.md §3A;执行在 SceneExecutor。
 * 几何改动必须同步 ScenePlanContract 与内置机位 —— 契约测试钉死这个三角。
 */
public final class ScenePresets {
    /** 场景区边界(含清场盒与坐标守卫;fill 一次体积上限 32768,此盒 = 44x20x31 = 27280)。 */
    public static final int REGION_MIN_X = 1985, REGION_MAX_X = 2028;
    public static final int REGION_MIN_Y = 119, REGION_MAX_Y = 138;
    public static final int REGION_MIN_Z = -15, REGION_MAX_Z = 15;

    /** 全预设通用平台:21x21 平滑石,层 y=120。 */
    private static final ScenePresets.Fill PLATFORM =
            new Fill("minecraft:smooth_stone", 1990, 120, -10, 2010, 120, 10);

    /** 方块填充计划:x1..x2 闭区间。 */
    public record Fill(String block, int x1, int y1, int z1, int x2, int y2, int z2) {
        public Fill {
            if (x1 > x2) { int t = x1; x1 = x2; x2 = t; }
            if (y1 > y2) { int t = y1; y1 = y2; y2 = t; }
            if (z1 > z2) { int t = z1; z1 = z2; z2 = t; }
        }

        public boolean contains(int x, int y, int z) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }

        public boolean isAir() {
            return "minecraft:air".equals(block);
        }
    }

    /** 实体生成计划。 */
    public record Spawn(String entity, double x, double y, double z) {}

    /** 块查询采样器(在线 verify 传世界侧实现;测试传期望表引用)。 */
    @FunctionalInterface
    public interface BlockLookup {
        String at(int x, int y, int z);
    }

    /** verify 结果:total = fills 并集总格数;mismatch = 与期望块不一致的格数;first = 前 8 条 "x,y,z 期望!=实际"。 */
    public record VerifyResult(int total, int mismatch, List<String> first) {}

    /** 终态一格:folds 后该格应写入的块 id(last-writer-wins)。 */
    public record Expected(int x, int y, int z, String block) {}

    /** RED 占位:终态折叠待补(契约 verifySemantics 钉死 572/无重复/重叠格地板胜出)。 */
    public static List<Expected> expectedList(Plan plan) {
        List<Expected> out = new ArrayList<>();
        for (java.util.Map.Entry<Long, String> e : fold(plan).entrySet()) {
            long k = e.getKey();
            out.add(new Expected((int) ((k >> 42) - OFFSET),
                    (int) ((k & 0x1FFFFF) - OFFSET),
                    (int) (((k >> 21) & 0x1FFFFF) - OFFSET),
                    e.getValue()));
        }
        return out;
    }

    /** 折叠表(唯一真源):verify 与构建共用;LinkedHashMap 保 fill 顺序,坐标打包自实现零 MC 依赖。 */
    private static java.util.LinkedHashMap<Long, String> fold(Plan plan) {
        java.util.LinkedHashMap<Long, String> expected = new java.util.LinkedHashMap<>();
        for (Fill f : plan.fills()) {
            for (int x = f.x1(); x <= f.x2(); x++)
                for (int y = f.y1(); y <= f.y2(); y++)
                    for (int z = f.z1(); z <= f.z2(); z++)
                        expected.put(posKey(x, y, z), f.block());
        }
        return expected;
    }

    /** verify 语义:期望值 = fills 顺序最后包含者胜出(与 SceneExecutor 顺序 setBlock 一致);
     *  逐格对照采样器,union 计数(重叠格只算一次)。坐标打包自实现,保持本类零 MC 依赖。 */
    public static VerifyResult verify(Plan plan, BlockLookup lookup) {
        java.util.Map<Long, String> expected = new java.util.LinkedHashMap<>();
        for (Fill f : plan.fills()) {
            for (int x = f.x1(); x <= f.x2(); x++)
                for (int y = f.y1(); y <= f.y2(); y++)
                    for (int z = f.z1(); z <= f.z2(); z++)
                        expected.put(posKey(x, y, z), f.block());
        }
        int mismatch = 0;
        List<String> first = new ArrayList<>();
        for (java.util.Map.Entry<Long, String> e : expected.entrySet()) {
            long k = e.getKey();
            int x = (int) ((k >> 42) - OFFSET);
            int z = (int) (((k >> 21) & 0x1FFFFF) - OFFSET);
            int y = (int) ((k & 0x1FFFFF) - OFFSET);
            String actual = lookup.at(x, y, z);
            if (!e.getValue().equals(actual)) {
                mismatch++;
                if (first.size() < 8) first.add(x + "," + y + "," + z + " 期望" + e.getValue() + "!=" + actual);
            }
        }
        return new VerifyResult(expected.size(), mismatch, List.copyOf(first));
    }

    private static final long OFFSET = 1048576L;   // ±2^20 覆盖 ±1,048,575,场景区富余

    private static long posKey(int x, int y, int z) {
        return ((long) (x + OFFSET) << 42) | ((long) (z + OFFSET) << 21) | (long) (y + OFFSET);
    }

    /** 一个布景预设 = 有序 fills(后写覆盖先写)+ spawns。 */
    public record Plan(String name, List<Fill> fills, List<Spawn> spawns) {
        /** 指定格是否为实心(非空气 fill 覆盖)。 */
        public boolean solidAt(int x, int y, int z) {
            for (Fill f : fills) {
                if (!f.isAir() && f.contains(x, y, z)) return true;
            }
            return false;
        }
    }

    public static List<String> names() {
        return List.of("wall", "grass", "corridor", "bloom", "duo");
    }

    /** 未知预设返回 null(命令侧报错)。 */
    public static Plan plan(String name) {
        if (name == null) return null;
        return switch (name) {
            case "wall" -> wall();
            case "grass" -> grass();
            case "corridor" -> corridor();
            case "bloom" -> bloom();
            case "duo" -> duo();
            default -> null;
        };
    }

    private static List<Fill> withPlatform(Fill... extra) {
        List<Fill> out = new ArrayList<>();
        out.add(PLATFORM);
        for (Fill f : extra) out.add(f);
        return out;
    }

    /** 石砖墙 12x3x1 东西向,墙心过原点;墙前(+Z,相机侧)/墙后 2 格各 1 蜘蛛。 */
    private static Plan wall() {
        return new Plan("wall",
                withPlatform(new Fill("minecraft:stone_bricks", 1994, 121, 0, 2005, 123, 0)),
                List.of(new Spawn("minecraft:spider", 2000, 121, 2),
                        new Spawn("minecraft:spider", 2000, 121, -2)));
    }

    /** 草台面 16x16 + 30 株确定性植被 + 橡树/云杉各一棵。 */
    private static Plan grass() {
        List<Fill> fills = withPlatform(
                new Fill("minecraft:grass_block", 1992, 120, -8, 2007, 120, 7));
        // 橡树 @ (1995,-5):先冠后干(干覆盖冠中心列)
        fills.add(new Fill("minecraft:oak_leaves", 1994, 123, -6, 1996, 125, -4));   // 内核 3x3x3
        fills.add(new Fill("minecraft:oak_leaves", 1993, 123, -5, 1993, 124, -5));   // 西翼
        fills.add(new Fill("minecraft:oak_leaves", 1997, 123, -5, 1997, 124, -5));   // 东翼
        fills.add(new Fill("minecraft:oak_leaves", 1995, 123, -7, 1995, 124, -7));   // 北翼
        fills.add(new Fill("minecraft:oak_leaves", 1995, 123, -3, 1995, 124, -3));   // 南翼
        fills.add(new Fill("minecraft:oak_leaves", 1995, 125, -5, 1995, 125, -5));   // 顶
        fills.add(new Fill("minecraft:oak_log", 1995, 121, -5, 1995, 124, -5));      // 干
        // 云杉 @ (2005,-4):塔状三层环 + 顶
        fills.add(new Fill("minecraft:spruce_leaves", 2003, 124, -6, 2007, 125, -2));
        fills.add(new Fill("minecraft:spruce_leaves", 2004, 126, -5, 2006, 127, -3));
        fills.add(new Fill("minecraft:spruce_leaves", 2005, 128, -4, 2005, 129, -4));
        fills.add(new Fill("minecraft:spruce_log", 2005, 121, -4, 2005, 128, -4));
        // 30 株确定性植被(固定种子 → 重复执行结果一致;散在南半场避开树区)
        String[] palette = {"minecraft:grass", "minecraft:fern", "minecraft:dandelion",
                "minecraft:poppy", "minecraft:oxeye_daisy"};
        Set<Long> used = new LinkedHashSet<>();
        Random rnd = new Random(20260829L);
        while (used.size() < 30) {
            int x = 1993 + rnd.nextInt(14);
            int z = rnd.nextInt(7); // z 0..6
            used.add((long) x * 1000 + z);
        }
        int i = 0;
        for (long key : used) {
            int x = (int) (key / 1000);
            int z = (int) (key % 1000);
            fills.add(new Fill(palette[i % palette.length], x, 121, z, x, 121, z));
            i++;
        }
        return new Plan("grass", fills, List.of());
    }

    /** 封闭走廊:内部 24 长(x2001..2024) x 4 宽(z-2..1) x 3 高(y121..123),黑混凝土低反照率。 */
    private static Plan corridor() {
        return new Plan("corridor", withPlatform(
                new Fill("minecraft:black_concrete", 2000, 120, -3, 2025, 120, 2),   // 地
                new Fill("minecraft:black_concrete", 2000, 124, -3, 2025, 124, 2),   // 顶
                new Fill("minecraft:black_concrete", 2000, 121, 2, 2025, 123, 2),    // 南壁
                new Fill("minecraft:black_concrete", 2000, 121, -3, 2025, 123, -3),  // 北壁
                new Fill("minecraft:black_concrete", 2000, 121, -2, 2000, 123, 1),   // 西端
                new Fill("minecraft:black_concrete", 2025, 121, -2, 2025, 123, 1),   // 东端
                new Fill("minecraft:air", 2001, 121, -2, 2024, 123, 1)),             // 内部(幂等)
                List.of());
    }

    /** 黑暗小屋 6x5x4,北墙 2x2 门口,屋内机位朝门外看(bloom 晕圈/错位复现位)。 */
    private static Plan bloom() {
        return new Plan("bloom", withPlatform(
                new Fill("minecraft:black_concrete", 2008, 120, 6, 2013, 120, 10),   // 地
                new Fill("minecraft:black_concrete", 2008, 124, 6, 2013, 124, 10),   // 顶
                new Fill("minecraft:black_concrete", 2008, 121, 10, 2013, 123, 10),  // 南墙
                new Fill("minecraft:black_concrete", 2013, 121, 6, 2013, 123, 10),   // 东墙
                new Fill("minecraft:black_concrete", 2008, 121, 6, 2008, 123, 10),   // 西墙
                new Fill("minecraft:black_concrete", 2008, 121, 6, 2009, 123, 6),    // 北墙左段
                new Fill("minecraft:black_concrete", 2012, 121, 6, 2013, 123, 6),    // 北墙右段
                new Fill("minecraft:black_concrete", 2010, 123, 6, 2011, 123, 6),    // 门楣(门 2x2 在 y121..122)
                new Fill("minecraft:air", 2009, 121, 7, 2012, 123, 9)),              // 内部(幂等)
                List.of());
    }

    /** 无建筑:仅平台(手电+枪灯双源混合由操作者用 kit 点亮)。 */
    private static Plan duo() {
        return new Plan("duo", withPlatform(), List.of());
    }

    private ScenePresets() {}
}

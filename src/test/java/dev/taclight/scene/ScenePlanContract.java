package dev.taclight.scene;

import dev.taclight.command.CamStore;
import dev.taclight.command.ScenePresets;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 布景计划契约(纯 JVM,ScenePresets 无 Minecraft 依赖)。
 * 规格 = docs/调试环境搭建计划.md §3A(五预设)+ CamStore 内置机位的交叉一致性:
 * 同一 bug 的 A/B 对比依赖"机位站在该场景可站的位置",几何改了机位没改(或反过来)
 * 会导致玩家卡墙/踩空 —— 这条交叉断言在实现前就把这类漂移钉死。
 */
public class ScenePlanContract {
    private static int checks;

    public static void main(String[] args) {
        names();
        unknownPreset();
        regionGuard();
        idempotent();
        platformFloorEverywhere();
        wallSpec();
        corridorSpec();
        bloomSpec();
        grassSpec();
        duoSpec();
        camSafety();
        commandArgs();
        verifySemantics();
        System.out.println("ScenePlanContract: ALL PASS (" + checks + " checks)");
    }

    /**
     * 命令参数类型契约(实机 2026-08-29 教训):内置机位名含 '@',word() 会拒绝
     * ("Incorrect argument")—— cam save/goto 的参数必须是 string(),且全部内置名可解析。
     */
    private static void commandArgs() {
        com.mojang.brigadier.arguments.StringArgumentType word =
                com.mojang.brigadier.arguments.StringArgumentType.word();
        com.mojang.brigadier.arguments.StringArgumentType str =
                com.mojang.brigadier.arguments.StringArgumentType.string();
        com.mojang.brigadier.StringReader rw = new com.mojang.brigadier.StringReader("bloom@inside");
        boolean wordThrew = false;
        try {
            word.parse(rw);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            wordThrew = true;
        }
        check(wordThrew || !rw.getRemaining().isEmpty(),
                "word() 对含 '@' 名须拒绝或只消费前缀(残留='" + rw.getRemaining() + "'),防回归到 word");
        for (String name : CamStore.defaults().keySet()) {
            try {
                com.mojang.brigadier.StringReader rs = new com.mojang.brigadier.StringReader(name);
                String parsed = str.parse(rs);
                check(parsed.equals(name) && rs.getRemaining().isEmpty(),
                        "机位名经 string() 全量解析且保真: " + name);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                check(false, "机位名 string() 解析失败: " + name);
            }
        }
    }

    private static void names() {
        List<String> n = ScenePresets.names();
        check(n.equals(List.of("wall", "grass", "corridor", "bloom", "duo")), "五预设名与顺序: " + n);
    }

    private static void unknownPreset() {
        check(ScenePresets.plan("nope") == null, "未知预设返回 null");
        check(ScenePresets.plan(null) == null, "null 预设返回 null");
    }

    private static void regionGuard() {
        for (String name : ScenePresets.names()) {
            ScenePresets.Plan p = ScenePresets.plan(name);
            for (ScenePresets.Fill f : p.fills()) {
                check(f.x1() >= ScenePresets.REGION_MIN_X && f.x2() <= ScenePresets.REGION_MAX_X
                        && f.y1() >= ScenePresets.REGION_MIN_Y && f.y2() <= ScenePresets.REGION_MAX_Y
                        && f.z1() >= ScenePresets.REGION_MIN_Z && f.z2() <= ScenePresets.REGION_MAX_Z,
                        name + " fill 越出场景区: " + f);
            }
            for (ScenePresets.Spawn s : p.spawns()) {
                check(s.x() >= ScenePresets.REGION_MIN_X && s.x() <= ScenePresets.REGION_MAX_X
                        && s.y() >= ScenePresets.REGION_MIN_Y && s.y() <= ScenePresets.REGION_MAX_Y
                        && s.z() >= ScenePresets.REGION_MIN_Z && s.z() <= ScenePresets.REGION_MAX_Z,
                        name + " spawn 越出场景区: " + s);
            }
        }
    }

    private static void idempotent() {
        for (String name : ScenePresets.names()) {
            check(ScenePresets.plan(name).equals(ScenePresets.plan(name)), name + " 计划幂等(两次生成一致)");
        }
    }

    private static void platformFloorEverywhere() {
        for (String name : ScenePresets.names()) {
            boolean hasFloor = ScenePresets.plan(name).fills().stream().anyMatch(f ->
                    f.y1() == 120 && f.y2() == 120);
            check(hasFloor, name + " 有 y=120 平台层(顶面 y=121,机位契约)");
        }
    }

    private static void wallSpec() {
        ScenePresets.Plan p = ScenePresets.plan("wall");
        check(p.fills().stream().anyMatch(f -> f.block().equals("minecraft:stone_bricks")
                && f.x1() == 1994 && f.x2() == 2005 && f.y1() == 121 && f.y2() == 123
                && f.z1() == 0 && f.z2() == 0), "wall: 石砖墙 12x3x1 墙心过原点");
        check(p.spawns().size() == 2, "wall: 2 只蜘蛛");
        for (ScenePresets.Spawn s : p.spawns()) {
            check(s.entity().equals("minecraft:spider"), "wall: 蜘蛛实体 " + s);
            check(s.x() == 2000 && s.y() == 121 && Math.abs(Math.abs(s.z()) - 2) < 1e-9,
                    "wall: 蜘蛛位于墙前/墙后 2 格(x=2000,y=121,z=±2): " + s);
        }
        check(p.spawns().stream().anyMatch(s -> s.z() > 0) && p.spawns().stream().anyMatch(s -> s.z() < 0),
                "wall: 墙前(+Z,相机侧)与墙后(-Z)各一");
    }

    private static void corridorSpec() {
        ScenePresets.Plan p = ScenePresets.plan("corridor");
        // 内部空间:4 宽(x2001..2024 长24) x 3 高(y121..123) x z-2..1(4 宽)
        check(!p.solidAt(2001, 122, -1) && !p.solidAt(2024, 122, 0), "corridor: 内部无实心(两端)");
        check(p.solidAt(2025, 122, -1), "corridor: 东端墙实心(x=2025)");
        check(p.solidAt(2000, 122, -1), "corridor: 西端墙实心(x=2000)");
        check(p.solidAt(2001, 124, -1) && p.solidAt(2024, 124, 0), "corridor: 顶盖实心(y=124)");
        check(p.solidAt(2010, 122, 2) && p.solidAt(2010, 122, -3), "corridor: 两侧壁实心(z=2 / z=-3)");
        boolean darkWall = p.fills().stream().anyMatch(f ->
                f.block().equals("minecraft:black_concrete") && f.y1() >= 121);
        check(darkWall, "corridor: 壁体用低反照率黑混凝土");
        check(airContains(p, 2010, 122, 0), "corridor: 中段内部为空气");
    }

    private static void bloomSpec() {
        ScenePresets.Plan p = ScenePresets.plan("bloom");
        // 门:北墙(z=6)x2010..2011,y121..122 为 2x2 空洞
        check(!p.solidAt(2010, 121, 6) && !p.solidAt(2011, 121, 6)
                && !p.solidAt(2010, 122, 6) && !p.solidAt(2011, 122, 6), "bloom: 2x2 门口开放");
        check(p.solidAt(2010, 123, 6), "bloom: 门楣实心(y=123 封口)");
        check(p.solidAt(2008, 122, 10) && p.solidAt(2013, 122, 6), "bloom: 南/西墙实心");
        check(airContains(p, 2010, 122, 9), "bloom: 屋内机位处为空气");
    }

    private static void grassSpec() {
        ScenePresets.Plan p = ScenePresets.plan("grass");
        check(p.fills().stream().anyMatch(f -> f.block().equals("minecraft:grass_block")
                && f.y1() == 120 && f.y2() == 120), "grass: 草方块台面");
        List<ScenePresets.Fill> tufts = p.fills().stream()
                .filter(f -> f.x1() == f.x2() && f.y1() == 121 && f.y2() == 121 && f.z1() == f.z2()
                        && !f.block().equals("minecraft:grass_block")).toList();
        check(tufts.size() == 30, "grass: 30 株植被,实得 " + tufts.size());
        Set<String> palette = Set.of("minecraft:grass", "minecraft:fern", "minecraft:dandelion",
                "minecraft:poppy", "minecraft:oxeye_daisy");
        long distinct = tufts.stream().map(f -> f.x1() + "," + f.z1()).distinct().count();
        check(distinct == 30, "grass: 植被位置无重复");
        for (ScenePresets.Fill t : tufts) {
            check(palette.contains(t.block()), "grass: 植被在调色板内 " + t.block());
            check(t.x1() >= 1992 && t.x1() <= 2007 && t.z1() >= -8 && t.z1() <= 7,
                    "grass: 植被在台面内 " + t);
        }
        check(p.fills().stream().anyMatch(f -> f.block().equals("minecraft:oak_log")),
                "grass: 橡树(原木)");
        check(p.fills().stream().anyMatch(f -> f.block().equals("minecraft:spruce_log")),
                "grass: 云杉(原木)");
        check(p.fills().stream().anyMatch(f -> f.block().equals("minecraft:oak_leaves")),
                "grass: 橡树冠");
        check(p.fills().stream().anyMatch(f -> f.block().equals("minecraft:spruce_leaves")),
                "grass: 云杉冠");
        // 树干必须落地:主干底格 y=121 且其正下 y=120 是实体
        for (String log : new String[]{"minecraft:oak_log", "minecraft:spruce_log"}) {
            ScenePresets.Fill trunk = p.fills().stream().filter(f -> f.block().equals(log)).findFirst().orElseThrow();
            check(trunk.y1() == 121, log + " 主干自地面起: " + trunk);
        }
    }

    private static void duoSpec() {
        ScenePresets.Plan p = ScenePresets.plan("duo");
        check(p.fills().size() == 1, "duo: 仅平台,无建筑");
        check(p.spawns().isEmpty(), "duo: 无实体");
    }

    /** 交叉契约:每个预设的内置机位必须站在可站位置(不卡墙、不悬空在实心里)。 */
    private static void camSafety() {
        Map<String, CamStore.Cam> cams = CamStore.defaults();
        checkCams(cams);
        camSafe(cams, "wall", "wall_front");
        camSafe(cams, "grass", "grass_low");
        camSafe(cams, "corridor", "corridor_end");
        camSafe(cams, "bloom", "bloom_inside");
    }

    private static void checkCams(Map<String, CamStore.Cam> cams) {
        for (String key : List.of("wall_front", "grass_low", "corridor_end", "bloom_inside")) {
            check(cams.containsKey(key), "内置机位存在: " + key);
        }
    }

    private static void camSafe(Map<String, CamStore.Cam> cams, String preset, String camKey) {
        CamStore.Cam cam = cams.get(camKey);
        ScenePresets.Plan p = ScenePresets.plan(preset);
        int cx = (int) Math.floor(cam.x);
        int cz = (int) Math.floor(cam.z);
        int cy = (int) Math.floor(cam.y);
        // teleport 目标格(脚部 cell)不得卡实心
        check(!p.solidAt(cx, cy, cz), preset + " 机位 " + camKey + " 脚部无实心 ("
                + cx + "," + cy + "," + cz + ")");
        // 可站立:从机位向下必有落点 —— 该格与其上一格非实心,且下方一格是支撑
        check(standable(p, cx, cy, cz), preset + " 机位 " + camKey + " 有可落站立点");
        // 封闭场景(corridor/bloom)额外要求:机位格位于该场景的空气内部 fill 中
        if (preset.equals("corridor") || preset.equals("bloom")) {
            check(airContains(p, cx, cy, cz), preset + " 机位在室内空气内 (" + cx + "," + cy + "," + cz + ")");
        }
    }

    private static boolean standable(ScenePresets.Plan p, int x, int yStart, int z) {
        for (int y = yStart; y >= ScenePresets.REGION_MIN_Y + 1; y--) {
            if (p.solidAt(x, y, z) || p.solidAt(x, y + 1, z)) return false; // 下落途中撞实心 = 卡墙
            if (p.solidAt(x, y - 1, z)) return true;                        // 找到支撑 = 可站
        }
        return false;
    }

    private static boolean airContains(ScenePresets.Plan p, int x, int y, int z) {
        return p.fills().stream().anyMatch(f -> f.block().equals("minecraft:air") && f.contains(x, y, z));
    }

    /**
     * verify 语义契约(2026-08-29 坑位 16 配套):ScenePresets.verify 按"最后包含的 fill
     * 胜出"的期望值对照采样器,union 计数;total/不匹配坐标必须能指名到格 ——
     * 在线用途:把"重复 scene 仍报 blocks=30"的回滚格定位到具体坐标。
     */
    private static void verifySemantics() {
        for (String preset : ScenePresets.names()) {
            ScenePresets.Plan p = ScenePresets.plan(preset);
            java.util.Map<String, String> exp = expectedMap(p);
            ScenePresets.VerifyResult r = ScenePresets.verify(p, (x, y, z) -> exp.get(x + "," + y + "," + z));
            check(r.total() == exp.size(), preset + " verify total=union 格数 " + exp.size());
            check(r.mismatch() == 0, preset + " 期望全量采样零不匹配");
        }
        // 定向变异 1:bloom 屋顶整层缺失(坑位 16 的"每次 30 格"头号嫌疑)→ 恰 30 条,坐标指名
        ScenePresets.Plan bp = ScenePresets.plan("bloom");
        java.util.Map<String, String> mut = expectedMap(bp);
        for (int x = 2008; x <= 2013; x++)
            for (int z = 6; z <= 10; z++) mut.put(x + ",124," + z, "minecraft:air");
        ScenePresets.VerifyResult rb = ScenePresets.verify(bp, (x, y, z) -> mut.get(x + "," + y + "," + z));
        check(rb.mismatch() == 30, "bloom 屋顶缺失 = 恰 30 格不匹配(实际 " + rb.mismatch() + ")");
        check(rb.total() == 572, "bloom union 总格数 = 572(实际 " + rb.total() + ")");
        check(!rb.first().isEmpty() && rb.first().get(0).startsWith("2008,124,6"),
                "首条不匹配坐标指名 2008,124,6: " + rb.first());
        // 定向变异 2:wall 单格缺失 → 1 条,坐标指名
        ScenePresets.Plan wp = ScenePresets.plan("wall");
        java.util.Map<String, String> mw = expectedMap(wp);
        mw.put("1999,122,0", "minecraft:air");
        ScenePresets.VerifyResult rw = ScenePresets.verify(wp, (x, y, z) -> mw.get(x + "," + y + "," + z));
        check(rw.mismatch() == 1 && rw.first().get(0).contains("1999,122,0"),
                "wall 单格缺失 = 1 条且坐标指名");
        // 终态列表契约(坑位 16 终解,2026-08-29):expectedList = last-writer-wins 折叠,
        // 构建侧按它写 → 重叠 fill(平台↔小屋地板)不再互相翻转,重复构建 blocks=0。
        java.util.List<ScenePresets.Expected> eb = ScenePresets.expectedList(bp);
        check(eb.size() == 572, "bloom expectedList 并集=572(实际 " + eb.size() + ")");
        ScenePresets.Expected overlap = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean dup = false;
        for (ScenePresets.Expected e : eb) {
            if (!seen.add(e.x() + "," + e.y() + "," + e.z())) dup = true;
            if (e.x() == 2008 && e.y() == 120 && e.z() == 6) overlap = e;
        }
        check(!dup, "expectedList 无重复格(翻转源已除)");
        check(overlap != null && overlap.block().equals("minecraft:black_concrete"),
                "重叠格 (2008,120,6) 终态 = black_concrete(地板胜出,非平台 smooth_stone)");
    }

    /** 独立实现的期望值表(与被测实现互证):顺序扫描 fills,最后包含者胜出。 */
    private static java.util.Map<String, String> expectedMap(ScenePresets.Plan p) {
        java.util.Map<String, String> exp = new java.util.HashMap<>();
        for (ScenePresets.Fill f : p.fills()) {
            for (int x = f.x1(); x <= f.x2(); x++)
                for (int y = f.y1(); y <= f.y2(); y++)
                    for (int z = f.z1(); z <= f.z2(); z++)
                        exp.put(x + "," + y + "," + z, f.block());
        }
        return exp;
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError("FAIL " + what);
    }

    private ScenePlanContract() {}
}

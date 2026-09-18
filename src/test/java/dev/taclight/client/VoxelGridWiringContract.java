package dev.taclight.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分类接线 + 双路径镜像契约(2026-09-18 雪地菱形阵列根因轮)。纯 JVM,不加载 MC。
 *
 * <p>Java 体素路径的形状采样要真 Level/BlockState,契约测试跑不到(无 registry bootstrap);
 * 因此这里用<b>源码文本级断言</b>钉住"接线"这一层,配合 {@code VoxelClassifyContract}
 * 的纯逻辑断言(= 语义层),两层一起覆盖根因①②③:</p>
 * <ul>
 *   <li>根因② 接线:{@code CURSOR.set(x, y, z)} 必须在 {@code getCollisionShape(level, CURSOR)}
 *       之前,且旧的 {@code isSolidRender(level, CURSOR)} 已被移除;</li>
 *   <li>根因③ 缓存策略:{@code CLASS_CACHE.put} 只出现在形状分支之前(位置无关档),
 *       形状分支(从 {@code CURSOR.set} 起的尾部)不得写入缓存、不得回 {@code CODE_SOLID};</li>
 *   <li>镜像一致性:{@code block.properties} 的 2001/2002 与 {@code VoxelGrid} 的
 *       VEG_IDS/LEAF_IDS 同源;新增的 2003(透光档)与着色器 0.0 分支对齐。</li>
 * </ul>
 */
public class VoxelGridWiringContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String grid = read("src/main/java/dev/taclight/client/VoxelGrid.java");
        String props = read("pack/shaders/block.properties");
        String terrain = read("pack/shaders/gbuffers_terrain.fsh");
        String adapter = read("pack/shaders/lib/taclight_adapter.glsl");

        // ---------------- 1. 根因②:坐标必须用上 ----------------
        String body = methodBody(grid, "private static int classify(");
        check(body.contains("CURSOR.set(x, y, z)"), "classify 内出现 CURSOR.set(x, y, z)(坐标真正被使用)");
        int setAt = body.indexOf("CURSOR.set(x, y, z)");
        int shapeAt = body.indexOf("getCollisionShape(level, CURSOR)");
        check(shapeAt > 0, "classify 内按方块坐标查询 getCollisionShape(level, CURSOR)");
        check(setAt > 0 && setAt < shapeAt, "CURSOR.set 先于 getCollisionShape(旧代码从不 set ⇒ 恒在世界原点)");
        check(!body.contains("isSolidRender"),
                "旧的 isSolidRender(level, CURSOR) 判定已移除(同一 CURSOR 被两处复用是根因②的成因)");

        // ---------------- 2. 根因①③:形状分档 + 缓存策略 ----------------
        int elseAt = body.lastIndexOf("else {", setAt);
        check(elseAt > 0, "形状分支是 classify 内的 else { ... } 块");
        String shapeBranch = balancedBlock(body, body.indexOf('{', elseAt));
        check(shapeBranch.contains("VoxelClassifier.centerColumnTopY"), "形状分支用 VoxelClassifier.centerColumnTopY 取中心列顶高");
        check(shapeBranch.contains("VoxelClassifier.codeForTopHeight"), "形状分支用 VoxelClassifier.codeForTopHeight 落档(不再是兜底实心)");
        check(!shapeBranch.contains("CODE_SOLID"),
                "形状分支不得回 CODE_SOLID(旧兜底 = 雪层整格实心 ⇒ 方格阵列;违反即本断言变红)");
        check(!shapeBranch.contains("CLASS_CACHE"), "形状分支不写位置无关档的 CLASS_CACHE(位置相关结论不得按 state 冻结)");
        check(shapeBranch.contains("SHAPE_CACHE.get(shape)") && shapeBranch.contains("SHAPE_CACHE.put(shape, shapeCode)"),
                "形状分支以 VoxelShape 实例为缓存键(位置相关性已编码在 shape 中 ⇒ 不存在根因③的冻结,同时免逐格 toAabbs 分配)");
        check(!shapeBranch.contains("get(state)") && !shapeBranch.contains("put(state"),
                "形状缓存键必须是形状而非 BlockState(键回退到 state 即等于重演根因③)");
        int putAt = body.indexOf("CLASS_CACHE.put");
        check(putAt > setAt,
                "唯一的 CLASS_CACHE.put 位于形状分支之后 ⇒ 形状分支已 return、不可达(位置相关结论永不入 state 级缓存)");
        check(!body.contains("CODE_SOLID"),
                "classify 方法体内不出现 CODE_SOLID(实心只能经 codeForTopHeight 依真实形状产出)");
        check(!body.contains("默认实心"), "classify 内不再有\"默认实心\"兜底");

        // ---------------- 3. 镜像一致性:2001/2002 与 Java ID 表同源 ----------------
        Set<String> veg = javaIds(grid, "VEG_IDS");
        Set<String> leaf = javaIds(grid, "LEAF_IDS");
        Set<String> g2001 = group(props, 2001);
        Set<String> g2002 = group(props, 2002);
        Set<String> g2003 = group(props, 2003);
        check(!veg.isEmpty() && !leaf.isEmpty(), "从 VoxelGrid 源码提取到 VEG_IDS(" + veg.size() + ") / LEAF_IDS(" + leaf.size() + ")");
        check(g2002.equals(leaf), "block.2002 与 LEAF_IDS 逐项一致(实际差集: " + diff(g2002, leaf) + ")");
        check(g2001.containsAll(veg), "block.2001 覆盖全部 VEG_IDS(缺失: " + diff(veg, g2001) + ")");
        check(!g2003.isEmpty(), "block.2003(透光档)存在且非空");
        check(g2003.contains("minecraft:snow:layers=1") && g2003.contains("minecraft:snow:layers=2"),
                "block.2003 含雪 1-2 层(= Java CODE_EMPTY 档的阵列主因)");
        check(g2001.contains("minecraft:snow:layers=3"), "block.2001 含雪 3-7 层(= Java CODE_VEG 档)");
        check(g2001.contains("minecraft:oak_slab:type=bottom"), "block.2001 含半砖 bottom(= Java CODE_VEG 档)");
        check(g2001.contains("minecraft:oak_stairs:half=bottom"), "block.2001 含楼梯 bottom(= Java CODE_VEG 档)");
        check(disjoint(g2001, g2002) && disjoint(g2001, g2003) && disjoint(g2002, g2003),
                "2001/2002/2003 三组 token 互不相交(Iris 用 putIfAbsent ⇒ 重复项会被先解析者吃掉,必须无歧义)");

        // ---------------- 4. 状态谓词语法(离线实测口径) ----------------
        boolean tokenSyntaxOk = true;
        String badToken = null;
        for (String t : tokenSet(g2001, g2002, g2003)) {
            int colons = t.length() - t.replace(":", "").length();
            if (t.indexOf('[') >= 0 || t.indexOf(']') >= 0 || colons < 1 || colons > 2 || t.endsWith(":")) {
                tokenSyntaxOk = false;
                badToken = t;
                break;
            }
        }
        check(tokenSyntaxOk,
                "所有 token 形如 namespace:name[:key=value] 且不含 [ ](Oculus 1.8.0 实测:方括号形式解析为不存在的"
                        + " minecraft:minecraft;离题 token = " + badToken + ")");

        // ---------------- 5. 适配层系数(着色器消费侧)对齐 ----------------
        check(terrain.contains("vblockId > 2002.5 && vblockId < 2003.5"),
                "gbuffers_terrain 增加 2003 分支(薄片档)");
        int b3 = terrain.indexOf("vblockId > 2002.5 && vblockId < 2003.5");
        String branch3 = terrain.substring(b3, Math.min(terrain.length(), b3 + 400));
        check(branch3.contains("occl = 0.0"), "2003 分支写 occl = 0.0(与 Java CODE_EMPTY 同档)");
        check(terrain.contains("occl = 0.25") && terrain.contains("occl = 0.6"),
                "2001/2002 系数(0.25 / 0.6)保持不变(与 Java CODE_VEG/CODE_LEAF 同档)");
        check(adapter.contains("0.0 薄片档"), "adapter 遮挡系数注释登记 0.0 薄片档(跨包移植面必须看到新档位)");

        System.out.println("VoxelGridWiringContract: ALL PASS (" + checks + " checks)");
    }

    private static Set<String> tokenSet(Set<String>... groups) {
        Set<String> all = new LinkedHashSet<>();
        for (Set<String> g : groups) all.addAll(g);
        return all;
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (String t : a) if (b.contains(t)) return false;
        return true;
    }

    private static String diff(Set<String> expected, Set<String> actual) {
        Set<String> d = new LinkedHashSet<>(expected);
        d.removeAll(actual);
        return d.isEmpty() ? "无" : d.toString();
    }

    /** block.properties 的 block.<id> 组(按 token 去重、保序)。 */
    private static Set<String> group(String props, int id) {
        String prefix = "block." + id + "=";
        for (String line : props.split("\n")) {
            if (line.startsWith(prefix)) {
                Set<String> set = new LinkedHashSet<>();
                for (String t : line.substring(prefix.length()).trim().split("\\s+")) {
                    if (!t.isEmpty()) set.add(t);
                }
                return set;
            }
        }
        throw new AssertionError("FAIL block.properties 缺 " + prefix);
    }

    /** 从 VoxelGrid 源码里抠出 {@code field = Set.of("...", ...)} 的 ID 集合。 */
    private static Set<String> javaIds(String src, String field) {
        int i = src.indexOf(field + " = Set.of(");
        if (i < 0) throw new AssertionError("FAIL 找不到 " + field);
        int j = src.indexOf(");", i);
        if (j < 0) throw new AssertionError("FAIL " + field + " 字面量不闭合");
        Set<String> set = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"(minecraft:[a-z0-9_]+)\"").matcher(src.substring(i, j));
        while (m.find()) set.add(m.group(1));
        return set;
    }

    /** 取方法的完整源码体(花括号配平)。 */
    private static String methodBody(String src, String signatureStart) {
        int i = src.indexOf(signatureStart);
        if (i < 0) throw new AssertionError("FAIL 找不到方法: " + signatureStart);
        return balancedBlock(src, src.indexOf('{', i));
    }

    /** 从某个 '{' 起取到配对的 '}'(含两端)。 */
    private static String balancedBlock(String src, int openBrace) {
        int depth = 0;
        for (int p = openBrace; p < src.length(); p++) {
            char c = src.charAt(p);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(openBrace, p + 1);
            }
        }
        throw new AssertionError("FAIL 花括号不闭合(offset " + openBrace + ")");
    }

    private static String read(String rel) throws Exception {
        Path p = Path.of(rel);
        if (!Files.exists(p)) throw new AssertionError("FAIL 缺失文件: " + rel);
        return Files.readString(p);
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

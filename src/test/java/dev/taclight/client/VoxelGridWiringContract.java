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
 * <p><b>层级声明(Lead 复核项 ③,防误读)</b>:本契约的断言是<b>源码文本级</b>,
 * <b>不是运行时行为断言</b>——"文本断言变红"只证明"接线被改坏了",不证明"运行时行为已验"。
 * 运行时那一层由 {@code VoxelRealRegistryContract}(真 registry + 真 {@code Blocks.*} +
 * 直接调用生产 {@code VoxelGrid.classify})与 {@code VoxelClassifyContract}(纯逻辑分档表)
 * 承担;三者互不替代:</p>
 * <ul>
 *   <li>{@code VoxelClassifyContract} —— 纯逻辑(合成 AABB 夹具):分档表 + 位置相关语义;</li>
 *   <li>{@code VoxelRealRegistryContract} —— 运行时(真方块):真碰撞形 ⇒ 分类码;</li>
 *   <li>本契约 —— 文本:接线(CURSOR/缓存键/兜底)+ 双路径镜像一致性;</li>
 *   <li>{@code BoundedIdentityCacheContract} —— 纯逻辑:形状身份缓存的<b>有界性</b>。</li>
 * </ul>
 *
 * <p>本契约钉住:</p>
 * <ul>
 *   <li>根因② 接线:{@code CURSOR.set(x, y, z)} 必须在 {@code getCollisionShape(level, CURSOR)}
 *       之前,且旧的 {@code isSolidRender(level, CURSOR)} 已被移除;{@code classify} 包私有 +
 *       形参 {@code BlockGetter}(供真 registry 契约直接驱动);</li>
 *   <li>根因③ 缓存策略:形状分支不得写 {@code CLASS_CACHE}/{@code BLOCK_CAT}(位置无关档)且不得回
 *       {@code CODE_SOLID};形状键必须是 {@code VoxelShape} 实例,且由有界
 *       {@code BoundedIdentityCache} 承载;</li>
 *   <li>分类快路径接线(2026-09-25,{@code !voxel classcache on|off}):{@code classify} 首行按开关
 *       分派 ⇒ off 走旧路径 {@code classifyLegacy};快路径 = 空气逐 state 判 + 树叶/软植被按
 *       {@code Block} 身份查有界 {@code BLOCK_CAT} + <b>流体仍逐 state 判</b> + 形状不变;
 *       旧路径必须原样保留(逐格 {@code toString()} + 两次字符串哈希 + state 级 CLASS_CACHE),
 *       且两条路径的<b>形状分支逐字相同</b>。运行时的"开关两侧逐状态同码"由
 *       {@code VoxelRealRegistryContract} 承担;</li>
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
        // 2026-10-03 R21:分类主体搬到 classifyInto(多一个 CellOut 回填形状盒),classify 只做
        // "空回填"的转调 —— 这正是"契约/golden 口径绝不占调色板槽"的接线保证,故一并钉住。
        String body = methodBody(grid, "static int classifyInto(");
        check(grid.contains("static int classify(BlockGetter level, BlockState state, int x, int y, int z)"),
                "classify 为包私有 + 形参 BlockGetter(供 VoxelRealRegistryContract 直接驱动生产方法,测的路径 = 生产路径)");
        check(methodBody(grid, "static int classify(").contains("classifyInto(level, state, x, y, z, null)"),
                "classify 转调 classifyInto 且回填位传 null(稳定口径不占用/不触发调色板 ⇒ 全 registry 穷举不会撑爆调色板)");
        check(body.contains("CURSOR.set(x, y, z)"), "分类路径内出现 CURSOR.set(x, y, z)(坐标真正被使用)");
        int setAt = body.indexOf("CURSOR.set(x, y, z)");
        int shapeAt = body.indexOf("getCollisionShape(level, CURSOR)");
        check(shapeAt > 0, "classify 内按方块坐标查询 getCollisionShape(level, CURSOR)");
        check(setAt > 0 && setAt < shapeAt, "CURSOR.set 先于 getCollisionShape(旧代码从不 set ⇒ 恒在世界原点)");
        check(!body.contains("isSolidRender"),
                "旧的 isSolidRender(level, CURSOR) 判定已移除(同一 CURSOR 被两处复用是根因②的成因)");

        // ---------------- 2. 根因①③:形状分档 + 缓存策略(快路径) ----------------
        int elseAt = body.lastIndexOf("else {", setAt);
        check(elseAt > 0, "形状分支是 classify 内的 else { ... } 块");
        String shapeBranch = balancedBlock(body, body.indexOf('{', elseAt));
        check(shapeBranch.contains("getCollisionShape(level, CURSOR)")
                        && shapeBranch.contains("getOcclusionShape(level, CURSOR)"),
                "形状分支同时取碰撞形与遮挡形(C 口径:守卫读 coll、占比读 occ)");
        check(shapeBranch.contains("VoxelClassifier.codeForShapes"), "形状分支用 VoxelClassifier.codeForShapes 落档(不再是兜底实心)");
        check(!shapeBranch.contains("CODE_SOLID"),
                "形状分支不得回 CODE_SOLID(旧兜底 = 雪层整格实心 ⇒ 方格阵列;违反即本断言变红)");
        check(!shapeBranch.contains("CLASS_CACHE") && !shapeBranch.contains("BLOCK_CAT"),
                "形状分支不写任何按方块/状态冻结的类别缓存(位置相关结论不得按 state/block 冻结)");
        check(shapeBranch.contains("SHAPE_CACHE.get(occ)")
                        && shapeBranch.contains("new ShapeCode(coll, shapeCode, shapeBoxes)"),
                "形状缓存主键 = occ 形状实例(占比真源),值里带 coll 实例 + 规范化盒(调色板输入)");
        check(shapeBranch.contains("hit.coll == coll"),
                "命中还需 coll 身份一致(守卫 2 读 occ;只按 occ 命中在模组方块常量 occ + 变 coll 时会误命中)");
        check(!shapeBranch.contains("get(state)") && !shapeBranch.contains("put(state"),
                "形状缓存键必须是形状而非 BlockState(键回退到 state 即等于重演根因③)");
        check(!shapeBranch.contains("SHAPE_CACHE.clear()"),
                "形状分支不自带清空逻辑(上限+清空已内聚到 BoundedIdentityCache,避免两处口径分叉)");
        // 有界性接线(Lead 复核项 ①):上限必须真的接上纯类,而不是裸露 IdentityHashMap
        check(grid.contains("private static final int SHAPE_CACHE_CAP = 4096;"),
                "SHAPE_CACHE 容量常量 = 4096");
        check(grid.contains("new BoundedIdentityCache<>(SHAPE_CACHE_CAP)")
                        && grid.contains("BoundedIdentityCache<VoxelShape, ShapeCode> SHAPE_CACHE"),
                "SHAPE_CACHE 由 BoundedIdentityCache 承载(有界性语义在纯类里,由 BoundedIdentityCacheContract 断言)");
        check(!body.contains("CODE_SOLID"),
                "classify 方法体内不出现 CODE_SOLID(实心只能经 codeForShapes 依真实形状产出)");
        check(!body.contains("默认实心"), "classify 内不再有\"默认实心\"兜底");

        // ---------------- 2b. 分类快路径接线(2026-09-25,!voxel classcache on) ----------------
        // 红对照(已做,见 commit 信息):把 classify 改回旧路径(直接 return classifyLegacy)⇒
        // 本组断言(BLOCK_CAT/leafVegCategory/分派行)全部变红。
        check(body.contains("if (!classCacheEnabled) return classifyLegacy(level, state, x, y, z, out);"),
                "classify 首行按 classcache 开关分派:off ⇒ 旧路径 classifyLegacy(一键回退)");
        check(body.contains("if (state.isAir()) return VoxelField.CODE_EMPTY;"),
                "空气逐 state 判(不按方块冻结:isAir 是 state 谓词 Block.isAir(BlockState),模组可按状态覆写)");
        check(body.contains("Block block = state.getBlock();") && body.contains("BLOCK_CAT.get(block)"),
                "树叶/软植被按 Block 身份查有界缓存(取代逐格 getKey().toString() + 两次字符串哈希)");
        check(body.contains("VoxelClassifier.leafVegCategory(LEAF_IDS.contains(key), VEG_IDS.contains(key))"),
                "类别判定收敛到纯函数 VoxelClassifier.leafVegCategory(顺序 = 树叶先于软植被)");
        check(body.contains("VoxelClassifier.codeForBlockCategory(cat)")
                        && body.contains("CODE_FALLTHROUGH"),
                "只有 LEAF/VEG 能早返回(=codeForBlockCategory);CAT_OTHER ⇒ 哨兵,继续走流体/形状");
        check(body.contains("!state.getFluidState().isEmpty()"),
                "流体仍逐 state 判(水logged 的同种方块 fluid state 不同 ⇒ 不得按 Block 冻结)");
        int catPutAt = body.indexOf("BLOCK_CAT.put");
        int fluidAt = body.indexOf("state.getFluidState()");
        check(catPutAt > 0 && fluidAt > catPutAt,
                "类别缓存写入区先于流体判定出现 ⇒ 流体结论永不入按方块的类别缓存");
        check(body.indexOf("state.isAir()") < body.indexOf("BLOCK_CAT.get"),
                "判定顺序与旧路径一致:空气 → 树叶 → 软植被 → 流体 → 形状");
        check(grid.contains("private static final int BLOCK_CAT_CAP = 4096;"),
                "BLOCK_CAT 容量常量 = 4096(与 CLASS_CACHE 既有策略同款)");
        check(grid.contains("new BoundedIdentityCache<>(BLOCK_CAT_CAP)")
                        && grid.contains("BoundedIdentityCache<Block, Integer> BLOCK_CAT"),
                "BLOCK_CAT 由 BoundedIdentityCache 承载(有界性语义在纯类里,由 BoundedIdentityCacheContract 断言)");

        // ---------------- 2c. 旧路径必须原样存在(!voxel classcache off 的对照臂) ----------------
        String legacy = methodBody(grid, "static int classifyLegacy(");
        check(legacy.contains("BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()"),
                "旧路径保留逐格注册 ID → String(off = 改动前的逐格字符串判定)");
        check(legacy.contains("LEAF_IDS.contains(key)") && legacy.contains("VEG_IDS.contains(key)"),
                "旧路径保留两次字符串 HashSet 查找(正是快路径要消掉的成本)");
        check(legacy.contains("CLASS_CACHE.get(state)") && legacy.contains("CLASS_CACHE.put(state, code)"),
                "旧路径保留 state 级 CLASS_CACHE(快路径不写它)");
        int legacyShapeReturn = legacy.indexOf("return shapeCode;");
        int legacyPutAt = legacy.indexOf("CLASS_CACHE.put");
        check(legacyShapeReturn > 0 && legacyPutAt > legacyShapeReturn,
                "旧路径唯一的 CLASS_CACHE.put 位于形状分支之后 ⇒ 形状分支已 return、不可达(位置相关结论永不入 state 级缓存)");
        String legacyShapeBranch = balancedBlock(legacy,
                legacy.indexOf('{', legacy.lastIndexOf("else {", legacyShapeReturn)));
        check(sameLines(legacyShapeBranch, shapeBranch),
                "两条路径的形状分支逐行同文(缩进层级不同,内容必须一致)⇒ 不允许悄悄漂移"
                        + "(运行时防线 = 全 registry 的开关两侧对账)");
        check(!legacy.contains("CODE_SOLID") && !legacy.contains("默认实心"),
                "旧路径同样不出现 CODE_SOLID / \"默认实心\"兜底(off 不是回到更旧的雪地方格阵列版本)");

        // ---------------- 2c'. 形状调色板接线(2026-10-03 R21) ----------------
        // 关键语义:基础码能精确表达的形状**不占槽**(否则槽位被半砖/雪层吃掉,
        // 真正需要调色板的栅栏/楼梯反而溢出退回);因此顺序必须是"先判精确、再取槽"。
        String packed = methodBody(grid, "private static int resolvePacked(");
        int exactAt = packed.indexOf("baseCodeExpresses");
        int slotAt = packed.indexOf("PAL.slotFor");
        check(exactAt > 0 && slotAt > 0 && exactAt < slotAt,
                "resolvePacked 先问 baseCodeExpresses(基础码已精确 ⇒ 不占槽)再取调色板槽");
        check(packed.contains("VoxelField.CODE_PALETTE_BASE + sc.slot"),
                "打包值 = CODE_PALETTE_BASE + slot(码域 16..255 指向调色板)");
        check(packed.contains("sc.slotStamp != paletteStamp"),
                "槽号按构建代数打戳(调色板每帧重建、槽号复用 ⇒ 不加戳会把旧槽号串到别的形状)");
        check(packed.contains("if (sc.slot < 0)"),
                "超容量/超盒数(槽 = -1)⇒ 退回基础码(降级到今天的行为)");
        // ⚠ 2026-10-04 真机订正:门控**不许**依赖"基础码 ≥4"(非满方块落的是 VEG=1,
        // 那样调色板对它该服务的形状全部不生效)。正确门控 = 形状分支有没有填 boxes。
        check(packed.contains("if (out.boxes == null || out.sc == null) return baseCode;")
                        && !packed.contains("CODE_SLAB_BOTTOM_BASE"),
                "门控 = 形状分支是否填了 boxes(不是\"基础码≥4\";真机实测栅栏/墙/楼梯落 VEG ⇒ 旧门控让调色板永不生效)");
        String fillBody = methodBody(grid, "private static void fill(Level level, VoxelField.Box box)");
        check(fillBody.contains("PAL.clear();") && fillBody.contains("paletteStamp++;"),
                "每帧重建:fill 开头清调色板并推进代数(旧槽号立即失效)");
        check(fillBody.contains("resolvePacked(") && fillBody.contains("classifyInto("),
                "fill 逐格走 classifyInto(带 CellOut)+ resolvePacked ⇒ 调色板真的接在生产路径上");
        check(fillBody.contains("CELL_OUT.reset()"),
                "逐格复位 CellOut(否则上一格的形状盒会漏到下一格 ⇒ 假形状)");
        check(grid.contains("VoxelGrid::gridBoxes") && grid.contains("s.palette().boxesInSlot(slot)"),
                "!voxray 的调色板盒取自**已上传的槽**(s.palette()),不是重算一遍(否则探针 ≠ 渲染看到的)");
        check(grid.contains("PAL, version") || grid.contains("DATA, PAL, version"),
                "Snapshot 携带调色板引用(与 DATA 同款:上传侧读同一份数据)");

        // ---------------- 2d. `!voxel profile reset` 的边界(2026-09-25 待办 ⑰) ----------------
        // BLOCK_CAT 是**稳态缓存**(按方块身份,内容与轮次无关):profile reset 只清"这一轮"的诊断
        // 计数,**刻意不清**它 —— 清了只会让下一帧白重算一遍、把 A/B 第一帧污染成"未命中风暴"。
        // 这条必须钉住,否则下一个人会当 bug"修"掉(运行期那一半在 VoxelRealRegistryContract)。
        int prAt = grid.indexOf("a.equals(\"profile reset\")");
        int prEnd = prAt < 0 ? -1 : grid.indexOf("return \"voxel profile reset\";", prAt);
        String prBranch = (prAt < 0 || prEnd < 0) ? "" : grid.substring(prAt, prEnd);
        check(prBranch.contains("catHits = catMisses = 0;") && prBranch.contains("刻意不清")
                        && !prBranch.contains("BLOCK_CAT.clear()"),
                "profile reset 分支:清 cat 计数 + 注释写明 BLOCK_CAT 刻意不清 + **不调用** BLOCK_CAT.clear()");
        int hookAt = grid.indexOf("static void resetBlockCategoryCache()");
        String hook = hookAt < 0 ? "" : balancedBlock(grid, grid.indexOf('{', hookAt));
        check(hook.contains("BLOCK_CAT.clear()") && occurrences(grid, "BLOCK_CAT.clear()") == 1,
                "BLOCK_CAT.clear() 全类只出现一次,且在契约钩子 resetBlockCategoryCache() 里"
                        + "(生产旋钮 !voxel profile reset 不碰它)");

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

    /** 子串出现次数(用于"全类只此一处"这类断言)。 */
    private static int occurrences(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /** 逐行 trim 后比对(用于"两份代码是否同文"的断言;忽略缩进层级差异,但逐行内容必须一致)。 */
    private static boolean sameLines(String a, String b) {
        String[] x = a.trim().split("\\R");
        String[] y = b.trim().split("\\R");
        if (x.length != y.length) return false;
        for (int i = 0; i < x.length; i++) {
            if (!x[i].trim().equals(y[i].trim())) return false;
        }
        return true;
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

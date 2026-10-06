package dev.taclight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 最低可玩性契约:两个设备都必须能在<b>生存</b>里拿到(2026-10-06 用户提问立项)。
 *
 * <p><b>为什么单独立一条</b>:功能能跑 ≠ 玩家拿得到。手电筒此前只出现在创造模式物品栏与
 * 调试命令({@code /taclight kit})里,而 {@code src/main/resources/data/} 整棵树是空的
 * ⇒ <b>生存里没有任何获取途径</b>,发布出去等于"看得到、玩不到"。枪挂灯依赖 gunpack 里的
 * TaCZ 枪械工作台配方,同样必须钉住:gunpack 是<b>独立资源树</b>(不在 {@code data/} 下),
 * 删文件、改错 {@code type} 都不会产生任何编译错误或运行时报错,只会静默地"造不出来"。</p>
 *
 * <p><b>口径(与 TaCZ 自带配件一致)</b>:TaCZ 自带 4 个激光配件全部是
 * {@code tacz:gun_smith_table_crafting} 配方,在枪械工作台合成
 * ({@code laser_compact} 6 铁 + 4 红石 …{@code laser_peq6} 16 铁 + 4 红石 + 1 紫水晶),
 * 且 TaCZ 自己也不发 advancement(靠 JEI 展示)。本模组沿用同一形态,不另造一套。</p>
 *
 * <p><b>如实边界</b>:本契约证明"配方文件在、类型对、产物 id 对、原料是原版物品"。
 * 它<b>不</b>证明配方在真机里能被合成(那要进游戏摆格子),也不证明枪在
 * {@code allow_attachments} 里放行 —— 后者由 {@code GunLightAllowContract} 负责。</p>
 */
public class RecipePlayabilityContract {
    private static int checks;

    private static final String FLASHLIGHT_RECIPE =
            "src/main/resources/data/taclight/recipes/flashlight.json";
    private static final String GUN_LIGHT_RECIPE =
            "src/main/resources/assets/taclight/gunpack/data/taclight/recipes/attachments/gun_light.json";
    /** 配方解锁 advancement:没有它,配方在**原版配方书里根本不出现**(只能靠 JEI 或背下来)。
     *  形态逐字段照 1.20.1 原版 `data/minecraft/advancements/recipes/**` 抄
     *  (parent=recipes/root + inventory_changed + recipe_unlocked + rewards.recipes)。 */
    private static final String FLASHLIGHT_ADVANCEMENT =
            "src/main/resources/data/taclight/advancements/recipes/flashlight.json";

    public static void main(String[] args) throws Exception {
        flashlightCraftable();
        flashlightRecipeBookVisible();
        gunLightCraftable();
        idsMatchRegistry();
        System.out.println("RecipePlayabilityContract: ALL PASS (" + checks + " checks)");
    }

    /** 手电筒:vanilla 合成,且原料<b>全部原版可得</b>(没装 TaCZ 的玩家也要能造)。 */
    private static void flashlightCraftable() throws Exception {
        Path p = Path.of(FLASHLIGHT_RECIPE);
        check(Files.isRegularFile(p), "手电筒有 vanilla 配方文件: " + FLASHLIGHT_RECIPE);
        if (!Files.isRegularFile(p)) return;
        JsonObject r = parse(p);
        check("minecraft:crafting_shaped".equals(str(r, "type")),
                "配方类型 = minecraft:crafting_shaped(原版工作台,不依赖任何模组)");
        JsonObject result = obj(r, "result");
        check(result != null && "taclight:flashlight".equals(str(result, "item")),
                "产物 = taclight:flashlight");
        List<String> rows = rows(r);
        check(!rows.isEmpty() && rows.size() <= 3 && rows.stream().allMatch(x -> x.length() <= 3),
                "图案不超过 3×3(实际 " + rows.size() + " 行)");
        JsonObject key = obj(r, "key");
        check(key != null && key.size() > 0, "有 key(原料判据表)");
        if (key == null) return;
        Set<String> used = new LinkedHashSet<>();
        for (char c : String.join("", rows).toCharArray()) {
            if (c != ' ') used.add(String.valueOf(c));
        }
        for (String c : used) {
            JsonObject def = obj(key, c);
            check(def != null, "图案里的 '" + c + "' 在 key 中有定义");
            if (def == null) continue;
            String id = str(def, "item");
            check(id != null && id.startsWith("minecraft:"),
                    "原料 '" + c + "' = 原版物品(" + id + "),不依赖 TaCZ 或其它模组");
            check(!def.has("tag"), "原料 '" + c + "' 直接写物品 id(不用 tag ⇒ 少一层依赖)");
        }
        for (String c : key.keySet()) {
            check(used.contains(c), "key 里的 '" + c + "' 真的被图案用到了(防改图案忘改 key)");
        }
    }

    /**
     * 手电筒配方要在<b>原版配方书</b>里可见:1.20.1 的机制是"配方 + 对应 advancement"
     * (trigger {@code minecraft:recipe_unlocked} + {@code rewards.recipes})。
     * 只有配方文件、没有 advancement ⇒ 能手工摆出来,但配方书里永远不显示 ——
     * 对"最低可玩性"来说等于没给(玩家只能背格子或装 JEI)。
     */
    private static void flashlightRecipeBookVisible() throws Exception {
        Path p = Path.of(FLASHLIGHT_ADVANCEMENT);
        check(Files.isRegularFile(p), "手电筒配方有解锁 advancement: " + FLASHLIGHT_ADVANCEMENT);
        if (!Files.isRegularFile(p)) return;
        JsonObject a = parse(p);
        check("minecraft:recipes/root".equals(str(a, "parent")),
                "advancement parent = minecraft:recipes/root(挂在原版配方书根下)");
        JsonObject rewards = obj(a, "rewards");
        boolean rewarded = false;
        if (rewards != null && rewards.has("recipes") && rewards.get("recipes").isJsonArray()) {
            for (var e : rewards.getAsJsonArray("recipes")) {
                if ("taclight:flashlight".equals(e.getAsString())) rewarded = true;
            }
        }
        check(rewarded, "rewards.recipes 含 taclight:flashlight(否则配方书仍不解锁)");
        JsonObject crit = obj(a, "criteria");
        boolean unlocked = false;
        boolean inventory = false;
        if (crit != null) {
            for (String k : crit.keySet()) {
                JsonObject c = obj(crit, k);
                if (c == null) continue;
                if ("minecraft:recipe_unlocked".equals(str(c, "trigger"))
                        && obj(c, "conditions") != null
                        && "taclight:flashlight".equals(str(obj(c, "conditions"), "recipe"))) {
                    unlocked = true;
                }
                if ("minecraft:inventory_changed".equals(str(c, "trigger"))) inventory = true;
            }
        }
        check(unlocked, "criteria 含 recipe_unlocked 且 recipe = taclight:flashlight(与配方 id 对得上)");
        check(inventory, "criteria 含 inventory_changed(拿到原料即解锁,不必先做出成品)");
    }

    /** 枪挂灯:TaCZ 枪械工作台配方(gunpack 独立树,静默失效风险最高)。 */
    private static void gunLightCraftable() throws Exception {
        Path p = Path.of(GUN_LIGHT_RECIPE);
        check(Files.isRegularFile(p), "枪挂灯有 gunpack 配方: " + GUN_LIGHT_RECIPE);
        if (!Files.isRegularFile(p)) return;
        JsonObject r = parse(p);
        check("tacz:gun_smith_table_crafting".equals(str(r, "type")),
                "配方类型 = tacz:gun_smith_table_crafting(与 TaCZ 自带配件同形态,枪械工作台合成)");
        JsonObject result = obj(r, "result");
        check(result != null && "attachment".equals(str(result, "type"))
                        && "taclight:gun_light".equals(str(result, "id")),
                "产物 = attachment taclight:gun_light");
        JsonArray mats = r.has("materials") && r.get("materials").isJsonArray()
                ? r.getAsJsonArray("materials") : null;
        check(mats != null && mats.size() > 0, "材料非空(实际 " + (mats == null ? 0 : mats.size()) + " 项)");
        if (mats == null) return;
        int total = 0;
        for (var m : mats) {
            JsonObject mo = m.getAsJsonObject();
            check(mo.has("count") && mo.get("count").getAsInt() > 0, "材料项有正的 count");
            total += mo.has("count") ? mo.get("count").getAsInt() : 0;
        }
        // 与 TaCZ 自带激光同量级(4..32):防"要 64 个下界之星"这类脱离玩法的成本,
        // 也防"1 个铁"这种把配件变成白送。上限下界由 TaCZ 四个激光的实际用量推出。
        check(total >= 4 && total <= 32,
                "材料总量 " + total + " 与 TaCZ 自带激光配件同量级(4..32)");
    }

    /** 配方产物必须真的注册过(改名后配方会指向空气,而 JSON 依然合法)。 */
    private static void idsMatchRegistry() throws Exception {
        Path p = Path.of("src/main/java/dev/taclight/registry/ModItems.java");
        check(Files.isRegularFile(p), "找到物品注册表 ModItems.java");
        if (!Files.isRegularFile(p)) return;
        String src = Files.readString(p, StandardCharsets.UTF_8);
        check(src.contains("ITEMS.register(\"flashlight\""),
                "注册表里注册了 flashlight(手电筒配方的产物不是空气)");
        // gunpack 索引:附件必须在 index 里登记,否则枪械工作台里根本不出现这件配件
        Path idx = Path.of("src/main/resources/assets/taclight/gunpack/data/taclight/index/attachments/gun_light.json");
        check(Files.isRegularFile(idx), "gunpack 索引登记了 gun_light 附件(" + idx + ")");
        if (Files.isRegularFile(idx)) {
            JsonObject i = parse(idx);
            check("taclight.gun_light".equals(str(i, "name")) || str(i, "name") != null,
                    "索引含展示名(name=" + str(i, "name") + ")");
            check(i.has("data") && i.has("display"), "索引含 data/display(否则配件显示为空白)");
        }
    }

    // ---------------- 小工具 ----------------

    private static JsonObject parse(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static List<String> rows(JsonObject r) {
        List<String> out = new ArrayList<>();
        if (r != null && r.has("pattern") && r.get("pattern").isJsonArray()) {
            for (var e : r.getAsJsonArray("pattern")) out.add(e.getAsString());
        }
        return out;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

package dev.taclight.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手电筒开/关图标契约(2026-10-06 用户需求:"开启状态下在图标前面加发光提示")。
 *
 * <p><b>为什么要有这条</b>:物品图标随状态切换靠一条<b>跨语言、跨文件</b>的隐式约定
 * —— Java 里注册的属性 id ↔ 模型 JSON 的 {@code predicate} 键 ↔ {@code *_on} 模型
 * ↔ 贴图文件。这四处<b>任何一处写错都不会报错</b>,只会安静地"图标不跟着变"
 * (与项目历史上"代码写了但游戏里没生效"那一族缺陷同型)。本契约把四处串成一条链钉住。</p>
 *
 * <p><b>还钉死"只加光效、不改本体"</b>:机身区(x≤11)的像素必须与关闭态逐位相同,
 * 且灯头右侧(x≥14)的暖色不透明当量必须显著增大 —— 防止后人重画这张图时把机身也改了
 * (那不是本需求的授权范围),或者把"发光"画到灯尾去。</p>
 *
 * <p><b>如实边界</b>:本契约证明"链路齐、判定源唯一、图确实更亮"。
 * 它<b>不</b>证明真机里图标真的会切换(那要在游戏内看背包/手上模型) ——
 * 真机判据 = 按 {@code J} 开灯后图标出现光锥。</p>
 */
public class ItemIconStateContract {
    private static int checks;

    private static final String MODBUS = "src/main/java/dev/taclight/client/ItemModelModBus.java";
    private static final String MODEL_OFF = "src/main/resources/assets/taclight/models/item/flashlight.json";
    private static final String MODEL_ON = "src/main/resources/assets/taclight/models/item/flashlight_on.json";
    /** 灯头一侧:x≥此列才算"光效区"(机身/握把在 x≤11)。 */
    private static final int HEAD_FROM = 14;
    private static final int BODY_TO = 11;

    public static void main(String[] args) throws Exception {
        String modId = modId();
        String propId = registeredProperty(modId);
        modelChain(modId, propId);
        textureChain(modId);
        System.out.println("ItemIconStateContract: ALL PASS (" + checks + " checks)");
    }

    /** mod_id 单一真源:gradle.properties。 */
    private static String modId() throws Exception {
        for (String line : Files.readAllLines(Path.of("gradle.properties"), StandardCharsets.UTF_8)) {
            String l = line.trim();
            if (l.startsWith("mod_id=")) {
                String v = l.substring("mod_id=".length()).trim();
                check(!v.isEmpty(), "gradle.properties 的 mod_id 非空(" + v + ")");
                return v;
            }
        }
        check(false, "gradle.properties 有 mod_id 行");
        return "taclight";
    }

    /** ① Java 侧注册:属性 id 必须是 {@code <modid>:<ON_PROPERTY>},且判定源是 FlashlightItem.isOn。 */
    private static String registeredProperty(String modId) throws Exception {
        Path p = Path.of(MODBUS);
        check(Files.isRegularFile(p), "有顶层 MOD 总线注册类: " + MODBUS);
        if (!Files.isRegularFile(p)) return modId + ":on";
        String code = Files.readString(p, StandardCharsets.UTF_8);
        check(code.contains("ItemProperties.register("), "确实调用 ItemProperties.register(注册模型谓词)");
        Matcher m = Pattern.compile("ON_PROPERTY\\s*=\\s*\"([^\"]+)\"").matcher(code);
        boolean declared = m.find();
        check(declared, "类里声明了 ON_PROPERTY 常量(谓词名单一真源)");
        String prop = declared ? m.group(1) : "on";
        check(!prop.isEmpty() && !prop.contains(":"),
                "ON_PROPERTY 是纯名字(不带命名空间):" + prop);
        check(code.contains("new ResourceLocation(TacLightMod.MODID, ON_PROPERTY)"),
                "注册 id 由 modid + ON_PROPERTY 拼出(与模型 JSON 的键同源)");
        check(code.contains("FlashlightItem.isOn("),
                "★ 谓词读 FlashlightItem.isOn(与开灯判定同源,不另写一份 NBT 判断)");
        String full = modId + ":" + prop;
        check(code.contains("item property register:"),
                "注册成功留可观测日志行(它是'注册处理器确实跑过'的判据)");
        return full;
    }

    /** ②③④ 模型链:off 模型 → override(predicate=propId) → on 模型 → layer0 贴图。 */
    private static void modelChain(String modId, String propId) throws Exception {
        JsonObject off = parse(Path.of(MODEL_OFF));
        check(off != null, "关闭态模型可解析: " + MODEL_OFF);
        if (off == null) return;
        String baseTex = off.has("textures") ? str(off.getAsJsonObject("textures"), "layer0") : null;
        check((modId + ":item/flashlight").equals(baseTex),
                "关闭态 layer0 = " + modId + ":item/flashlight(原图标不动)");
        JsonArray ov = off.has("overrides") && off.get("overrides").isJsonArray()
                ? off.getAsJsonArray("overrides") : null;
        check(ov != null && ov.size() > 0, "关闭态模型带 overrides(状态切换的入口)");
        if (ov == null || ov.isEmpty()) return;
        JsonObject first = ov.get(0).getAsJsonObject();
        JsonObject pred = first.has("predicate") ? first.getAsJsonObject("predicate") : null;
        check(pred != null && pred.has(propId),
                "★ override 的 predicate 键 == Java 注册的属性 id(" + propId + ");"
                        + "不一致时图标永远不切换且无任何报错");
        if (pred != null && pred.has(propId)) {
            double v = pred.get(propId).getAsDouble();
            check(v >= 1.0, "开启态阈值为 1.0(谓词只在开灯时返回 1.0,实际 " + v + ")");
        }
        String onModel = str(first, "model");
        check((modId + ":item/flashlight_on").equals(onModel),
                "override 指向开启态模型: " + onModel);
        JsonObject on = parse(Path.of(MODEL_ON));
        check(on != null, "开启态模型存在且可解析: " + MODEL_ON);
        if (on == null) return;
        check("item/generated".equals(str(on, "parent")) || "minecraft:item/generated".equals(str(on, "parent")),
                "开启态模型同样用 item/generated(与关闭态同构)");
        String onTex = on.has("textures") ? str(on.getAsJsonObject("textures"), "layer0") : null;
        check((modId + ":item/flashlight_on").equals(onTex),
                "开启态 layer0 = " + modId + ":item/flashlight_on");
    }

    /** 贴图:16×16 RGBA、机身逐位不动、灯头侧确实更亮(可量化,不靠肉眼)。 */
    private static void textureChain(String modId) throws Exception {
        String ns = modId;
        BufferedImage offImg = image("src/main/resources/assets/" + ns + "/textures/item/flashlight.png");
        BufferedImage onImg = image("src/main/resources/assets/" + ns + "/textures/item/flashlight_on.png");
        check(offImg != null && onImg != null, "两张贴图都可读");
        if (offImg == null || onImg == null) return;
        check(offImg.getWidth() == 16 && offImg.getHeight() == 16
                        && onImg.getWidth() == 16 && onImg.getHeight() == 16,
                "两张都是 16×16(实际 " + offImg.getWidth() + "×" + offImg.getHeight()
                        + " / " + onImg.getWidth() + "×" + onImg.getHeight() + ")");
        check(offImg.getWidth() == onImg.getWidth() && offImg.getHeight() == onImg.getHeight(),
                "两图尺寸一致(否则 UI 里会跳)");

        int bodyChanged = 0;
        int diff = 0;
        double glowOff = 0;
        double glowOn = 0;
        for (int y = 0; y < offImg.getHeight(); y++) {
            for (int x = 0; x < offImg.getWidth(); x++) {
                int a = offImg.getRGB(x, y);
                int b = onImg.getRGB(x, y);
                if (a != b) diff++;
                if (x <= BODY_TO && a != b) bodyChanged++;
                if (x >= HEAD_FROM) {
                    glowOff += warmAlpha(a);
                    glowOn += warmAlpha(b);
                }
            }
        }
        check(diff > 0, "开启态贴图与关闭态不同(实际差 " + diff + " 像素)");
        check(bodyChanged == 0,
                "★ 机身区(x≤" + BODY_TO + ")逐位相同(本需求只授权加光效,不许改本体;改动="
                        + bodyChanged + " 像素)");
        check(glowOn >= glowOff * 3.0,
                "★ 灯头侧(x≥" + HEAD_FROM + ")暖色不透明当量显著增大:"
                        + String.format("%.0f -> %.0f", glowOff, glowOn) + "(要求 ≥3×)");
        check(glowOff > 0, "关闭态灯头侧本来就有微弱余光(" + String.format("%.0f", glowOff)
                + "):开态是在同一处加亮,不是另起一处");
    }

    /** 暖色不透明当量:红>200 且 绿>180 的像素按 alpha 加权;其余算 0。 */
    private static double warmAlpha(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        if (a == 0 || r <= 200 || g <= 180) return 0;
        return a;
    }

    private static BufferedImage image(String rel) {
        try {
            Path p = Path.of(rel);
            if (!Files.isRegularFile(p)) return null;
            return ImageIO.read(p.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject parse(Path p) throws Exception {
        if (!Files.isRegularFile(p)) return null;
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

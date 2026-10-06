package dev.taclight.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 手持灯持物门契约(2026-09-25,用户报的 bug)。
 *
 * <p><b>现象</b>:手里拿着枪时,<b>枪灯与手持灯同时亮</b>;把手电筒/手上物品换掉,手持灯也不灭。
 * 根因:上传器只查开关({@code ClientLightState.isOn()}),不查手里拿的是什么;
 * 而枪灯早就有"开关 × 持枪探针"的乘法门。</p>
 *
 * <p><b>用户口述的设计</b>:①先看手里是不是拿着 {@code taclight:flashlight};
 * ②再看开关;③从手里移除后自动关上。</p>
 *
 * <p>分两层:内容层=纯函数 {@code effective}/{@code autoClear} 的真值表(离线可判定);
 * 接线层=源码级,确认上传器 / 按键 / 中继 / 快照 / Iris 物品光源**五处口径一致**
 * (改漏一处 = 假绿,这是本项目反复被咬的地方)。</p>
 */
public class HandheldGateContract {
    private static int checks;
    private static final java.util.List<String> FAILURES = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        contentLayer();
        wiringLayer();
        lightSemantics();
        keybindingLayer();
        if (!FAILURES.isEmpty()) {
            throw new AssertionError("FAIL " + FAILURES.size() + " 条: " + FAILURES);
        }
        System.out.println("HandheldGateContract: ALL PASS (" + checks + " checks)");
    }

    /**
     * 键位层(2026-09-26 task-16):开灯<b>默认键不得与任何默认绑定撞车</b>。
     *
     * <p><b>为什么必须有这条</b>:旧默认 L 撞了原版 {@code key.advancements}(76),导致玩家按 L 开灯
     * 同时弹出成就界面(task-10 真机)。这次修完还要**防再犯**:谁把默认改回 L(或改成 TaCZ 占用的键)
     * 都必须**变红**。依据表来自一次性普查(vanilla {@code Options} 构造 + TaCZ 1.1.8 各 {@code *Key}
     * 类的默认绑定),写在 {@code KeyBindings} 的 javadoc 里可复核。</p>
     */
    private static void keybindingLayer() throws Exception {
        int def = KeyBindings.FLASHLIGHT_TOGGLE.getDefaultKey().getValue();
        check(KeyBindings.FLASHLIGHT_TOGGLE.getDefaultKey().getType()
                        == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                "开灯默认键是键盘键(不是鼠标键)");
        check(def > 0, "开灯默认键已绑定(不是 GLFW_KEY_UNKNOWN/0),实际值=" + def);
        // 原版 1.20.1 默认占用:字母 W A S D E F Q T P L C X + 1..9 + SPACE/LSHIFT/LCTRL/TAB/SLASH + F2/F5/F7/F11
        int[] vanillaKeys = {87, 65, 83, 68, 69, 70, 81, 84, 80, 76, 67, 88, 32, 340, 341, 258, 47,
                291, 294, 296, 300, 49, 50, 51, 52, 53, 54, 55, 56, 57};
        check(!contains(vanillaKeys, def),
                "开灯默认键不与原版 1.20.1 默认键撞车(值=" + def + ")");
        check(def != 76,
                "[旧码必红] 开灯默认键不是 L(76 = 原版 key.advancements;旧默认就是它 ⇒ 按 L 弹成就界面)");
        // TaCZ 1.1.8 默认:R(换弹) H(检视) G(开火模式) V(近战+变焦) C(匍匐) Z(改枪) O(交互) T(配置)
        int[] taczKeys = {82, 72, 71, 86, 67, 90, 79, 84};
        check(!contains(taczKeys, def),
                "开灯默认键不与 TaCZ 1.1.8 默认键撞车(R/H/G/V/C/Z/O/T —— Lead 建议的 V/H/G/R 全在其中)");
        // TacLight 自己的其它键:M(枪灯) K(霓虹) N(诊断) B(基准) F9(快照)
        int[] ownKeys = {77, 75, 78, 66, 294};
        check(!contains(ownKeys, def), "开灯默认键不与 TacLight 其它键撞车(M/K/N/B/F9)");
        // 文案标签防漂移:纯类里的常量必须等于 KeyBindings 默认键的"字母符号"
        // (不调 getDisplayName():它可能碰 GLFW 本地库;用 GLFW_KEY_A..Z 的纯算术换算,headless 安全)
        check(ShaderPackDiagLogic.FLASHLIGHT_KEY_LABEL.equals(letterOf(def)),
                "[防漂移] ShaderPackDiagLogic.FLASHLIGHT_KEY_LABEL == KeyBindings 默认键符号("
                        + ShaderPackDiagLogic.FLASHLIGHT_KEY_LABEL + " vs " + letterOf(def) + ")");

        // ---- 源码层:写死的 "L" 必须清干净(否则下次改键又会过期) ----
        String kb = Files.readString(Path.of("src/main/java/dev/taclight/client/KeyBindings.java"), StandardCharsets.UTF_8);
        check(codeLine(kb, "GLFW.GLFW_KEY_J"), "KeyBindings 默认改用 GLFW_KEY_J(代码行)");
        check(!codeLine(kb, "GLFW.GLFW_KEY_L"), "[旧码必红] KeyBindings 不再把 GLFW_KEY_L 当默认键");
        String ev = Files.readString(Path.of("src/main/java/dev/taclight/client/ClientEvents.java"), StandardCharsets.UTF_8);
        check(codeLine(ev, "getTranslatedKeyMessage()"),
                "持物门提示里的键名从 KeyMapping 现取(getTranslatedKeyMessage,不写死)");
        check(!ev.contains("再按 L"), "[旧码必红] ClientEvents 不再写死 '再按 L'");
        String diag = Files.readString(Path.of("src/main/java/dev/taclight/client/ShaderPackDiagLogic.java"), StandardCharsets.UTF_8);
        check(diag.contains("FLASHLIGHT_KEY_LABEL + \"=手电筒开关, \""), "光影诊断文案用标签常量拼(不写死 L)");
        check(!diag.contains("L=手电筒开关"), "[旧码必红] 光影诊断文案不再出现 'L=手电筒开关'");
        String relay = Files.readString(Path.of("src/main/java/dev/taclight/client/DebugCommandRelay.java"), StandardCharsets.UTF_8);
        check(codeLine(relay, "case \"advancements\": return mc.options.keyAdvancements;"),
                "!key 可注入原版成就键(advancements ⇒ mc.options.keyAdvancements)");
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) return true;
        }
        return false;
    }

    /** GLFW_KEY_A..Z(65..90)⇒ "A".."Z";其余键⇒ "?<code>"(纯算术,不碰 GLFW)。 */
    private static String letterOf(int keyCode) {
        if (keyCode >= 65 && keyCode <= 90) {
            return String.valueOf((char) ('A' + (keyCode - 65)));
        }
        return "?" + keyCode;
    }

    private static void contentLayer() {
        // effective = 开关 × (持物门 ∪ 霓虹旁路):8 种组合全覆盖
        for (boolean on : new boolean[] { false, true }) {
            for (boolean holding : new boolean[] { false, true }) {
                for (boolean neon : new boolean[] { false, true }) {
                    boolean expected = on && (holding || neon);
                    check(ClientLightState.effective(on, holding, neon) == expected,
                            "effective(switch=" + on + ", holding=" + holding + ", neon=" + neon + ")=" + expected);
                }
            }
        }
        // ↓↓↓ 旧码必红锚点:旧实现只查开关 ⇒ 未持物时也会亮(即用户报的"两盏灯同时开") ↓↓↓
        check(!ClientLightState.effective(true, false, false),
                "[旧码必红] 未持手电筒 + 开关开 ⇒ 不亮(旧码在此为亮)");
        check(ClientLightState.effective(true, true, false), "持手电筒 + 开关开 ⇒ 亮");
        check(!ClientLightState.effective(false, true, false), "开关关 ⇒ 不亮(与持物无关)");
        check(ClientLightState.effective(true, false, true),
                "霓虹调试豁免持物门:它的用途是证明 SSBO 通道,与手里拿什么无关");

        // ---- 2026-10-04 定案:开关 = **每支手电筒自己的属性**(旧 autoClear 已删除留痕) ----
        // ---- 2026-10-06 用户改判:默认**关**(旧口径"缺标签 = 开"已作废) ----
        // 纯函数核(零注册表依赖):缺标签 = 关;有标签 = 该值。
        check(!dev.taclight.item.FlashlightSwitch.resolveTag(false, false),
                "开关语义:缺标签 ⇒ 默认关(新拿到的电筒/新装的枪灯不自己亮 —— 2026-10-06 定案)");
        check(!dev.taclight.item.FlashlightSwitch.resolveTag(false, true),
                "开关语义:缺标签时即使 value=true 也是关(有无标签才是判据,防「没写=开」回潮)");
        check(!dev.taclight.item.FlashlightSwitch.resolveTag(true, false),
                "开关语义:显式 false ⇒ 关");
        check(dev.taclight.item.FlashlightSwitch.resolveTag(true, true), "开关语义:显式 true ⇒ 开");
        // 真 NBT 往返:用户原话「我手里拿了很多个手电筒,每一个的开关状态都是它自己的」 ⇒
        // **两个标签必须各记各的**。这是本轮修复的核心判据(旧码:全局 static,必然同时变)。
        // 走**纯 NBT 存储层**(isOnTag/setOnTag):本契约 JVM 不做注册表引导,
        // 构造 Item/ItemStack 会撞 Bootstrap.checkBootstrapCalled(本轮实测踩到),故不在此构造物品。
        net.minecraft.nbt.CompoundTag ta = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.CompoundTag tb = new net.minecraft.nbt.CompoundTag();
        check(!dev.taclight.item.FlashlightSwitch.isOnTag(ta) && !dev.taclight.item.FlashlightSwitch.isOnTag(tb),
                "新建电筒默认关(两支都是:空标签 ⇒ 默认关)");
        dev.taclight.item.FlashlightSwitch.setOnTag(ta, true);
        check(dev.taclight.item.FlashlightSwitch.isOnTag(ta), "★ A 自己记住了「开」");
        check(!dev.taclight.item.FlashlightSwitch.isOnTag(tb), "★ 把 A 打开**不影响** B —— 旧全局开关在此必红");
        check(ta.contains(dev.taclight.item.FlashlightSwitch.TAG_ON)
                        && "taclight_on".equals(dev.taclight.item.FlashlightSwitch.TAG_ON),
                "★ 开关是**显式写进物品自己的 NBT**(键名契约钉死),不是「没写=开」");
        dev.taclight.item.FlashlightSwitch.setOnTag(ta, false);
        dev.taclight.item.FlashlightSwitch.setOnTag(tb, true);
        check(!dev.taclight.item.FlashlightSwitch.isOnTag(ta) && dev.taclight.item.FlashlightSwitch.isOnTag(tb),
                "★ 两个堆各自独立:置位后 A 关 / B 开(单一真源 = 物品自己的 NBT)");
        check(!dev.taclight.item.FlashlightSwitch.isOnTag(null), "null 标签按「缺键」处理 ⇒ 默认关(不 NPE)");
    }

    /** 接线层:源码文本级(非运行时行为验证)。 */
    private static void wiringLayer() throws Exception {
        Path uploader = Path.of("src/main/java/dev/taclight/channel/ClientSpotlightUploader.java");
        Path events = Path.of("src/main/java/dev/taclight/client/ClientEvents.java");
        Path relay = Path.of("src/main/java/dev/taclight/client/DebugCommandRelay.java");
        Path item = Path.of("src/main/java/dev/taclight/item/FlashlightItemIris.java");
        Path state = Path.of("src/main/java/dev/taclight/client/ClientLightState.java");
        Path net = Path.of("src/main/java/dev/taclight/network/TacLightNetwork.java");
        check(Files.isRegularFile(uploader) && Files.isRegularFile(events) && Files.isRegularFile(relay)
                        && Files.isRegularFile(net),
                "找到七个受影响源文件");
        if (!Files.isRegularFile(uploader)) return;
        String up = Files.readString(uploader, StandardCharsets.UTF_8);
        String ev = Files.readString(events, StandardCharsets.UTF_8);
        String rl = Files.readString(relay, StandardCharsets.UTF_8);
        String it = Files.readString(item, StandardCharsets.UTF_8);
        String cs = Files.readString(state, StandardCharsets.UTF_8);
        String nw = Files.readString(net, StandardCharsets.UTF_8);

        check(up.contains("ClientLightState.handheldEffective()"), "接线①:上传器用手持灯有效值");
        check(!up.contains("selfOn && ClientLightState.isOn()"), "[旧码必红] 上传器不再只看开关");
        check(ev.contains("ClientLightState.setHandheldProbe("), "接线②:tick 覆写持物探针");
        // 接线③(2026-10-04 改钉):开关真源从"全局 static"改为"手上那支电筒的 NBT"。
        check(ev.contains("FlashlightItem.heldStack(mc.player)"), "接线③:tick 取「手上那支电筒」堆(主手→副手)");
        check(ev.contains("FlashlightItem.isOn(heldFlash)"), "接线③b:tick 从**物品自己的标签**读开关");
        check(!ev.contains("autoClear("), "[旧码必红] tick 不再执行「离手自动关」(旧码在此为真)");
        check(ev.contains("FlashlightItem.toggleOn(heldFlash)"), "接线③c:L 键把开关**写进物品**,不写全局");
        // 接线③e/f(2026-10-04 R55):**per-item 状态必须服务端权威** —— 只写客户端那份 ItemStack
        // 会被槽同步/重进抹掉(用户实测"切回来自己关了";R54 服务端读数实测确认,BACKLOG §2.164/§2.165)。
        check(ev.contains("sendToggleItemLight("), "接线③e:L 键把开关**意图上报服务端**(服务端权威)");
        check(nw.contains("serverApplyItemLight"), "接线③f:服务端把开关写进**它自己那份** ItemStack(权威)");
        check(nw.contains("inventoryMenu.broadcastChanges()"), "接线③g:写完做原版库存同步(客户端跟随真源)");
        check(!cs.contains("public static boolean autoClear"),
                "[旧码必红] ClientLightState 的 autoClear 函数体已删除(只留留痕注释)");
        check(cs.contains("FlashlightItem.TAG_ON"), "接线③d:ClientLightState 留痕指向新真源(可追)");
        check(ev.contains("holdingFlashlight("), "接线④:L 键走同一判据");
        check(rl.contains("ClientEvents.holdingFlashlight(mc.player)"), "接线⑤:中继 !light 走同一判据");
        check(it.contains("ClientLightState.handheldEffective()"), "接线⑦:Iris 物品光源用同一口径");
    }

    /**
     * {@code !light} 参数语义(2026-09-26 task-14):修"忽略参数、永远 toggle"的仪器缺陷。
     *
     * <p><b>为什么这属于持物门契约</b>:{@code !light} 是 L 键(受持物门管)的程序化等价通道,
     * 它的语义错位会直接让"先置 OFF 再加按键"这条因果链失效(测试同事 task-10 实际踩到)。
     * 内容层用纯函数 {@link dev.taclight.devonly.LightCommand} 真值表;接线层钉中继分支。</p>
     */
    private static void lightSemantics() throws Exception {
        // ---- 内容层:参数解析(旧码必红锚点:旧实现没有解析,任何参数都当 toggle) ----
        check(dev.taclight.devonly.LightCommand.action(null) == dev.taclight.devonly.LightCommand.ACTION_TOGGLE
                        && dev.taclight.devonly.LightCommand.action("") == dev.taclight.devonly.LightCommand.ACTION_TOGGLE,
                "无参/null = toggle(向后兼容)");
        check(dev.taclight.devonly.LightCommand.action("on") == dev.taclight.devonly.LightCommand.ACTION_ON
                        && dev.taclight.devonly.LightCommand.action(" ON ") == dev.taclight.devonly.LightCommand.ACTION_ON
                        && dev.taclight.devonly.LightCommand.action("off") == dev.taclight.devonly.LightCommand.ACTION_OFF
                        && dev.taclight.devonly.LightCommand.action("Off") == dev.taclight.devonly.LightCommand.ACTION_OFF
                        && dev.taclight.devonly.LightCommand.action("status") == dev.taclight.devonly.LightCommand.ACTION_STATUS,
                "on/off/status(大小写与空白不敏感)");
        check(dev.taclight.devonly.LightCommand.action("oops") == dev.taclight.devonly.LightCommand.ACTION_NONE,
                "[旧码必红] 未知名 ⇒ NONE(旧实现忽略参数照样 toggle;中继据此报 usage 且不改状态)");
        check(dev.taclight.devonly.LightCommand.isSet(dev.taclight.devonly.LightCommand.ACTION_ON)
                        && dev.taclight.devonly.LightCommand.isSet(dev.taclight.devonly.LightCommand.ACTION_OFF)
                        && !dev.taclight.devonly.LightCommand.isSet(dev.taclight.devonly.LightCommand.ACTION_TOGGLE),
                "isSet 只对 on/off 为真");
        check(!dev.taclight.devonly.LightCommand.mutates(dev.taclight.devonly.LightCommand.ACTION_STATUS)
                        && !dev.taclight.devonly.LightCommand.mutates(dev.taclight.devonly.LightCommand.ACTION_NONE)
                        && dev.taclight.devonly.LightCommand.mutates(dev.taclight.devonly.LightCommand.ACTION_TOGGLE),
                "status/NONE 不改状态");

        // ---- 置位幂等 + 切换 ----
        check(dev.taclight.devonly.LightCommand.nextState(dev.taclight.devonly.LightCommand.ACTION_ON, false)
                        && dev.taclight.devonly.LightCommand.nextState(dev.taclight.devonly.LightCommand.ACTION_ON, true),
                "set ON 幂等(→ ON,无论原状态)");
        check(!dev.taclight.devonly.LightCommand.nextState(dev.taclight.devonly.LightCommand.ACTION_OFF, true)
                        && !dev.taclight.devonly.LightCommand.nextState(dev.taclight.devonly.LightCommand.ACTION_OFF, false),
                "set OFF 幂等(→ OFF,无论原状态)");
        check(!dev.taclight.devonly.LightCommand.nextState(dev.taclight.devonly.LightCommand.ACTION_TOGGLE, true)
                        && dev.taclight.devonly.LightCommand.nextState(dev.taclight.devonly.LightCommand.ACTION_TOGGLE, false),
                "toggle 双向翻转");

        // ---- 回执必须能区分"切换/置位"(task-14 验收原话:回执必须含"我是 toggle 还是置位") ----
        String tg = dev.taclight.devonly.LightCommand.describe(dev.taclight.devonly.LightCommand.ACTION_TOGGLE, true, false);
        String on = dev.taclight.devonly.LightCommand.describe(dev.taclight.devonly.LightCommand.ACTION_ON, false, true);
        String off = dev.taclight.devonly.LightCommand.describe(dev.taclight.devonly.LightCommand.ACTION_OFF, true, false);
        String st = dev.taclight.devonly.LightCommand.describe(dev.taclight.devonly.LightCommand.ACTION_STATUS, true, true);
        check(tg.contains("toggle") && on.contains("set ON") && off.contains("set OFF") && st.contains("status"),
                "回执措辞区分 toggle / set / status: [" + tg + "] [" + on + "] [" + off + "]");

        // ---- 接线层(源码文本级,只看代码行) ----
        Path relay = Path.of("src/main/java/dev/taclight/client/DebugCommandRelay.java");
        String rl = Files.readString(relay, StandardCharsets.UTF_8);
        check(codeLine(rl, "LightCommand.action(arg)"), "接线①:!light 先解析参数(LightCommand.action)");
        int idxNone = rl.indexOf("LightCommand.ACTION_NONE");
        int idxSet = rl.indexOf("ClientLightState.setHandheld(after)");
        check(idxNone > 0 && idxSet > idxNone,
                "接线②:未知名分支在建状态**之前**(order: NONE@" + idxNone + " < setHandheld@" + idxSet + ")");
        check(codeLine(rl, "ClientLightState.setHandheld(after)"), "接线③:on/off 走置位 setHandheld(不再只有 toggle)");
        check(!codeLine(rl, "ClientLightState.toggle();"), "[旧码必红] 中继不再用无参 toggle()(语义含糊)");
        check(codeLine(rl, "LightCommand.describe(act, before, after)"), "接线④:回执带 toggle/set 措辞");
        check(codeLine(rl, "LightCommand.ACTION_STATUS"), "接线⑤:status 只读分支存在");
    }

    /** 是否存在**代码行**(跳过注释行)包含该子串 —— 防"注释里提过"就算接线在场。 */
    private static boolean codeLine(String src, String needle) {
        for (String line : src.split("\\R")) {
            String l = line.trim();
            if (l.startsWith("*") || l.startsWith("//") || l.startsWith("/*")) continue;
            if (l.contains(needle)) return true;
        }
        return false;
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) {
            FAILURES.add(what);
            System.out.println("  FAIL " + what);
            return;
        }
        System.out.println("  PASS " + what);
    }
}

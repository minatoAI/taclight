package dev.taclight.channel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code !key} 合成按键注入契约(2026-09-26 待办 A5)。纯 JVM:名字表 / 参数解析 /
 * 毫秒自动抬起状态机 / Tracker 全部是纯函数,Tracker 还能离线真跑;接线部分是源码文本级。
 *
 * <p><b>本契约要钉住的两个"历史坑"(都是缺一即静默无效的那种)</b>:</p>
 * <ol>
 *   <li><b>消费路径不同</b>:TacLight 自己的按键(L/M/K/N/B/F9)在 {@code InputEvent.Key} 里
 *       {@code while (consumeClick()) …},而 {@code consumeClick()} 读 {@code clickCount} ——
 *       只有真实按下路径才递增 ⇒ 只 {@code setDown} 会静默无效。契约钉住:这些名字
 *       {@code path == "event"}、原版名字 {@code path == "tick"}、且注入胶水走的是
 *       {@code KeyboardHandler.keyPress}(同一个真实路径:内部 set + click + fire 事件);</li>
 *   <li><b>焦点</b>:轮次里窗口不聚焦 ⇒ 依赖 GLFW 回调的路子不触发。契约钉住注入走
 *       <b>进程内</b>入口({@code mc.keyboardHandler.keyPress(...)},其首行只校验 window 句柄)。</li>
 * </ol>
 *
 * <p>红对照:每条断言都做过"反做 ⇒ 必红"(见 commit 信息与
 * {@code docs/evidence/2026-09-26-keyinject-dev/}),还原后 sha256 一致。</p>
 */
public final class KeyInjectContract {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final String RELAY = "src/main/java/dev/taclight/client/DebugCommandRelay.java";
    private static final String PURE = "src/main/java/dev/taclight/channel/KeyInject.java";

    public static void main(String[] args) throws Exception {
        // ================= 1. 名字表(可注入集合 + 消费路径) =================
        check(KeyInject.INJECTABLE.length == KeyInject.VANILLA.length + KeyInject.TACLIGHT.length,
                "INJECTABLE = VANILLA + TACLIGHT(" + KeyInject.VANILLA.length + " + "
                        + KeyInject.TACLIGHT.length + " = " + KeyInject.INJECTABLE.length + ")");
        check(noDup(KeyInject.INJECTABLE), "名字表无重复项");
        for (String n : KeyInject.INJECTABLE) {
            check(KeyInject.isInjectable(n), "isInjectable(" + n + ")");
        }
        check(!KeyInject.isInjectable("use2") && !KeyInject.isInjectable("USE")
                        && !KeyInject.isInjectable("") && !KeyInject.isInjectable(null)
                        && !KeyInject.isInjectable("hotbar.0") && !KeyInject.isInjectable("hotbar.10")
                        && !KeyInject.isInjectable("hotbar"),
                "未知名/大小写不符/hotbar.0/hotbar.10/hotbar 均不可注入(→ 报错列可选项,测试当负对照)");
        check(KeyInject.isVanilla("use") && KeyInject.isVanilla("hotbar.9") && !KeyInject.isVanilla("flashlight"),
                "VANILLA 判定:原版 5 键 + hotbar.1..9");
        check(KeyInject.isTacLightKey("flashlight") && KeyInject.isTacLightKey("snapshot")
                        && !KeyInject.isTacLightKey("use"),
                "TACLIGHT 判定:L/M/K/N/B/F9 六个语义名");
        check(KeyInject.TACLIGHT.length == KeyInject.TACLIGHT_EQUIV.length,
                "TACLIGHT 与 TACLIGHT_EQUIV 一一对应(长度 " + KeyInject.TACLIGHT.length + ")");
        boolean eqOk = true;
        for (String e : KeyInject.TACLIGHT_EQUIV) {
            if (e == null || !e.startsWith("!")) eqOk = false;
        }
        check(eqOk, "等价通道都是中继命令(以 ! 开头): " + String.join(" ", KeyInject.TACLIGHT_EQUIV));

        // ================= 2. 消费路径(本任务的核心风险) =================
        check(KeyInject.PATH_TICK.equals(KeyInject.consumptionPath("use"))
                        && KeyInject.PATH_TICK.equals(KeyInject.consumptionPath("attack"))
                        && KeyInject.PATH_TICK.equals(KeyInject.consumptionPath("jump"))
                        && KeyInject.PATH_TICK.equals(KeyInject.consumptionPath("sneak"))
                        && KeyInject.PATH_TICK.equals(KeyInject.consumptionPath("sprint"))
                        && KeyInject.PATH_TICK.equals(KeyInject.consumptionPath("hotbar.5")),
                "原版键(含 hotbar)= tick 路径(isDown/consumeClick 在 Minecraft.handleKeybinds)");
        check(KeyInject.PATH_EVENT.equals(KeyInject.consumptionPath("flashlight"))
                        && KeyInject.PATH_EVENT.equals(KeyInject.consumptionPath("gunlight"))
                        && KeyInject.PATH_EVENT.equals(KeyInject.consumptionPath("debug"))
                        && KeyInject.PATH_EVENT.equals(KeyInject.consumptionPath("diag"))
                        && KeyInject.PATH_EVENT.equals(KeyInject.consumptionPath("bench"))
                        && KeyInject.PATH_EVENT.equals(KeyInject.consumptionPath("snapshot")),
                "TacLight 键 = event 路径(InputEvent.Key 里 consumeClick ⇒ 必须 set+click+fire 事件)");
        check(KeyInject.PATH_UNKNOWN.equals(KeyInject.consumptionPath("nope"))
                        && KeyInject.PATH_UNKNOWN.equals(KeyInject.consumptionPath(null)),
                "未知名字 path = unknown");

        // ================= 3. hotbar 名字解析 =================
        check(KeyInject.hotbarIndex("hotbar.1") == 0 && KeyInject.hotbarIndex("hotbar.9") == 8,
                "hotbar.1..9 ⇒ 下标 0..8");
        check(KeyInject.hotbarIndex("hotbar.0") == -1 && KeyInject.hotbarIndex("hotbar.10") == -1
                        && KeyInject.hotbarIndex("use") == -1 && KeyInject.hotbarIndex(null) == -1
                        && KeyInject.hotbarIndex("hotbar.") == -1,
                "hotbar 越界/缺位/非 hotbar ⇒ -1(调用方拒绝)");

        // ================= 4. 参数解析(非法一律 NONE,不静默猜测) =================
        check(KeyInject.actionFor("down") == KeyInject.ACTION_DOWN
                        && KeyInject.actionFor(" up ") == KeyInject.ACTION_UP,
                "down/up(容忍首尾空白)");
        check(KeyInject.actionFor("1") == KeyInject.ACTION_TAP
                        && KeyInject.actionFor("800") == KeyInject.ACTION_TAP
                        && KeyInject.actionFor("60000") == KeyInject.ACTION_TAP,
                "1/800/60000 ⇒ TAP");
        check(KeyInject.actionFor("0") == KeyInject.ACTION_NONE
                        && KeyInject.actionFor("-5") == KeyInject.ACTION_NONE
                        && KeyInject.actionFor("60001") == KeyInject.ACTION_NONE
                        && KeyInject.actionFor("99999") == KeyInject.ACTION_NONE
                        && KeyInject.actionFor("1.5") == KeyInject.ACTION_NONE
                        && KeyInject.actionFor("abc") == KeyInject.ACTION_NONE
                        && KeyInject.actionFor("") == KeyInject.ACTION_NONE
                        && KeyInject.actionFor(null) == KeyInject.ACTION_NONE,
                "0/负数/超上界/小数/非数字/空 ⇒ NONE(报错 + usage)");
        check(KeyInject.tapMs("800") == 800 && KeyInject.tapMs("60000") == KeyInject.MAX_TAP_MS
                        && KeyInject.tapMs("60001") == -1 && KeyInject.tapMs("0") == -1,
                "tapMs 边界 = [MIN_TAP_MS, MAX_TAP_MS]");

        // ================= 5. 毫秒自动抬起状态机(纯函数) =================
        check(KeyInject.isDown(1000L, 800L, 1000L) && KeyInject.isDown(1000L, 800L, 1799L),
                "t0=1000 dur=800:now=1000/1799 ⇒ down(左闭)");
        check(!KeyInject.isDown(1000L, 800L, 1800L), "now=1800 ⇒ up(右开:到点即抬起)");
        check(!KeyInject.isDown(1000L, 800L, 999L), "now<t0(时钟回拨)⇒ up(安全侧)");
        check(!KeyInject.isDown(1000L, 0L, 1000L) && !KeyInject.isDown(1000L, -5L, 1000L),
                "dur<=0 ⇒ 永远 up");
        check(KeyInject.clampHoldMs(0) == KeyInject.MIN_TAP_MS && KeyInject.clampHoldMs(-5) == KeyInject.MIN_TAP_MS
                        && KeyInject.clampHoldMs(800) == 800
                        && KeyInject.clampHoldMs(9_999_999L) == KeyInject.SAFETY_HOLD_MS,
                "clampHoldMs:下限兜 1、正常原样、上限封 60s");
        check(KeyInject.held(0L, 9_999_999L, 59_000L) && !KeyInject.held(0L, 9_999_999L, 60_000L),
                "held 的兜底:巨长时长也只按 60s 算(防永久卡键)");

        // ================= 6. Tracker(离线真跑的"无卡键"证据) =================
        KeyInject.Tracker t = new KeyInject.Tracker();
        t.hold("use", 800L, 10_000L);
        check(t.size() == 1 && t.isHeld("use", 10_799L) && !t.isHeld("use", 10_800L),
                "Tracker:窗口内 held,到点不再 held");
        check(t.dueAt(10_799L).isEmpty() && t.dueAt(10_800L).equals(List.of("use")),
                "dueAt 只在到点后返回该名字(⇒ 每 tick 调一次就不会有卡键)");
        t.forget("use");
        check(t.size() == 0, "forget 后不再跟踪");
        t.hold("jump", 0L, 0L);
        check(t.size() == 0, "hold(dur=0) 不记录(等价立即抬起)");
        t.hold("jump", 9_999_999L, 0L);
        check(!t.dueAt(59_000L).contains("jump") && t.dueAt(60_000L).contains("jump"),
                "Tracker 兜底:巨长时长在 60s 处 due(即使调用方传了越界值)");
        t.hold("sprint", 500L, 0L);
        check(t.tracked().equals(List.of("jump", "sprint")), "tracked 保插入序(用 !key list 输出稳定)");
        t.clear();
        check(t.size() == 0 && t.tracked().isEmpty(), "clear 全清");

        // ================= 7. 文本级:纯核心零 MC 依赖 + 用法/报错含可选项 =================
        String pure = read(PURE);
        // 只看**代码行**(跳过 javadoc/行注释):口径说明里提到 KeyMapping/GLFW 是允许的,
        // 不许出现的是 import 或代码引用(否则纯核心就不再纯、发布路径也少一层保证)。
        boolean pureCode = true;
        for (String line : pure.split("\\R")) {
            String l = line.trim();
            if (l.startsWith("*") || l.startsWith("//") || l.startsWith("/*")) continue;
            if (l.contains("KeyMapping") || l.contains("GLFW") || l.contains("net.minecraft")
                    || l.contains("org.lwjgl") || l.contains("InputConstants")) {
                pureCode = false;
            }
        }
        check(pureCode,
                "KeyInject 是纯逻辑核心(代码行零 MC/GLFW 依赖 ⇒ 能进纯 JVM 契约,也不会把按键注入带进发布路径)");
        check(KeyInject.usage().contains("!key") && KeyInject.usage().contains("list")
                        && KeyInject.usage().contains("clear") && KeyInject.usage().contains("use"),
                "usage 串含 !key / list / clear / 名字样例");
        String unk = KeyInject.unknownName("bogus");
        check(unk.contains("bogus") && unk.contains("use") && unk.contains("hotbar.1")
                        && unk.contains("flashlight") && unk.contains("snapshot"),
                "未知名报错必须点名 + 列出全部可选项(测试当负对照): " + unk);

        // ================= 8. 文本级:dev 中继接线(路径选择 + 无卡键 + 诊断) =================
        // 行为类断言一律用 codeLineContains(**跳过注释行**):否则"注释里提过"就算接线在场,
        // 这正是本轮踩到的坑(把 releaseAll 换成别的调用,注释里还留着 KeyMapping.releaseAll())。
        String relay = read(RELAY);
        check(codeLineContains(relay, "line.startsWith(\"!key\")") && codeLineContains(relay, "KeyInjectRuntime.handle(mc, arg)"),
                "中继有 !key 分支并交给 KeyInjectRuntime(代码行)");
        check(codeLineContains(relay, "KeyInjectRuntime.tick(Minecraft.getInstance());"),
                "每 client tick 都跑 KeyInjectRuntime.tick(到点抬起;代码行)");
        int tickCall = relay.indexOf("KeyInjectRuntime.tick(Minecraft.getInstance());");
        int cooldownGate = relay.indexOf("++cooldown % 10 != 0");
        check(tickCall > 0 && cooldownGate > tickCall,
                "tick 调用**先于** 2Hz 轮询门控 ⇒ 无世界/没消费命令时也保证抬起(否则退出世界留卡键)");
        check(codeLineContains(relay, "mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key.getValue(), 0,"),
                "键盘类映射走 mc.keyboardHandler.keyPress(...) = 真实按键回调同一个入口(内含 set+click+fire InputEvent.Key)");
        check(codeLineContains(relay, "GLFW.GLFW_PRESS") && codeLineContains(relay, "GLFW.GLFW_RELEASE"),
                "按下/抬起 action 明确");
        check(codeLineContains(relay, "KeyMapping.set(key, down)") && codeLineContains(relay, "if (down) KeyMapping.click(key);"),
                "鼠标类映射(vanilla use/attack 默认绑鼠标)退化为 set+click(MouseHandler 无公开入口)");
        check(relay.contains("InputEvent.Key") && relay.contains("clickCount") && relay.contains("consumeClick"),
                "注释里写明两类消费路径与 clickCount 依据(下一个人不用重新考古)");
        check(codeLineContains(relay, "KeyMapping.releaseAll();"),
                "!key clear 用 KeyMapping.releaseAll()(isDown=false 且 clickCount=0 的最强兜底;代码行)");
        check(codeLineContains(relay, "mc.level == null && TRACKER.size() > 0"),
                "退出世界(level=null)兜底全抬(代码行)");
        check(codeLineContains(relay, "m.setDown(false)") && relay.contains("已强制抬起"),
                "异常路径 try/catch 里强制 setDown(false) ⇒ 绝不留卡键");
        // 逐个名字钉"名字 → 映射"接线(一次一条 ⇒ 红了就知道是哪个名字断的)
        String[][] mappings = {
                {"use", "mc.options.keyUse"},
                {"attack", "mc.options.keyAttack"},
                {"jump", "mc.options.keyJump"},
                {"sneak", "mc.options.keyShift"},
                {"sprint", "mc.options.keySprint"},
                {"flashlight", "KeyBindings.FLASHLIGHT_TOGGLE"},
                {"gunlight", "KeyBindings.GUNLIGHT_TOGGLE"},
                {"debug", "KeyBindings.DEBUG_TOGGLE"},
                {"diag", "KeyBindings.DIAG_DUMP"},
                {"bench", "KeyBindings.BENCH"},
                {"snapshot", "TacSnapshotKeys.SNAPSHOT"},
        };
        for (String[] m : mappings) {
            check(relay.contains("case \"" + m[0] + "\": return " + m[1] + ";"),
                    "映射接线 " + m[0] + " → " + m[1]);
        }
        check(relay.contains("KeyInject.hotbarIndex(name)") && relay.contains("mc.options.keyHotbarSlots[i]"),
                "hotbar.N 经纯函数解析到 mc.options.keyHotbarSlots[i]");
        check(relay.contains("handheld toggle ignored (not holding flashlight)"),
                "!key list 的排障提示点名了**持物门**日志行(区分'事件没到'与'到了但被业务门拒')");
        check(relay.contains("pauseOnLostFocus") && relay.contains("不依赖 GLFW 回调"),
                "注释写明焦点无关(轮次里窗口不聚焦也生效)");
        check(relay.contains("static final class KeyInjectRuntime"),
                "KeyInjectRuntime 是 DebugCommandRelay 的**嵌套类**");
        check(!Files.exists(Path.of("src/main/java/dev/taclight/client/KeyInjectRuntime.java")),
                "没有独立的 KeyInjectRuntime.java ⇒ 类名仍是 DebugCommandRelay$…，"
                        + "发布包 exclude 'DebugCommandRelay*.class' 依旧把它整类剔除(InteropPackagingContract 守)");
        for (String cmd : KeyInject.TACLIGHT_EQUIV) {
            check(relay.contains("line.equals(\"" + cmd + "\")") || relay.contains("line.startsWith(\"" + cmd + "\")"),
                    "等价通道 " + cmd + " 在中继里确实存在");
        }

        if (!FAILURES.isEmpty()) {
            throw new AssertionError("FAIL " + FAILURES.size() + " 条: " + FAILURES);
        }
        System.out.println("KeyInjectContract: ALL PASS (" + checks + " checks)");
    }

    private static boolean noDup(String[] arr) {
        for (int i = 0; i < arr.length; i++) {
            for (int j = i + 1; j < arr.length; j++) {
                if (arr[i].equals(arr[j])) return false;
            }
        }
        return true;
    }

    /**
     * 是否存在**代码行**(跳过 javadoc/行注释行)包含该子串。
     * 行为类接线断言必须用它:否则"注释里提到过"也会算通过(实测踩过:
     * 把 {@code KeyMapping.releaseAll()} 换成别的调用,注释里还留着同名串 ⇒ 断言假绿)。
     */
    private static boolean codeLineContains(String src, String needle) {
        for (String line : src.split("\\R")) {
            String l = line.trim();
            if (l.startsWith("*") || l.startsWith("//") || l.startsWith("/*")) continue;
            if (l.contains(needle)) return true;
        }
        return false;
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(Path.of(rel)), StandardCharsets.UTF_8);
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (cond) {
            System.out.println("PASS: " + what);
        } else {
            FAILURES.add(what);
            System.out.println("FAIL: " + what);
        }
    }

    private KeyInjectContract() {}
}

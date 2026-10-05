package dev.taclight.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按键存档值持久化契约(2026-09-26 task-32)。
 *
 * <p><b>要钉的两件事</b>:① 纯函数 {@link KeyPersist#parse} 的解析口径(缺行/空文件/未知名字/格式异常
 * /同名多行);② 接线顺序 —— {@code ClientEvents.ModBus.onRegisterKeys} 里必须**先注册、后应用**,
 * 且应用**只发生一次**(初始化阶段),否则会覆盖同一次会话里玩家的即时改动。</p>
 *
 * <p><b>背景(结构性证据,不猜机制)</b>:存档值的应用点 {@code Options.processOptionsForge(...)} 按
 * {@code KeyMapping.getName()} 字符串匹配;映射是构造时登记 ⇒ 本模组映射若在那一刻还不存在,
 * 存档行无处落地。注册与 {@code Options.load()} 的先后在 123 个 forge jar 里<b>查不到</b>
 * ({@code onRegisterKeyMappings} 无调用点)⇒ 机制未定论 ⇒ 修法走与机制无关的路线(自己应用一次)。</p>
 */
public final class KeyPersistContract {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final String EVENTS = "src/main/java/dev/taclight/client/ClientEvents.java";
    private static final String PURE = "src/main/java/dev/taclight/client/KeyPersist.java";
    private static final String MODBUS_SRC = "src/main/java/dev/taclight/client/KeyBindingsModBus.java";
    private static final List<String> OURS = List.of(
            "key.taclight.flashlight_toggle", "key.taclight.gunlight_toggle", "key.taclight.debug_toggle",
            "key.taclight.diag_dump", "key.taclight.bench");

    public static void main(String[] args) throws Exception {
        contentLayer();
        wiringLayer();
        if (!FAILURES.isEmpty()) throw new AssertionError("FAIL " + FAILURES.size() + " 条: " + FAILURES);
        System.out.println("KeyPersistContract: ALL PASS (" + checks + " checks)");
    }

    /** 纯函数真值表(全部离线可判定)。 */
    private static void contentLayer() {
        String txt = String.join("\n",
                "#Options",                                   // 注释行
                "version:3465",
                "key_key.advancements:key.keyboard.j",         // 原版行 ⇒ 必须忽略
                "key_key.taclight.flashlight_toggle:key.keyboard.x",   // 我们的行
                "",
                "  key_key.taclight.gunlight_toggle:key.keyboard.h  ",  // 行首尾空白
                "key_key.taclight.debug_toggle:key.keyboard.unknown",
                "garbage",
                "key_key.taclight.diag_dump",                  // 没有冒号 ⇒ 跳过
                "key_key.:key.keyboard.a",                     // 没有名字 ⇒ 跳过
                "key_key.taclight.bench:",                     // 没有值 ⇒ 跳过
                "key_key.taclight.bench:key.keyboard.b",       // 同名多行:这行在后 ⇒ 生效
                "key_key.taclight.flashlight_toggle:key.keyboard.z");   // 再覆盖一次 ⇒ 最后一行生效
        Map<String, String> m = KeyPersist.parse(txt, OURS);
        check(m.size() == 4, "解析出本模组的 4 个名字(flashlight/gunlight/debug/bench;实际 " + m.size()
                + ": " + m.keySet() + ")");
        check("key.keyboard.z".equals(m.get("key.taclight.flashlight_toggle")),
                "同名多行 ⇒ 最后一行生效(flashlight=key.keyboard.z,实际 " + m.get("key.taclight.flashlight_toggle") + ")");
        check("key.keyboard.h".equals(m.get("key.taclight.gunlight_toggle")),
                "行首尾空白被忽略(gunlight=key.keyboard.h)");
        check("key.keyboard.b".equals(m.get("key.taclight.bench")),
                "没有值的行被跳过、有值的行生效(bench=key.keyboard.b)");
        check("key.keyboard.unknown".equals(m.get("key.taclight.debug_toggle"))
                        && !m.containsKey("key.taclight.diag_dump"),
                "值原样保留(unknown);没有冒号的行(diag_dump)⇒ 这个名字不出现");
        check(!m.containsKey("key.advancements") && !m.containsKey("key_key.advancements"),
                "原版键位行一律忽略(精确名字匹配)");

        check(KeyPersist.parse(null, OURS).isEmpty(), "null 文本 ⇒ 空结果(不抛)");
        check(KeyPersist.parse("", OURS).isEmpty(), "空文件 ⇒ 空结果");
        check(KeyPersist.parse("version:3465\nkey_key.taclight.bench:key.keyboard.b", List.of()).isEmpty(),
                "names 为空 ⇒ 空结果(不做无谓匹配)");
        check(KeyPersist.parse("key_key.taclight.bench:key.keyboard.b", OURS).size() == 1,
                "缺行(只有一条)⇒ 只返回那一条");
        check(KeyPersist.parse("key_unknown_name:key.keyboard.q", OURS).isEmpty(),
                "未知名字 ⇒ 忽略(不猜)");
        check("key.keyboard.g".equals(KeyPersist.parse("key_key.taclight.bench:key.keyboard.g", OURS)
                .get("key.taclight.bench")), "正常行 ⇒ 原样取值");
        // 大小写/格式异常:值原样返回(不解析成键码 —— 那一步在接线层由 InputConstants.getKey 做)
        check("KEY.KEYBOARD.G".equals(KeyPersist.parse("key_key.taclight.bench:KEY.KEYBOARD.G", OURS)
                .get("key.taclight.bench")), "值不做大小写改写(原样交给 InputConstants.getKey)");

        // 纯类不得引入 MC 依赖(代码行、去掉字符串字面量后)
        try {
            String pure = Files.readString(Path.of(PURE), StandardCharsets.UTF_8);
            boolean clean = true;
            for (String line : pure.split("\\R")) {
                String l = line.trim();
                if (l.startsWith("*") || l.startsWith("//") || l.startsWith("/*")) continue;
                String code = l.replaceAll("\"[^\"]*\"", "\"\"");
                if (code.contains("net.minecraft") || code.contains("KeyMapping")
                        || code.contains("Files.") || code.contains("Path")) {
                    clean = false;
                }
            }
            check(clean, "KeyPersist 是纯解析类(代码行零 MC/IO 依赖 ⇒ 可离线契约)");
        } catch (Exception e) {
            check(false, "读取 " + PURE + " 失败: " + e);
        }
    }

    /** 接线层:先注册后应用 + 只应用一次 + 可观测行(源码文本级)。 */
    private static void wiringLayer() throws Exception {
        String ev = Files.readString(Path.of(EVENTS), StandardCharsets.UTF_8);
        // (1) 注册搬进**顶层**类(task-32 v2);两个嵌套 MOD 订阅都不再存在
        String reg = Files.readString(Path.of(MODBUS_SRC), StandardCharsets.UTF_8);
        check(reg.contains("Bus.MOD"), "顶层 KeyBindingsModBus 是 MOD 总线订阅类");
        check(reg.contains("event.register("),
                "top-level KeyBindingsModBus registers keys with explicit event.register");
        check(reg.contains("keybind register:") && reg.contains("registered="),
                "注册处理器自带可观测行 keybind register: registered=(task-34 的判据)");
        check(!ev.contains("Bus.MOD"), "[旧结构必红] ClientEvents 里不再有嵌套 MOD 总线订阅");
        String tick = methodBody(ev, "public static void onClientTick(TickEvent.ClientTickEvent event)");
        check(tick != null, "取到 onClientTick 的方法体");
        check(tick != null && tick.contains("applySavedKeysOnceAtFirstTick()"),
                "[★摘掉即必红] 首 tick 兜底应用在 onClientTick **方法体内**(FORGE 总线:tick 已被中继证明存活)");
        check(tick != null && tick.contains("applySavedKeysOnceAtFirstTick()")
                        && tick.indexOf("applySavedKeysOnceAtFirstTick()") < tick.indexOf("mc.level == null"),
                "兜底应用放在 world-null 提前返回**之前**(没进世界也会执行)");
        // (3) once-only 幂等守卫 + 唯一调用点
        check(ev.contains("if (appliedSavedKeys) return;") && ev.contains("appliedSavedKeys = true;"),
                "once-only 守卫存在(幂等 ⇒ 不覆盖玩家同一会话里的即时改动)");
        check(occurrences(ev, "ModBus.applySavedKeysOnceAtFirstTick();") == 1,
                "唯一调用点是 `ModBus.applySavedKeysOnceAtFirstTick();`(实际 "
                        + occurrences(ev, "ModBus.applySavedKeysOnceAtFirstTick();") + " 次;文档里的提及不算)");
        check(codeLine(ev, "KeyPersist.parse(text, ours.keySet())"), "接线用纯解析器 KeyPersist.parse(...)");
        check(ev.contains("getKey(e.getValue())") && codeLine(ev, "m.setKey(want);"),
                "存档值经 InputConstants.getKey(...) 解析后 setKey 到映射");
        check(codeLine(ev, "FMLPaths.GAMEDIR.get()") && codeLine(ev, "resolve(\"options.txt\")"),
                "读的是 <gameDir>/options.txt(与 MC 的真源一致)");
        // (4) 两条日志各自记账:兜底路径只报自己改的(不冒领注册路径的功劳)
        check(ev.contains("changed=") && ev.contains("already="),
                "应用日志分别报 changed=/already=(不把注册路径的功劳记到兜底路径头上)");
        check(codeLine(ev, "if (m.getKey().equals(want)) {") && codeLine(ev, "already++;"),
                "逐映射比较:m.getKey() 与目标值相同 ⇒ 记 already、不 setKey(幂等、不冒领)");
        check(ev.contains("keybind persist:") && ev.contains("saved=") && ev.contains("before=")
                        && ev.contains("after="),
                "应用日志保留 saved=/before=/after=(供 task-34 判定机制)");
        // 参数级断言:日志第 4 个占位符必须吃 **changed**(不是 saved.size())——
        // 否则"把注册路径的功劳记到兜底路径头上"无人拦(红对照 t32v2c_changed冒领)。
        check(codeLine(ev, "file, exists, saved.size(), changed, already, before, describeKeys(ours)"),
                "[冒领必红] 日志按 saved= / changed= / already= / before= / after= 顺序传参"
                        + "(changed 不得写成 saved.size())");
        check(!ev.contains("字节码被证明"),
                "措辞纪律:不声称\"字节码被证明是死代码\"(只说该处理器没有产生可观测副作用)");
        check(ev.contains("保持代码默认"),
                "异常路径明确\"保持代码默认\"(task-16 的默认 J 不被破坏)");
    }

    /** 取某方法的方法体(大括号配平);找不到签名 ⇒ null。作用域内断言专用(位置无关的 contains 不构成证据)。 */
    private static String methodBody(String src, String signature) {
        int i = src.indexOf(signature);
        if (i < 0) return null;
        int open = src.indexOf('{', i);
        if (open < 0) return null;
        int depth = 0;
        for (int j = open; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, j + 1);
            }
        }
        return null;
    }

    private static int occurrences(String src, String needle) {
        int n = 0, i = 0;
        while ((i = src.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

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
        if (cond) {
            System.out.println("PASS: " + what);
        } else {
            FAILURES.add(what);
            System.out.println("FAIL: " + what);
        }
    }

    private KeyPersistContract() {}
}

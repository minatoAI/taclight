package dev.taclight;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// 事件订阅"作用域 + 可观测性"契约(2026-09-26 task-54)。
//
// 由来(task-32/34/35 真机证据):ClientEvents$ModBus 与 TacSnapshotKeys$ModBus 两个**嵌套**
// @Mod.EventBusSubscriber(bus = MOD) 类没有产生任何可观测副作用(日志 0 命中、字节码里有)
// ⇒ 按键存档值不生效;搬到**顶层** KeyBindingsModBus 后真机确认生效(register=6、before= 已是玩家写的值)。
// 机制(Forge 是否把 MOD 总线事件投递给嵌套类)无法从测试侧判定 ⇒ 本契约不判机制,只钉结构纪律:
//   ① 源码中不得存在"嵌套类携带 @Mod.EventBusSubscriber"(任何 bus) —— 订阅类一律顶层;
//   ② 有副作用的订阅站点必须可观测:顶层 + 一条会打印的日志(或由**边界标记**承担);
//      类内无日志的站点必须在表里注明由谁承担,并交叉验证**调用方字面量**与**打印侧日志行**两处。
//
// 注意:本文件的说明性文字**不得**包含"斜杠星号"开头的字符序列(会提前闭合注释 —— task-54 第一版
// 就因此编译失败:注释提前闭合后,后面的中文句号成了非法字符)。
public final class SubscriberScopeContract {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final Path MAIN = Path.of("src/main");

    /** 站点 → 必须存在的"它确实跑过"标记(类内标记)。 */
    private static final Map<String, String> SIDE_EFFECT_MARKERS = new LinkedHashMap<>();

    static {
        SIDE_EFFECT_MARKERS.put("client/ClientEvents.java", "TacLightMod.LOGGER");
        SIDE_EFFECT_MARKERS.put("client/KeyBindingsModBus.java", "keybind register:");
        SIDE_EFFECT_MARKERS.put("debug/command/TacLightCommand.java", "CMDS RegisterCommandsEvent firing");
        SIDE_EFFECT_MARKERS.put("debug/TicketBridge.java", "bridge armed in");
        SIDE_EFFECT_MARKERS.put("devonly/DevLanAuthHook.java", "DEV: integrated server LAN auth disabled");
    }

    /**
     * 类内无日志站点的**边界标记**:站点 → {承载文件, 标记}。
     *
     * <p>{@code LightStatePersistence} 类内确实不打日志(登录时把存档灯态应用回去),但边界可观测 ——
     * ⚠️ 该口径第一版栽过:字面量 {@code login-restore} 是**调用方**(LightStatePersistence)传给
     * {@code TacLightNetwork.serverApply} 的,而**打印**发生在 TacLightNetwork(行里含 {@code LIGHT-SYNC})
     * ⇒ **两条都要查**,只查打印侧或只查类内都会假红/假绿。</p>
     */
    private static final Map<String, String[]> BOUNDARY_MARKERS = new LinkedHashMap<>();

    static {
        BOUNDARY_MARKERS.put("叶片恢复:调用方字面量",
                new String[]{"sync/LightStatePersistence.java", "login-restore"});
        BOUNDARY_MARKERS.put("叶片恢复:打印侧日志行",
                new String[]{"network/TacLightNetwork.java", "LIGHT-SYNC"});
    }

    public static void main(String[] args) throws Exception {
        noNestedSubscriber();
        sideEffectObservability();
        if (!FAILURES.isEmpty()) throw new AssertionError("FAIL " + FAILURES.size() + " 条: " + FAILURES);
        System.out.println("SubscriberScopeContract: ALL PASS (" + checks + " checks)");
    }

    /** ① 源码里不得存在"嵌套类携带 @Mod.EventBusSubscriber"(任何 bus);注释已等长屏蔽。 */
    private static void noNestedSubscriber() throws Exception {
        List<String> violations = new ArrayList<>();
        List<String> sites = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                // 只看**代码**:javadoc/注释里提到该注解名不算(第一版栽过 ⇒ 假红)
                String text = maskComments(Files.readString(p, StandardCharsets.UTF_8));
                int idx = 0;
                while ((idx = text.indexOf("@Mod.EventBusSubscriber", idx)) >= 0) {
                    sites.add(p.getFileName().toString());
                    if (depthAt(text, idx) > 0) violations.add(p.getFileName() + "@" + lineOf(text, idx));
                    idx += 10;
                }
            }
        }
        check(!sites.isEmpty(), "扫到订阅站点(代码内,已屏蔽注释)");
        check(violations.isEmpty(),
                "[★必红] 不存在\"嵌套类携带 @Mod.EventBusSubscriber\"(任何 bus);违反处=" + violations);
        check(sites.size() >= 8, "订阅站点数 ≥8(顶层 7 + 顶层注册类 1);实际 " + sites.size());
    }

    /** ② 有副作用的站点必须有标记;类内无日志者查**两条**边界标记。 */
    private static void sideEffectObservability() throws Exception {
        for (Map.Entry<String, String> e : SIDE_EFFECT_MARKERS.entrySet()) {
            Path p = MAIN.resolve("java/dev/taclight/" + e.getKey());
            check(Files.exists(p) && Files.readString(p, StandardCharsets.UTF_8).contains(e.getValue()),
                    "[可观测] " + e.getKey() + " 含标记 '" + e.getValue() + "'(它确实跑过的判据)");
        }
        for (Map.Entry<String, String[]> e : BOUNDARY_MARKERS.entrySet()) {
            Path carrier = MAIN.resolve("java/dev/taclight/" + e.getValue()[0]);
            check(Files.exists(carrier) && Files.readString(carrier, StandardCharsets.UTF_8)
                            .contains(e.getValue()[1]),
                    "[边界可观测] " + e.getKey() + " ⇒ " + e.getValue()[0] + " 含 '" + e.getValue()[1] + "'");
        }
        Path modbus = MAIN.resolve("java/dev/taclight/client/KeyBindingsModBus.java");
        String mb = Files.readString(modbus, StandardCharsets.UTF_8);
        check(mb.contains("event.register(") && mb.contains("TacSnapshotKeys.SNAPSHOT"),
                "顶层注册类用显式 event.register(...)(含快照键)");
    }

    /** 注释与字符串替换为等长空白(索引不变)。 */
    static String maskComments(String text) {
        char[] a = text.toCharArray();
        boolean line = false, block = false, str = false, chr = false;
        for (int i = 0; i < a.length; i++) {
            char c = a[i];
            char n = i + 1 < a.length ? a[i + 1] : '\0';
            if (line) {
                if (c == '\n') line = false; else a[i] = ' ';
            } else if (block) {
                if (c == '*' && n == '/') { a[i] = ' '; a[i + 1] = ' '; block = false; i++; }
                else if (c != '\n') a[i] = ' ';
            } else if (str || chr) {
                char q = str ? '"' : '\'';
                if (c == '\\') { a[i] = ' '; if (i + 1 < a.length) a[i + 1] = ' '; i++; }
                else if (c == q) { a[i] = ' '; str = false; chr = false; }
                else if (c != '\n') a[i] = ' ';
            } else if (c == '/' && n == '/') {
                line = true; a[i] = ' ';
            } else if (c == '/' && n == '*') {
                block = true; a[i] = ' ';
            } else if (c == '"') {
                str = true; a[i] = ' ';
            } else if (c == '\'') {
                chr = true; a[i] = ' ';
            }
        }
        return new String(a);
    }

    /** index 处的大括号深度(0 = 顶层 class 体;>0 = 嵌在别的类里)。 */
    static int depthAt(String text, int index) {
        int depth = 0;
        for (int i = 0; i < index && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth;
    }

    private static int lineOf(String text, int index) {
        int n = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
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

    private SubscriberScopeContract() {}
}

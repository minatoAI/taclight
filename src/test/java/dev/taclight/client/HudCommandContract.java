package dev.taclight.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code !hud off|on|status} 契约(2026-09-26 task-51 第 2 步)。
 *
 * <p><b>五条</b>:① 三分支真值表;② {@code !mutates(ACTION_NONE)} 且 {@code !mutates(ACTION_STATUS)}
 * (把"无参/未知参不改状态"写成机器判据 —— 这正是 {@code !light} 曾经出错的地方);
 * ③ {@code off} 的赋值必须落在 <b>{@code !hud} 分支片段内</b>(位置无关的 contains 不算,沿用 task-24/32 教训);
 * ④ STATUS 块内<b>不得出现</b> {@code mc.options.hideGui =}(只读)+ {@code describe} 逐字;
 * ⑤ <b>dev-only</b>:{@code !hud} 串在 {@code src/main} 只出现在 relay,且 release 件不含 relay 类。</p>
 */
public final class HudCommandContract {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final String RELAY = "src/main/java/dev/taclight/client/DebugCommandRelay.java";
    private static final String PURE = "src/main/java/dev/taclight/client/HudCommand.java";

    public static void main(String[] args) throws Exception {
        contentLayer();
        wiringLayer();
        if (!FAILURES.isEmpty()) throw new AssertionError("FAIL " + FAILURES.size() + " 条: " + FAILURES);
        System.out.println("HudCommandContract: ALL PASS (" + checks + " checks)");
    }

    /** ① 三分支真值表 + ② mutates + describe 逐字。 */
    private static void contentLayer() {
        check(HudCommand.action("off") == HudCommand.ACTION_OFF, "action(\"off\") = ACTION_OFF");
        check(HudCommand.action("on") == HudCommand.ACTION_ON, "action(\"on\") = ACTION_ON");
        check(HudCommand.action("status") == HudCommand.ACTION_STATUS, "action(\"status\") = ACTION_STATUS");
        check(HudCommand.action(" OFF ") == HudCommand.ACTION_OFF
                        && HudCommand.action("On") == HudCommand.ACTION_ON,
                "大小写不敏感 + 忽略首尾空白");
        check(HudCommand.action("") == HudCommand.ACTION_NONE
                        && HudCommand.action(null) == HudCommand.ACTION_NONE
                        && HudCommand.action("toggle") == HudCommand.ACTION_NONE
                        && HudCommand.action("offx") == HudCommand.ACTION_NONE,
                "无参 / null / 'toggle' / 未知串 ⇒ ACTION_NONE(不猜;**本命令没有 toggle**)");
        // ② "不改状态"必须机器可判
        check(!HudCommand.mutates(HudCommand.ACTION_NONE) && !HudCommand.mutates(HudCommand.ACTION_STATUS),
                "[★必红] !mutates(ACTION_NONE) 且 !mutates(ACTION_STATUS)(无参/未知与只读都不改状态)");
        check(HudCommand.mutates(HudCommand.ACTION_ON) && HudCommand.mutates(HudCommand.ACTION_OFF),
                "on/off 是幂等置位(会改状态)");
        check(HudCommand.targetHideGui(HudCommand.ACTION_OFF) && !HudCommand.targetHideGui(HudCommand.ACTION_ON),
                "off ⇒ hideGui=true;on ⇒ false");
        check("hud=off(hideGui=true)".equals(HudCommand.describe(true))
                        && "hud=on(hideGui=false)".equals(HudCommand.describe(false)),
                "[逐字] describe 回执 = hud=off(hideGui=true) / hud=on(hideGui=false)");
        check(HudCommand.usage().contains("off") && HudCommand.usage().contains("on")
                        && HudCommand.usage().contains("status"),
                "usage 里三个分支都点名");
        // 纯核心不得提供 toggle 语义(代码行,去注释)
        try {
            String pure = Files.readString(Path.of(PURE), StandardCharsets.UTF_8);
            boolean clean = true;
            for (String line : pure.split("\\R")) {
                String l = line.trim();
                if (l.startsWith("*") || l.startsWith("//") || l.startsWith("/*")) continue;
                if (l.contains("nextState") || l.contains("toggle(")) clean = false;
            }
            check(clean, "HudCommand 代码行不含 toggle/nextState(本命令不提供反转语义)");
        } catch (Exception e) {
            check(false, "读取 " + PURE + " 失败: " + e);
        }
    }

    /** ③④⑤ 接线与 dev-only(源码文本级 + release 件名断言)。 */
    private static void wiringLayer() throws Exception {
        String relay = Files.readString(Path.of(RELAY), StandardCharsets.UTF_8);
        int start = relay.indexOf("line.startsWith(\"!hud\")");
        check(start > 0, "relay 里有 !hud 分支");
        int end = start < 0 ? -1 : relay.indexOf("\n        if (", start);
        String frag = (start < 0 || end < 0) ? null : relay.substring(start, end);
        check(frag != null, "取到 !hud 分支片段(到下一个同级 if 之前)");
        // ③ 赋值必须在这个片段内(位置无关 contains 不算)
        check(frag != null && frag.contains("mc.options.hideGui = HudCommand.targetHideGui(act);"),
                "[★必红·作用域内] off/on 的赋值落在 !hud 分支片段内"
                        + "(`mc.options.hideGui = HudCommand.targetHideGui(act);`)");
        // ④ STATUS 块只读
        int st = frag == null ? -1 : frag.indexOf("HudCommand.ACTION_STATUS");
        int stEnd = st < 0 ? -1 : frag.indexOf("return;", st);
        String statusBlock = (st < 0 || stEnd < 0) ? null : frag.substring(st, stEnd);
        check(statusBlock != null, "取到 STATUS 块");
        check(statusBlock != null && !statusBlock.contains("mc.options.hideGui ="),
                "[★必红] STATUS 块内不得出现 mc.options.hideGui =(只读,不改状态)");
        check(statusBlock != null && statusBlock.contains("HudCommand.describe(mc.options.hideGui)"),
                "STATUS 回执经 describe(mc.options.hideGui) 读当前值");
        check(frag != null && frag.contains("HudCommand.ACTION_NONE") && frag.contains("HudCommand.usage()"),
                "无参/未知参数 ⇒ 报 usage 且 return(不改状态)");
        // ⑤ dev-only:命令串只出现在"relay + 其纯核心"这两个文件里(没有第三处)
        // ⚠️ 口径(第一版断言写错、被绿跑抓到):**不能**声称"release jar 不含 '!hud' 串" ——
        // HudCommand.usage() 的字符串会随 HudCommand.class 进包(与 R3 指出"发布包其实含 KeyInject.class"同源)。
        // 能诚实主张的是:**发布件里该命令不可达**(唯一解析/派发点 DebugCommandRelay 被 exclude)。
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    if (Files.readString(p, StandardCharsets.UTF_8).contains("!hud")) hits.add(p.toString());
                } catch (Exception ignored) {
                }
            });
        }
        boolean onlyExpected = hits.size() == 2;
        for (String h : hits) {
            String n = h.replace('\\', '/');
            if (!n.endsWith("client/DebugCommandRelay.java") && !n.endsWith("client/HudCommand.java")) {
                onlyExpected = false;
            }
        }
        check(onlyExpected, "[dev-only] '!hud' 串在 src/main 只出现在 relay 与其纯核心 HudCommand(实际 " + hits + ")");
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        check(gradle.contains("DebugCommandRelay*.class"),
                "[dev-only] build.gradle 仍把 DebugCommandRelay* 排除在发布件外 ⇒ **发布件里该命令不可达**"
                        + "(唯一解析/派发点不在包里;不声称'包内不含该字符串')");
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

    private HudCommandContract() {}
}

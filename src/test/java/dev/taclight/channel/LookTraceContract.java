package dev.taclight.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * LookTrace 契约(消融探针,09-01):格式稳定性与窗口行为是离线消融分析器(lookreplay.js)
 * 的解析前提,必须钉死。09-01 晚追加:on 门控模式(MotionCapture 驱动开/收窗)与
 * posO/posC 位置字段(位置同步链路留痕;lookreplay 行正则尾部不锚定,向后兼容)。
 */
public final class LookTraceContract {
    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("PASS: " + msg);
    }

    public static void main(String[] args) {
        // ---- 收集器注入 ----
        List<String> out = new ArrayList<>();
        LookTrace.sink = out::add;

        // ---- 1) 默认关闭,状态可查 ----
        check(!LookTrace.active(), "默认不采集");
        String st = LookTrace.configure("");
        check(st.contains("off"), "空参返回状态含 off: " + st);

        // ---- 2) 帧数开启 + 行格式稳定(解析器依赖) ----
        LookTrace.configure("120");
        check(LookTrace.active(), "configure(120) 后采集开启");
        long nano = 0;
        for (int i = 0; i < 50; i++) {
            nano += 8_333_333L;
            LookTrace.row(42, "minecraft:armor_stand", nano,
                    0.5f, -179.0f + i * 0.2f, -178.0f + i * 0.2f, 0f, 0.2f, 10f, 11f,
                    -179.1f + i * 0.2f, 10.5f, 1.0f, 1.25f,
                    10.5, 64.0, -3.25, 10.75, 64.0, -3.00);
        }
        check(out.stream().anyMatch(s -> s.contains("LOOKTRACE-START id=42 type=minecraft:armor_stand")),
                "首帧发 START 标记(含 id/type)");
        check(out.stream().anyMatch(s -> s.contains("LOOKTRACE-START id=42") && s.contains("剩余=120")),
                "START 标记带窗口剩余帧数");
        Pattern row = Pattern.compile("\\[TacLight\\] LOOKTRACE t=\\d+\\.\\d+ pt=\\d+\\.\\d+{4} hO=-?\\d+\\.\\d+{3} hC=-?\\d+\\.\\d+{3} bO=-?\\d+\\.\\d+{3} bC=-?\\d+\\.\\d+{3} pO=-?\\d+\\.\\d+{3} pC=-?\\d+\\.\\d+{3} base=\\(-?\\d+\\.\\d+{3},-?\\d+\\.\\d+{3}\\) om=-?\\d+\\.\\d+{3} ext=-?\\d+\\.\\d+{3}"
                + " posO=\\(-?\\d+\\.\\d{2},-?\\d+\\.\\d{2},-?\\d+\\.\\d{2}\\) posC=\\(-?\\d+\\.\\d{2},-?\\d+\\.\\d{2},-?\\d+\\.\\d{2}\\)");
        check(out.stream().filter(s -> s.contains("LOOKTRACE t=")).allMatch(s -> row.matcher(s).matches()),
                "全部数据行匹配固定格式正则(" + out.stream().filter(s -> s.contains("LOOKTRACE t=")).count() + " 行,含 pos 字段)");

        // ---- 3) 相对时间从 0 单调递增 ----
        String first = out.stream().filter(s -> s.contains("LOOKTRACE t=")).findFirst().orElse("");
        check(first.contains(" t=0.0 "), "首行相对时间为 0: " + first);
        double last = -1;
        boolean mono = true;
        for (String s : out) {
            if (!s.contains("LOOKTRACE t=")) continue;
            double t = Double.parseDouble(s.replaceAll(".* LOOKTRACE t=([\\d.]+) .*", "$1"));
            if (t < last) mono = false;
            last = t;
        }
        check(mono, "相对时间单调不减");

        // ---- 4) 目标切换发新 START(采样对象漂移可追溯) ----
        LookTrace.row(77, "minecraft:zombie", nano + 8_333_333L, 0.5f, 1f, 2f, 0f, 0f, 0f, 0f, 1.5f, 0f, 1f, 0f,
                0, 0, 0, 0, 0, 0);
        check(out.stream().anyMatch(s -> s.contains("LOOKTRACE-START id=77 type=minecraft:zombie")),
                "目标变更时发新 START 标记");

        // ---- 5) 窗口自动停 + END 标记 ----
        for (int i = 0; i < 200; i++) {
            nano += 8_333_333L;
            LookTrace.row(77, "minecraft:zombie", nano, 0.5f, 1f, 2f, 0f, 0f, 0f, 0f, 1.5f, 0f, 0f, 0f,
                    0, 0, 0, 0, 0, 0);
        }
        check(!LookTrace.active(), "剩余帧耗尽自动停");
        check(out.stream().anyMatch(s -> s.contains("LOOKTRACE-END") && s.contains("why=窗口")),
                "窗口结束发 END 标记");
        check(LookTrace.configure("").contains("off"), "自动停后状态回 off");

        // ---- 6) 停止后 row 静默;手动 off 幂等 ----
        int n = out.size();
        LookTrace.row(77, "minecraft:zombie", nano, 0.5f, 1f, 2f, 0f, 0f, 0f, 0f, 1.5f, 0f, 0f, 0f,
                0, 0, 0, 0, 0, 0);
        check(out.size() == n, "停止后 row 不产生输出");
        LookTrace.configure("off");
        LookTrace.configure("off");
        check(true, "手动 off 幂等不抛");

        // ---- 7) 帧数钳制(60-7200) ----
        String clamped = LookTrace.configure("999999");
        check(clamped.contains("7200"), "超大帧数钳到 7200: " + clamped);
        LookTrace.configure("off");
        String clampedLow = LookTrace.configure("1");
        check(clampedLow.contains("60"), "过小帧数钳到 60: " + clampedLow);
        LookTrace.configure("off");

        // ---- 8) on 门控模式:无窗口上限,手动 off 收窗(MotionCapture 驱动) ----
        out.clear();
        LookTrace.configure("on");
        check(LookTrace.active(), "configure(on) 开启门控模式");
        for (int i = 0; i < 7500; i++) {
            nano += 8_333_333L;
            LookTrace.row(9, "minecraft:player", nano, 0.5f, 1f, 2f, 0f, 0f, 0f, 0f, 1.5f, 0f, 0f, 0f,
                    10.5, 64.0, -3.25, 10.75, 64.0, -3.00);
        }
        check(out.stream().anyMatch(s -> s.contains("LOOKTRACE-START") && s.contains("剩余=gated")),
                "门控模式 START 标记剩余=gated");
        check(LookTrace.active(), "门控模式超 7200 帧不自动停");
        int before = out.size();
        LookTrace.configure("off");
        check(out.size() > before && out.get(out.size() - 1).contains("LOOKTRACE-END") && out.get(out.size() - 1).contains("why=手动"),
                "门控模式手动 off 发 END(why=手动)");
        LookTrace.row(9, "minecraft:player", nano, 0.5f, 1f, 2f, 0f, 0f, 0f, 0f, 1.5f, 0f, 0f, 0f,
                10.5, 64.0, -3.25, 10.75, 64.0, -3.00);
        check(out.size() == before + 1, "门控 off 后 row 静默");
        check(LookTrace.configure("").contains("off"), "门控收窗后状态回 off");

        // ---- 还原默认 sink ----
        LookTrace.sink = null;
        System.out.println("LookTraceContract: ALL PASS (" + passed + " checks)");
    }
}

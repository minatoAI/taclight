package dev.taclight.channel;

/**
 * !perf 计时器契约(2026-09-25 性能 ⑨):状态机 + 相位均值 + 百分位数学,纯 JVM。
 *
 * <p>不测帧间隔(那是 nanoTime,真机才有意义);只测"喂进去的数能原样算出均值"、
 * "非法参数被拒绝"、"stop/reset 语义"。旧码必红:删掉任一相位累加 ⇒ 均值断言变红。
 */
public final class PerfStatsContract {
    private static int passed;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("PASS: " + msg);
    }

    public static void main(String[] args) {
        // 非法参数:不启动、不抛异常
        check(PerfStats.configure("bogus").startsWith("usage:"), "非法参数回 usage");
        check(!PerfStats.active(), "非法参数后仍 idle");
        check(PerfStats.configure("start 1").startsWith("range"), "秒数下界被拒绝");
        check(PerfStats.configure("start 61").startsWith("range"), "秒数上界被拒绝");
        check(PerfStats.configure("start xx").startsWith("bad arg"), "非数字被拒绝");
        check(!PerfStats.active(), "越界参数后仍 idle");

        // 状态机:一轮完整臂
        check(PerfStats.configure("start 5").equals("PERF start (5s)"), "start 回显臂长");
        check(PerfStats.active(), "start 后 active");
        PerfStats.notePhases(1.0, 2.0, 0.5, 0.25, 3.75, 2);
        PerfStats.notePhases(3.0, 4.0, 1.5, 0.75, 9.25, 2);
        PerfStats.noteUpload(816, 0);
        PerfStats.noteUpload(816, 186 * 1024);
        String rep = PerfStats.configure("stop");
        check(!PerfStats.active(), "stop 后 inactive");
        check(rep.contains("collect=2.00/3.00"), "collect 均值/峰值: " + firstLine(rep));
        check(rep.contains("voxel=3.00/4.00"), "voxel 均值/峰值");
        check(rep.contains("upload=0.50/0.75"), "upload 均值/峰值");
        check(rep.contains("total=6.50/9.25"), "total 均值/峰值");
        check(rep.contains("lights avg=2.00 max=2"), "灯数均值/峰值");
        check(rep.contains("calls=2") && rep.contains("tailUploads=1"), "上传计数(头2次/尾1次)");

        // status 在停止后回放上一份报告;reset 回 idle
        check(PerfStats.configure("status").contains("(stopped)"), "stop 后 status 回放");
        check(PerfStats.configure("reset").equals("PERF reset"), "reset 回显");
        check(PerfStats.configure("status").contains("idle"), "reset 后 idle");

        // 二次 start 清零上一轮(均值不串台)
        PerfStats.configure("start 3");
        PerfStats.notePhases(10.0, 10.0, 10.0, 10.0, 40.0, 8);
        String rep2 = PerfStats.configure("stop");
        check(rep2.contains("collect=10.00/10.00") && rep2.contains("lights avg=8.00 max=8"),
                "二次 start 清零上一轮");
        PerfStats.configure("reset");

        // 百分位数学(与 bench 同式,此处直接钉死)
        double[] s4 = {0.01, 0.02, 0.03, 0.04};
        check(Math.abs(PerfStats.percentileFps(s4, 0.25) - 25.0) < 1e-9, "p25 = 1/最慢帧");
        check(Math.abs(PerfStats.percentileFps(s4, 0.5) - 2 / 0.07) < 1e-9, "p50 = 2/最慢两帧之和");
        check(Math.abs(PerfStats.percentileFps(s4, 0.01) - 25.0) < 1e-9, "不足1%时至少取1帧");

        System.out.println("PerfStatsContract: ALL PASS (" + passed + " checks)");
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}

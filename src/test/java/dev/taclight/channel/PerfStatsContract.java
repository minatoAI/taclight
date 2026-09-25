package dev.taclight.channel;

import java.util.List;

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

        measurementSemantics();
        System.out.println("PerfStatsContract: ALL PASS (" + passed + " checks)");
    }

    /**
     * <b>测量口径钉死(2026-09-25 待办 ⑰:三类数字不许混用)</b>。
     *
     * <p>为什么需要:历史上两次误判都源于"把一个读数当成另一个量" —— ① {@code !synth 6} 时
     * RELAY 自报 {@code total ssbo count = 2+6 = 8},而同一轮的 PERF 行报 {@code lights avg=1.00}
     * (实测:{@code docs/evidence/2026-09-25-voxel-heavy/relay-log-full.txt} L154 与 L160);
     * ② 拿 {@code ssbo count} 与 {@code lights} 做"一致性校验"。根因是**口径没写下来**。</p>
     *
     * <p>本节做的是<b>文本级(接线级)断言</b>:这几条语义由"谁在哪个调用点取哪个数"决定,
     * 离线没有 GL/世界,驱动不了运行期 ⇒ 只能钉源码文本 + 生产调用点。运行期可判定的那部分
     * ({@code profile reset} 不清 BLOCK_CAT)在 {@code VoxelRealRegistryContract} 里钉。</p>
     */
    private static void measurementSemantics() {
        String uploader = read("src/main/java/dev/taclight/channel/ClientSpotlightUploader.java");
        String perf = read("src/main/java/dev/taclight/channel/PerfStats.java");
        String lightbuf = read("src/main/java/dev/taclight/channel/LightBuffer.java");
        String mixin = read("src/main/java/dev/taclight/mixin/debug/SynthLightMixin.java");
        String events = read("src/main/java/dev/taclight/client/ClientEvents.java");

        // ---- (A) PERF `lights` = 世界推导灯数(自灯+远端),不含 !synth 合成灯 ----
        int uploadCall = uploader.indexOf("LightBuffer.upload(lights, extraFlags, voxelGrid);");
        int noteCall = uploader.indexOf("PerfStats.notePhases(");
        check(uploadCall > 0 && noteCall > uploadCall,
                "PerfStats.notePhases 的测量点在 LightBuffer.upload 调用之后(量的是同一帧的上传)");
        check(uploader.contains("(tP4 - tP0) / 1e6, lights.size());"),
                "lights 实参 = 调用方列表的 lights.size()(合成灯不在这个数里)");
        check(mixin.contains("@ModifyVariable(") && mixin.contains("argsOnly = true")
                        && mixin.contains("@At(\"HEAD\")") && mixin.contains("index = 0")
                        && mixin.contains("upload(Ljava/util/List;ILdev/taclight/channel/VoxelField$Snapshot;)V"),
                "合成灯注入点 = @ModifyVariable(argsOnly=true, HEAD, index=0) ⇒ 只换 upload 的形参,不回写调用方列表");
        check(perf.contains("不含") && perf.contains("合成灯"),
                "PerfStats 源码写明 lights 口径 = 不含合成灯(下一个人不会当 bug 改掉)");

        // ---- (B) `!diag` 的 `ssbo count` = 实际上传槽数(含合成灯,钳 MAX_LIGHTS) ----
        check(lightbuf.contains("int count = Math.min(lights.size(), SpotlightBufferLayout.MAX_LIGHTS);"),
                "count = min(形参列表长度, MAX_LIGHTS) ⇒ 它是槽数(含合成灯)");
        check(lightbuf.contains("SpotlightBufferLayout.writeHeader(buf, count, 1.0f, flags);")
                        && lightbuf.contains("for (int i = 0; i < count; i++)")
                        && lightbuf.contains("SpotlightBufferLayout.writeLight(buf, i, lights.get(i));"),
                "同一个 count 既写 SSBO 头字、又决定 writeLight 循环 ⇒ 就是 GLSL 遍历的槽数");
        check(lightbuf.contains("int count = buf.getInt();") && lightbuf.contains("\"count=%d"),
                "dumpLight0 的 count= 从 GPU 头第 0 字回读,与上传写进去的是同一个数");
        check(lightbuf.contains("含") && lightbuf.contains("合成灯") && lightbuf.contains("不得互相校验"),
                "LightBuffer 源码写明 count 口径 = 含合成灯、不得与 PERF lights 互校");
        check(events.contains("| ssbo {} |") && events.contains("LightBuffer.dumpLight0()"),
                "日志字段 `ssbo count=` 与生产者 dumpLight0 绑定(驱动 grep 的目标有定义)");

        // ---- (C) 轮预算字段真源在 harness 报告,不在 mod 侧(位置断言 + 双字段口径留档) ----
        // 2026-09-26 task-13:harness 侧改名为 roundBudgetCapSec(上限) + 新增 roundBudgetElapsedSec
        // (实际已用墙钟秒 = roundWallSeconds);旧键 roundBudgetUsedSec 保留但 deprecated。
        // 两条都必须"mod 主源码(剥注释后)零命中":名字写进 mod 源码本身就说明有人在 mod 侧伪造该量。
        List<String> hits = mainSourcesMentioning("roundBudgetUsedSec");
        check(hits.isEmpty(),
                "mod 主源码(剥注释后)零命中 roundBudgetUsedSec" + hits
                        + " ⇒ 旧键 deprecated、真源在 harness 报告(与 roundBudgetCapSec 同值,仅供旧 JSON 兼容)");
        List<String> hitsCap = mainSourcesMentioning("roundBudgetCapSec");
        check(hitsCap.isEmpty(),
                "mod 主源码(剥注释后)零命中 roundBudgetCapSec" + hitsCap
                        + " ⇒ 该字段=驱动配置的**预算上限**(默认 1500;-RoundBudgetSec 10 的干跑写 10),"
                        + "mod 侧无此量、也不得引用");
        List<String> hitsElapsed = mainSourcesMentioning("roundBudgetElapsedSec");
        check(hitsElapsed.isEmpty(),
                "mod 主源码(剥注释后)零命中 roundBudgetElapsedSec" + hitsElapsed
                        + " ⇒ 该字段=本轮**实际已用墙钟秒**(= harness 的 roundWallSeconds);"
                        + "要\"已用秒\"直接读它,不必由驱动另行计时");
        // 口径说明必须真在源码里(读原文,不剥注释):三个字段名 + roundWallSeconds 等价 + 旧话已作废
        String perfSrc = read("src/main/java/dev/taclight/channel/PerfStats.java");
        // ⚠️ 用**带 {@code 花括号}的完整形态**匹配:第一版写成裸 "roundWallSeconds" ⇒ 变异成
        // "roundWallSeconds_unsynced" 时**仍然包含**该子串 ⇒ 断言假绿(实测,红对照 t13b 抓到)。
        check(perfSrc.contains("{@code roundBudgetCapSec}") && perfSrc.contains("{@code roundBudgetElapsedSec}")
                        && perfSrc.contains("{@code roundWallSeconds}") && perfSrc.contains("deprecated"),
                "PerfStats 口径 javadoc 已同步双字段(上限 / 已用墙钟秒=roundWallSeconds / 旧键 deprecated)");
        int legacy = countOf(perfSrc, "须由驱动另行计时");
        check(perfSrc.contains("作废") && legacy == 1,
                "[旧口径必红] 旧句\"须由驱动另行计时\"只作为**作废说明**出现一次(实际 " + legacy + " 次)");
    }

    private static int countOf(String src, String needle) {
        int n = 0, i = 0;
        while ((i = src.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** 读工程内相对路径的文本(缺失即红;口径断言必须"读得到"才有意义)。 */
    private static String read(String rel) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(rel)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("FAIL: 读不到 " + rel + " : " + e);
        }
    }

    /**
     * {@code src/main} 下(剥注释后)含指定标识符的 .java 文件。
     * 剥注释很重要:口径**说明**写在 javadoc 里是允许的,不许出现的是**代码**(字段/引用)。
     */
    private static List<String> mainSourcesMentioning(String needle) {
        List<String> hits = new java.util.ArrayList<>();
        try (var s = java.nio.file.Files.walk(java.nio.file.Path.of("src/main"))) {
            for (java.nio.file.Path p : s.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                if (stripComments(read(p.toString().replace('\\', '/'))).contains(needle)) {
                    hits.add(p.toString());
                }
            }
        } catch (Exception e) {
            throw new AssertionError("FAIL: 扫 src/main 失败: " + e);
        }
        return hits;
    }

    /** 剥掉 C 风格行注释与块注释(含字符串/字符字面量保护;只为上面的位置断言用)。 */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean line = false, block = false, inStr = false, inChr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') { line = false; out.append(c); }
                continue;
            }
            if (block) {
                if (c == '*' && n == '/') { block = false; i++; }
                continue;
            }
            if (inStr || inChr) {
                out.append(c);
                if (c == '\\' && n != '\0') { out.append(n); i++; }
                else if (inStr && c == '"') inStr = false;
                else if (inChr && c == '\'') inChr = false;
                continue;
            }
            if (c == '/' && n == '/') { line = true; i++; continue; }
            if (c == '/' && n == '*') { block = true; i++; continue; }
            if (c == '"') inStr = true;
            if (c == '\'') inChr = true;
            out.append(c);
        }
        return out.toString();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}

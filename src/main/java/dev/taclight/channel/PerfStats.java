package dev.taclight.channel;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CPU 侧性能计时器(2026-09-25 性能瓶颈定位 ⑨,第一刀:消融 + 相位计时)。
 *
 * <p>回答三个问题,不碰 GPU(我们的注入是每像素×每灯的体素 DDA,GPU 侧大头只能由
 * 消融差值反推或 Nsight GPU Trace 归因;本类只给 CPU 侧的确数):
 * <ol>
 *   <li>帧率:与 B 键 {@code !bench} 同口径(avgFPS / 1% low / minFPS),但窗口可配
 *       (默认 8s,够一次消融臂稳定),且自动收尾打日志;</li>
 *   <li>CPU 相位:{@code ClientSpotlightUploader.onFrame} 内四段
 *       (collect / voxel / post / upload)的 avg/max ms —— 直接回答"体素重建
 *       是不是 CPU 先卡住"(悬案 {@code builds=934} 与 31ms {@code lastBuildMs});</li>
 *   <li>上传量:SSBO 头(816B/帧)与体素尾(仅 version 变化时,约 180KB/次)的
 *       调用数与字节总数 —— 回答"1MB SSBO 上传贵不贵"。体素盒/builds 数
 *       由既有 {@code !voxel status} 给,本类不重复(零耦合)。</li>
 * </ol>
 *
 * <p><b>测量口径(2026-09-25 待办 ⑰:三类数字不许混用,历史上已误判两次)</b>:</p>
 * <ol>
 *   <li>{@code PERF ... lights avg/max} = 本帧<b>世界推导</b>灯数(自灯 handheld/gun + 远端灯),
 *       <b>不含</b> {@code !synth} 合成灯 —— 合成灯只在 {@code LightBuffer.upload} 内部由 dev mixin
 *       替换<b>形参</b>、不回写调用方列表,而本数取的是 {@code ClientSpotlightUploader} 里调用方的
 *       {@code lights.size()}。⇒ 它<b>不是</b> SSBO 槽数,<b>不得</b>与 {@code ssbo count} 互校
 *       ({@code !synth 6} 时 PERF 报 1..2 而 {@code !diag} 报 7..8 是<b>预期</b>);</li>
 *   <li>{@code !diag} 的 {@code ssbo count=N} = {@code LightBuffer.dumpLight0()} 的 {@code count=}
 *       (从 GPU 头第 0 字回读)= 实际上传槽数 = {@code min(列表长度, MAX_LIGHTS=8)},
 *       <b>含</b>合成灯,并等于 GLSL 每像素遍历的灯槽数(见 {@code LightBuffer.upload} 的口径段);</li>
 *   <li>{@code roundBudgetUsedSec}(harness 报告字段,<b>mod 侧无此量</b>)= 驱动配置的
 *       <b>预算上限</b>,不是用量(证据:同一生成器在 {@code -RoundBudgetSec 10} 的干跑里写 10、
 *       默认轮写 1500)⇒ 引用时必须写"预算上限";要"已用秒"须由驱动另行计时。</li>
 * </ol>
 * <p><b>臂标识</b>只认配置/开关字段本身({@code !voxel classcache=on|off}、{@code !synth N} 回显、
 * {@code cone=}/{@code lagmax=}),<b>不要</b>用 {@code lights=}/{@code cat=} 这类会被上述口径差异
 * 影响的读数当"这一轮跑的是哪个臂"的依据({@code !voxel profile reset} 还会把 {@code cat=} 清零)。</p>
 *
 * <p><b>为什么放在 channel 而不是 debug</b>:发布包会剔除 {@code dev.taclight.debug.**}
 * 与 {@code DebugCommandRelay},生产代码若直引 debug 类则 release 编译即断
 * (先例:合成灯经 {@code mixin/debug} 混入,生产类零改)。本类沿
 * {@link FrameRecorder} 的先例 —— 常驻 channel、默认关闭、开销 = 每帧一次
 * volatile 读;{@code !perf} 入口只在 relay 变体存在,release 里是死代码。</p>
 *
 * <p><b>线程</b>:{@code tickFrame}/{@code notePhases}/{@code noteUpload} 在渲染线程
 * (AFTER_LEVEL);{@code configure} 在客户端 tick 线程(中继)。全部状态经
 * synchronized 互斥,读 {@code active()} 为无锁 volatile。</p>
 */
public final class PerfStats {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 默认臂长(秒):8s ≈ 250 帧@30fps,均值稳定,又能在 150s 上限里排下 6 臂。 */
    public static final int DEFAULT_SECONDS = 8;
    public static final int MIN_SECONDS = 2;
    public static final int MAX_SECONDS = 60;

    private static volatile boolean active;
    private static long startNanos;
    private static long deadlineNanos;
    private static long prevFrameNanos;
    private static final List<Long> DELTAS = new ArrayList<>();
    private static double sumCollect, sumVoxel, sumPost, sumUpload, sumTotal;
    private static double maxCollect, maxVoxel, maxPost, maxUpload, maxTotal;
    private static long phaseFrames;
    private static long sumLights;
    private static int maxLights;
    private static long uploadCalls, headerBytes, tailUploads, tailBytes;
    private static String lastReport;

    private PerfStats() {}

    /** 渲染线程热路径守卫:关着时调用方连 nanoTime 都不取。 */
    public static boolean active() {
        return active;
    }

    /**
     * 中继入口。用法 {@code !perf start [秒] | stop | status | reset}。
     * 无参 = status。返回多行文本,调用方按行打日志。
     */
    public static synchronized String configure(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty() || a.equals("status")) {
            return statusLocked();
        }
        if (a.equals("stop")) {
            if (!active && lastReport == null) return "PERF idle (never started)";
            String rep = finishLocked(System.nanoTime(), false);
            return rep;
        }
        if (a.equals("reset")) {
            resetLocked();
            return "PERF reset";
        }
        if (a.equals("start") || a.startsWith("start ")) {
            int sec = DEFAULT_SECONDS;
            String rest = a.length() > 5 ? a.substring(5).trim() : "";
            if (!rest.isEmpty()) {
                try {
                    sec = Integer.parseInt(rest);
                } catch (NumberFormatException e) {
                    return "bad arg '" + rest + "' (want start [2..60]/stop/status/reset)";
                }
                if (sec < MIN_SECONDS || sec > MAX_SECONDS) {
                    return "range " + MIN_SECONDS + ".." + MAX_SECONDS + ", got " + sec;
                }
            }
            resetLocked();
            long now = System.nanoTime();
            startNanos = now;
            prevFrameNanos = 0;
            deadlineNanos = now + (long) sec * 1_000_000_000L;
            active = true;
            return "PERF start (" + sec + "s)";
        }
        return "usage: !perf start [2..60] | stop | status | reset";
    }

    /** 每渲染帧调用(与 benchTickFrame 同点)。记录帧间隔;到 deadline 自动收尾打日志。 */
    public static void tickFrame() {
        long now = System.nanoTime();
        String auto = null;
        synchronized (PerfStats.class) {
            if (!active) return;
            if (prevFrameNanos > 0) DELTAS.add(now - prevFrameNanos);
            prevFrameNanos = now;
            if (now >= deadlineNanos) {
                auto = finishLocked(now, true);
            }
        }
        if (auto != null) {
            for (String line : auto.split("\n")) {
                LOGGER.info("[TacLight] {} (auto-stop)", line);
            }
        }
    }

    /**
     * 上游 onFrame 每帧调用(仅 active 时)。
     *
     * @param collectMs 灯收集(含远程)ms
     * @param voxelMs   VoxelGrid.update ms(含 20Hz 节流命中复用,复用帧≈0)
     * @param postMs    钳制 + 时间复用置信度 ms
     * @param uploadMs  LightBuffer.upload ms(含 GPU  stalls,见类注释)
     * @param totalMs   四段总和外加 temporal/开关杂项(FrameRecorder/lookTrace 布防时除外)
     * @param lights    本帧<b>世界推导</b>灯数(自灯 + 远端灯):取的是 {@code ClientSpotlightUploader}
     *                  调用方的 {@code lights.size()} ⇒ <b>不含</b> {@code !synth} 合成灯
     *                  (合成灯只在 {@code LightBuffer.upload} 体内替换形参,不回写调用方列表)。
     *                  <b>不得</b>与 {@code !diag} 的 {@code ssbo count}(实际上传槽数,含合成灯)
     *                  互校 —— 详见类注释"测量口径"。
     */
    public static synchronized void notePhases(double collectMs, double voxelMs, double postMs,
                                               double uploadMs, double totalMs, int lights) {
        if (!active) return;
        sumCollect += collectMs; maxCollect = Math.max(maxCollect, collectMs);
        sumVoxel += voxelMs; maxVoxel = Math.max(maxVoxel, voxelMs);
        sumPost += postMs; maxPost = Math.max(maxPost, postMs);
        sumUpload += uploadMs; maxUpload = Math.max(maxUpload, uploadMs);
        sumTotal += totalMs; maxTotal = Math.max(maxTotal, totalMs);
        phaseFrames++;
        sumLights += lights;
        maxLights = Math.max(maxLights, lights);
    }

    /** LightBuffer 每成功上传一次调用(头必传,尾仅 version 变化时)。 */
    public static synchronized void noteUpload(long headerB, long tailB) {
        if (!active) return;
        uploadCalls++;
        headerBytes += headerB;
        if (tailB > 0) {
            tailUploads++;
            tailBytes += tailB;
        }
    }

    private static String statusLocked() {
        if (active) {
            return reportLocked(System.nanoTime()) + "\nPERF (running)";
        }
        if (lastReport != null) return lastReport + "\nPERF (stopped)";
        return "PERF idle (never started)";
    }

    private static String finishLocked(long now, boolean auto) {
        active = false;
        lastReport = reportLocked(now);
        return lastReport + (auto ? "\nPERF (auto-stop)" : "\nPERF (stopped)");
    }

    private static void resetLocked() {
        active = false;
        DELTAS.clear();
        sumCollect = sumVoxel = sumPost = sumUpload = sumTotal = 0;
        maxCollect = maxVoxel = maxPost = maxUpload = maxTotal = 0;
        phaseFrames = 0;
        sumLights = 0;
        maxLights = 0;
        uploadCalls = 0;
        headerBytes = 0;
        tailUploads = 0;
        tailBytes = 0;
        lastReport = null;
    }

    private static String reportLocked(long now) {
        double durS = (now - startNanos) / 1e9;
        StringBuilder sb = new StringBuilder();
        if (DELTAS.isEmpty()) {
            sb.append("PERF frames=0 dur=").append(fmt(durS)).append("s (no-samples)");
        } else {
            double[] sorted = new double[DELTAS.size()];
            double total = 0;
            for (int i = 0; i < sorted.length; i++) {
                sorted[i] = DELTAS.get(i) / 1e9;
                total += sorted[i];
            }
            java.util.Arrays.sort(sorted);
            double avg = sorted.length / total;
            double p1 = percentileFps(sorted, 0.01);
            double min = 1.0 / sorted[sorted.length - 1];
            double avgMs = total / sorted.length * 1000.0;
            sb.append("PERF frames=").append(sorted.length)
                    .append(" dur=").append(fmt(durS)).append("s")
                    .append(" avgFPS=").append(fmt(avg))
                    .append(" p1Low=").append(fmt(p1))
                    .append(" minFPS=").append(fmt(min))
                    .append(" avgFrameMs=").append(fmt(avgMs));
        }
        sb.append('\n');
        if (phaseFrames == 0) {
            sb.append("PERF phases: no-samples");
        } else {
            double n = phaseFrames;
            sb.append("PERF phases avg/max ms: collect=").append(fmt(sumCollect / n)).append('/').append(fmt(maxCollect))
                    .append(" voxel=").append(fmt(sumVoxel / n)).append('/').append(fmt(maxVoxel))
                    .append(" post=").append(fmt(sumPost / n)).append('/').append(fmt(maxPost))
                    .append(" upload=").append(fmt(sumUpload / n)).append('/').append(fmt(maxUpload))
                    .append(" total=").append(fmt(sumTotal / n)).append('/').append(fmt(maxTotal))
                    .append(" lights avg=").append(fmt(sumLights / n)).append(" max=").append(maxLights);
        }
        sb.append('\n');
        sb.append("PERF upload calls=").append(uploadCalls)
                .append(" headerTotal=").append(headerBytes / 1024).append("KB")
                .append(" tailUploads=").append(tailUploads)
                .append(" tailTotal=").append(tailBytes / 1024).append("KB");
        if (tailUploads > 0) {
            sb.append(" avgTail=").append(tailBytes / tailUploads / 1024).append("KB");
        }
        sb.append(" (dur ").append(fmt(durS)).append("s)");
        return sb.toString();
    }

    /** worstFrac 比例最慢帧的调和平均(1% low 惯例;与 ClientEvents.bench 同式)。 */
    static double percentileFps(double[] sortedAsc, double worstFrac) {
        int n = Math.max(1, (int) Math.ceil(sortedAsc.length * worstFrac));
        double sum = 0;
        for (int i = 0; i < n; i++) sum += sortedAsc[sortedAsc.length - 1 - i];
        return n / sum;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}

package dev.taclight.channel;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/**
 * S4a 烘焙驱动(2026-10-05,spike 专用、非发布路径)。
 *
 * <p>状态机:{@code IDLE → ARMED(!shadowbake) → BAKING(逐帧 N 行) → DONE};
 * {@code !shadowoff} 回到 IDLE 并作废缓冲。设计取舍(刻意、留痕):</p>
 * <ol>
 *   <li><b>烘焙只 frozen 一次</b>:灯移动后 depth 即过期(状态行报 age),
 *       S4a 只回答"depth 有没有用",逐帧跟随是 S4b 的题。</li>
 *   <li><b>逐帧限行</b>({@link #ROWS_PER_FRAME}):262144 条 {@code level.clip}
 *       一次跑完会卡主线程秒级 ⇒ 摊到约 64 帧,每帧耗时进状态行。</li>
 *   <li><b>形状口径</b>:{@code Block.COLLIDER}(与"挡实体"同口径;栅栏横杆在内)。
 *       若日后发现 COLLIDER 与遮挡形分歧,换口径即新实验,不在此混入变量。</li>
 *   <li>只烘焙 <b>light0</b>(spike 单灯;多灯是版本化的题)。</li>
 * </ol>
 */
public final class ShadowDepthBake {
    /** 每渲染帧烘焙行数(512/8 = 64 帧 ≈ 1 秒级完成,单帧耗时见状态行)。 */
    public static final int ROWS_PER_FRAME = 8;

    private enum State { IDLE, ARMED, BAKING, DONE }

    private static State state = State.IDLE;
    private static float[] data;
    private static double[] basis;
    private static double tanHalf;
    private static double px, py, pz, far;
    private static double near = ShadowDepthBaker.NEAR;
    private static int cursor;
    private static long t0;
    private static long doneMs;
    private static long lastTickMs;
    private static long doneFrame;
    private static boolean armedLogged;

    private ShadowDepthBake() {}

    /** 布防:下一帧 onRenderLevel 按当时 light0 开烘。 */
    public static synchronized String arm() {
        state = State.ARMED;
        armedLogged = false;
        data = null;
        cursor = 0;
        return "shadowbake armed(下一帧按当时 light0 开烘;烘焙中请勿动灯/视角)";
    }

    /** 取消 + 作废缓冲(Java 侧 valid=false ⇒ 包侧回退体素/SSO,零行为残留)。 */
    public static synchronized String cancel() {
        state = State.IDLE;
        data = null;
        ShadowDepthBuffer.invalidate();
        return "shadowbake off(depth 作废,回退体素/SSO)";
    }

    public static synchronized String status() {
        long age = state == State.DONE
                ? dev.taclight.channel.ClientSpotlightUploader.currentRenderFrame() - doneFrame : -1;
        return "shadowbake state=" + state
                + " cursor=" + cursor + "/" + ShadowDepthBaker.SIZE
                + " lastTickMs=" + lastTickMs
                + (state == State.DONE ? " doneMs=" + doneMs + " ageFrames=" + age : "")
                + " depthValid=" + ShadowDepthBuffer.hasValid();
    }

    /** 渲染线程调用(AFTER_LEVEL,onFrame 之后)。 */
    public static void tick(Minecraft mc) {
        State s;
        synchronized (ShadowDepthBake.class) {
            s = state;
        }
        if (s == State.ARMED) {
            SpotlightData l0 = ClientSpotlightUploader.lastLight0();
            if (l0 == null || mc.level == null || mc.player == null) {
                synchronized (ShadowDepthBake.class) {
                    if (!armedLogged) {
                        armedLogged = true;
                        dev.taclight.TacLightMod.LOGGER.info(
                                "[TacLight] shadowbake armed,等待 light0(请先开灯)…");
                    }
                }
                return;
            }
            begin(l0);
        } else if (s == State.BAKING) {
            if (mc.level == null || mc.player == null) return;
            step(mc);
        }
    }

    private static synchronized void begin(SpotlightData l0) {
        basis = ShadowDepthBaker.basis(l0.dirX(), l0.dirY(), l0.dirZ());
        tanHalf = ShadowDepthBaker.tanHalf(l0.cosOuter());
        px = l0.posX(); py = l0.posY(); pz = l0.posZ();
        far = Math.max(l0.radius(), 4.0);
        data = new float[ShadowDepthBaker.TOTAL_FLOATS];
        java.util.Arrays.fill(data, ShadowDepthBaker.HEAD_FLOATS, data.length, 1.0f);
        cursor = 0;
        t0 = System.nanoTime();
        state = State.BAKING;
        dev.taclight.TacLightMod.LOGGER.info(
                "[TacLight] shadowbake begin pos=({},{},{}) far={} tanHalf={}",
                String.format("%.2f", px), String.format("%.2f", py), String.format("%.2f", pz),
                String.format("%.1f", far), String.format("%.3f", tanHalf));
    }

    private static void step(Minecraft mc) {
        long a = System.nanoTime();
        int rows;
        synchronized (ShadowDepthBake.class) {
            rows = Math.min(ROWS_PER_FRAME, ShadowDepthBaker.SIZE - cursor);
        }
        Vec3 start = new Vec3(px, py, pz);
        for (int r = 0; r < rows; r++) {
            int ty;
            synchronized (ShadowDepthBake.class) {
                ty = cursor++;
            }
            for (int tx = 0; tx < ShadowDepthBaker.SIZE; tx++) {
                double[] d = ShadowDepthBaker.rayDirForTexel(tx, ty, basis, tanHalf);
                Vec3 end = start.add(d[0] * far, d[1] * far, d[2] * far);
                BlockHitResult hit = mc.level.clip(
                        new ClipContext(start, end, ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE, mc.player));
                float v;
                if (hit.getType() == HitResult.Type.MISS) {
                    v = 1.0f;
                } else {
                    double dist = start.distanceTo(hit.getLocation());
                    v = ShadowDepthBaker.encode(dist, near, far);
                }
                data[ShadowDepthBaker.HEAD_FLOATS + ty * ShadowDepthBaker.SIZE + tx] = v;
            }
        }
        long ms = (System.nanoTime() - a) / 1_000_000L;
        boolean done = false;
        synchronized (ShadowDepthBake.class) {
            lastTickMs = ms;
            done = cursor >= ShadowDepthBaker.SIZE;
        }
        if (done) finish();
    }

    private static synchronized void finish() {
        ShadowDepthBaker.writeHead(data, true, px, py, pz, basis, tanHalf, near, far);
        ShadowDepthBuffer.upload(data);
        doneMs = (System.nanoTime() - t0) / 1_000_000L;
        doneFrame = ClientSpotlightUploader.currentRenderFrame();
        state = State.DONE;
        // S4-1 判据原料:条纹方差(纯白/纯黑即 FAIL),随完成行一起打出来
        double mean = 0;
        int n = ShadowDepthBaker.DEPTH_FLOATS;
        for (int i = 0; i < n; i++) mean += data[ShadowDepthBaker.HEAD_FLOATS + i];
        mean /= n;
        double var = 0;
        for (int i = 0; i < n; i++) {
            double d = data[ShadowDepthBaker.HEAD_FLOATS + i] - mean;
            var += d * d;
        }
        var /= n;
        dev.taclight.TacLightMod.LOGGER.info(
                "[TacLight] shadowbake done rows={} doneMs={} mean={} var={} (S4-1: var>0 才算非空)",
                cursor, doneMs, String.format("%.4f", mean), String.format("%.6f", var));
    }
}

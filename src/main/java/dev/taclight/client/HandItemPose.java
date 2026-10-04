package dev.taclight.client;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * HandItemPose —— 手持灯的"**手部**"锚点修正（2026-10-04 R59；用户需求 ⑤⑥）。
 *
 * <p><b>用户原话</b>：⑤"现在的光线默认从角色右手模型（长方体端点）发出，要改成从**物品上**发出，
 * 并跟随角色手部晃动**同步摆动**"；⑥"切换主副手时，光线发出的方向也要从对应的手发散
 * —— 现在切换主副手不影响光线"。</p>
 *
 * <p><b>为什么是解析式而不是矩阵捕获（R59 实测教训）</b>：先试了 Forge 的
 * {@code RenderHandEvent}（带 {@code PoseStack}，看起来最直接），真机实测捕获到的
 * **平移恒为 (0,0,0)**（`capMain=(0.000,-0.000,0.000)`）—— 该事件触发点的手部变换**还没写进**
 * 这个 pose stack ⇒ 拿不到物品位置（枪口那条链能拿到，是因为 TaCZ 的 {@code BeamRenderer}
 * 在自己的矩阵里已经算好了手部空间）。⇒ 改为**解析式**：直接用玩家动画状态
 * （{@code getAttackAnim} = 挥动进度）+ 主副手手性，复刻 vanilla
 * {@code ItemInHandRenderer} 的挥动位移量级与相位。</p>
 *
 * <p><b>语义</b>：锚点 = 旧的眼位基准 + {@link #handBase(Vec3, boolean)}（前/左-右/下的手部基准，
 * 主副手**左右翻转** ⇒ ⑥）+ {@link #sway(Vec3, float, boolean)}（挥动摆动 ⇒ ⑤）。</p>
 */
public final class HandItemPose {

    /** 手部基准（相对眼位）：前 0.35 / 右 0.22 / 下 0.14（旧行为，右手；副手把"右"翻成"左"）。 */
    public static final double BASE_FORWARD = 0.35;
    public static final double BASE_SIDE = 0.22;
    public static final double BASE_DOWN = 0.14;

    /** 灯头沿视线再前移的距离（格）；{@code !lens 0} = 阳性对照（看摆动是否只由挥动提供）。 */
    public static final float DEFAULT_LENS_FORWARD = 0.18f;
    private static volatile float lensForward = DEFAULT_LENS_FORWARD;

    // ---- 历史环:摆动幅度只能从"最近一小段"里量（!key attack 的挥动在日志写入时往往已结束） ----
    private static final int HIST = 180;
    private static final Vec3[] H = new Vec3[HIST];
    private static final long[] HT = new long[HIST];
    private static volatile int hi;
    private static final long WINDOW_NANOS = 1_200_000_000L;

    private static volatile Vec3 lastLens;
    private static volatile Vec3 lastDir;
    private static volatile String lastSrc = "(尚未上传)";
    private static volatile boolean lastOffHand;
    private static volatile float lastSwing;
    private static volatile int poseUsed;
    private static volatile int fallbackUsed;

    private HandItemPose() {}

    /** 视线右手方向（与上传器 rightVector 同式）：look×up = (-z,0,x) 归一化。 */
    private static Vec3 right(Vec3 look) {
        Vec3 r = new Vec3(-look.z, 0, look.x);
        return r.length() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize();
    }

    /** 手部基准（相对眼位）：前 / 左右（主副手翻转 ⇒ ⑥）/ 下。 */
    public static Vec3 handBase(Vec3 look, boolean offHand) {
        double side = offHand ? -BASE_SIDE : BASE_SIDE;
        return look.scale(BASE_FORWARD).add(right(look).scale(side)).add(0, -BASE_DOWN, 0);
    }

    /**
     * 挥动摆动（视图空间偏移，与 vanilla {@code applyItemArmAttackTransform} 同相位、同量级）：
     * {@code swingProgress} = {@code player.getAttackAnim(partialTick)} ∈ [0,1]。
     *
     * <p>三轴：视图空间 x=右、y=上、z=**后**（-Z 前）⇒ 返回值直接用
     * {@code MuzzlePoseMath.gunViewDirToWorld} 世界化。副手整体左右镜像。</p>
     */
    public static Vec3 sway(Vec3 look, float swingProgress, boolean offHand) {
        float s = Mth.clamp(swingProgress, 0f, 1f);
        float up = -0.28f * Mth.sin(Mth.sqrt(s) * (float) Math.PI);
        float side = 0.14f * Mth.sin(Mth.sqrt(s) * (float) (Math.PI * 2.0));
        float back = -0.12f * Mth.sin(s * (float) Math.PI);
        double sign = offHand ? -1.0 : 1.0;
        return new Vec3(side * sign, up, back);
    }

    public static float lensForward() {
        return lensForward;
    }

    public static void setLensForward(float v) {
        lensForward = v;
    }

    public static void resetLensForward() {
        lensForward = DEFAULT_LENS_FORWARD;
    }

    public static void noteUploaded(Vec3 lens, Vec3 dir, String src, boolean offHand, float swing) {
        lastLens = lens;
        lastDir = dir;
        lastSrc = src;
        lastOffHand = offHand;
        lastSwing = swing;
        if ("pose-item".equals(src)) {
            poseUsed++;
        } else {
            fallbackUsed++;
        }
        push(lens);
    }

    private static void push(Vec3 p) {
        int i = hi;
        H[i] = p;
        HT[i] = System.nanoTime();
        hi = (i + 1) % HIST;
    }

    /** 最近 WINDOW 内的样本数与"相对最老样本的最大位移"（格）—— 摆动幅度的机判量。 */
    public static double[] spanInWindow() {
        long now = System.nanoTime();
        Vec3 first = null;
        double span = 0.0;
        int n = 0;
        for (int k = 0; k < HIST; k++) {
            int i = (hi - 1 - k + HIST * 2) % HIST;
            Vec3 p = H[i];
            if (p == null || now - HT[i] > WINDOW_NANOS) {
                break;
            }
            if (first == null) {
                first = p;
            }
            span = Math.max(span, p.distanceTo(first));
            n++;
        }
        return new double[]{n, span};
    }

    /** {@code !anchor} 的回执串。 */
    public static String describe() {
        Vec3 a = lastLens;
        Vec3 d = lastDir;
        double[] sw = spanInWindow();
        return String.format(java.util.Locale.ROOT,
                "anchor=(%.4f, %.4f, %.4f) dir=(%.4f, %.4f, %.4f) src=%s hand=%s lens=%.3f swing=%.3f "
                        + "samples=%d span=%.4f poseUsed=%d fallbackUsed=%d",
                a == null ? 0.0 : a.x, a == null ? 0.0 : a.y, a == null ? 0.0 : a.z,
                d == null ? 0.0 : d.x, d == null ? 0.0 : d.y, d == null ? 0.0 : d.z,
                lastSrc, lastOffHand ? "off" : "main", lensForward, lastSwing,
                (int) sw[0], sw[1], poseUsed, fallbackUsed);
    }
}

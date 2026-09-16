package dev.taclight.debug;

import dev.taclight.channel.ClientSpotlightUploader;
import dev.taclight.channel.LightLevelOverride;
import dev.taclight.channel.LightTuneOverride;
import dev.taclight.channel.SpotlightBufferLayout;
import dev.taclight.channel.SpotlightData;
import dev.taclight.config.TacLightConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * T11/T13/T15 测试光源夹具:继电器 {@code !synth <N> [dist] [mode=arc|slots]} 追加 N 盏**合成**聚光灯。
 *
 * <p>用途:本 rig 单人存档最多 2 盏灯(手持+枪灯,{@code ClientSpotlightUploader:93-128}),而
 * 优化3 的第二块表({@code composite1.fsh:147-153},灯槽 4..7 走 {@code row_b1})需要**总 count ≥ 5**
 * 才执行。合成灯让"≥5 盏"在单客户端可达。</p>
 *
 * <p><b>默认关、零副作用</b>:{@code count == 0} 时 {@link #append(List)} 原样返回入参
 * (不新建 List、不读 world、不改状态) ⇒ 不开开关时 SSBO 内容/数量/标志与现状逐字节一致。</p>
 *
 * <p><b>几何模式</b>(不带参数 = 旧行为不变):</p>
 * <ul>
 *   <li>{@code !synth N} / {@code !synth N 0} / {@code mode=arc}:T11/T13 旧几何——绕玩家 2.5 格弧线
 *       ({@code dist>0} 时前移 dist 格)瞄准眼前 6 格光池。</li>
 *   <li>{@code !synth N mode=slots}(T15/T16):**固定 8 槽**横向梯形几何,槽位与 N 无关,
 *       只启用前 N 个 ⇒ N 是嵌套子集(跨 N 比较不再混入几何变量,预审 S25)。
 *       槽位在相机前方 1.0~2.5 格、左右 ±2~8 格、略低于视线,统一瞄准眼前 {@value #ROI_DIST} 格的光池;
 *       灯到光池距离 4~9.4 格(旧几何 ~2.5 格)⇒ DDA 路更长,更接近远处灯的真实负载。</li>
 * </ul>
 *
 * <p><b>贡献自证</b>({@link #echo}):逐灯打印 {@code d2eye/d2roi/cosAng/inCone/spot/atten/ph/term/pass/insideSolid}。
 * {@code term = spot*atten*ph} 与 {@code composite1.fsh:142} 的便宜门({@code < 1e-4} ⇒ continue)
 * **同一算式**(常量同源:{@code TACLIGHT_BEAM_NORM 0.4}、{@code TACLIGHT_ATTEN_K 20}、
 * {@code taclight_attenuation()} 见 {@code taclight_core.glsl:166-172}),{@code gatePass=false} 即该灯
 * 对当前 ROI 不贡献采样 ⇒ 该臂无效,不再静默。</p>
 */
public final class SynthLights {

    /** 合成灯上限:SSBO 上限 8 − 自身 2 盏 = 6。 */
    public static final int MAX_SYNTH = SpotlightBufferLayout.MAX_LIGHTS - 2;

    /** 旧几何:弧线半径(格)。 */
    private static final double ARC_RADIUS = 2.5;
    /** 旧几何:弧线张角(度,单侧)。 */
    private static final double ARC_SPAN_DEG = 55.0;
    /** 旧几何:瞄准/前移距离(格)。 */
    private static final double TARGET_DIST = 6.0;
    /** 远几何距离上限(格):与 remoteLightMaxDist 默认 48 对齐。 */
    public static final double MAX_GEO_DIST = 48.0;
    /** 强度倍率:与手持灯同档({@code toSpot(..., 0.9f)})。 */
    private static final float INTENSITY_MULT = 0.9f;

    /** T15/T16 新几何:固定 8 槽(嵌套)。槽位与 N 无关 ⇒ 前 N 个槽是 N+1 的子集。 */
    private static final double[] SLOT_FWD = {1.0, 1.0, 1.5, 1.5, 2.0, 2.0, 2.5, 2.5};
    private static final double[] SLOT_LAT = {-2.0, 2.0, -4.0, 4.0, -6.0, 6.0, -8.0, 8.0};
    private static final double[] SLOT_DOWN = {0.30, 0.30, 0.40, 0.40, 0.50, 0.50, 0.60, 0.60};
    /** ROI(光池)瞄准距离(格):与 T11/T12 的"眼前 6 格光池"同源。 */
    private static final double ROI_DIST = 6.0;

    /** 与着色器同源的常量(用于贡献自证,非渲染)。 */
    private static final float BEAM_NORM = 0.4f;        // #define TACLIGHT_BEAM_NORM 0.4
    private static final float ATTEN_K_DEFAULT = 20.0f; // #define TACLIGHT_ATTEN_K 20.0
    private static final float GATE_EPS = 1.0e-4f;      // composite1.fsh:142

    private static volatile int count = 0;              // 0 = 关,重启清零
    private static volatile double geoDist = 0.0;       // 0 = 旧近几何(d>0 = 旧远几何)
    private static volatile boolean slotsMode = false;  // true = 固定 8 槽嵌套几何(T15)
    private static volatile boolean pendingEcho = false;

    private SynthLights() {}

    public static int count() {
        return count;
    }

    public static double geoDist() {
        return geoDist;
    }

    public static boolean slotsMode() {
        return slotsMode;
    }

    /**
     * relay 入口。用法 {@code !synth <0..6> [dist 0..48] [mode=arc|slots]};无参/status = 回显。
     * 默认(mode 缺省) = arc,且 dist 缺省 0 ⇒ 与 T11/T12 行为逐字节一致。
     */
    public static String configure(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            pendingEcho = count > 0;
            return "synth=" + count + " dist=" + geoDist + " mode=" + modeName() + " (0.." + MAX_SYNTH
                    + ", 0=off; total ssbo count = 2+" + count + " = " + (2 + count)
                    + " when both self lamps are on)";
        }
        String[] tok = arg.trim().split("\\s+");
        int n = -1;
        double d = 0.0;
        boolean slots = false;
        for (int i = 0; i < tok.length; i++) {
            String s = tok[i];
            try {
                if (s.startsWith("dist=")) {
                    d = Double.parseDouble(s.substring(5));
                } else if (s.startsWith("mode=")) {
                    String m = s.substring(5);
                    if (!m.equals("arc") && !m.equals("slots")) return "mode must be arc|slots, got " + m;
                    slots = m.equals("slots");
                } else if (n < 0) {
                    n = Integer.parseInt(s);
                } else {
                    d = Double.parseDouble(s);              // 兼容旧位置参数:!synth <N> <dist>
                }
            } catch (NumberFormatException e) {
                return "bad arg '" + s + "' (want <0.." + MAX_SYNTH + "> [dist 0.." + (int) MAX_GEO_DIST + "] [mode=arc|slots])";
            }
        }
        if (n < 0) return "usage: !synth <0.." + MAX_SYNTH + "> [dist 0.." + (int) MAX_GEO_DIST + "] [mode=arc|slots]";
        if (n > MAX_SYNTH) return "range 0.." + MAX_SYNTH + ", got " + n;
        if (d < 0.0 || d > MAX_GEO_DIST) return "dist range 0.." + (int) MAX_GEO_DIST + ", got " + d;
        count = n;
        geoDist = d;
        slotsMode = slots;
        pendingEcho = true;
        return "synth=" + n + " dist=" + d + " mode=" + modeName() + " (total ssbo count = 2+" + n + " = " + (2 + n)
                + " when both self lamps are on; !synth 0 resets)";
    }

    private static String modeName() {
        return slotsMode ? "slots(8-slot nested)" : (geoDist > 0 ? "arc-far" : "arc-legacy");
    }

    /** 追加合成灯。默认(count==0)原样返回入参,保证"默认关 = 逐字节一致"。 */
    public static List<SpotlightData> append(List<SpotlightData> in) {
        final int n = count;
        if (n <= 0) { if (pendingEcho) { pendingEcho = false; echo(List.of(), null, null); } return in; }   // 默认关:零副作用
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return in;

        Vec3 eye = mc.player.getEyePosition(mc.getPartialTick());
        Vec3 look = mc.player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 1.0e-6) flat = new Vec3(0.0, 0.0, -1.0);
        flat = flat.normalize();
        Vec3 right = new Vec3(-flat.z, 0.0, flat.x);
        final double gd = geoDist;
        Vec3 center = (!slotsMode && gd > 0.0) ? eye.add(flat.scale(gd)) : eye;
        Vec3 roi = eye.add(flat.scale(ROI_DIST));
        Vec3 arcTarget = eye.add(flat.scale(TARGET_DIST));

        // 与手持灯完全同源的参数快照(同 config、同 LightLevelOverride/!lv 覆盖)
        ClientSpotlightUploader.LightParams p = new ClientSpotlightUploader.LightParams(
                TacLightConfig.RADIUS.get().floatValue(),
                (float) TacLightConfig.RADIUS_MAX,
                LightLevelOverride.intensityFor(TacLightConfig.INTENSITY.get().floatValue()),
                TacLightConfig.cosDeg(TacLightConfig.CONE_OUTER_DEG.get()),
                TacLightConfig.cosDeg(TacLightConfig.CONE_INNER_DEG.get()),
                TacLightConfig.BEAM_DENSITY.get().floatValue());

        List<SpotlightData> out = new ArrayList<>(in);   // 入参可能是 List.of()(不可变),绝不原地改
        final int before = out.size();
        for (int i = 0; i < n && out.size() < SpotlightBufferLayout.MAX_LIGHTS; i++) {
            Vec3 pos;
            Vec3 aim;
            if (slotsMode) {
                // 固定槽位(与 N 无关):只启用前 N 个 ⇒ 嵌套子集
                int s = i % SLOT_FWD.length;
                pos = eye.add(right.scale(SLOT_LAT[s])).add(flat.scale(SLOT_FWD[s])).add(0.0, -SLOT_DOWN[s], 0.0);
                aim = roi.add(right.scale(SLOT_LAT[s] * 0.10));    // 略收敛,光束横跨视锥落在光池
            } else {
                double t = (n == 1) ? 0.5 : (double) i / (double) (n - 1);
                double a = Math.toRadians(-ARC_SPAN_DEG + 2.0 * ARC_SPAN_DEG * t);
                pos = center
                        .add(right.scale(ARC_RADIUS * Math.sin(a)))
                        .add(flat.scale(ARC_RADIUS * Math.cos(a)))
                        .add(0.0, -0.14, 0.0);
                aim = (gd > 0.0) ? pos.add(flat.scale(TARGET_DIST)) : arcTarget;
            }
            Vec3 dir = aim.subtract(pos);
            if (dir.lengthSqr() < 1.0e-6) dir = flat;
            out.add(ClientSpotlightUploader.buildSpotBeam(
                    pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, p, INTENSITY_MULT));
        }
        if (pendingEcho) {
            pendingEcho = false;
            echo(out.subList(before, out.size()), eye, roi);
        }
        return out;
    }

    /** 逐灯参数 + 贡献自证(!diag 只打 L0,故夹具自证)。 */
    private static void echo(List<SpotlightData> built, Vec3 eye, Vec3 roi) {
        org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
        final float kOverride = LightTuneOverride.attenK();     // = SSBO cone.z(与 shader 同源)
        log.info("[TacLight] SYNTH echo: n={} dist={} mode={} appended={} (each line = pos/dir/radius/cone/sideFloor/density/beam/type/intensity + cheap-gate self-proof)",
                count, geoDist, modeName(), built.size());
        for (int i = 0; i < built.size(); i++) {
            SpotlightData l = built.get(i);
            Vec3 lp = new Vec3(l.posX(), l.posY(), l.posZ());
            Vec3 ld = new Vec3(l.dirX(), l.dirY(), l.dirZ());
            if (ld.lengthSqr() > 1.0e-9) ld = ld.normalize();
            String proof = "n/a";
            if (eye != null && roi != null) {
                Vec3 toL = lp.subtract(roi);
                double d2roi = toL.length();
                double cosAng = d2roi > 1.0e-6 ? (toL.scale(-1.0 / d2roi)).dot(ld) : 0.0;
                float spot = smoothstep(l.cosOuter(), l.cosInner(), (float) cosAng);
                Vec3 rd = roi.subtract(eye);                    // 相机 → ROI 的射线方向
                if (rd.lengthSqr() > 1.0e-9) rd = rd.normalize();
                double cosT = d2roi > 1.0e-6 ? rd.dot(toL.scale(-1.0 / d2roi)) : 0.0;
                float ph = BEAM_NORM * (float) (l.sideFloor() + (1.0 - l.sideFloor()) * (1.0 - cosT * cosT));
                float atten = attenuation((float) Math.max(d2roi, 0.75), l.radius(), kOverride);
                float term = spot * atten * ph;
                boolean inside = false;
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.level != null) {
                        BlockPos bp = BlockPos.containing(lp.x, lp.y, lp.z);
                        inside = mc.level.getBlockState(bp).canOcclude();
                    }
                } catch (Throwable ignored) { }
                proof = String.format(java.util.Locale.ROOT,
                        "d2eye=%.2f d2roi=%.2f cosAng=%.3f inCone=%s spot=%.3f atten=%.4f ph=%.4f term=%.3e gatePass=%s insideSolid=%s radius=%.1f",
                        lp.distanceTo(eye), d2roi, cosAng, (cosAng >= l.cosOuter()),
                        spot, atten, ph, term, (term >= GATE_EPS), inside, l.radius());
            }
            log.info("[TacLight] SYNTH L{} slot={} pos=({},{},{}) dir=({},{},{}) cone=(outer={},inner={}) sideFloor={} density={} beam={} type={} intensity={} | {}",
                    i, (slotsMode ? (i % SLOT_FWD.length) : -1),
                    l.posX(), l.posY(), l.posZ(), l.dirX(), l.dirY(), l.dirZ(),
                    l.cosOuter(), l.cosInner(), l.sideFloor(), l.density(), l.beam(),
                    l.type(), l.intensity(), proof);
        }
    }

    /** 与 {@code taclight_core.glsl:166-172} 同式(贡献自证用,不参与渲染)。 */
    private static float attenuation(float dist, float radius, float kOverride) {
        float kk = kOverride > 0.0f ? kOverride : ATTEN_K_DEFAULT;
        float k = kk / Math.max(radius * radius, 1.0e-4f);
        float tail = 1.0f / (1.0f + k * radius * radius);
        float e = 1.0f / (1.0f + k * dist * dist) - tail;
        return Math.max(e, 0.0f) / (1.0f - tail);
    }

    /** 与 GLSL smoothstep 同式。 */
    private static float smoothstep(float e0, float e1, float x) {
        if (e1 <= e0) return x >= e1 ? 1.0f : 0.0f;
        float t = Math.max(0.0f, Math.min(1.0f, (x - e0) / (e1 - e0)));
        return t * t * (3.0f - 2.0f * t);
    }
}

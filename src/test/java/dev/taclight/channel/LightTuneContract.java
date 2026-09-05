package dev.taclight.channel;

import dev.taclight.channel.ClientSpotlightUploader.LightParams;

/**
 * 手电五旋钮覆盖层契约(2026-09-04 三旋钮,2026-09-05 加 !knee/!beam):
 * {@code !bright} 直接亮度(绝对强度) / {@code !dist} 绝对照距 /
 * {@code !atten} 衰减系数 K(经 SSBO cone.z 逐灯透传) /
 * {@code !knee} 近场软肩 G(经 SSBO cone.w 逐灯透传,0=恒等直通) /
 * {@code !beam} 体积光密度(经 SSBO vlParams.y 直接换值,0=完全关光束,off=回 config 默认) /
 * {@code !beamonly} 只看光束(头部 flags bit2,跳过 M1 表面照明,composite1 体积束照常)。
 * 默认全关 = 零行为变化;越界/非法拒绝不污染;收尾全关(零残留)。
 */
public class LightTuneContract {
    public static void main(String[] args) {
        LightTuneOverride.configureBright("off");
        LightTuneOverride.configureDist("off");
        LightTuneOverride.configureAtten("off");
        LightTuneOverride.configureKnee("off");
        LightTuneOverride.configureBeam("off");
        LightTuneOverride.configureBeamonly("off");

        // ---- 默认:各路直通 ----
        check(Math.abs(LightTuneOverride.brightnessFor(6.0f) - 6.0f) < 1e-6, "默认亮度直通");
        check(Math.abs(LightTuneOverride.radiusFor(36.0f, 96.0f) - 36.0f) < 1e-6, "默认半径直通");
        check(LightTuneOverride.attenK() == 0.0f, "默认 attenK=0(GLSL 回退编译期默认)");

        // ---- bright:绝对强度 ----
        String b12 = LightTuneOverride.configureBright("12");
        check(b12.contains("12"), "bright 12 回显: " + b12);
        check(Math.abs(LightTuneOverride.brightnessFor(6.0f) - 12.0f) < 1e-6, "bright 覆盖生效(与基值无关)");
        String bBad = LightTuneOverride.configureBright("99");
        check(bBad.startsWith("range"), "bright 越界拒绝: " + bBad);
        check(Math.abs(LightTuneOverride.brightnessFor(6.0f) - 12.0f) < 1e-6, "越界不污染当前值");
        String bNaN = LightTuneOverride.configureBright("abc");
        check(bNaN.startsWith("bad arg"), "bright 非法输入拒绝: " + bNaN);
        String bSt = LightTuneOverride.configureBright("status");
        check(bSt.contains("12"), "bright status 回显当前值: " + bSt);

        // ---- dist:绝对照距 ----
        String d24 = LightTuneOverride.configureDist("24");
        check(d24.contains("24"), "dist 24 回显: " + d24);
        check(Math.abs(LightTuneOverride.radiusFor(50.9f, 96.0f) - 24.0f) < 1e-6, "dist 覆盖生效( bypass √耦合)");
        String dBad = LightTuneOverride.configureDist("200");
        check(dBad.startsWith("range"), "dist 越界拒绝: " + dBad);
        String dNaN = LightTuneOverride.configureDist("far");
        check(dNaN.startsWith("bad arg"), "dist 非法输入拒绝: " + dNaN);

        // ---- atten:逐灯 K ----
        String a1 = LightTuneOverride.configureAtten("1.0");
        check(a1.contains("1.0"), "atten 1.0 回显: " + a1);
        check(Math.abs(LightTuneOverride.attenK() - 1.0f) < 1e-6, "atten 覆盖生效");
        String aBad = LightTuneOverride.configureAtten("0.01");
        check(aBad.startsWith("range"), "atten 越界拒绝: " + aBad);
        check(Math.abs(LightTuneOverride.attenK() - 1.0f) < 1e-6, "越界不污染当前值");

        // ---- knee:逐灯近场软肩 G(2026-09-05 第四旋钮,SSBO cone.w 透传) ----
        // 0 = cone.w 留 0,GLSL 恒等直通(表面路径今日无 knee,零行为变化)。
        check(LightTuneOverride.kneeG() == 0.0f, "默认 kneeG=0(GLSL 恒等直通,零行为变化)");
        String k1 = LightTuneOverride.configureKnee("3.0");
        check(k1.contains("3.0"), "knee 3.0 回显: " + k1);
        check(Math.abs(LightTuneOverride.kneeG() - 3.0f) < 1e-6, "knee 覆盖生效");
        String kBad = LightTuneOverride.configureKnee("20");
        check(kBad.startsWith("range"), "knee 越界拒绝: " + kBad);
        check(Math.abs(LightTuneOverride.kneeG() - 3.0f) < 1e-6, "越界不污染当前值");
        String kNaN = LightTuneOverride.configureKnee("soft");
        check(kNaN.startsWith("bad arg"), "knee 非法输入拒绝: " + kNaN);

        // ---- beam:体积光密度(2026-09-05 第五旋钮,SSBO vlParams.y 直接换值) ----
        // 0=完全关光束(A/B 开关对比);off=回 config 默认 0.05。密度是消费值本身,
        // 与 atten/knee 的 0 哨兵语义不同:显式 0 就是关,不回退。
        check(Math.abs(LightTuneOverride.beamDensityOr(0.05f) - 0.05f) < 1e-6, "默认 beamDensity 直通(config 值)");
        String bm0 = LightTuneOverride.configureBeam("0");
        check(bm0.contains("beam=0.0"), "beam 0 回显: " + bm0);
        check(LightTuneOverride.beamDensityOr(0.05f) == 0.0f, "beam 0 = 完全关闭光束(显式零≠回默认)");
        String bm35 = LightTuneOverride.configureBeam("0.35");
        check(bm35.contains("0.35"), "beam 0.35 回显: " + bm35);
        check(Math.abs(LightTuneOverride.beamDensityOr(0.05f) - 0.35f) < 1e-6, "beam 覆盖生效(与 config 值无关)");
        String bmHi = LightTuneOverride.configureBeam("2.0");
        check(bmHi.startsWith("range"), "beam 越界拒绝(>1): " + bmHi);
        check(Math.abs(LightTuneOverride.beamDensityOr(0.05f) - 0.35f) < 1e-6, "越界不污染当前值");
        String bmNeg = LightTuneOverride.configureBeam("-0.1");
        check(bmNeg.startsWith("range"), "beam 越界拒绝(<0): " + bmNeg);
        String bmNaN = LightTuneOverride.configureBeam("fog");
        check(bmNaN.startsWith("bad arg"), "beam 非法输入拒绝: " + bmNaN);

        // ---- beamonly:只看光束(2026-09-05,头部 flags bit2 → GLSL 跳过 M1 表面照明) ----
        check(!LightTuneOverride.beamOnly(), "默认 beamonly=false(零行为变化)");
        String bo1 = LightTuneOverride.configureBeamonly("on");
        check(bo1.contains("on"), "beamonly on 回显: " + bo1);
        check(LightTuneOverride.beamOnly(), "beamonly on 生效");
        String boSt = LightTuneOverride.configureBeamonly("status");
        check(boSt.contains("on"), "beamonly status 回显当前态: " + boSt);
        String bo0 = LightTuneOverride.configureBeamonly("off");
        check(bo0.contains("off"), "beamonly off 回显: " + bo0);
        check(!LightTuneOverride.beamOnly(), "beamonly off 生效");
        String boBad = LightTuneOverride.configureBeamonly("yes");
        check(boBad.startsWith("bad arg"), "beamonly 非法输入拒绝: " + boBad);
        check(!LightTuneOverride.beamOnly(), "非法输入不污染当前态");
        // bit2 空闲无冲突(布局常量守卫:动 flags 位必须先过这关)
        check(dev.taclight.channel.SpotlightBufferLayout.FLAG_BEAM_ONLY == 4
                        && (SpotlightBufferLayout.FLAG_BEAM_ONLY
                            & (SpotlightBufferLayout.FLAG_HAS_DATA
                               | SpotlightBufferLayout.FLAG_DEBUG
                               | SpotlightBufferLayout.FLAG_TIMING_PROBE)) == 0,
                "FLAG_BEAM_ONLY=bit2(4)与既有 flags 无冲突");
        try {
            String core = java.nio.file.Files.readString(
                    java.nio.file.Path.of("pack/shaders/lib/taclight_core.glsl"));
            check(core.contains("#define TACLIGHT_FLAG_BEAM_ONLY"), "GLSL:taclight_core 定义 BEAM_ONLY 位");
            String comp = java.nio.file.Files.readString(
                    java.nio.file.Path.of("pack/shaders/composite.fsh"));
            check(comp.contains("(flags & TACLIGHT_FLAG_BEAM_ONLY) == 0u"),
                    "GLSL:composite M1 分支有 beamonly 门(跳过表面照明)");
        } catch (Exception e) {
            throw new AssertionError("FAIL GLSL 源读取: " + e);
        }

        // ---- buildSpotBeam 集成 ----
        LightParams p = new LightParams(36.0f, 96.0f, 6.0f, 0.848f, 0.951f, 0.05f);
        SpotlightData tuned = ClientSpotlightUploader.buildSpotBeam(
                0, 0, 0, 0, 0, -1, p, 1.0f);
        check(Math.abs(tuned.intensity() - 12.0f) < 1e-4, "集成:强度=bright 绝对值 12");
        check(Math.abs(tuned.radius() - 24.0f) < 1e-4, "集成:半径=dist 绝对值 24(不跟亮度走)");
        check(Math.abs(tuned.coneReservedZ() - 1.0f) < 1e-6, "集成:cone.z=atten K 1.0 透传");
        check(Math.abs(tuned.coneReservedW() - 3.0f) < 1e-6, "集成:cone.w=knee G 3.0 透传");
        check(Math.abs(tuned.density() - 0.35f) < 1e-4, "集成:vlParams.y=beam 密度 0.35 覆盖(config 0.05 被换)");
        java.nio.ByteBuffer buf = SpotlightBufferLayout.newBuffer(1);
        SpotlightBufferLayout.writeLight(buf, 0, tuned);
        SpotlightData read = SpotlightBufferLayout.readLight(buf, 0);
        check(Math.abs(read.coneReservedZ() - 1.0f) < 1e-6, "cone.z 经 SSBO 读写回环");
        check(Math.abs(read.coneReservedW() - 3.0f) < 1e-6, "cone.w 经 SSBO 读写回环");

        // ---- 默认灯 cone.z=0(GLSL 回退) ----
        SpotlightData plain = SpotlightData.spot(1f, 2f, 3f, 24f,
                1f, 0.96f, 0.88f, 6f, 0f, 0f, -1f, 0.848f, 0.951f);
        check(plain.coneReservedZ() == 0.0f && plain.coneReservedW() == 0.0f, "spot() 默认 cone.z/w=0(保留槽)");

        // ---- 收尾全关(零残留) ----
        LightTuneOverride.configureBright("off");
        LightTuneOverride.configureDist("off");
        LightTuneOverride.configureAtten("off");
        LightTuneOverride.configureKnee("off");
        LightTuneOverride.configureBeam("off");
        LightTuneOverride.configureBeamonly("off");
        SpotlightData clean = ClientSpotlightUploader.buildSpotBeam(
                0, 0, 0, 0, 0, -1, p, 1.0f);
        check(Math.abs(clean.intensity() - 6.0f) < 1e-4
                        && Math.abs(clean.radius() - 36.0f) < 1e-4
                        && clean.coneReservedZ() == 0.0f
                        && clean.coneReservedW() == 0.0f,
                "收尾关闭:强度/半径/atten/knee 全回默认(零残留)");
        check(Math.abs(clean.density() - 0.05f) < 1e-4, "收尾关闭:density 回 config 默认 0.05");

        System.out.println("LightTuneContract: ALL PASS (51 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

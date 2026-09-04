package dev.taclight.channel;

import dev.taclight.channel.ClientSpotlightUploader.LightParams;

/**
 * 手电三旋钮覆盖层契约(2026-09-04,用户体感调参):
 * {@code !bright} 直接亮度(绝对强度) / {@code !dist} 绝对照距 /
 * {@code !atten} 衰减系数 K(经 SSBO cone.z 逐灯透传)。
 * 默认全关 = 零行为变化;越界/非法拒绝不污染;收尾全关(零残留)。
 */
public class LightTuneContract {
    public static void main(String[] args) {
        LightTuneOverride.configureBright("off");
        LightTuneOverride.configureDist("off");
        LightTuneOverride.configureAtten("off");

        // ---- 默认:三路直通 ----
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

        // ---- buildSpotBeam 集成 ----
        LightParams p = new LightParams(36.0f, 96.0f, 6.0f, 0.848f, 0.951f, 0.05f);
        SpotlightData tuned = ClientSpotlightUploader.buildSpotBeam(
                0, 0, 0, 0, 0, -1, p, 1.0f);
        check(Math.abs(tuned.intensity() - 12.0f) < 1e-4, "集成:强度=bright 绝对值 12");
        check(Math.abs(tuned.radius() - 24.0f) < 1e-4, "集成:半径=dist 绝对值 24(不跟亮度走)");
        check(Math.abs(tuned.coneReservedZ() - 1.0f) < 1e-6, "集成:cone.z=atten K 1.0 透传");
        java.nio.ByteBuffer buf = SpotlightBufferLayout.newBuffer(1);
        SpotlightBufferLayout.writeLight(buf, 0, tuned);
        SpotlightData read = SpotlightBufferLayout.readLight(buf, 0);
        check(Math.abs(read.coneReservedZ() - 1.0f) < 1e-6, "cone.z 经 SSBO 读写回环");

        // ---- 默认灯 cone.z=0(GLSL 回退) ----
        SpotlightData plain = SpotlightData.spot(1f, 2f, 3f, 24f,
                1f, 0.96f, 0.88f, 6f, 0f, 0f, -1f, 0.848f, 0.951f);
        check(plain.coneReservedZ() == 0.0f && plain.coneReservedW() == 0.0f, "spot() 默认 cone.z/w=0(保留槽)");

        // ---- 收尾全关(零残留) ----
        LightTuneOverride.configureBright("off");
        LightTuneOverride.configureDist("off");
        LightTuneOverride.configureAtten("off");
        SpotlightData clean = ClientSpotlightUploader.buildSpotBeam(
                0, 0, 0, 0, 0, -1, p, 1.0f);
        check(Math.abs(clean.intensity() - 6.0f) < 1e-4
                        && Math.abs(clean.radius() - 36.0f) < 1e-4
                        && clean.coneReservedZ() == 0.0f,
                "收尾关闭:强度/半径/atten 全回默认(零残留)");

        System.out.println("LightTuneContract: ALL PASS (22 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

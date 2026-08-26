package dev.taclight.channel;

import dev.taclight.channel.ClientSpotlightUploader.LightParams;

/**
 * 上传侧语义契约(doc06 §8 审批记录 + §8.7):
 * 1) 坐标语义:上传必须 world 直传——输出灯位 x/y/z == 输入 world 值,
 *    调用链不允许出现"减相机眼位"(v0.8.4 漂移事故红线);
 * 2) 亮度-距离耦合:有效半径 = radius × √(intensity/6.0),钳制 ≤ radiusMax
 *    (用户需求"亮度与距离正相关";挡位设计 = 只改亮度,照距自动 √ 缩放)。
 */
public class UploaderSemanticContract {
    public static void main(String[] args) {
        LightParams p = new LightParams(
                56.0f,   // radius(基准半径 @ 亮度6)
                96.0f,   // radiusMax
                6.0f,    // intensity(= 参考亮度)
                0.848f,  // cosOuter
                0.951f,  // cosInner
                0.35f);  // beamDensity

        // ---- 1) world 直传 ----
        double wx = 1024.75, wy = -33.25, wz = 512.125;
        double dx = 0.1, dy = 0.2, dz = 0.97;
        SpotlightData light = ClientSpotlightUploader.buildSpotBeam(wx, wy, wz, dx, dy, dz, p, 1.0f);
        check(Float.compare(light.posX(), (float) wx) == 0, "posX == world x(无眼位减法)");
        check(Float.compare(light.posY(), (float) wy) == 0, "posY == world y");
        check(Float.compare(light.posZ(), (float) wz) == 0, "posZ == world z");
        check(light.type() == 1.0f, "type=1 spot");
        check(Float.compare(light.cosOuter(), 0.848f) == 0 && Float.compare(light.cosInner(), 0.951f) == 0, "内外锥 cos 直传");
        check(Math.abs(Math.sqrt((double) light.dirX() * light.dirX()
                + (double) light.dirY() * light.dirY()
                + (double) light.dirZ() * light.dirZ()) - 1.0) < 1e-4, "dir 归一化(spot() 内部行为)");

        // ---- 2) 亮度-距离 √ 耦合 ----
        check(Math.abs(light.radius() - 56.0f) < 0.01, "参考亮度=基准半径 56(√1)");
        check(Float.compare(light.intensity(), 6.0f) == 0, "intensity 直传");

        SpotlightData x2 = ClientSpotlightUploader.buildSpotBeam(wx, wy, wz, dx, dy, dz, p, 2.0f);
        check(Math.abs(x2.radius() - 56.0f * (float) Math.sqrt(2.0)) < 0.01, "亮度×2 → 半径×√2 ≈ 79.20");
        check(Float.compare(x2.intensity(), 12.0f) == 0, "intensity ×2 = 12");

        LightParams hot = new LightParams(56.0f, 96.0f, 30.0f, 0.848f, 0.951f, 0.35f);
        SpotlightData clamped = ClientSpotlightUploader.buildSpotBeam(wx, wy, wz, dx, dy, dz, hot, 3.0f);
        check(Float.compare(clamped.radius(), 96.0f) == 0, "半径钳制 ≤ radiusMax(90亮度→√15×56→钳96)");

        SpotlightData dim = ClientSpotlightUploader.buildSpotBeam(wx, wy, wz, dx, dy, dz, p, 0.05f);
        check(Math.abs(dim.radius() - 56.0f * (float) Math.sqrt(0.05)) < 0.01, "亮度×0.05 → 半径×√0.05 ≈ 12.52(暗光短照)");

        // ---- 3) 纯函数可复现 ----
        SpotlightData again = ClientSpotlightUploader.buildSpotBeam(wx, wy, wz, dx, dy, dz, p, 2.0f);
        check(Float.compare(again.posX(), x2.posX()) == 0
                && Float.compare(again.radius(), x2.radius()) == 0
                && Float.compare(again.intensity(), x2.intensity()) == 0, "重复构造逐位一致");

        System.out.println("UploaderSemanticContract: ALL PASS (12 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

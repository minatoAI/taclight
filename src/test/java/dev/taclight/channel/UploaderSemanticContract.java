package dev.taclight.channel;

import dev.taclight.channel.ClientSpotlightUploader.LightParams;
import net.minecraft.world.phys.Vec3;

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

        // ---- 4) 右手向量手性契约(2026-08-29 实机 bug:right() 手性反了,灯锚在左手侧,
        //         锥轴投影整体偏左 = "辉光团与门洞亮斑分离"根因)----
        // Minecraft 坐标系:+X 东 +Z 南 +Y 上;朝北看 look=(0,0,-1) 时右手在东 (+X)。
        Vec3 north = ClientSpotlightUploader.rightVector(new Vec3(0, 0, -1));
        check(Math.abs(north.x - 1) < 1e-6 && Math.abs(north.y) < 1e-6 && Math.abs(north.z) < 1e-6,
                "朝北看右手=东 (+1,0,0)");
        Vec3 east = ClientSpotlightUploader.rightVector(new Vec3(1, 0, 0));
        check(Math.abs(east.x) < 1e-6 && Math.abs(east.y) < 1e-6 && Math.abs(east.z - 1) < 1e-6,
                "朝东看右手=南 (0,0,+1)");
        Vec3 south = ClientSpotlightUploader.rightVector(new Vec3(0, 0, 1));
        check(Math.abs(south.x + 1) < 1e-6 && Math.abs(south.y) < 1e-6 && Math.abs(south.z) < 1e-6,
                "朝南看右手=西 (-1,0,0)");
        Vec3 west = ClientSpotlightUploader.rightVector(new Vec3(-1, 0, 0));
        check(Math.abs(west.x) < 1e-6 && Math.abs(west.y) < 1e-6 && Math.abs(west.z + 1) < 1e-6,
                "朝西看右手=北 (0,0,-1)");
        Vec3 diagLook = new Vec3(1, 0, 1).normalize();
        Vec3 diagRight = ClientSpotlightUploader.rightVector(diagLook);
        check(Math.abs(diagRight.dot(diagLook)) < 1e-6 && Math.abs(diagRight.length() - 1.0) < 1e-6,
                "斜视向:右手垂直于视线且归一化");
        check(Math.abs(diagRight.x + diagLook.z) < 1e-6 && Math.abs(diagRight.z - diagLook.x) < 1e-6,
                "斜视向:right = look×up = (-lz,0,lx)");

        // ---- 5) 手持灯锚点偏移契约:偏移必须落在视线右手侧 ----
        // 设计常量(V3-p1):前 0.35 / 右 0.22 / 下 0.14;枪灯回退:前 0.55 / 右 0.18 / 下 0.10。
        Vec3 handOff = ClientSpotlightUploader.handheldOffset(new Vec3(0, 0, -1));
        Vec3 handRight = ClientSpotlightUploader.rightVector(new Vec3(0, 0, -1));
        check(Math.abs(handOff.dot(new Vec3(0, 0, -1)) - 0.35) < 1e-6, "手持偏移沿视线分量 = +0.35");
        check(Math.abs(handOff.dot(handRight) - 0.22) < 1e-6, "手持偏移沿右手分量 = +0.22(右手侧!)");

        Vec3 handOffE = ClientSpotlightUploader.handheldOffset(new Vec3(1, 0, 0));
        check(Math.abs(handOffE.z - 0.22) < 1e-6, "朝东看手持偏移向南(z=+0.22,右手侧)");
        check(Math.abs(handOffE.y + 0.14) < 1e-6, "手持偏移垂直分量 = -0.14");

        Vec3 gunOff = ClientSpotlightUploader.gunFallbackOffset(new Vec3(0, 0, -1));
        check(Math.abs(gunOff.dot(new Vec3(0, 0, -1)) - 0.55) < 1e-6, "枪灯回退偏移沿视线分量 = +0.55");
        check(Math.abs(gunOff.dot(handRight) - 0.18) < 1e-6, "枪灯回退偏移沿右手分量 = +0.18(右手侧!)");
        check(Math.abs(gunOff.y + 0.10) < 1e-6, "枪灯回退偏移垂直分量 = -0.10");

        // ---- 6) F2 自体胶囊 cookie 契约(2026-08-30)----
        // 默认灯 cookie w=0 → GLSL 侧不启用豁免(向后兼容旧灯数据)。
        check(Float.compare(light.cookieA(), 0.0f) == 0, "默认灯 cookie w=0(自体胶囊未启用)");
        SpotlightData capped = light.withSelfCapsule(1.5f, -2.5f, 3.5f, 0.45f);
        check(Float.compare(capped.cookieR(), 1.5f) == 0
                && Float.compare(capped.cookieG(), -2.5f) == 0
                && Float.compare(capped.cookieB(), 3.5f) == 0
                && Float.compare(capped.cookieA(), 0.45f) == 0,
                "withSelfCapsule 写入 (灯→胶囊中心偏移.xyz, 半径)");
        check(Float.compare(capped.posX(), light.posX()) == 0
                && Float.compare(capped.radius(), light.radius()) == 0
                && Float.compare(capped.intensity(), light.intensity()) == 0,
                "withSelfCapsule 不改变布局其余字段(灯位/半径/亮度原样)");
        // 纯函数 selfCapped:胶囊中心 = 玩家眼位 -0.55y;cookie 偏移 = 中心 − 灯位。
        // light 的灯位 = (wx, wy, wz) = (1024.75, -33.25, 512.125);眼位 (9.4, 70.0, -3.3)
        // → 中心 (9.4, 69.45, -3.3) → 偏移 = 中心 − 灯位。
        Vec3 eyeAt = new Vec3(9.4, 70.0, -3.3);
        SpotlightData cappedAuto = ClientSpotlightUploader.selfCapped(light, eyeAt);
        check(Math.abs(cappedAuto.cookieR() - (9.4 - wx)) < 1e-3
                && Math.abs(cappedAuto.cookieG() - (69.45 - wy)) < 1e-3
                && Math.abs(cappedAuto.cookieB() - (-3.3 - wz)) < 1e-3,
                "selfCapped:cookie 偏移 = (眼位−0.55y) − 灯位");
        check(Math.abs(cappedAuto.cookieA() - ClientSpotlightUploader.SELF_CAP_RADIUS) < 1e-6
                && ClientSpotlightUploader.SELF_CAP_RADIUS > 0.0f,
                "selfCapped:cookie w = 常量 SELF_CAP_RADIUS(>0 启用)");
        check(Float.compare(cappedAuto.posX(), light.posX()) == 0
                && Float.compare(cappedAuto.intensity(), light.intensity()) == 0,
                "selfCapped 不改变布局其余字段");

        System.out.println("UploaderSemanticContract: ALL PASS (29 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

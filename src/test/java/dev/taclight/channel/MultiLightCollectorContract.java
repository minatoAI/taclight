package dev.taclight.channel;

import dev.taclight.channel.MultiLightCollector.Candidate;
import dev.taclight.channel.MultiLightCollector.Selected;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 多人灯收集契约(M5,纯函数语义):
 * 1) 距离剔除:> maxDist 丢弃(= maxDist 保留);无设备候选不占名额;
 * 2) 就近上限:按到相机距离升序取前 maxLights;等距保持输入序(稳定);
 * 3) 远程灯展开语义:手持 = 玩家眼位 + handheldOffset(look)(与本地第三人称分支
 *    同一条数学),world 直传,无相机减法;枪灯近似 = 眼位 + look×0.45。
 */
public class MultiLightCollectorContract {
    public static void main(String[] args) {
        Vec3 cam = new Vec3(0, 0, 0);

        // ---- 1) 距离剔除与边界 ----
        List<Selected> r1 = MultiLightCollector.select(List.of(
                new Candidate(0, 10, 0, 0, true, false)),   // 距离 10
                cam.x, cam.y, cam.z, 10.0, 8);
        check(r1.size() == 1 && r1.get(0).index() == 0, "恰好 = maxDist 保留(≤ 语义)");
        List<Selected> r2 = MultiLightCollector.select(List.of(
                new Candidate(0, 10.001, 0, 0, true, false)),
                cam.x, cam.y, cam.z, 10.0, 8);
        check(r2.isEmpty(), "> maxDist 丢弃");

        // ---- 2) 无设备候选不占名额 ----
        List<Selected> r3 = MultiLightCollector.select(List.of(
                new Candidate(0, 1, 0, 0, false, false),
                new Candidate(1, 5, 0, 0, true, false)),
                cam.x, cam.y, cam.z, 48.0, 8);
        check(r3.size() == 1 && r3.get(0).index() == 1, "无设备候选被过滤,不占就近名额");

        // ---- 3) 就近上限 + 稳定序 ----
        List<Selected> r4 = MultiLightCollector.select(List.of(
                new Candidate(0, 30, 0, 0, true, false),
                new Candidate(1, 5, 0, 0, true, true),
                new Candidate(2, 12, 0, 0, false, true),
                new Candidate(3, 20, 0, 0, true, false)),
                cam.x, cam.y, cam.z, 48.0, 2);
        check(r4.size() == 2, "上限=2 只留 2 个");
        check(r4.get(0).index() == 1 && r4.get(1).index() == 2, "按距离升序就近保留(5m,12m)");
        check(r4.get(0).handheld() && r4.get(0).gun(), "选中保留设备开关语义(handheld+gun)");

        List<Selected> r5 = MultiLightCollector.select(List.of(
                new Candidate(0, 8, 0, 0, true, false),
                new Candidate(1, 8, 0, 0, false, true)),
                cam.x, cam.y, cam.z, 48.0, 1);
        check(r5.size() == 1 && r5.get(0).index() == 0, "等距保持输入序(稳定,先入先留)");

        // ---- 4) 空输入/零上限 ----
        check(MultiLightCollector.select(List.of(), 0, 0, 0, 48.0, 8).isEmpty(), "空输入 → 空");
        check(MultiLightCollector.select(List.of(new Candidate(0, 1, 0, 0, true, false)),
                0, 0, 0, 48.0, 0).isEmpty(), "maxLights=0 → 空");

        // ---- 5) 远程灯展开语义(world 直传 + 第三人称同款锚定) ----
        ClientSpotlightUploader.LightParams p = new ClientSpotlightUploader.LightParams(
                56.0f, 96.0f, 6.0f, 0.848f, 0.951f, 0.05f);
        double wx = 200.5, wy = 70.0, wz = -300.25;
        Vec3 look = new Vec3(0, 0, -1);
        Vec3 remoteEye = new Vec3(wx, wy, wz);
        Vec3 expected = remoteEye.add(ClientSpotlightUploader.handheldOffset(look));
        // 与本地第三人称分支同一条调用:toSpot = buildSpotBeam(world 直传)
        var light = ClientSpotlightUploader.buildSpotBeam(
                expected.x, expected.y, expected.z, look.x, look.y, look.z, p, 0.9f);
        check(Float.compare(light.posX(), (float) expected.x) == 0
                && Float.compare(light.posY(), (float) expected.y) == 0
                && Float.compare(light.posZ(), (float) expected.z) == 0,
                "远程手持灯 = 玩家眼位 + handheldOffset(world 直传,无相机减法)");
        var gunLight = ClientSpotlightUploader.buildSpotBeam(
                remoteEye.add(look.scale(0.45)).x, remoteEye.add(look.scale(0.45)).y,
                remoteEye.add(look.scale(0.45)).z, look.x, look.y, look.z, p, 1.1f);
        check(Float.compare(gunLight.intensity(), 6.0f * 1.1f) == 0,
                "远程枪灯近似锚眼位+0.45look,强度=GUN_MULTIPLIER");

        System.out.println("MultiLightCollectorContract: ALL PASS (11 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

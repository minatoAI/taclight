package dev.taclight.channel;

import dev.taclight.client.ClientLightState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 每帧收集设备姿态并上传 SSBO(binding 7)。
 * 阶段一(V3-p1):光位=相机位置+偏移,方向=相机视线;
 * 枪口精确矩阵(BeamRenderer 捕获)列为 V4 增强。
 */
public final class ClientSpotlightUploader {
    private static final float RADIUS = 24.0f;
    private static final float INTENSITY = 6.0f;
    private static final float COS_OUTER = 0.848f;  // 约 32 度半角
    private static final float COS_INNER = 0.951f;  // 约 18 度半角
    private static final float R = 1.0f, G = 0.96f, B = 0.88f;

    private ClientSpotlightUploader() {}

    public static void onFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            LightBuffer.upload(List.of());
            return;
        }
        List<SpotlightData> lights = new ArrayList<>(2);
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.getPosition();
        org.joml.Vector3f lookJoml = cam.getLookVector();
        Vec3 look = new Vec3(lookJoml.x(), lookJoml.y(), lookJoml.z());

        if (ClientLightState.isOn()) {
            Vec3 p = eye.add(look.scale(0.35)).add(right(look).scale(0.22)).add(0, -0.14, 0);
            lights.add(toSpot(p, eye, look, 0.9f));
        }
        if (ClientLightState.gunLightOn()) {
            dev.taclight.pose.MuzzlePoseMath.Pose muzzle = dev.taclight.client.MuzzlePoseCapture.consumeFresh();
            if (muzzle != null && mc.options.getCameraType().isFirstPerson()) {
                // 视图空间 → 场景空间:相机旋转共轭
                org.joml.Quaternionf rotConj = new org.joml.Quaternionf(cam.rotation()).conjugate();
                org.joml.Vector3f off = new org.joml.Vector3f(muzzle.ox(), muzzle.oy(), muzzle.oz()).rotate(rotConj);
                org.joml.Vector3f fwd = new org.joml.Vector3f(muzzle.fx(), muzzle.fy(), muzzle.fz()).rotate(rotConj);
                Vec3 pos = eye.add(off.x(), off.y(), off.z());
                Vec3 dir = new Vec3(fwd.x(), fwd.y(), fwd.z());
                lights.add(toSpot(pos, eye, dir, 1.1f));
            } else {
                Vec3 p = eye.add(look.scale(0.55)).add(right(look).scale(0.18)).add(0, -0.10, 0);
                lights.add(toSpot(p, eye, look, 1.1f));
            }
        }
        LightBuffer.upload(lights);
    }

    /** ABI:posRadius.xyz = 场景相对坐标(world - cameraPosition);vlParams 开启体积束。 */
    private static SpotlightData toSpot(Vec3 worldPos, Vec3 eye, Vec3 dir, float mult) {
        Vec3 rel = worldPos.subtract(eye);
        return SpotlightData.spotBeam(
                (float) rel.x, (float) rel.y, (float) rel.z, RADIUS,
                R, G, B, INTENSITY * mult,
                (float) dir.x, (float) dir.y, (float) dir.z,
                COS_OUTER, COS_INNER, 0.05f, 1.0f);
    }

    private static Vec3 right(Vec3 look) {
        Vec3 r = new Vec3(look.z, 0.0, -look.x);
        double len = r.length();
        return len > 1e-6 ? r.scale(1.0 / len) : new Vec3(1.0, 0.0, 0.0);
    }
}

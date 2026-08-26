package dev.taclight.channel;

import dev.taclight.client.ClientLightState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 每渲染帧收集设备姿态并上传 SSBO(binding 7)。
 * 调用点 = RenderLevelStageEvent AFTER_LEVEL(ClientEvents)——渲染帧级刷新;
 * 严禁回迁 ClientTick:20Hz 上传会让灯位滞后相机最多 50ms,转视角拖拽可见
 * (v0.9.0 实机热修教训,社区同型问题共识 = 数据必须每帧更新)。
 * 阶段一(V3-p1):光位=相机位置+偏移,方向=相机视线;
 * 枪口精确矩阵(BeamRenderer 捕获)列为 V4 增强。
 *
 * <p>坐标语义(v0.9.0 起,doc06 §2.5 铁律 3):posRadius.xyz 上传
 * <b>world 坐标</b>,上传侧不做任何相机相对化;scene-relative 转换由光影包
 * 消费侧执行(pack/shaders/lib/taclight_common.glsl 的
 * taclight_world_to_scene / taclight_scene_to_view)。禁止在下面
 * {@link #buildSpotBeam} 调用链中重新引入相机减法——v0.8.4 的
 * scene-relative 方案已被审批替换(doc06 §8 审批记录·6.1)。</p>
 */
public final class ClientSpotlightUploader {
    private static final float R = 1.0f, G = 0.96f, B = 0.88f;

    /** 从 config 提取的可注入参数快照(每帧一次;契约测试可手工构造)。 */
    public static final class LightParams {
        /** 基准半径(@INTENSITY_REFERENCE 亮度下;实际半径按 √亮度 缩放)。 */
        public final float radius;
        /** 半径硬上限(配置 defineInRange 同源)。 */
        public final float radiusMax;
        public final float intensity;
        public final float cosOuter;
        public final float cosInner;
        public final float beamDensity;

        public LightParams(float radius, float radiusMax, float intensity, float cosOuter, float cosInner, float beamDensity) {
            this.radius = radius;
            this.radiusMax = radiusMax;
            this.intensity = intensity;
            this.cosOuter = cosOuter;
            this.cosInner = cosInner;
            this.beamDensity = beamDensity;
        }

        static LightParams load() {
            return new LightParams(
                    dev.taclight.config.TacLightConfig.RADIUS.get().floatValue(),
                    (float) dev.taclight.config.TacLightConfig.RADIUS_MAX,
                    dev.taclight.config.TacLightConfig.INTENSITY.get().floatValue(),
                    dev.taclight.config.TacLightConfig.cosDeg(dev.taclight.config.TacLightConfig.CONE_OUTER_DEG.get()),
                    dev.taclight.config.TacLightConfig.cosDeg(dev.taclight.config.TacLightConfig.CONE_INNER_DEG.get()),
                    dev.taclight.config.TacLightConfig.BEAM_DENSITY.get().floatValue());
        }
    }

    private ClientSpotlightUploader() {}

    public static void onFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            LightBuffer.upload(List.of());
            return;
        }
        LightParams cfg = LightParams.load();
        List<SpotlightData> lights = new ArrayList<>(2);
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.getPosition();
        org.joml.Vector3f lookJoml = cam.getLookVector();
        Vec3 look = new Vec3(lookJoml.x(), lookJoml.y(), lookJoml.z());

        if (ClientLightState.isOn()) {
            // 世界空间锚定(第三人称/他人视角需求,Handheld Moon 型效果的行为前提):
            // 第一人称锚相机(原行为,含 view-bob 手感);第三人称锚玩家眼睛 + 玩家视线,
            // 避免"灯浮在相机上"。光锥本体(体积光束)由 M3 composite1 raymarch 呈现。
            boolean fp = mc.options.getCameraType().isFirstPerson();
            Vec3 anchor = fp ? eye : mc.player.getEyePosition(mc.getPartialTick());
            Vec3 lookDir = fp ? look : mc.player.getLookAngle();
            Vec3 p = anchor.add(lookDir.scale(0.35)).add(right(lookDir).scale(0.22)).add(0, -0.14, 0);
            lights.add(toSpot(p, lookDir, cfg, 0.9f));
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
                lights.add(toSpot(pos, dir, cfg, dev.taclight.config.TacLightConfig.GUN_MULTIPLIER.get().floatValue()));
            } else {
                Vec3 p = eye.add(look.scale(0.55)).add(right(look).scale(0.18)).add(0, -0.10, 0);
                lights.add(toSpot(p, look, cfg, dev.taclight.config.TacLightConfig.GUN_MULTIPLIER.get().floatValue()));
            }
        }
        int extraFlags = ClientLightState.debugMode() ? SpotlightBufferLayout.FLAG_DEBUG : 0;
        LightBuffer.upload(lights, extraFlags);
        dumpDiagOnce();
    }

    /** 参考亮度:radius 配置语义的锚点;亮度-距离按反平方等照度律耦合(d ∝ √I,doc06 §8.7)。 */
    public static final float INTENSITY_REFERENCE = 6.0f;

    /** 纯数据装配:world 坐标原样直传给 SSBO(唯一合法性入口;无相机/GL/MC 类型,可离线测试)。
     *  亮度-距离耦合:有效半径 = radius × √(finalIntensity/6.0),钳制 ≤ radiusMax ——
     *  未来"挡位"只需改亮度,照距自动 √ 缩放(用户需求:亮度和距离正相关)。 */
    public static SpotlightData buildSpotBeam(double wx, double wy, double wz,
                                              double dx, double dy, double dz,
                                              LightParams p, float intensityMult) {
        float intensity = p.intensity * intensityMult;
        float radius = p.radius * (float) Math.sqrt(Math.max(intensity, 1e-3f) / INTENSITY_REFERENCE);
        radius = Math.min(radius, p.radiusMax);
        return SpotlightData.spotBeam(
                (float) wx, (float) wy, (float) wz, radius,
                R, G, B, intensity,
                (float) dx, (float) dy, (float) dz,
                p.cosOuter, p.cosInner, p.beamDensity, 1.0f);
    }

    /** ABI:posRadius.xyz = world 坐标(v0.9.0);GLSL 消费侧负责转 scene-relative。 */
    private static SpotlightData toSpot(Vec3 worldPos, Vec3 dir, LightParams cfg, float intensityMult) {
        return buildSpotBeam(worldPos.x, worldPos.y, worldPos.z,
                dir.x, dir.y, dir.z, cfg, intensityMult);
    }

    private static boolean diagDumped;

    /** 诊断:打印相机与灯参数(确认 Java 侧上传值)。 */
    private static void dumpDiagOnce() {
        if (diagDumped) return;
        diagDumped = true;
        if (!"1".equals(System.getenv("TACLIGHT_PROBE"))) return;
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        var l = cam.getLookVector();
        dev.taclight.TacLightMod.LOGGER.info("[TacLight] LIGHT0 eye=({},{},{}) dir=({},{},{}) r={} cos=({},{},{}) i={}",
            cam.getPosition().x, cam.getPosition().y, cam.getPosition().z,
            l.x(), l.y(), l.z(), radius(), cosOuter(), intensity(), intensity());
    }

    private static float radius() { return dev.taclight.config.TacLightConfig.RADIUS.get().floatValue(); }
    private static float cosOuter() { return dev.taclight.config.TacLightConfig.cosDeg(dev.taclight.config.TacLightConfig.CONE_OUTER_DEG.get()); }
    private static float intensity() { return dev.taclight.config.TacLightConfig.INTENSITY.get().floatValue(); }

    private static Vec3 right(Vec3 look) {
        Vec3 r = new Vec3(look.z, 0.0, -look.x);
        double len = r.length();
        return len > 1e-6 ? r.scale(1.0 / len) : new Vec3(1.0, 0.0, 0.0);
    }
}

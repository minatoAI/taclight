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
                    // 2026-09-03 真实感调参:!lv 覆盖层(无覆盖 = 原值,零行为变化)
                    LightLevelOverride.intensityFor(
                            dev.taclight.config.TacLightConfig.INTENSITY.get().floatValue()),
                    dev.taclight.config.TacLightConfig.cosDeg(dev.taclight.config.TacLightConfig.CONE_OUTER_DEG.get()),
                    dev.taclight.config.TacLightConfig.cosDeg(dev.taclight.config.TacLightConfig.CONE_INNER_DEG.get()),
                    dev.taclight.config.TacLightConfig.BEAM_DENSITY.get().floatValue());
        }
    }

    private ClientSpotlightUploader() {}

    public static void onFrame() {
        recFrame++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            MotionCapture.shutdown("world-unload");
            LightBuffer.upload(List.of());
            return;
        }
        LightParams cfg = LightParams.load();
        List<SpotlightData> lights = new ArrayList<>(2);
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.getPosition();
        org.joml.Vector3f lookJoml = cam.getLookVector();
        Vec3 look = new Vec3(lookJoml.x(), lookJoml.y(), lookJoml.z());

        // Freecam/旁观修正(旁观视角与多人调试方案.md §2):相机实体不是本地玩家时
        // (Freecam 分离相机)一律锚玩家,防止灯跟着观察相机跑。
        boolean fp = mc.options.getCameraType().isFirstPerson() && cam.getEntity() == mc.player;
        // F2 自体胶囊基准为玩家眼位；相机眼只表示本帧相机位置，不能当作 bob 信号。
        Vec3 playerEye = mc.player.getEyePosition(mc.getPartialTick());
        if (ClientLightState.isOn()) {
            // 世界空间锚定:手持灯锚取玩家眼位，视线仍取实际观察相机(所见即所照)；
            // 这保证第一/第三人称与 Freecam 的灯源归属语义一致。vanilla view-bob 位于
            // projection，不写 Java Camera.position；不能把本锚点规则解释成 bob 根治。
            Vec3 anchor = spotAnchor(eye, playerEye);
            Vec3 lookDir = fp ? look : mc.player.getLookAngle();
            SpotlightData hand = toSpot(anchor.add(handheldOffset(lookDir)), lookDir, cfg, 0.9f);
            lights.add(selfCapped(hand, playerEye));
        }
        if (ClientLightState.gunLightOn()) {
            dev.taclight.pose.MuzzlePoseMath.Pose muzzle = dev.taclight.client.MuzzlePoseCapture.consumeFresh();
            if (muzzle != null && fp) {
                // 枪渲染空间 → 世界(2026-09-02 坑60):捕获矩阵是 GL 视图空间(-Z 前),
                // Camera.rotation() 的 +Z 为前,先 Ry(180) 再相机旋转;旧共轭实现方向
                // 恒为 -look(实机 DIAG L0 y/z 双翻钉死)。
                org.joml.Vector3f off = dev.taclight.pose.MuzzlePoseMath.gunViewDirToWorld(
                        muzzle.ox(), muzzle.oy(), muzzle.oz(), cam.rotation());
                org.joml.Vector3f fwdW = dev.taclight.pose.MuzzlePoseMath.gunViewDirToWorld(
                        muzzle.fx(), muzzle.fy(), muzzle.fz(), cam.rotation());
                Vec3 pos = eye.add(off.x(), off.y(), off.z());
                Vec3 dir = new Vec3(fwdW.x(), fwdW.y(), fwdW.z());
                SpotlightData gun = toSpot(pos, dir, cfg, dev.taclight.config.TacLightConfig.GUN_MULTIPLIER.get().floatValue());
                lights.add(selfCapped(gun, playerEye));
            } else {
                // F4(2026-08-30):非第一人称(或枪口姿态未捕获)禁止锚相机——
                // TP 下灯浮在观察相机上(0830 R4.2"TP 枪灯 fallback 锚相机")。
                // 与手持灯同一规则(09-01):锚一律玩家眼位,视线 FP 用相机。
                Vec3 gAnchor = spotAnchor(eye, playerEye);
                Vec3 gLook = fp ? look : mc.player.getLookAngle();
                SpotlightData gun = toSpot(gAnchor.add(gunFallbackOffset(gLook)), gLook, cfg,
                        dev.taclight.config.TacLightConfig.GUN_MULTIPLIER.get().floatValue());
                lights.add(selfCapped(gun, playerEye));
            }
        }
        collectRemoteLights(mc, eye, cfg, lights);
        int extraFlags = ClientLightState.debugMode() ? SpotlightBufferLayout.FLAG_DEBUG : 0;
        // 体素遮挡栅格(09-01 深夜④ DDA):墙后漏光立项,与灯数据同缓冲上传;
        // 禁用/无灯 → null,GLSL 逐光线回退屏幕空间 SSO。
        var voxelGrid = dev.taclight.client.VoxelGrid.update(mc, lights);
        LightBuffer.upload(lights, extraFlags, voxelGrid);
        if (FrameRecorder.active()) {
            long t = System.nanoTime() / 1_000_000L;
            // C 行明确区分相机眼/玩家眼位，并记录 vanilla bobView 的真实驱动字段。
            FrameRecorder.append(FrameRecorder.cameraRow(t, recFrame,
                    eye.x, eye.y, eye.z, mc.player.getYRot(), mc.player.getXRot(),
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    playerEye.x, playerEye.y, playerEye.z,
                    mc.player.walkDist, mc.player.walkDistO, mc.player.bob, mc.player.oBob,
                    mc.options.bobView().get()));
            // L 行:每灯一次(顺序=SSBO 顺序,自身/枪/远程)
            for (int i = 0; i < lights.size() && i < 8; i++) {
                SpotlightData s = lights.get(i);
                FrameRecorder.append(FrameRecorder.lightRow(t, recFrame, i,
                        s.posX(), s.posY(), s.posZ(), s.dirX(), s.dirY(), s.dirZ(),
                        s.radius(), s.intensity(), s.cosOuter(), s.cosInner()));
            }
        }
        lookTraceTick(mc);
        dumpDiagOnce();
    }

    /** Monotonic render-hook frame number used by all C/L/R/P/S/F/D rows. */
    private static long recFrame;

    public static long currentRenderFrame() { return recFrame; }

    /** 消融探针胶水(09-01):!looktrace 激活时逐帧记录最近非自身 LivingEntity 的角度链路;
     *  09-01 晚兼作 MotionCapture 门控输入(布防时每帧喂位姿,会话开/关联动 LookTrace 门控模式)。 */
    private static boolean mcapAutoTrace;
    private static int mcapSeenSession;

    private static void lookTraceTick(Minecraft mc) {
        boolean mcapArmed = dev.taclight.channel.MotionCapture.armed();
        if (!dev.taclight.channel.LookTrace.active() && !mcapArmed) return;
        long nano = System.nanoTime();
        // 本地(观察者)通道(09-01 深夜⑥):自身走路/转视角同样门控 —— 必须先于
        // 远程目标扫描(best==null 时本地运动仍要记录，观察者运动场景正是"对方灯不动")。
        if (mcapArmed) {
            dev.taclight.channel.MotionCapture.observeLocal(mc.player.getYRot(), mc.player.getXRot(),
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), nano);
        }
        net.minecraft.world.entity.LivingEntity best = null;
        double bestD = 48.0 * 48.0;
        for (var ent : mc.level.entitiesForRendering()) {
            if (ent == mc.player || !(ent instanceof net.minecraft.world.entity.LivingEntity le)) continue;
            double d = le.distanceToSqr(mc.player);
            if (d < bestD) { bestD = d; best = le; }
        }
        if (best == null) return;
        if (mcapArmed) {
            dev.taclight.channel.MotionCapture.observe(best.getId(), best.yHeadRot, best.getXRot(),
                    best.getX(), best.getY(), best.getZ(), nano);
            int ses = dev.taclight.channel.MotionCapture.sessionId();
            if (ses != mcapSeenSession) {
                if (ses != 0) {
                    if (!dev.taclight.channel.LookTrace.active()) {
                        dev.taclight.channel.LookTrace.configure("on");
                        mcapAutoTrace = true;
                    }
                } else if (mcapAutoTrace) {
                    dev.taclight.channel.LookTrace.configure("off");
                    mcapAutoTrace = false;
                }
                mcapSeenSession = ses;
            }
        }
        if (!dev.taclight.channel.LookTrace.active()) return;
        float pt = mc.getPartialTick();
        float hO = best.yHeadRotO, hC = best.yHeadRot;
        float bO = best.yBodyRotO, bC = best.yBodyRot;
        float pO = best.xRotO, pC = best.getXRot();
        // base/ext 列 = 管线实际使用的值(snap on 时读快照插值只读视图;未跟踪退回 lerp)
        float baseYaw, basePitch, rowExt;
        dev.taclight.channel.RemoteBaseSnap.Out pk = dev.taclight.channel.RemoteBaseSnap.enabled()
                ? dev.taclight.channel.RemoteBaseSnap.peek(best.getId(), hC, pC, nano) : null;
        if (pk != null) {
            baseYaw = pk.yaw();
            basePitch = pk.pitch();
            rowExt = pk.extYaw();
        } else {
            baseYaw = net.minecraft.util.Mth.rotLerp(pt, hO, hC);
            basePitch = net.minecraft.util.Mth.lerp(pt, pO, pC);
            rowExt = dev.taclight.channel.RemoteLookPredictor.peekExtYaw(best.getId());
        }
        float omYaw = net.minecraft.util.Mth.wrapDegrees(hC - hO);
        // 位置链列(09-01 深夜③):tgt=同步目标真值(mixin accessor);disp=管线实际用的
        // 灯锚点(psnap on=快照插值+超前+眼高,off=getEyePosition)——A/B 与离线分解的真源
        dev.taclight.mixin.LivingEntityLerpAccess la = (dev.taclight.mixin.LivingEntityLerpAccess) best;
        double tX = la.taclight$lerpX(), tY = la.taclight$lerpY(), tZ = la.taclight$lerpZ();
        double dX, dY, dZ;
        dev.taclight.channel.RemotePosSnap.Out pp = dev.taclight.channel.RemotePosSnap.enabled()
                ? dev.taclight.channel.RemotePosSnap.peek(best.getId(), best.getX(), best.getY(), best.getZ(), nano) : null;
        if (pp != null) {
            dX = pp.x();
            dY = pp.y() + best.getEyeHeight();
            dZ = pp.z();
        } else {
            dX = net.minecraft.util.Mth.lerp(pt, best.xo, best.getX());
            dY = net.minecraft.util.Mth.lerp(pt, best.yo, best.getY()) + best.getEyeHeight();
            dZ = net.minecraft.util.Mth.lerp(pt, best.zo, best.getZ());
        }
        dev.taclight.channel.LookTrace.row(best.getId(), best.getType().toString().intern(), nano,
                pt, hO, hC, bO, bC, pO, pC, baseYaw, basePitch, omYaw, rowExt,
                best.xo, best.yo, best.zo, best.getX(), best.getY(), best.getZ(),
                tX, tY, tZ, dX, dY, dZ);
        // R 行(09-01 深夜⑥):与 LOOKTRACE 同值的 CSV 副本,会话期内离线拼链路
        if (dev.taclight.channel.FrameRecorder.active()) {
            FrameRecorder.append(FrameRecorder.remoteRow(nano / 1_000_000L, recFrame, best.getId(), pt,
                    hO, hC, bO, bC, pO, pC, baseYaw, basePitch, omYaw, rowExt,
                    best.xo, best.yo, best.zo, best.getX(), best.getY(), best.getZ(),
                    tX, tY, tZ, dX, dY, dZ));
        }
    }

    /** SSBO 硬上限(LightBuffer/GLSL 两侧同值 8;自身灯优先,远程补足余量)。 */
    private static final int MAX_LIGHTS = 8;

    /** M5 远程玩家灯收集:实体数据开关 → 距离剔除/就近上限 → 第三人称锚定数学复用。 */
    private static void collectRemoteLights(Minecraft mc, Vec3 camEye, LightParams cfg, List<SpotlightData> out) {
        if (out.size() >= MAX_LIGHTS) return;
        var remotes = new ArrayList<net.minecraft.client.player.AbstractClientPlayer>();
        var cands = new ArrayList<MultiLightCollector.Candidate>();
        for (var p : mc.level.players()) {
            if (p == mc.player) continue;
            boolean h = dev.taclight.sync.PlayerLightAccess.flashlight(p);
            boolean g = dev.taclight.sync.PlayerLightAccess.gunLight(p);
            if (!h && !g) continue;
            Vec3 eye = p.getEyePosition(mc.getPartialTick());
            cands.add(new MultiLightCollector.Candidate(remotes.size(), eye.x, eye.y, eye.z, h, g));
            remotes.add(p);
        }
        double maxDist = dev.taclight.config.TacLightConfig.REMOTE_LIGHT_MAX_DIST.get();
        int cap = Math.min(dev.taclight.config.TacLightConfig.REMOTE_LIGHT_MAX_COUNT.get(), MAX_LIGHTS - out.size());
        for (MultiLightCollector.Selected sel : MultiLightCollector.select(cands, camEye.x, camEye.y, camEye.z, maxDist, cap)) {
            var p = remotes.get(sel.index());
            // 坑36(08-30 深夜,远程移动闪烁):getLookAngle() 是 tick 瞬时值(20Hz 台阶),
            // 而实体模型渲染走 O→current 的 partialTick 角度插值 —— A 一转视角,B 眼里
            // 光斑就以 20Hz 跳动(模型平滑、灯抖动)。灯的方向必须与渲染同源插值。
            float pt = mc.getPartialTick();
            float xRot, yHead, extYaw, extPitch;
            if (dev.taclight.channel.RemoteBaseSnap.enabled()) {
                // snap+pred(09-01 深夜,用户批准):基角 = 延迟一段快照插值(自用 C 历史,
                // 不碰 O/C 对,构造上位置连续,消源1 锯齿)+ 两级 EMA 预测保留超前。
                // 非对称确认与消融数字见 evidence/2026-09-01-asymmetry-confirm/;!bsnap off 退回旧管线。
                var o = dev.taclight.channel.RemoteBaseSnap.step(
                        p.getId(), p.yHeadRot, p.getXRot(), System.nanoTime());
                yHead = o.yaw();
                xRot = o.pitch();
                extYaw = o.extYaw();
                extPitch = o.extPitch();
            } else {
                xRot = net.minecraft.util.Mth.lerp(pt, p.xRotO, p.getXRot());
                yHead = net.minecraft.util.Mth.rotLerp(pt, p.yHeadRotO, p.yHeadRot);
                // 方案A(08-31,用户批准):同源角速度外推,对抗原版同步链 ~100-250ms 可感滞后
                // (用户复测:闪烁消但转动滞后)。ω̂ 与渲染同源(O→current 差值);钳制+EMA
                // 细节与调参(!extrap)见 RemoteLookPredictor。
                float omegaYaw = net.minecraft.util.Mth.wrapDegrees(p.yHeadRot - p.yHeadRotO);
                float omegaPitch = p.getXRot() - p.xRotO;
                RemoteLookPredictor.Ext ext = RemoteLookPredictor.step(
                        p.getId(), yHead, xRot, omegaYaw, omegaPitch, System.nanoTime());
                extYaw = ext.yawDeg();
                extPitch = ext.pitchDeg();
            }
            Vec3 look = Vec3.directionFromRotation(xRot + extPitch, yHead + extYaw);
            // 位置链(09-01 深夜③):getEyePosition(pt)=lerp(o→C) 的逐 tick 增量有 ±20% 速度
            // 调制,墙光斑 1:1 放大(真实步行平移残差 ≈100px)。死推+速度导引匀速重构消调制;
            // v1 延迟段插值因目标序列突发式台阶实机更糟已否决。!psnap off 退回旧管线。
            Vec3 eye;
            if (dev.taclight.channel.RemotePosSnap.enabled()) {
                var po = dev.taclight.channel.RemotePosSnap.step(
                        p.getId(), p.getX(), p.getY(), p.getZ(), System.nanoTime());
                eye = new Vec3(po.x(), po.y() + p.getEyeHeight(), po.z());
            } else {
                eye = p.getEyePosition(pt);
            }
            if (sel.handheld()) {
                SpotlightData hand = toSpot(eye.add(handheldOffset(look)), look, cfg, 0.9f);
                out.add(selfCapped(hand, eye));
            }
            if (sel.gun() && out.size() < MAX_LIGHTS) {
                // 他人枪灯:无精确枪口矩阵(本地捕获仅第一人称),眼位近似(旁观方案 §4.5)
                SpotlightData gun = toSpot(eye.add(look.scale(0.45)), look, cfg,
                        dev.taclight.config.TacLightConfig.GUN_MULTIPLIER.get().floatValue());
                out.add(selfCapped(gun, eye));
            }
        }
    }

    // ---- F2 自体胶囊常量(GLSL 侧消费,竖直半高 TACLIGHT_SELF_CAP_HALF=1.05)----
    /** 胶囊中心在玩家眼位下方(身体近似竖直圆柱,眼上 0.5m 到脚下 1.6m)。 */
    static final double SELF_CAP_CENTER_DROP = 0.55;
    /** 胶囊半径:玩家碰撞半宽 0.3 + 灯锚偏移/视差余量。 */
    static final float SELF_CAP_RADIUS = 0.45f;

    /** F2 纯函数:为灯附加自体胶囊(cookie = 灯→胶囊中心偏移.xyz + 半径)。
     *  胶囊中心 = 玩家眼位 −0.55y。偏移在 Java 侧以 double 计算后降 float ——
     *  灯锚与眼位同源同帧,差值小,无 world 精度问题;GLSL 侧以灯位为基准
     *  重建胶囊中心,与灯同用一路 world→view 变换,无二次误差。 */
    static SpotlightData selfCapped(SpotlightData light, Vec3 playerEye) {
        Vec3 cap = playerEye.add(0, -SELF_CAP_CENTER_DROP, 0);
        return light.withSelfCapsule(
                (float) (cap.x - light.posX()),
                (float) (cap.y - light.posY()),
                (float) (cap.z - light.posZ()),
                SELF_CAP_RADIUS);
    }

    /** 参考亮度:radius 配置语义的锚点;亮度-距离按反平方等照度律耦合(d ∝ √I,doc06 §8.7)。 */
    public static final float INTENSITY_REFERENCE = 6.0f;

    /** 灯源锚点(2026-09-01 "地面条纹随观察者视角晃动同频放大"修复):
     *  第一人称灯若锚渲染相机眼位，灯源会耦合相机变换并令地面光池同频脉动。
     *  统一锚玩家眼位(getEyePosition,与 SSO 豁免胶囊同源);真实 bob 分析必须读取
     *  walkDist/walkDistO/bob/oBob/bobView，而不能把 cameraEye-playerEye 冒充 bob。
     *  freecam/TP 语义不变。
     *  ⚠ 契约:taclightContracts → UploaderSemanticContract §7 钉死"FP 锚 = 玩家眼位"。
     *  @param cameraEye 渲染相机眼位(仅诊断/旁路用)  @param playerEye 玩家眼位
     *  @return 玩家眼位 */
    public static Vec3 spotAnchor(Vec3 cameraEye, Vec3 playerEye) {
        return playerEye;
    }

    /** 纯数据装配:world 坐标原样直传给 SSBO(唯一合法性入口;无相机/GL/MC 类型,可离线测试)。
     *  亮度-距离耦合:有效半径 = radius × √(finalIntensity/6.0),钳制 ≤ radiusMax ——
     *  未来"挡位"只需改亮度,照距自动 √ 缩放(用户需求:亮度和距离正相关)。
     *  2026-09-04 三旋钮(体感调参):!bright 在 !lv 之后取值(绝对亮度,互斥以后写者为准);
     *  !dist 覆盖本耦合(绝对照距);!atten 经 cone.z 逐灯透传(0=GLSL 回退编译期默认)。 */
    public static SpotlightData buildSpotBeam(double wx, double wy, double wz,
                                              double dx, double dy, double dz,
                                              LightParams p, float intensityMult) {
        float intensity = LightTuneOverride.brightnessFor(p.intensity * intensityMult);
        float radius = LightTuneOverride.radiusFor(
                p.radius * (float) Math.sqrt(Math.max(p.intensity * intensityMult, 1e-3f) / INTENSITY_REFERENCE),
                p.radiusMax);
        radius = Math.min(radius, p.radiusMax);
        SpotlightData plain = SpotlightData.spotBeam(
                (float) wx, (float) wy, (float) wz, radius,
                R, G, B, intensity,
                (float) dx, (float) dy, (float) dz,
                p.cosOuter, p.cosInner, p.beamDensity, 1.0f);
        float k = LightTuneOverride.attenK();
        if (k == 0.0f) return plain;
        return new SpotlightData(plain.posX(), plain.posY(), plain.posZ(), plain.radius(),
                plain.red(), plain.green(), plain.blue(), plain.intensity(),
                plain.dirX(), plain.dirY(), plain.dirZ(), plain.type(),
                plain.cosOuter(), plain.cosInner(), k, plain.coneReservedW(),
                plain.anisotropy(), plain.density(), plain.beam(), plain.vlReservedW(),
                plain.cookieR(), plain.cookieG(), plain.cookieB(), plain.cookieA());
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

    /** 手持灯锚点偏移(相对眼位,纯函数):前 0.35 / 右 0.22 / 下 0.14。右手性契约见 UploaderSemanticContract §5。 */
    static Vec3 handheldOffset(Vec3 look) {
        return look.scale(0.35).add(rightVector(look).scale(0.22)).add(0, -0.14, 0);
    }

    /** 枪灯回退锚点偏移(枪口姿态不可用时):前 0.55 / 右 0.18 / 下 0.10。 */
    static Vec3 gunFallbackOffset(Vec3 look) {
        return look.scale(0.55).add(rightVector(look).scale(0.18)).add(0, -0.10, 0);
    }

    /** 视线在水平面的右手方向 = look×up = (-z, 0, x) 归一化(朝北看时右手=东)。 */
    static Vec3 rightVector(Vec3 look) {
        Vec3 r = new Vec3(-look.z, 0.0, look.x);
        double len = r.length();
        return len > 1e-6 ? r.scale(1.0 / len) : new Vec3(1.0, 0.0, 0.0);
    }
}

package dev.taclight.client;

import dev.taclight.TacLightMod;
import dev.taclight.tacz.GunLaserReader;
import dev.taclight.tacz.TaczClientLightProbe;
import dev.taclight.tacz.TaczCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT)
public class ClientEvents {
    private static GunLaserReader.Status lastGunStatus = GunLaserReader.Status.NONE;
    private static boolean lastSentEffective = false;
    private static int e2eTick;
    private static boolean e2eLogged;
    /** tune 持久化恢复(一次性,首个世界 tick;config 此时必已加载,比 MOD setup 更稳)。 */
    private static boolean tuneRestored;

    /** 端到端探针回读(仅 TACLIGHT_PROBE=1 时启用;诊断用,默认静默)。 */
    private static void probeIfEnabled() {
        if (!"1".equals(System.getenv("TACLIGHT_PROBE"))) return;
        if (++e2eTick % 60 != 0) return;
        int bits = dev.taclight.channel.LightBuffer.readReserved();
        TacLightMod.LOGGER.info("[TacLight] E2E-PROBE reserved=0x{} {}", Integer.toHexString(bits), decodeProbe(bits));
        TacLightMod.LOGGER.info("[TacLight] GPU-READBACK {}", dev.taclight.channel.LightBuffer.dumpLight0());
        if (!e2eLogged && (bits & 3) == 3) { e2eLogged = true; }
    }

    /** 解码 GLSL 探针位:1=surface 进入,2=门通过,4=光已贡献,8=debug 标志已见,0x10=beam,0x20=specular。 */
    private static String decodeProbe(int bits) {
        StringBuilder sb = new StringBuilder();
        if ((bits & 1) != 0) sb.append(" |surface-entered");
        if ((bits & 2) != 0) sb.append(" |gate-passed");
        if ((bits & 4) != 0) sb.append(" |light-contributed");
        if ((bits & 8) != 0) sb.append(" |debug-flag-seen");
        if ((bits & 16) != 0) sb.append(" |beam-entered");
        if ((bits & 32) != 0) sb.append(" |specular-entered");
        if ((bits & 1024) != 0) sb.append(" |slot0-sees-our-data");
        if ((bits & 2048) != 0) sb.append(" |slot1-sees-our-data");
        if ((bits & 4096) != 0) sb.append(" |slot8-sees-our-data");
        return sb.length() == 0 ? "(none)" : sb.toString().substring(1);
    }
    private static int probeTick;
    private static boolean probeConfirmed;
    private static int diagTick;
    private static ShaderPackDiagLogic.Status lastDiagStatus;
    private static boolean diagAutoApplied;
    private static int boardTick;
    private static final String DERIVED_PACK = "iterationT 3.2.0 (taclight)";

    @Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.FLASHLIGHT_TOGGLE);
            event.register(KeyBindings.GUNLIGHT_TOGGLE);
            event.register(KeyBindings.DEBUG_TOGGLE);
            event.register(KeyBindings.DIAG_DUMP);
            event.register(KeyBindings.BENCH);
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        while (KeyBindings.FLASHLIGHT_TOGGLE.consumeClick()) {
            // 持物门(2026-09-25 用户设计):①先看手里是不是拿着手电筒 ②再看开关。
            // 未持有且不在霓虹调试时**不改状态**,只给可操作提示(避免"关了但看着还亮"的假成功)。
            if (!holdingFlashlight(Minecraft.getInstance().player) && !ClientLightState.debugMode()) {
                Minecraft mcL = Minecraft.getInstance();
                if (mcL.player != null) {
                    mcL.player.displayClientMessage(Component.literal(
                            "[TacLight] 未手持手电筒(taclight:flashlight):先拿在手上再按 L"), false);
                }
                TacLightMod.LOGGER.info("[TacLight] handheld toggle ignored (not holding flashlight)");
                continue;
            }
            ClientLightState.toggle();
            // M5:开关上报纸服务端(SynchedEntityData 真源),其他玩家客户端可见
            dev.taclight.network.TacLightNetwork.sendSetLight(
                    ClientLightState.handheldEffective(), ClientLightState.gunLightEffective());
            TacLightMod.LOGGER.info("[TacLight] handheld flashlight {}", ClientLightState.isOn() ? "ON" : "OFF");
        }
        while (KeyBindings.GUNLIGHT_TOGGLE.consumeClick()) {
            boolean next = dev.taclight.client.GunControl.toggleGunManual();
            // M5:上报有效灯(偏好×持枪门),空手按 M 只存偏好不亮灯,切回枪即复
            boolean eff = ClientLightState.gunLightEffective();
            dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.handheldEffective(), eff);
            TacLightMod.LOGGER.info("[TacLight] gun light {} (key, manual, effective={})", next ? "ON" : "OFF", eff ? "ON" : "OFF");
        }
        while (KeyBindings.DEBUG_TOGGLE.consumeClick()) {
            ClientLightState.toggleDebug();
            boolean dbg = ClientLightState.debugMode();
            boolean autoOn = false;
            if (dbg && !ClientLightState.isOn()) {
                ClientLightState.forceHandheldOn();
                autoOn = true;
            }
            TacLightMod.LOGGER.info("[TacLight] debug neon mode {}{}", dbg ? "ON" : "OFF", autoOn ? " (auto-ON flashlight)" : "");
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[TacLight] 霓虹调试 "
                        + (dbg ? "ON —— 应看到绿色锥形光(=我们的SSBO通道)" : "OFF") + (autoOn ? "(手电筒已自动开启)" : "")), false);
            }
        }
        while (KeyBindings.DIAG_DUMP.consumeClick()) {
            dumpDiag();
        }
        while (KeyBindings.BENCH.consumeClick()) {
            startBench();
        }
    }

    /**
     * 持物门判据:主手或副手是否持 {@code taclight:flashlight}。
     * 按键、中继({@code !light})、tick 探针**共用同一判据**,不允许三处各写一套。
     */
    public static boolean holdingFlashlight(net.minecraft.world.entity.player.Player p) {
        if (p == null) return false;
        try {
            net.minecraft.world.item.Item item = dev.taclight.registry.ModItems.FLASHLIGHT.get();
            return p.getMainHandItem().is(item) || p.getOffhandItem().is(item);
        } catch (Throwable t) {
            return false; // 注册表未就绪等异常一律视为"未持有":宁可不亮,不误亮
        }
    }

    /** N 键:一行结构化诊断(调试自动化 grep 用;字段顺序=契约,tools/session 依赖;新增字段只追加尾部)。 */
    static void dumpDiag() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { TacLightMod.LOGGER.info("[TacLight] DIAG no-world"); return; }
        var cam = mc.gameRenderer.getMainCamera();
        var p = cam.getPosition();
        // 客户端命令树取证(计划文档坑位 9:P0 发现聊天框命令全被客户端预览拒绝,疑树为空)
        String cmdtree = "no-conn";
        if (mc.getConnection() != null) {
            var root = mc.getConnection().getCommands().getRoot();
            cmdtree = root.getChildren().size() + " taclight=" + (root.getChild("taclight") != null);
        }
        TacLightMod.LOGGER.info("[TacLight] DIAG {} | cam=({}) yRot={} xRot={} flash={} neon={} pack={}({}) | ssbo {} | cmdtree {}",
                dev.taclight.TacLightMod.VERSION,
                String.format("%.2f,%.2f,%.2f", p.x, p.y, p.z),
                String.format("%.1f", mc.player.getYRot()), String.format("%.1f", mc.player.getXRot()),
                ClientLightState.isOn(), ClientLightState.debugMode(),
                ShaderPackDiag.activeStatus(), ShaderPackDiag.activePackName(),
                dev.taclight.channel.LightBuffer.dumpLight0(),
                cmdtree);
        // 08-31 远程同步探针:读侧(syncReady/accessor id/各玩家标志)一行一玩家
        if (mc.level != null) {
            TacLightMod.LOGGER.info("[TacLight] DIAG-REMOTE syncReady={} accIds=(flash={},gun={})",
                    dev.taclight.sync.PlayerLightAccess.syncReady,
                    dev.taclight.sync.PlayerLightAccess.FLASHLIGHT.getId(),
                    dev.taclight.sync.PlayerLightAccess.GUNLIGHT.getId());
            // 里程碑①排障插桩(2026-09-02):白名单匹配的决定性证据
            try {
                var ak47 = new net.minecraft.resources.ResourceLocation("tacz", "ak47");
                var hk = new net.minecraft.resources.ResourceLocation("tacz", "hk416d");
                var att = new net.minecraft.resources.ResourceLocation("taclight", "gun_light");
                var prov = (com.tacz.guns.resource.ICommonResourceProvider)
                        com.tacz.guns.resource.CommonAssetsManager.get();
                var tagsAk = prov.getAllowAttachmentTags(ak47);
                var tagsHk = prov.getAllowAttachmentTags(hk);
                TacLightMod.LOGGER.info(
                        "[TacLight] DIAG-ALLOW ak47tags={} hk416dtags={} matchAk={} matchHk={}",
                        tagsAk == null ? "null" : tagsAk.size(),
                        tagsHk == null ? "null" : tagsHk.size(),
                        com.tacz.guns.util.AllowAttachmentTagMatcher.match(ak47, att),
                        com.tacz.guns.util.AllowAttachmentTagMatcher.match(hk, att));
            } catch (Throwable t) {
                TacLightMod.LOGGER.info("[TacLight] DIAG-ALLOW error: {}", t.toString());
            }
            for (var pl : mc.level.players()) {
                TacLightMod.LOGGER.info("[TacLight] DIAG-REMOTE player={} self={} flash={} gun={} pos=({})",
                        pl.getGameProfile().getName(), pl == mc.player,
                        dev.taclight.sync.PlayerLightAccess.flashlight(pl),
                        dev.taclight.sync.PlayerLightAccess.gunLight(pl),
                        String.format("%.1f,%.1f,%.1f", pl.getX(), pl.getY(), pl.getZ()));
                // 里程碑②:第三人称枪口捕获状态;09-02 屏外连续性:直接读上传器解析
                // 摘要(单一真源,含 state/weight/age/模式/原样读数;诊断只读不消费)。
                if (pl != mc.player && dev.taclight.sync.PlayerLightAccess.gunLight(pl)) {
                    var plLook = pl.getLookAngle();
                    String probe = dev.taclight.channel.ClientSpotlightUploader.TP_PROBE.get(pl.getId());
                    if (probe != null) {
                        TacLightMod.LOGGER.info("[TacLight] DIAG-TP player={} look=({}) {} tpCount={}",
                                pl.getGameProfile().getName(),
                                String.format("%.3f,%.3f,%.3f", plLook.x, plLook.y, plLook.z),
                                probe,
                                dev.taclight.client.MuzzlePoseCapture.tpCapturedCount());
                    } else {
                        TacLightMod.LOGGER.info("[TacLight] DIAG-TP player={} capture=never tpCount={}",
                                pl.getGameProfile().getName(),
                                dev.taclight.client.MuzzlePoseCapture.tpCapturedCount());
                    }
                }
            }
        }
    }

    // ---- B 键:3 秒帧率基准(帧间 nanoTime 差;采样在渲染线程,开销为零) ----
    private static boolean benchActive;
    private static long benchStartNanos;
    private static long benchLastFrameNanos;
    private static final java.util.List<Long> BENCH_SAMPLES = new java.util.ArrayList<>();
    private static final long BENCH_DURATION_NANOS = 3_000_000_000L;

    static void startBench() {
        BENCH_SAMPLES.clear();
        benchStartNanos = System.nanoTime();
        benchLastFrameNanos = 0;
        benchActive = true;
        TacLightMod.LOGGER.info("[TacLight] BENCH start (3s)");
    }

    private static void benchTickFrame() {
        if (!benchActive) return;
        long now = System.nanoTime();
        if (benchLastFrameNanos > 0) BENCH_SAMPLES.add(now - benchLastFrameNanos);
        benchLastFrameNanos = now;
        if (now - benchStartNanos < BENCH_DURATION_NANOS) return;
        benchActive = false;
        if (BENCH_SAMPLES.isEmpty()) { TacLightMod.LOGGER.info("[TacLight] BENCH no-samples"); return; }
        double[] sorted = new double[BENCH_SAMPLES.size()];
        double total = 0;
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = BENCH_SAMPLES.get(i) / 1e9;
            total += sorted[i];
        }
        java.util.Arrays.sort(sorted); // 升序:尾部=最慢帧
        double avg = sorted.length / total;
        double p1Low = percentileFps(sorted, 0.01);
        double min = 1.0 / sorted[sorted.length - 1];
        TacLightMod.LOGGER.info("[TacLight] BENCH frames={} avgFPS={} onePctLow={} minFPS={}",
                sorted.length, String.format("%.1f", avg), String.format("%.1f", p1Low), String.format("%.1f", min));
    }

    /** worstFrac 比例的最慢帧的调和平均(1% low 惯例)。sorted 升序帧时长。 */
    private static double percentileFps(double[] sortedAsc, double worstFrac) {
        int n = Math.max(1, (int) Math.ceil(sortedAsc.length * worstFrac));
        double sum = 0;
        for (int i = 0; i < n; i++) sum += sortedAsc[sortedAsc.length - 1 - i];
        return n / sum;
    }


    /** 每 5 秒检查活动光影包;状态变化时聊天+日志提示(选错包是 90% 的问题)。 */
    private static void checkShaderPackDiag(Minecraft mc) {
        if (++diagTick % 100 != 0) return;
        ShaderPackDiagLogic.Status st = ShaderPackDiag.activeStatus();
        if (st == lastDiagStatus) return;
        lastDiagStatus = st;
        String pack = ShaderPackDiag.activePackName();
        // 判定与文案同源(纯类):2026-09-25 之前 INTEROP_INJECTED 这类状态不存在,
        // 注入成功也被判成 ORIGINAL_PACK ⇒ 聊天栏 ✘ "无 TacLight 注入"(用户实测 bug)。
        String msg = ShaderPackDiagLogic.message(st, pack, DERIVED_PACK);
        TacLightMod.LOGGER.info("[TacLight] diag: {}", msg);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
    }

    /** Number of screenshot encodes still pending; scheduling pressure is recorded as D rows. */
    private static final java.util.concurrent.atomic.AtomicInteger MCAP_PENDING =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Recorder run root is initialized once per client process. */
    static {
        dev.taclight.channel.FrameRecorder.setBaseDir(
                net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toFile());
    }

    /** Frame-end screenshot execution. Reservation writes P synchronously; callback writes S/F by token. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            dev.taclight.channel.MotionCapture.shutdown("world-unload");
            return;
        }
        if (!dev.taclight.channel.MotionCapture.armed()) return;
        long nano = System.nanoTime();
        long renderFrame = dev.taclight.channel.ClientSpotlightUploader.currentRenderFrame();
        dev.taclight.channel.FrameRecorder.ShotToken token =
                dev.taclight.channel.MotionCapture.reserveShot(nano, renderFrame);
        if (token == null) return;
        MCAP_PENDING.incrementAndGet();
        try {
            net.minecraft.client.Screenshot.grab(token.sessionDir(), token.filename(), mc.getMainRenderTarget(), component -> {
                try {
                    boolean success = token.expectedFile().isFile();
                    dev.taclight.channel.FrameRecorder.completeShot(token, success,
                            success ? "ok" : "expected-file-missing");
                    if (success) TacLightMod.LOGGER.info("[TacLight] REC shot {}", token.expectedFile());
                    else TacLightMod.LOGGER.error("[TacLight] REC shot missing {} callback={}",
                            token.expectedFile(), component.getString());
                } finally {
                    MCAP_PENDING.decrementAndGet();
                }
            });
        } catch (Throwable t) {
            MCAP_PENDING.decrementAndGet();
            dev.taclight.channel.FrameRecorder.completeShot(token, false,
                    "grab-throw:" + t.getClass().getSimpleName());
            TacLightMod.LOGGER.error("[TacLight] REC grab failed for {}", token.expectedFile(), t);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        // SSBO 数据必须按"渲染帧"刷新(v0.9.0 热修:原挂在 ClientTick 仅 20Hz,
        // 转视角时灯位滞后相机最多 50ms,肉眼可见拖拽)。AFTER_LEVEL 时相机已是
        // 本帧终值,且先于 Iris composite 执行;社区同型案例共识 = 每帧更新数据。
        dev.taclight.channel.ClientSpotlightUploader.onFrame();
        dev.taclight.channel.LightBuffer.rebindBase();
        dev.taclight.channel.PerfStats.tickFrame();
        benchTickFrame();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            // 退出世界后可信关闭录制并停摆 SSBO；shutdown 幂等，可被多个 world-null hook 调用。
            dev.taclight.channel.MotionCapture.shutdown("world-unload");
            dev.taclight.channel.LightBuffer.upload(java.util.List.of());
            return;
        }

        if (!tuneRestored) {
            tuneRestored = true;
            // /taclight tune 写回的 toml 在重启后回填覆盖层(新增键 atten/knee/scat/beamcap/
            // voxel 的消费者只认覆盖层;既有键 bright/dist/beam/cone 直读 config,恢复层不碰)。
            try {
                String r = dev.taclight.tune.TunePersist.restore(
                        dev.taclight.tune.TunePersist.forgeSink(), TuneClientGate.GATE,
                        (knob, arg) -> switch (knob) {
                            case "atten" -> dev.taclight.channel.LightTuneOverride.configureAtten(arg);
                            case "knee" -> dev.taclight.channel.LightTuneOverride.configureKnee(arg);
                            case "scat" -> dev.taclight.channel.LightTuneOverride.configureScat(arg);
                            case "beamcap" -> dev.taclight.channel.LightTuneOverride.configureBeamcap(arg);
                            default -> "bad arg " + arg + " (restore only handles persisted knobs)";
                        });
                TacLightMod.LOGGER.info("[TacLight] TUNE {}", r);
            } catch (Throwable t) {
                TacLightMod.LOGGER.warn("[TacLight] TUNE restore failed: {}", t.toString());
            }
        }

        if (!diagAutoApplied && "1".equals(System.getenv("TACLIGHT_DIAG"))) {
            diagAutoApplied = true;
            ClientLightState.setDebug(true);
            TacLightMod.LOGGER.info("[TacLight] DIAG auto: debug neon ON at login");
        }
        // 手持灯持物门(2026-09-25 用户报的 bug):每 tick 覆写探针;离手即**自动关**并同步服务端
        // (第三条:从手里移除后自动关闭)。霓虹调试期间不清,否则一放手电筒霓虹就灭。
        boolean holdingNow = holdingFlashlight(mc.player);
        boolean wasHolding = ClientLightState.handheldProbeOn();
        ClientLightState.setHandheldProbe(holdingNow);
        if (ClientLightState.autoClear(ClientLightState.isOn(), wasHolding, holdingNow, ClientLightState.debugMode())) {
            ClientLightState.setHandheld(false);
            dev.taclight.network.TacLightNetwork.sendSetLight(false, ClientLightState.gunLightEffective());
            TacLightMod.LOGGER.info("[TacLight] handheld flashlight OFF (left hand: no longer holding flashlight)");
        }

        GunLaserReader.Status status = GunLaserReader.Status.NONE;
        String detail = "no-tacz";
        if (TaczCompat.present()) {
            try {
                var probe = TaczClientLightProbe.probe(mc.player);
                if (probe != null) {
                    status = GunLaserReader.classify(probe.gunId(), probe.laserAttachmentId());
                    detail = probe.gunId() + " | " + probe.laserAttachmentId();
                } else {
                    detail = "no-gun";
                }
            } catch (Throwable t) {
                detail = "probe-error:" + t.getClass().getSimpleName();
            }
        }

        boolean probeOn = (status == GunLaserReader.Status.OUR_LIGHT);
        ClientLightState.setGunProbe(probeOn);
        ClientLightState.setGunLight(probeOn);
        // Gun state remains tick-driven above; SSBO collection/upload occurs only in onRenderLevel.
        probeIfEnabled();
        checkShaderPackDiag(mc);
        if ("1".equals(System.getenv("TACLIGHT_PROBE")) && ++boardTick % 60 == 0) {
            TacLightMod.LOGGER.info("[TacLight] BOARD pack={} debug={} binding7={} reserved=0x{}",
                    dev.taclight.client.ShaderPackDiag.activeStatus(),
                    ClientLightState.debugMode(),
                    dev.taclight.channel.LightBuffer.binding7(),
                    Integer.toHexString(dev.taclight.channel.LightBuffer.readReserved()));
        }
        if (status != lastGunStatus) {
            TacLightMod.LOGGER.info("[TacLight] gun light {} ({})",
                    status == GunLaserReader.Status.OUR_LIGHT ? "ON" : "OFF", detail);
            lastGunStatus = status;
        }
        // M5:有效灯变化即同步服务端真源(含手动期切走/切回:偏好不变但有效翻转)
        boolean effNow = ClientLightState.gunLightEffective();
        if (effNow != lastSentEffective) {
            lastSentEffective = effNow;
            dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.handheldEffective(), effNow);
        }
    }
}

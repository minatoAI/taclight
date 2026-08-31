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
    private static int e2eTick;
    private static boolean e2eLogged;

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
    private static ShaderPackDiag.Status lastDiagStatus;
    private static boolean diagAutoApplied;
    private static int boardTick;
    private static final String DERIVED_PACK = "iterationT 3.2.0 (taclight)";

    @Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.FLASHLIGHT_TOGGLE);
            event.register(KeyBindings.DEBUG_TOGGLE);
            event.register(KeyBindings.DIAG_DUMP);
            event.register(KeyBindings.BENCH);
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        while (KeyBindings.FLASHLIGHT_TOGGLE.consumeClick()) {
            ClientLightState.toggle();
            // M5:开关上报纸服务端(SynchedEntityData 真源),其他玩家客户端可见
            dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.isOn(), ClientLightState.gunLightOn());
            TacLightMod.LOGGER.info("[TacLight] handheld flashlight {}", ClientLightState.isOn() ? "ON" : "OFF");
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
            for (var pl : mc.level.players()) {
                TacLightMod.LOGGER.info("[TacLight] DIAG-REMOTE player={} self={} flash={} gun={} pos=({})",
                        pl.getGameProfile().getName(), pl == mc.player,
                        dev.taclight.sync.PlayerLightAccess.flashlight(pl),
                        dev.taclight.sync.PlayerLightAccess.gunLight(pl),
                        String.format("%.1f,%.1f,%.1f", pl.getX(), pl.getY(), pl.getZ()));
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
        ShaderPackDiag.Status st = ShaderPackDiag.activeStatus();
        if (st == lastDiagStatus) return;
        lastDiagStatus = st;
        String pack = ShaderPackDiag.activePackName();
        String msg;
        switch (st) {
            case TACLIGHT_PACK:
                msg = "[TacLight] \u2714 配套包已激活: L=手电筒开关, K=霓虹调试";
                break;
            case ORIGINAL_PACK:
                msg = "[TacLight] \u2718 当前包 '" + pack + "' 无 TacLight 注入。请到选项>视频设置>光影(shaders)选择 '"
                        + DERIVED_PACK + "', 然后按 K";
                break;
            case NO_PACK:
                msg = "[TacLight] \u2718 未激活光影包: 锥光仅为视觉模式, K 霓虹无效(需要光影包)";
                break;
            default:
                msg = "[TacLight] ? 无法判定当前光影包。若按 K 无反应, 请在光影选择界面选 '" + DERIVED_PACK + "'";
                break;
        }
        TacLightMod.LOGGER.info("[TacLight] diag: {}", msg);
        mc.player.displayClientMessage(Component.literal(msg), false);
    }

    /** MCAP 连拍编码积压计数(PNG 在 ioPool 线程落盘;>3 张未消化时跳帧防堆积)。 */
    private static final java.util.concurrent.atomic.AtomicInteger MCAP_PENDING =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 09-01 运动门控连拍执行端:RenderTick END 时主帧缓冲已含本帧最终画面(F2 同源);
     *  进程内直读渲染目标,无需前台窗口 —— 坑34 的根治(F2 postkey 链路整个旁路)。
     *  落盘路径 = <gameDir>/mcap/s%04d/screenshots/<时间戳>.png(vanilla grab 语义)。 */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !dev.taclight.channel.MotionCapture.armed()) return;
        long nano = System.nanoTime();
        if (!dev.taclight.channel.MotionCapture.shotDue(nano)) return;
        if (MCAP_PENDING.get() > 3) return;
        java.io.File dir = new java.io.File(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toFile(),
                "mcap/s" + String.format("%04d", dev.taclight.channel.MotionCapture.sessionId()));
        if (!dir.exists() && !dir.mkdirs()) return;
        dev.taclight.channel.MotionCapture.onShot(nano);
        MCAP_PENDING.incrementAndGet();
        try {
            net.minecraft.client.Screenshot.grab(dir, mc.getMainRenderTarget(), p -> {
                MCAP_PENDING.decrementAndGet();
                TacLightMod.LOGGER.info("[TacLight] MCAP shot {}", p.getString());
            });
        } catch (Throwable t) {
            MCAP_PENDING.decrementAndGet();
            TacLightMod.LOGGER.warn("[TacLight] MCAP grab failed: {}", t.toString());
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
        benchTickFrame();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            // 退出世界后停摆 SSBO(清空灯光,防残留数据被后续上下文读到)
            dev.taclight.channel.LightBuffer.upload(java.util.List.of());
            return;
        }

        if (!diagAutoApplied && "1".equals(System.getenv("TACLIGHT_DIAG"))) {
            diagAutoApplied = true;
            ClientLightState.setDebug(true);
            TacLightMod.LOGGER.info("[TacLight] DIAG auto: debug neon ON at login");
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

        ClientLightState.setGunLight(status == GunLaserReader.Status.OUR_LIGHT);
        probeIfEnabled();
        checkShaderPackDiag(mc);
        if ("1".equals(System.getenv("TACLIGHT_PROBE")) && ++boardTick % 60 == 0) {
            TacLightMod.LOGGER.info("[TacLight] BOARD pack={} debug={} binding7={} reserved=0x{}",
                    dev.taclight.client.ShaderPackDiag.activeStatus(),
                    ClientLightState.debugMode(),
                    dev.taclight.channel.LightBuffer.binding7(),
                    Integer.toHexString(dev.taclight.channel.LightBuffer.readReserved()));
        }
        dev.taclight.channel.ClientSpotlightUploader.onFrame();
        if (status != lastGunStatus) {
            TacLightMod.LOGGER.info("[TacLight] gun light {} ({})",
                    status == GunLaserReader.Status.OUR_LIGHT ? "ON" : "OFF", detail);
            lastGunStatus = status;
            // M5:枪灯状态变化同步服务端真源
            dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.isOn(), ClientLightState.gunLightOn());
        }
    }
}

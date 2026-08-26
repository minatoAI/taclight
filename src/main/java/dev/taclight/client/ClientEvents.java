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
        if (!"1".equals(System.getenv("TACLIGHT_PROBE")) || e2eLogged) return;
        if (++e2eTick % 30 != 0) return;
        int bits = dev.taclight.channel.LightBuffer.readReserved();
        if ((bits & 1) != 0) {
            e2eLogged = true;
            TacLightMod.LOGGER.info("[TacLight] E2E-PROBE: SSBO surface pass fired (reserved=0x{}), pipeline verified", Integer.toHexString(bits));
        }
    }
    private static int probeTick;
    private static boolean probeConfirmed;
    private static int diagTick;
    private static ShaderPackDiag.Status lastDiagStatus;
    private static final String DERIVED_PACK = "iterationT 3.2.0 (taclight)";

    @Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.FLASHLIGHT_TOGGLE);
            event.register(KeyBindings.DEBUG_TOGGLE);
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        while (KeyBindings.FLASHLIGHT_TOGGLE.consumeClick()) {
            ClientLightState.toggle();
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
                msg = "[TacLight] \u2714 派生包已激活: K=霓虹调试, L=手电筒开关";
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

    @SubscribeEvent
    public static void onRenderLevel(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() == net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            dev.taclight.channel.LightBuffer.rebindBase();
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

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
        dev.taclight.channel.ClientSpotlightUploader.onFrame();
        if (status != lastGunStatus) {
            TacLightMod.LOGGER.info("[TacLight] gun light {} ({})",
                    status == GunLaserReader.Status.OUR_LIGHT ? "ON" : "OFF", detail);
            lastGunStatus = status;
        }
    }
}

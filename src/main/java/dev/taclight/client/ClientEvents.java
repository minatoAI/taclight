package dev.taclight.client;

import dev.taclight.TacLightMod;
import dev.taclight.tacz.GunLaserReader;
import dev.taclight.tacz.TaczClientLightProbe;
import dev.taclight.tacz.TaczCompat;
import net.minecraft.client.Minecraft;
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
            TacLightMod.LOGGER.info("[TacLight] debug neon mode {}", ClientLightState.debugMode() ? "ON" : "OFF");
        }
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
        dev.taclight.channel.ClientSpotlightUploader.onFrame();
        if (status != lastGunStatus) {
            TacLightMod.LOGGER.info("[TacLight] gun light {} ({})",
                    status == GunLaserReader.Status.OUR_LIGHT ? "ON" : "OFF", detail);
            lastGunStatus = status;
        }
    }
}

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

    @Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.FLASHLIGHT_TOGGLE);
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        while (KeyBindings.FLASHLIGHT_TOGGLE.consumeClick()) {
            ClientLightState.toggle();
            TacLightMod.LOGGER.info("[TacLight] handheld flashlight {}", ClientLightState.isOn() ? "ON" : "OFF");
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
        dev.taclight.channel.ClientSpotlightUploader.onFrame();
        if (status != lastGunStatus) {
            TacLightMod.LOGGER.info("[TacLight] gun light {} ({})",
                    status == GunLaserReader.Status.OUR_LIGHT ? "ON" : "OFF", detail);
            lastGunStatus = status;
        }
    }
}

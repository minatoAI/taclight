package dev.taclight.sync;

import dev.taclight.TacLightMod;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 坑32(08-30,M5 首测):原版 1.20.1 IntegratedServer.initServer() 硬编码
 * setUsesAuthentication(true)(字节码级确认),局域网加入者必须通过 Mojang 会话验证;
 * dev 第二客户端无有效会话 → "Failed to log in: Invalid session" 约 2 秒内被踢,
 * LAN 双客户端联测从未可能。社区标准替代(runServer 独立服)又因 oculus/embeddium
 * 纯客户端 runtimeOnly 必崩,不可用(见 mp-session.ps1 头注)。
 * 本钩子仅在显式 -Dtaclight.dev.disableLanAuth=true 时关闭集成服会话验证
 * (仅 dev run 配置传入);生产环境无此属性,验证行为与原版一致。
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID)
public final class DevLanAuthHook {
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (Boolean.getBoolean("taclight.dev.disableLanAuth")) {
            event.getServer().setUsesAuthentication(false);
            TacLightMod.LOGGER.info("[TacLight] DEV: integrated server LAN auth disabled (taclight.dev.disableLanAuth)");
        }
    }

    private DevLanAuthHook() {}
}

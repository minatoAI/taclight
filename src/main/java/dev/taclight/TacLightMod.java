package dev.taclight;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * TacLight — 聚光灯照明设备模组(Forge 1.20.1)
 * V0:工程骨架,验证环境与构建链路。
 */
@Mod(TacLightMod.MODID)
public class TacLightMod {
    public static final String MODID = "taclight";
    private static final Logger LOGGER = LogUtils.getLogger();

    public TacLightMod() {
        LOGGER.info("[TacLight] loading: spotlight device mod (V0 skeleton)");
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::commonSetup);
        bus.addListener(this::clientSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[TacLight] common setup done");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("[TacLight] client setup done");
    }
}

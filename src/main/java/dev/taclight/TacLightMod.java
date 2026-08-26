package dev.taclight;

import com.mojang.logging.LogUtils;
import dev.taclight.registry.ModItems;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(TacLightMod.MODID)
public class TacLightMod {
    public static final String MODID = "taclight";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TacLightMod() {
        LOGGER.info("[TacLight] loading: spotlight device mod");
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.register(bus);
        bus.addListener(ModItems::onCreativeTabLoad);
        bus.addListener(this::commonSetup);
        bus.addListener(this::clientSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {}

    private void clientSetup(final FMLClientSetupEvent event) {}
}

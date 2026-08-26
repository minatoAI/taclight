package dev.taclight;

import com.mojang.logging.LogUtils;
import dev.taclight.registry.ModItems;
import dev.taclight.tacz.TaczCompat;
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
        tryRegisterGunpack();
        bus.addListener(this::commonSetup);
        bus.addListener(this::clientSetup);
    }

    /** 软依赖注册:仅当 TaCZ 存在时,反射调用 TaczGunpackHook(其类引用 TaCZ)。 */
    private static void tryRegisterGunpack() {
        if (!TaczCompat.present()) {
            LOGGER.info("[TacLight] TaCZ not detected: gunpack & gun-light integration disabled");
            return;
        }
        try {
            Class.forName("dev.taclight.tacz.TaczGunpackHook").getMethod("register").invoke(null);
        } catch (Throwable t) {
            LOGGER.warn("[TacLight] gunpack hook failed: {}", t.toString());
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {}

    private void clientSetup(final FMLClientSetupEvent event) {}
}

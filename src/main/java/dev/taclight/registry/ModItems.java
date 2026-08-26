package dev.taclight.registry;

import dev.taclight.TacLightMod;
import dev.taclight.item.FlashlightItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, TacLightMod.MODID);

    public static final RegistryObject<Item> FLASHLIGHT = ITEMS.register("flashlight", FlashlightItem::create);

    public static void register(IEventBus bus) { ITEMS.register(bus); }

    public static void onCreativeTabLoad(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(FLASHLIGHT);
        }
    }
}

package dev.taclight.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import dev.taclight.TacLightMod;
import dev.taclight.registry.ModItems;
import dev.taclight.tacz.TaczCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * /taclight kit —— 一键发放验收套件:手电筒 + HK416D + 战术枪灯。
 * TaCZ 部分走官方 API(IGun.setGunId / IAttachment.setAttachmentId),无 TaCZ 时仅给手电筒。
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID)
public class TacLightCommand {
    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("taclight")
                .then(Commands.literal("kit")
                        .requires(s -> s.hasPermission(0))
                        .executes(ctx -> giveKit(ctx.getSource()))));
    }

    private static int giveKit(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        player.getInventory().add(new ItemStack(ModItems.FLASHLIGHT.get()));
        int extra = 0;
        if (TaczCompat.present()) {
            try {
                if (tryAdd(player, "tacz", "modern_kinetic_gun", gun -> IGun.getIGunOrNull(gun).setGunId(gun, new ResourceLocation("tacz", "hk416d")))) {
                    extra++;
                }
                if (tryAdd(player, "tacz", "attachment", att -> IAttachment.getIAttachmentOrNull(att).setAttachmentId(att, new ResourceLocation("taclight", "gun_light")))) {
                    extra++;
                }
            } catch (Throwable t) {
                TacLightMod.LOGGER.warn("[TacLight] kit TaCZ part failed: {}", t.toString());
            }
        }
        String message = "[TacLight] kit given: flashlight" + (extra > 0 ? " + HK416D + gun_light" : "");
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static boolean tryAdd(net.minecraft.world.entity.player.Player player, String ns, String path,
                                  java.util.function.Consumer<ItemStack> configure) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ns, path));
        if (item == null) {
            return false;
        }
        ItemStack stack = new ItemStack(item);
        configure.accept(stack);
        player.getInventory().add(stack);
        return true;
    }

    private TacLightCommand() {}
}

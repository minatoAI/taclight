package dev.taclight.client;

import dev.taclight.TacLightMod;
import dev.taclight.item.FlashlightItem;
import dev.taclight.registry.ModItems;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 物品模型属性注册器(2026-10-06 用户需求:手电筒开/关图标要不一样)。
 *
 * <p><b>要做什么</b>:注册一个物品模型谓词 {@code taclight:on},值 = 手上那支电筒的开关状态,
 * 由 {@code models/item/flashlight.json} 的 {@code overrides} 消费 —— 开启时换用
 * {@code flashlight_on} 模型(贴图在灯头前加了一束光锥)。</p>
 *
 * <p><b>为什么单列一个顶层类</b>:与 {@link KeyBindingsModBus} 同因 —— 订阅类一律顶层
 * (见 {@code SubscriberScopeContract});并且注册成功必须留下<b>可观测的一行日志</b>:
 * "代码写好了" ≠ "代码被执行了"(task-34 的原话)。</p>
 *
 * <p><b>真源唯一</b>:谓词直接读 {@link FlashlightItem#isOn(net.minecraft.world.item.ItemStack)},
 * 与开灯判定同源。若在这里另写一份"NBT 里有没有某个键"的判断,就会出现
 * "灯亮了但图标没变"(或反过来)这种没有任何报错的漂移。</p>
 *
 * <p><b>静默失效的三处配对</b>(任一写错都不会有任何报错,只会图标不变):
 * ① 这里的属性 id {@code taclight:on} ↔ ② {@code models/item/flashlight.json} 的
 * {@code predicate} 键 ↔ ③ {@code flashlight_on} 模型/贴图文件。
 * {@code ItemIconStateContract} 把三处一起钉住。</p>
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ItemModelModBus {
    /** 模型谓词名(与模型 JSON 的 predicate 键必须逐字一致)。 */
    public static final String ON_PROPERTY = "on";

    private ItemModelModBus() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(ModItems.FLASHLIGHT.get(),
                    new ResourceLocation(TacLightMod.MODID, ON_PROPERTY),
                    (stack, level, entity, seed) -> FlashlightItem.isOn(stack) ? 1.0F : 0.0F);
            TacLightMod.LOGGER.info("[TacLight] item property register: {}:{} on flashlight (icon on/off)",
                    TacLightMod.MODID, ON_PROPERTY);
        });
    }
}

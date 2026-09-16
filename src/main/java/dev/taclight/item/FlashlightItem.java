package dev.taclight.item;

import dev.taclight.TacLightMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 手电筒物品(基础类,始终可注册)。
 * 若运行时存在 Oculus(Iris Forge 移植),create() 会返回实现
 * IrisItemLightProvider 的 FlashlightItemIris 变体 —— 手持时向光影包提供光源
 * (heldBlockLightValue / heldBlockLightColor),由光影包渲染锥形聚光灯;
 * 无 Oculus 时退化为纯装饰物品(不崩溃)。
 */
public class FlashlightItem extends Item {
    public FlashlightItem(Properties properties) {
        super(properties);
    }

    /** 工厂:按能力探测选择实现,保证无 Oculus 环境可用。 */
    public static Item create() {
        if (isIrisApiPresent()) {
            try {
                return (Item) Class.forName("dev.taclight.item.FlashlightItemIris")
                        .getConstructor().newInstance();
            } catch (Throwable t) {
                TacLightMod.LOGGER.warn("[TacLight] Iris light provider unavailable, degraded: {}", t.toString());
            }
        } else {
            TacLightMod.LOGGER.info("[TacLight] Oculus/Iris not detected: flashlight runs in plain-item mode");
        }
        return new FlashlightItem(new Item.Properties());
    }

    public static boolean isIrisApiPresent() {
        try {
            Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.taclight.flashlight.tip"));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}

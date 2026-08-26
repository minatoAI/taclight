package dev.taclight.item;

import dev.taclight.client.ClientLightState;
import net.irisshaders.iris.api.v0.item.IrisItemLightProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/**
 * Oculus/Iris 物品光源变体:实现官方 IrisItemLightProvider。
 * 手持时 Iris 把这些值暴露给光影包:heldBlockLightValue / heldBlockLightColor。
 * 开关 = 客户端本地状态(零网络包);关闭时返回 0 光强,锥形光立即消失。
 */
public class FlashlightItemIris extends FlashlightItem implements IrisItemLightProvider {
    public FlashlightItemIris() {
        super(new Item.Properties());
    }

    @Override
    public int getLightEmission(Player player, ItemStack stack) {
        return ClientLightState.isOn() ? 15 : 0;
    }

    @Override
    public Vector3f getLightColor(Player player, ItemStack stack) {
        // 暖白战术灯色(线性 RGB)
        return new Vector3f(1.0f, 0.96f, 0.88f);
    }
}

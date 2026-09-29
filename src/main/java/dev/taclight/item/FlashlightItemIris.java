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
        // 2026-09-04 用户体感:15 级氛围光太强(火把感)→5 级暖底基本消失(1/81)→10 级折中
        // ((10/15)^4≈1/5,暖氛围保留但收敛);锥形主光走 SSBO 通道不受此值影响。
        // 持物门生效值(2026-09-25):本接口虽由"手持该物品"的上下文调用,仍统一走同一个门,
        // 避免两条路径对"灯到底亮不亮"给出不同答案。
        return ClientLightState.handheldEffective() ? 10 : 0;
    }

    @Override
    public Vector3f getLightColor(Player player, ItemStack stack) {
        // 暖白战术灯色(线性 RGB)
        return new Vector3f(1.0f, 0.96f, 0.88f);
    }
}

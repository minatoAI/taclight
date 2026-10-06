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

    // ------------------------------------------------------------------------
    // 开关状态 = **该手电筒自己的属性**(用户 2026-10-04 定案)
    //
    // 用户原话:"开关应该是一个类似于标签一样的东西:我手里拿了很多个手电筒,每一个手电筒
    // 的开关状态都应该是针对每个手电筒的,是它自己的一个属性,而不是角色自身的一个开关。"
    // 旧设计是 ClientLightState 里一个**客户端全局** static + "离手自动关"(autoClear),
    // 于是切走再切回来开关就没了,还得再按一次。
    //
    // 现规则(契约 FlashlightSwitchContract 钉死):
    //   * 状态存在 **ItemStack 自己的 NBT**(键 TAG_ON);
    //   * **缺标签 = 关** —— 与 ClientLightState 的初始值一致(新拿到的电筒不自己亮;
    //     "拿到还要先开一下"这条抱怨随之消失);
    //   * **显式写 false = 关**,且**不再被"离手"清掉** ⇒ 每支电筒各记各的。
    // ------------------------------------------------------------------------

    /** 开关状态标签键(per-ItemStack)。规则层在 {@link FlashlightSwitch}(不继承 Item,可离线判定)。 */
    public static final String TAG_ON = FlashlightSwitch.TAG_ON;

    /** 纯函数(离线契约钉死,零注册表依赖):**缺标签 = 关;有标签 = 该值**(2026-10-06 改判)。 */
    public static boolean resolveTag(boolean hasKey, boolean value) {
        return FlashlightSwitch.resolveTag(hasKey, value);
    }

    /** 存储层(纯 NBT)。 */
    public static boolean isOnTag(net.minecraft.nbt.CompoundTag tag) {
        return FlashlightSwitch.isOnTag(tag);
    }

    /** 存储层写入(纯 NBT)。 */
    public static void setOnTag(net.minecraft.nbt.CompoundTag tag, boolean on) {
        FlashlightSwitch.setOnTag(tag, on);
    }

    /** 该手电筒自己的开关状态。非手电筒/空堆返回 false。 */
    public static boolean isOn(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return FlashlightSwitch.isOnTag(stack.getTag());
    }

    /** 置位该手电筒自己的开关(写进物品自己的 NBT;不动任何全局状态)。 */
    public static void setOn(ItemStack stack, boolean on) {
        if (stack == null || stack.isEmpty()) return;
        FlashlightSwitch.setOnTag(stack.getOrCreateTag(), on);
    }

    /** 翻转该手电筒自己的开关,返回翻转后的状态。 */
    public static boolean toggleOn(ItemStack stack) {
        boolean next = !isOn(stack);
        setOn(stack, next);
        return next;
    }

    /**
     * 手上(主手优先,其次副手)的那支手电筒;没拿返回 {@link ItemStack#EMPTY}。
     * 注册表未就绪等异常一律视为"未持有":宁可不亮,不误亮(与旧 holdingFlashlight 同约定)。
     */
    public static ItemStack heldStack(net.minecraft.world.entity.player.Player p) {
        if (p == null) return ItemStack.EMPTY;
        try {
            Item item = dev.taclight.registry.ModItems.FLASHLIGHT.get();
            if (p.getMainHandItem().is(item)) return p.getMainHandItem();
            if (p.getOffhandItem().is(item)) return p.getOffhandItem();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.taclight.flashlight.tip"));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}

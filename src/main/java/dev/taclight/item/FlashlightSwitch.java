package dev.taclight.item;

import net.minecraft.nbt.CompoundTag;

/**
 * 手电筒开关状态的**规则层**(用户 2026-10-04 定案)。
 *
 * <p>为什么单独一个类、而不是写在 {@link FlashlightItem} 里:
 * 契约是**无游戏环境**跑的 JVM。{@code FlashlightItem extends Item},
 * 而 {@code Item} 实现 {@code FeatureElement} ⇒ 只要引用 {@code FlashlightItem}
 * 就会触发 {@code FeatureElement.<clinit>} → {@code Registries.<clinit>} →
 * {@code Bootstrap.checkBootstrapCalled} 抛异常(本轮实测踩到,"java=1"那类假红之后
 * 又一个"看起来像判据坏了、其实是环境不允许"的例子)。
 * 把纯规则放在**不继承任何 Item 的类**里,规则本身就能被契约真往返。</p>
 *
 * <p>规则(契约 {@code HandheldGateContract} 钉死):
 * <b>缺键 = 开</b>(等价旧默认 {@code handheldOn=true}:新拿到的电筒直接亮),
 * <b>有键 = 该值</b>(显式 false 才是"这支关了")。</p>
 */
public final class FlashlightSwitch {
    /** 开关状态标签键(per-ItemStack)。 */
    public static final String TAG_ON = "taclight_on";

    private FlashlightSwitch() {}

    /** 纯函数:缺键 = 开;有键 = 该值。 */
    public static boolean resolveTag(boolean hasKey, boolean value) {
        return hasKey ? value : true;
    }

    /** 存储层读(纯 NBT,null 按"缺键"处理)。 */
    public static boolean isOnTag(CompoundTag tag) {
        boolean has = tag != null && tag.contains(TAG_ON);
        return resolveTag(has, has && tag.getBoolean(TAG_ON));
    }

    /** 存储层写(纯 NBT)。 */
    public static void setOnTag(CompoundTag tag, boolean on) {
        if (tag == null) return;
        tag.putBoolean(TAG_ON, on);
    }
}

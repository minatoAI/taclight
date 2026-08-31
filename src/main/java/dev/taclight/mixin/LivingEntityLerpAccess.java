package dev.taclight.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 位置链快照目标只读访问(09-01 深夜③,RemotePosSnap 用)。
 * lerpX/Y/Z = 客户端同步目标(服务器权威 20Hz 序列,protected 字段,1.20.2 前无公开
 * lerpTargetX)—— 位置闪烁修复的信号真源,离线回放已否决"3ΔX 重建"路线(噪声放大 3 倍)。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityLerpAccess {
    @Accessor("lerpX")
    double taclight$lerpX();

    @Accessor("lerpY")
    double taclight$lerpY();

    @Accessor("lerpZ")
    double taclight$lerpZ();
}

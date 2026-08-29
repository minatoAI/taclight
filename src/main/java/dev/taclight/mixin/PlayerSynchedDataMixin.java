package dev.taclight.mixin;

import dev.taclight.sync.PlayerLightAccess;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M5 多人灯状态:给 Player 的 SynchedEntityData 追加两个 boolean(手持/枪灯)。
 * 在公共 mixins 列表(不走 TaCZ 门控,见 TacLightMixinPlugin 旁路)——
 * 多人同步是核心功能,不依赖 TaCZ。
 *
 * 实机坑(2026-08-30):getEntityData() 声明在父类 Entity,Player 字节码里
 * 没有——@Shadow 无法定位继承成员("was not located in the target class"),
 * mixin 应用即崩。改用 Entity 接口 cast 调用,不经 Shadow。
 */
@Mixin(Player.class)
public abstract class PlayerSynchedDataMixin {

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void taclight$defineLightFlags(CallbackInfo ci) {
        SynchedEntityData data = ((Entity) (Object) this).getEntityData();
        data.define(PlayerLightAccess.FLASHLIGHT, false);
        data.define(PlayerLightAccess.GUNLIGHT, false);
        PlayerLightAccess.syncReady = true;
    }
}

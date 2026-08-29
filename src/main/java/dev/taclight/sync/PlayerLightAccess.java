package dev.taclight.sync;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.player.Player;

/**
 * 多人灯状态真源(M5,docs/旁观视角与多人调试方案.md §4):
 * 两个 boolean 挂在 Player 的 SynchedEntityData 上,由原版实体数据同步机制
 * 自动广播给所有可见该玩家的客户端——读侧任意客户端,写侧仅服务端。
 * define 由 {@code PlayerSynchedDataMixin}(mixin json 公共列表,TaCZ 门控旁路)注入。
 * syncReady 未置位(mixin 未应用,例如异常环境)时读恒 false,渲染优雅降级。
 */
public final class PlayerLightAccess {
    public static final EntityDataAccessor<Boolean> FLASHLIGHT =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Boolean> GUNLIGHT =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);

    /** 由 mixin 注入点置位;契约守护:mixin 必须在 json 公共列表且不被 TaCZ 门控。 */
    public static volatile boolean syncReady = false;

    public static boolean flashlight(Player p) {
        return read(p, FLASHLIGHT);
    }

    public static boolean gunLight(Player p) {
        return read(p, GUNLIGHT);
    }

    /** 服务端写侧(命令/C2S 包);原版自动同步。 */
    public static void setHandheld(Player p, boolean on) {
        p.getEntityData().set(FLASHLIGHT, on);
    }

    /** 服务端写侧:枪灯。 */
    public static void setGun(Player p, boolean on) {
        p.getEntityData().set(GUNLIGHT, on);
    }

    private static boolean read(Player p, EntityDataAccessor<Boolean> key) {
        if (!syncReady) return false;
        try {
            return p.getEntityData().get(key);
        } catch (Throwable t) {
            // 未 define 的 accessor 会抛异常(降级为无灯,不毒化渲染帧)
            return false;
        }
    }

    private PlayerLightAccess() {}
}

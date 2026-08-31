package dev.taclight.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 09-01 灯态持久化(用户 bug:退出时灯开着,重进游戏灯没了,要重新开关一次才亮)。
 *
 * <p>根因:灯开关真源挂 {@link PlayerLightAccess} 的 SynchedEntityData(实体实例字段),
 * 重进游戏时服务端重建玩家实体 → 数据回落原版默认 false,此前没有任何持久化层。</p>
 *
 * <p>方案:写口收敛在 {@code TacLightNetwork.serverApply}(唯一服务端写入口,C2S 与
 * /taclight 命令都经它)——每次写实体数据的同时落玩家 Forge 持久化子树
 * ({@code Player.PERSISTED_NBT_TAG}:跨 relog 保存、且跨死亡克隆);登录事件把
 * 旗标重放回 serverApply,实体数据与 SyncLightS2C 回包一并恢复(本人客户端
 * 本地状态经既有回包路径自动跟随,零新增网络包)。</p>
 *
 * <p>NBT 读写为纯函数(CompoundTag 直操),契约离线钉死;读侧仅认 persisted 子树,
 * 不被他模组同形裸键误触发。</p>
 */
@Mod.EventBusSubscriber(modid = dev.taclight.TacLightMod.MODID)
public final class LightStatePersistence {
    static final String KEY_HANDHELD = "taclight:handheld";
    static final String KEY_GUN = "taclight:gun";

    public record LightFlags(boolean present, boolean handheld, boolean gun) {}

    /** 纯函数:把灯态写入 Forge 持久化子树(跨 relog 且跨死亡克隆)。 */
    public static void saveTo(CompoundTag forgeData, boolean handheld, boolean gun) {
        CompoundTag persisted = forgeData.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putBoolean(KEY_HANDHELD, handheld);
        persisted.putBoolean(KEY_GUN, gun);
        forgeData.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** 纯函数:读回;从未写过(present=false)时不恢复,保持原版默认关。 */
    public static LightFlags readFrom(CompoundTag forgeData) {
        CompoundTag persisted = forgeData.getCompound(Player.PERSISTED_NBT_TAG);
        if (!persisted.contains(KEY_HANDHELD) && !persisted.contains(KEY_GUN)) {
            return new LightFlags(false, false, false);
        }
        return new LightFlags(true, persisted.getBoolean(KEY_HANDHELD), persisted.getBoolean(KEY_GUN));
    }

    /** 服务端胶水:登录时重放持久化灯态(serverApply 一并恢复实体数据与本人客户端本地状态)。 */
    public static void restoreOnLogin(ServerPlayer player) {
        LightFlags f = readFrom(player.getPersistentData());
        if (f.present()) {
            dev.taclight.network.TacLightNetwork.serverApply(player, f.handheld(), f.gun(), "login-restore");
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && !sp.level().isClientSide()) {
            restoreOnLogin(sp);
        }
    }

    private LightStatePersistence() {}
}

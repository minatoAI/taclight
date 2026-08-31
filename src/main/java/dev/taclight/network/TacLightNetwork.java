package dev.taclight.network;

import dev.taclight.TacLightMod;
import dev.taclight.sync.PlayerLightAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * M5 多人灯状态网络通道(taclight:main):
 * - SetLightC2S:客户端把自己的开关上报纸服务端(L 键 / 枪灯探针状态变化);
 * - SyncLightS2C:服务端改完实体数据后回发受影响玩家,令其本地 ClientLightState
 *   跟随服务端真源(命令改状态时本地渲染同步,避免"命令关了灯本地还亮")。
 * 实体数据本身由原版同步给其他玩家;这两个包只服务"本人客户端的本地状态一致"。
 * encode/decode 为纯静态函数,契约测试直接构造 FriendlyByteBuf 回环验证。
 */
public final class TacLightNetwork {
    private static final String PROTOCOL = "1";
    private static SimpleChannel channel;
    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(TacLightMod.MODID, "main"),
                () -> PROTOCOL,
                // 坑31(08-30,M5 首测):quickPlayMultiplayer/直连不做 status ping,客户端拿不到
                // FML 标志会把 Forge 服务器误判成 vanilla(HandshakeHandler "vanilla impl"),
                // 我方通道以 "ABSENT" 参与协商 → 旧谓词 PROTOCOL::equals 拒绝 → 登录 2s 静默断线。
                // 本 mod 的同步本就可降级(缺对端通道时灯仍是本地行为),标准做法 = acceptMissingOr。
                NetworkRegistry.acceptMissingOr(PROTOCOL),
                NetworkRegistry.acceptMissingOr(PROTOCOL));
        int id = 0;
        channel.registerMessage(id++, SetLightC2S.class, SetLightC2S::encode, SetLightC2S::decode, SetLightC2S::handle);
        channel.registerMessage(id++, SyncLightS2C.class, SyncLightS2C::encode, SyncLightS2C::decode, SyncLightS2C::handle);
        TacLightMod.LOGGER.info("[TacLight] NET channel registered ({} packets)", id);
    }

    /** 客户端发送:把本地开关上报(失败静默,单人/未连接时灯仍是本地行为)。 */
    public static void sendSetLight(boolean handheld, boolean gun) {
        try {
            if (channel != null) {
                channel.sendToServer(new SetLightC2S(handheld, gun));
            }
        } catch (Throwable ignored) {}
    }

    /** 服务端:改实体数据 + 回发 S2C 给受影响玩家(其本地状态跟随真源)。
     *  09-01:同时落玩家持久化 NBT(LightStatePersistence)——重进游戏由登录事件重放,
     *  否则实体实例重建后开关回落默认关(用户实测 bug:重进后灯没了)。 */
    public static void serverApply(ServerPlayer target, boolean handheld, boolean gun, String via) {
        PlayerLightAccess.setHandheld(target, handheld);
        PlayerLightAccess.setGun(target, gun);
        dev.taclight.sync.LightStatePersistence.saveTo(target.getPersistentData(), handheld, gun);
        if (channel != null) {
            // Forge 1.20.1 SimpleChannel.send 的签名是 (PacketTarget, MSG)——目标在前
            channel.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> target),
                    new SyncLightS2C(handheld, gun));
        }
        TacLightMod.LOGGER.info("[TacLight] LIGHT-SYNC {} handheld={} gun={} ({})",
                target.getGameProfile().getName(), handheld, gun, via);
    }

    // ---- 包定义 ----

    public record SetLightC2S(boolean handheld, boolean gun) {
        public static void encode(SetLightC2S msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.handheld);
            buf.writeBoolean(msg.gun);
        }

        public static SetLightC2S decode(FriendlyByteBuf buf) {
            return new SetLightC2S(buf.readBoolean(), buf.readBoolean());
        }

        public static void handle(SetLightC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender == null || sender.level().isClientSide()) return;
                TacLightNetwork.serverApply(sender, msg.handheld(), msg.gun(), "c2s");
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record SyncLightS2C(boolean handheld, boolean gun) {
        public static void encode(SyncLightS2C msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.handheld);
            buf.writeBoolean(msg.gun);
        }

        public static SyncLightS2C decode(FriendlyByteBuf buf) {
            return new SyncLightS2C(buf.readBoolean(), buf.readBoolean());
        }

        public static void handle(SyncLightS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    dev.taclight.client.ClientPacketHandlers.applyServerLightState(msg.handheld(), msg.gun()));
            ctx.get().setPacketHandled(true);
        }
    }

    private TacLightNetwork() {}
}

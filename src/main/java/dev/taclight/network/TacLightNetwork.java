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
 * - SetLightC2S:客户端把自己的开关上报纸服务端(开灯键 / 枪灯探针状态变化);
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
        // 2026-10-04 R55:per-item 开关的**服务端权威**通道(见 serverApplyItemLight 的注释)。
        channel.registerMessage(id++, ToggleItemLightC2S.class, ToggleItemLightC2S::encode,
                ToggleItemLightC2S::decode, ToggleItemLightC2S::handle);
        TacLightMod.LOGGER.info("[TacLight] NET channel registered ({} packets)", id);
    }

    /**
     * 客户端发送:把"**这一手**的开关置位"的**意图**上报服务端(2026-10-04 R55)。
     *
     * <p>为什么必须有这条(用户实测 + R54 真机复现,{@code BACKLOG §2.164/§2.165}):
     * 旧实现只在**客户端**那份 ItemStack 上写 {@code taclight_on},而 {@code sendSetLight} 只带两个布尔 ⇒
     * **服务端权威副本(与存档)里从来没有这个标签** ⇒ 任何一次槽同步/容器/维度/重进都会把它抹掉,
     * 用户看到的就是"开了 → 切走 → 切回自己关了"。R54 用服务端读数
     * {@code /data get entity <player> SelectedItem} 实测:真按 L 之后服务端那份**仍无 tag**。</p>
     */
    public static void sendToggleItemLight(boolean offhand, boolean on) {
        try {
            if (channel != null) {
                channel.sendToServer(new ToggleItemLightC2S(offhand, on));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 服务端:把开关写进**该手那份 ItemStack 自己的 NBT**(权威),再让原版库存同步把它回给客户端。
     *
     * @return true = 确实写进了一支手电筒;false = 该手不是手电筒(或注册表未就绪)⇒ 什么都不做
     */
    public static boolean serverApplyItemLight(ServerPlayer p, boolean offhand, boolean on) {
        if (p == null) return false;
        net.minecraft.world.item.ItemStack st = offhand ? p.getOffhandItem() : p.getMainHandItem();
        if (st == null || st.isEmpty()) return false;
        try {
            if (!st.is(dev.taclight.registry.ModItems.FLASHLIGHT.get())) return false;
        } catch (Throwable t) {
            return false;
        }
        dev.taclight.item.FlashlightItem.setOn(st, on);
        // 权威回写:让客户端那份跟着变(否则客户端显示与真源分叉)
        try {
            p.inventoryMenu.broadcastChanges();
        } catch (Throwable ignored) {}
        TacLightMod.LOGGER.info("[TacLight] ITEM-LIGHT {} {} -> {} (server-authoritative)",
                p.getGameProfile().getName(), offhand ? "offhand" : "main", on ? "ON" : "OFF");
        return true;
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

    /**
     * C2S:**这一手**的开关置位意图(2026-10-04 R55)。
     *
     * <p>只带"哪只手 + 目标状态",**不带槽号** —— 因为 R54 实测发现注入/切槽后
     * 客户端的选中槽与服务端可能不一致({@code BACKLOG §2.165 ④});按"手"解析由**服务端**自己做,
     * 与服务端那份权威 ItemStack 永远自洽。</p>
     */
    public record ToggleItemLightC2S(boolean offhand, boolean on) {
        public static void encode(ToggleItemLightC2S msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.offhand);
            buf.writeBoolean(msg.on);
        }

        public static ToggleItemLightC2S decode(FriendlyByteBuf buf) {
            return new ToggleItemLightC2S(buf.readBoolean(), buf.readBoolean());
        }

        public static void handle(ToggleItemLightC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender == null || sender.level().isClientSide()) return;
                TacLightNetwork.serverApplyItemLight(sender, msg.offhand(), msg.on());
            });
            ctx.get().setPacketHandled(true);
        }
    }

    private TacLightNetwork() {}
}

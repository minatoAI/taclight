package dev.taclight.client;

/**
 * S2C 包的客户端处理(独立类:避免网络包类在服务端被加载时拖入客户端类型)。
 */
public final class ClientPacketHandlers {
    /** 服务端真源 → 本地渲染状态跟随(命令改灯时,本人客户端同步)。 */
    public static void applyServerLightState(boolean handheld, boolean gun) {
        ClientLightState.setHandheld(handheld);
        ClientLightState.setGunLight(gun);
        dev.taclight.TacLightMod.LOGGER.info("[TacLight] LIGHT-SYNC-ACK handheld={} gun={}", handheld, gun);
    }

    private ClientPacketHandlers() {}
}

package dev.taclight.client;

/**
 * S2C 包的客户端处理(独立类:避免网络包类在服务端被加载时拖入客户端类型)。
 */
public final class ClientPacketHandlers {
    /** 服务端真源 → 本地渲染状态跟随(命令改灯时,本人客户端同步)。
     *  枪灯手动覆写(!gun)优先:手动后服务端回显不再覆盖本地枪灯状态,
     *  否则实机"开关切换没有变化"(手动 on 当 tick 即被回显/S2C 覆盖回 off)。 */
    public static void applyServerLightState(boolean handheld, boolean gun) {
        ClientLightState.setHandheld(handheld);
        if (!ClientLightState.gunManual()) {
            ClientLightState.setGunLight(gun);
        }
        dev.taclight.TacLightMod.LOGGER.info("[TacLight] LIGHT-SYNC-ACK handheld={} gun={}", handheld, gun);
    }

    private ClientPacketHandlers() {}
}

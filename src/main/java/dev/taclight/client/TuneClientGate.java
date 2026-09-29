package dev.taclight.client;

import dev.taclight.tune.TunePersist;

/**
 * tune voxel 的客户端门(2026-09-19)。
 *
 * <p>为什么单列一类:{@code VoxelGrid} 引用 {@code net.minecraft.client.Minecraft},
 * 专用服务器上加载即炸({@code NoClassDefFoundError})。服务端命令类
 * ({@code TacLightCommand})常驻两端,故它<b>绝不</b>直引 VoxelGrid——专用服走 MP 守卫
 * 提前返回,本类永不加载;集成服(SP/LAN 主机)才经此门调用。JVM 契约不碰本类
 * (走 {@link TunePersist.VoxelGate} 假门)。</p>
 *
 * <p>严格性补齐:{@code VoxelGrid.configure} 对任意串都回状态(含拼错也静默成功);
 * tune 路径先白名单 {@code on/off/status/空},其它走 {@code bad arg} 错误(契约钉死)。</p>
 */
public final class TuneClientGate {
    private TuneClientGate() {}

    /** 生产 Gate(命令层与恢复钩子用)。 */
    public static final TunePersist.VoxelGate GATE = new TunePersist.VoxelGate() {
        @Override
        public String apply(String arg) {
            return applyVoxel(arg);
        }

        @Override
        public String status() {
            return VoxelGrid.status();
        }

        @Override
        public void restore(boolean on) {
            VoxelGrid.configure(on ? "on" : "off");
        }
    };

    /** 白名单校验 + 转调;非法返回 "bad arg ..."(调用方判错,不写盘)。 */
    public static String applyVoxel(String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.isEmpty() || a.equals("status") || a.equals("on") || a.equals("off")) {
            return VoxelGrid.configure(a.isEmpty() ? "status" : a);
        }
        return "bad arg " + arg + " (want on/off/status)";
    }
}

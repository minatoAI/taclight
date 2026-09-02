package dev.taclight.client;

/**
 * TP 枪灯回退模式 A/B 旋钮(!tpfb,2026-09-02 屏外连续性):
 * blend = hold+连续混合(默认,新行为);hard = 旧二元回退(无 hold 无混合),
 * 供跳变定量对照 —— 与 !voxel/!psnap 同族的消融旋钮,契约 TpFallbackControlContract。
 */
public final class TpFallbackControl {
    private static volatile boolean blended = true;

    private TpFallbackControl() {}

    public static boolean blended() {
        return blended;
    }

    public static String configureFallback(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "tpfb=" + (blended ? "blend(hold+连续混合)" : "hard(旧二元回退)");
        }
        switch (arg) {
            case "blend" -> {
                blended = true;
                return "tpfb->blend(屏外 hold+连续混合)";
            }
            case "hard" -> {
                blended = false;
                return "tpfb->hard(旧二元回退,对照)";
            }
            default -> {
                return "无法解析 '" + arg + "' (用法: !tpfb <blend|hard>)";
            }
        }
    }
}

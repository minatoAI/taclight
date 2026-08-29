package dev.taclight.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * 多人灯收集选择器(M5,docs/旁观视角与多人调试方案.md §4.3,纯函数可契约测试):
 * 输入 = 候选玩家灯(世界坐标 + 设备开关);输出 = 距离 ≤ maxDist 的候选中,
 * 按到相机距离升序的至多 maxLights 个(就近保留;同距离保持输入序 = 稳定排序)。
 * SSBO 硬上限 8(LightBuffer/GLSL 两侧一致),cap 由 config 提供,此处只做选择不裁剪写入。
 */
public final class MultiLightCollector {

    /** 候选:一个远程玩家(eye 世界坐标 + 两个设备开关)。index = 输入序,回传用。 */
    public record Candidate(int index, double x, double y, double z, boolean handheld, boolean gun) {}

    /** 选中:输入序 index + 该玩家要展开的设备。 */
    public record Selected(int index, boolean handheld, boolean gun) {}

    public static List<Selected> select(List<Candidate> players,
                                        double camX, double camY, double camZ,
                                        double maxDist, int maxLights) {
        if (players == null || players.isEmpty() || maxLights <= 0) return List.of();
        double maxSq = maxDist * maxDist;
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : players) {
            double dx = c.x() - camX, dy = c.y() - camY, dz = c.z() - camZ;
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq > maxSq) continue;
            if (!c.handheld() && !c.gun()) continue;   // 无设备:不占名额
            insertSorted(kept, c, camX, camY, camZ);
        }
        List<Selected> out = new ArrayList<>(Math.min(kept.size(), maxLights));
        for (int i = 0; i < kept.size() && i < maxLights; i++) {
            Candidate c = kept.get(i);
            out.add(new Selected(c.index(), c.handheld(), c.gun()));
        }
        return out;
    }

    /** 按到相机距离升序的稳定插入(等距 = 后来者排后,保持输入序)。 */
    private static void insertSorted(List<Candidate> kept, Candidate c,
                                     double camX, double camY, double camZ) {
        double dSq = sq(c.x() - camX, c.y() - camY, c.z() - camZ);
        int pos = kept.size();
        while (pos > 0 && sq(kept.get(pos - 1).x() - camX, kept.get(pos - 1).y() - camY,
                kept.get(pos - 1).z() - camZ) > dSq) {
            pos--;
        }
        kept.add(pos, c);
    }

    private static double sq(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    private MultiLightCollector() {}
}

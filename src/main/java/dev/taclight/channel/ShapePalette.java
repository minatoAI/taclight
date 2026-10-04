package dev.taclight.channel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * <b>形状调色板</b>(2026-10-03 R21):把"每格一个 4bit 类别码"升级成"码指向一组格内盒"。
 *
 * <p><b>为什么需要它(有实测依据,不是拍脑袋)</b>:16 档基础码里能带形状的只有
 * "占满 XZ 足印的水平薄板"(4..15)与"整格"(3),其余只剩整格均匀衰减(VEG/LEAF)与
 * 完全不挡(EMPTY)—— 全都丢掉了水平足印。对真实注册表穷举(契约
 * {@code VoxelShapePaletteContract})实测:<b>24135 个方块状态里有 19424(80.5%)的遮挡形
 * 无法被 16 档码精确表达</b>,覆盖 676 种不同形状、482 种方块(楼梯/栅栏/墙/门/告示牌/
 * 红石线/火/活门/脚手架…)。故把格子加宽到 8bit,码 {@code 16+slot} 指向本表。</p>
 *
 * <p><b>规范化与去重</b>:盒坐标按 {@value #QUANT} 分之一量化(原版形状全落 1/16 网格,
 * 量化后<b>逐位精确</b>),每盒 6 个整数、行内按字典序排序后拼接 —— <b>同一组盒必得同一个键,
 * 与盒的先后顺序无关</b>。零体积盒(任一轴 min≥max)直接丢弃:它不可能遮挡任何射线,
 * 丢弃是精确的,不是近似。</p>
 *
 * <p><b>有界性与降级</b>:容量 {@value #MAX_SLOTS} 槽、单形状 {@value #MAX_BOXES} 盒。
 * 超容量 / 超盒数 / 全是零体积盒 ⇒ {@code slotFor} 返回 -1,调用方<b>退回基础码</b>
 * (即今天的行为)。降级有上界、可判定,不会崩、不会错位。容量是<b>每帧局部</b>的:
 * 调色板每帧按当前盒里实际出现的形状重建(盒是 128³,装不下全局 676 种形状)。</p>
 *
 * <p>纯 JVM 可测(零 MC 依赖)。上传契约见 {@link SpotlightBufferLayout} 的 voxPalMeta /
 * voxPalBox;GLSL 消费见 {@code pack/shaders/lib/taclight_core.glsl} 的
 * {@code taclight_vox_transmit} 与 {@code TemplateLibrary.FORWARD_VOX_STUB}(两条路径必须镜像)。</p>
 */
public final class ShapePalette {
    /** 槽数上限(= 8bit 码域 16..255)。 */
    public static final int MAX_SLOTS = 240;
    /** 单形状盒数上限(实测 8 盒覆盖 676 种形状里的 670 种 = 99.11%)。 */
    public static final int MAX_BOXES = 8;
    /** 每盒 float 数:[minX,minY,minZ,maxX,maxY,maxZ]。 */
    public static final int FLOATS_PER_BOX = 6;
    /**
     * 每槽 float 数:[盒数, 并集盒(6), 盒0(6), 盒1(6), …]。
     * <p>并集盒是给<b>建表路径</b>用的保守代理({@code taclight_vox_hit_dist} 每帧要按
     * 512×256 个方向各走一遍 DDA,逐盒展开会把成本乘上 8)⇒ 表用"盒集合的包围盒"一次判定,
     * 方向一致偏暗(宁可误挡不可漏光),逐采样路径仍逐盒精确。</p>
     */
    public static final int SLOT_STRIDE = 1 + FLOATS_PER_BOX + MAX_BOXES * FLOATS_PER_BOX;   // 55
    /** 调色板 float 总数(= 上传区字节数 / 4)。 */
    public static final int TOTAL_FLOATS = MAX_SLOTS * SLOT_STRIDE;         // 13200
    /** 槽内第一个真实盒的偏移(跳过分量 0 的盒数、分量 1..6 的并集盒)。 */
    public static final int SLOT_BOX0 = 1 + FLOATS_PER_BOX;                  // 7
    /** 并集盒在槽内的起始偏移。 */
    public static final int SLOT_UNION = 1;
    /** 坐标量化分母(格内 0..1 → 0..QUANT 整数)。 */
    public static final int QUANT = 256;

    private final float[] data = new float[TOTAL_FLOATS];
    private final Map<String, Integer> index = new HashMap<>();
    private int count;

    /** 每帧重建前调用:清空计数与去重表。<b>不擦 data</b> —— 槽只读 {@code count} 以内。 */
    public void clear() {
        count = 0;
        index.clear();
    }

    /** 已用槽数(写进 SSBO voxPalMeta.x;0 = 本帧无调色板)。 */
    public int count() {
        return count;
    }

    /** 上传用底层数组(只读 {@code count()*SLOT_STRIDE} 个 float)。 */
    public float[] data() {
        return data;
    }

    /**
     * 取(必要时新建)该形状的槽号;超容量 / 超盒数 / 无有效盒 ⇒ -1。
     *
     * @param boxes 格内盒列表,平铺 6 float/盒(0..1 局部坐标)
     * @param n     盒数
     */
    public int slotFor(float[] boxes, int n) {
        if (n <= 0) return -1;
        int[] q = new int[n * FLOATS_PER_BOX];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS_PER_BOX;
            int x0 = quant(boxes[o]), y0 = quant(boxes[o + 1]), z0 = quant(boxes[o + 2]);
            int x1 = quant(boxes[o + 3]), y1 = quant(boxes[o + 4]), z1 = quant(boxes[o + 5]);
            if (x0 >= x1 || y0 >= y1 || z0 >= z1) continue;      // 零体积盒:丢弃(精确)
            int b = m * FLOATS_PER_BOX;
            q[b] = x0; q[b + 1] = y0; q[b + 2] = z0;
            q[b + 3] = x1; q[b + 4] = y1; q[b + 5] = z1;
            m++;
        }
        if (m == 0 || m > MAX_BOXES) return -1;
        String key = keyOf(q, m);
        Integer hit = index.get(key);
        if (hit != null) return hit;
        if (count >= MAX_SLOTS) return -1;
        int slot = count++;
        int base = slot * SLOT_STRIDE;
        data[base] = m;
        // 并集盒(表路径的保守代理):逐分量取 min/max
        for (int k = 0; k < FLOATS_PER_BOX; k++) data[base + SLOT_UNION + k] = q[k];
        for (int i = 1; i < m; i++) {
            int o = i * FLOATS_PER_BOX;
            for (int k = 0; k < 3; k++) {
                if (q[o + k] < data[base + SLOT_UNION + k]) data[base + SLOT_UNION + k] = q[o + k];
            }
            for (int k = 3; k < FLOATS_PER_BOX; k++) {
                if (q[o + k] > data[base + SLOT_UNION + k]) data[base + SLOT_UNION + k] = q[o + k];
            }
        }
        for (int k = 0; k < FLOATS_PER_BOX; k++) data[base + SLOT_UNION + k] /= (float) QUANT;
        for (int i = 0; i < m * FLOATS_PER_BOX; i++) {
            data[base + SLOT_BOX0 + i] = q[i] / (float) QUANT;
        }
        index.put(key, slot);
        return slot;
    }

    /** 规范化键:盒行按字典序排序后拼接(与盒顺序无关)。契约/诊断用。 */
    public static String keyOf(int[] q, int m) {
        String[] rows = new String[m];
        for (int i = 0; i < m; i++) {
            int b = i * FLOATS_PER_BOX;
            rows[i] = q[b] + " " + q[b + 1] + " " + q[b + 2] + " "
                    + q[b + 3] + " " + q[b + 4] + " " + q[b + 5];
        }
        Arrays.sort(rows);
        return String.join(";", rows);
    }

    /** float 盒列表 → 量化整数(供 {@link #keyOf} 与契约复用);无效盒保留原样(由 slotFor 丢弃)。 */
    public static int[] quantize(float[] boxes, int n) {
        int[] q = new int[n * FLOATS_PER_BOX];
        for (int i = 0; i < q.length; i++) q[i] = quant(boxes[i]);
        return q;
    }

    /** 槽内盒数(诊断/契约用)。 */
    public int boxesInSlot(int slot) {
        if (slot < 0 || slot >= count) return -1;
        return (int) data[slot * SLOT_STRIDE];
    }

    /** 槽内某盒的分量(格内 0..1;诊断/契约用)。 */
    public float boxComponent(int slot, int box, int component) {
        return data[slot * SLOT_STRIDE + SLOT_BOX0 + box * FLOATS_PER_BOX + component];
    }

    /** 槽内并集盒的分量(格内 0..1;表路径的保守代理,契约用)。 */
    public float unionComponent(int slot, int component) {
        return data[slot * SLOT_STRIDE + SLOT_UNION + component];
    }

    /** 格内 0..1 → 0..QUANT 整数(钳到 0..QUANT;四舍五入)。 */
    static int quant(float v) {
        int q = Math.round(v * QUANT);
        if (q < 0) q = 0;
        if (q > QUANT) q = QUANT;
        return q;
    }
}

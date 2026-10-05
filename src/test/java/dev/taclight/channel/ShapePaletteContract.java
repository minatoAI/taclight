package dev.taclight.channel;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>形状调色板契约</b>(2026-10-03 R21)。
 *
 * <p>钉死四件事,每件都配"能拦"的演示:</p>
 * <ol>
 *   <li><b>精确性口径</b>:{@link VoxelClassifier#baseCodeExpresses} 只对"单一盒 + 占满足印 +
 *       y 端点落 1/8 网格"放行 —— 半砖/雪层不占槽,楼梯/栅栏/|非 1/8 端点| 必须占槽。
 *       反例(阳性对照)同场给出。</li>
 *   <li><b>去重与规范化</b>:同一组盒换顺序 ⇒ 同槽;零体积盒丢弃(精确);全是零体积 ⇒ 不建槽。</li>
 *   <li><b>有界性与降级</b>:容量 240 槽、单形状 8 盒;超限一律返回 -1,且<b>不破坏</b>已有槽
 *       (降级是"退回基础码",不是"写坏数据")。</li>
 *   <li><b>布局与 GLSL 逐值对齐</b>:{@code SpotlightBufferLayout} 的偏移/步长/数组长度与
 *       {@code taclight_core.glsl} 的声明字面量、盒内偏移逐值一致(错一个 = 读到别的浮点,
 *       画面会以"随机假遮挡"的形式坏掉,而不是报错)。</li>
 * </ol>
 */
public final class ShapePaletteContract {
    private static int checks;
    private static int fails;

    public static void main(String[] args) throws Exception {
        baseCodeExactness();
        dedupAndCanonical();
        capacityAndDegrade();
        unionBox();
        quantization();
        glslLayout();
        System.out.println("ShapePaletteContract: " + (fails == 0 ? "ALL PASS" : "FAILED")
                + " (" + checks + " checks, fails=" + fails + ")");
        if (fails > 0) throw new AssertionError("ShapePaletteContract FAILED: " + fails + "/" + checks);
    }

    /** ① 精确性口径:哪些形状该占槽、哪些不该。 */
    private static void baseCodeExactness() {
        check(VoxelClassifier.baseCodeExpresses(slab(0f, 0.5f), 1),
                "半砖 [0,0.5] 占满 XZ + y=0 起 + 1/8 端点 ⇒ 基础码(薄板码 7)已精确,不占槽");
        check(VoxelClassifier.baseCodeExpresses(slab(0f, 0.125f), 1),
                "雪 1 层 [0,0.125] ⇒ 基础码精确(码 4),不占槽");
        check(VoxelClassifier.baseCodeExpresses(slab(0f, 1.0f), 1),
                "整格 [0,1] ⇒ 码 3 精确");
        check(VoxelClassifier.baseCodeExpresses(slab(0.5f, 1.0f), 1),
                "顶半砖 [0.5,1] ⇒ 码 12 精确");
        check(!VoxelClassifier.baseCodeExpresses(slab(0f, 0.1875f), 1),
                "端点 0.1875 不在 1/8 网格 ⇒ 薄板码会把顶高挪成 0.25 ⇒ 必须占槽");
        check(!VoxelClassifier.baseCodeExpresses(slab(0.25f, 1.0f), 1),
                "顶薄板底高 0.25 会被 slabCodeFor 的 eighths<4⇒4 挪成 0.5 ⇒ 必须占槽");
        check(!VoxelClassifier.baseCodeExpresses(new float[]{
                0f, 0f, 0f, 1f, 0.5f, 1f,   0f, 0.5f, 0f, 0.5f, 1f, 1f}, 2),
                "楼梯(两盒)⇒ 只有 VEG 的整格近似 ⇒ 必须占槽");
        check(!VoxelClassifier.baseCodeExpresses(new float[]{
                0.4375f, 0f, 0.4375f, 0.5625f, 1f, 0.5625f}, 1),
                "栅栏柱(不占满 XZ 足印)⇒ 薄板码自陈'只带高度不带水平形状' ⇒ 必须占槽");
        check(VoxelClassifier.baseCodeExpresses(slab(0f, 1.0f), 1)
                        && !VoxelClassifier.baseCodeExpresses(slab(0f, 0.1875f), 1),
                "阳性对照:同一个判据在同一次执行里既给出 true 也给出 false(不是恒真/恒假的坏判据)");
    }

    /** ② 去重与规范化。 */
    private static void dedupAndCanonical() {
        ShapePalette p = new ShapePalette();
        p.clear();
        float[] a = {0f, 0f, 0f, 1f, 0.5f, 1f, 0f, 0.5f, 0f, 0.5f, 1f, 1f};
        float[] aShuffled = {0f, 0.5f, 0f, 0.5f, 1f, 1f, 0f, 0f, 0f, 1f, 0.5f, 1f};
        int s0 = p.slotFor(a, 2);
        int s1 = p.slotFor(aShuffled, 2);
        check(s0 == 0 && s1 == 0, "同一组盒换顺序 ⇒ 同槽(实测 " + s0 + " vs " + s1 + ")");
        int s2 = p.slotFor(slab(0f, 0.5f), 1);
        check(s2 == 1, "不同形状 ⇒ 新槽");
        check(p.count() == 2, "槽数 = 2(实测 " + p.count() + ")");
        // 零体积盒丢弃:把退化盒混进去,结果必须与不混时同槽
        float[] withDegen = {0f, 0f, 0f, 1f, 0.5f, 1f, 0f, 0.5f, 0f, 0.5f, 1f, 1f,
                0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f};
        check(p.slotFor(withDegen, 3) == s0, "零体积盒被丢弃 ⇒ 与不混退化盒同槽");
        check(p.slotFor(new float[]{0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f}, 1) == -1,
                "整组都是零体积盒 ⇒ 不建槽(返回 -1,退回基础码)");
    }

    /** ③ 有界性与降级不破坏已有槽。 */
    private static void capacityAndDegrade() {
        ShapePalette p = new ShapePalette();
        p.clear();
        // 坐标刻意取 1/256 的整数倍:量化后逐位精确,240 个形状两两可分
        // (随意取 0.001 步长会让 minX/maxX 量化成同一个整数 ⇒ 零体积盒被丢弃,
        //  那是**测量构造**的问题,不是被测代码的问题 —— 首版就这么错过一次)。
        for (int i = 0; i < ShapePalette.MAX_SLOTS; i++) {
            float x0 = (i + 1) / 256f;
            float x1 = (i + 2) / 256f;
            checkQuiet(p.slotFor(new float[]{x0, 0f, 0f, x1, 0.5f, 1f}, 1) == i,
                    "第 " + i + " 个形状拿到槽 " + i);
        }
        check(p.count() == ShapePalette.MAX_SLOTS, "容量上限 = " + ShapePalette.MAX_SLOTS);
        int overflow = p.slotFor(new float[]{255f / 256f, 0f, 0f, 1f, 0.5f, 1f}, 1);
        check(overflow == -1, "超容量 ⇒ -1(退回基础码),实测 " + overflow);
        check(p.count() == ShapePalette.MAX_SLOTS, "超容量后槽数不变(不写坏已有数据)");
        // 已存在的形状仍能命中(降级只影响新形状)
        check(p.slotFor(new float[]{1f / 256f, 0f, 0f, 2f / 256f, 0.5f, 1f}, 1) == 0,
                "降级后已存在的形状仍命中原槽");
        // 盒数上限
        ShapePalette q = new ShapePalette();
        q.clear();
        // 2026-10-04 R56:上限 8→16。**9 盒的栅栏现在必须能建槽**(这正是用户报的"木栅栏无孔洞"的修法),
        // 故这里的越界样本改成 17 盒;限内样本改成 16 盒。
        float[] many = new float[17 * 6];
        for (int i = 0; i < 17; i++) {
            many[i * 6] = 0.01f * i; many[i * 6 + 1] = 0f; many[i * 6 + 2] = 0f;
            many[i * 6 + 3] = 0.01f * i + 0.005f; many[i * 6 + 4] = 0.5f; many[i * 6 + 5] = 1f;
        }
        check(q.slotFor(many, 17) == -1, "17 盒超过单形状上限 " + ShapePalette.MAX_BOXES + " ⇒ -1");
        check(q.slotFor(many, 16) == 0, "16 盒在限内 ⇒ 建槽");
        check(q.boxesInSlot(0) == 16, "槽内盒数如实记录(实测 " + q.boxesInSlot(0) + ")");
        // ★ 用户报的那个形状:9 盒(4 面全连接的栅栏)必须**能建槽**(旧上限 8 时它是 -1 ⇒ 退回整格 ⇒ 无孔洞)
        float[] nine = new float[9 * 6];
        for (int i = 0; i < 9; i++) {
            nine[i * 6] = 0.01f * i; nine[i * 6 + 1] = 0f; nine[i * 6 + 2] = 0f;
            nine[i * 6 + 3] = 0.01f * i + 0.005f; nine[i * 6 + 4] = 0.5f; nine[i * 6 + 5] = 1f;
        }
        check(q.slotFor(nine, 9) >= 0, "★ 9 盒(4 面连接栅栏)必须能建槽 ⇒ 有真实形状(不再退回整格近似)");
    }

    /** ④ 并集盒 = 盒集合的包围盒(表路径的保守代理)。 */
    private static void unionBox() {
        ShapePalette p = new ShapePalette();
        p.clear();
        float[] boxes = {
                0.25f, 0.0f, 0.25f, 0.75f, 0.5f, 0.75f,
                0.0f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f};
        int s = p.slotFor(boxes, 2);
        check(s == 0, "两盒形状建槽");
        check(p.unionComponent(s, 0) == 0.0f && p.unionComponent(s, 1) == 0.0f && p.unionComponent(s, 2) == 0.25f
                        && p.unionComponent(s, 3) == 0.75f && p.unionComponent(s, 4) == 1.0f && p.unionComponent(s, 5) == 1.0f,
                "并集盒 = 逐分量 min/max(实测 min=(0,0,0.25) max=(0.75,1,1))");
        // 阳性对照:并集盒必须**真**覆盖两个盒(把并集写成"第一个盒"会漏出第二个盒的范围)
        check(p.unionComponent(s, 1) <= 0.0f && p.unionComponent(s, 4) >= 1.0f
                        && p.unionComponent(s, 3) >= 0.75f,
                "并集盒确实张开到两个盒的外包(不是只留第一个)");
    }

    /** ⑤ 量化:1/16 网格值逐位精确(原版形状全在该网格上)。 */
    private static void quantization() {
        float[] q = {0f, 0.0625f, 0.125f, 0.1875f, 0.25f, 0.5f, 0.5625f, 0.875f, 1f};
        boolean allExact = true;
        StringBuilder bad = new StringBuilder();
        for (float v : q) {
            int qi = ShapePalette.quant(v);
            if (qi / (float) ShapePalette.QUANT != v) {
                allExact = false;
                bad.append(v).append(' ');
            }
        }
        check(allExact, "1/16 网格值量化后逐位还原(QUANT=" + ShapePalette.QUANT + ")" + (bad.length() > 0 ? " 失败:" + bad : ""));
        check(ShapePalette.quant(-0.5f) == 0 && ShapePalette.quant(2.0f) == ShapePalette.QUANT,
                "越界坐标钳到 [0,QUANT]");
    }

    /** ⑥ 布局与 GLSL 逐值对齐。 */
    private static void glslLayout() throws Exception {
        check(ShapePalette.SLOT_STRIDE == 1 + 6 + ShapePalette.MAX_BOXES * 6,
                "槽步长 = 1(盒数) + 6(并集) + 8×6(盒) = " + ShapePalette.SLOT_STRIDE);
        check(ShapePalette.TOTAL_FLOATS == ShapePalette.MAX_SLOTS * ShapePalette.SLOT_STRIDE,
                "调色板 float 总数 = " + ShapePalette.TOTAL_FLOATS);
        check(SpotlightBufferLayout.OFF_VOX_PAL_META == SpotlightBufferLayout.OFF_VOX_META + 16,
                "voxPalMeta 紧接 voxMeta(偏移 " + SpotlightBufferLayout.OFF_VOX_PAL_META + ")");
        check(SpotlightBufferLayout.OFF_VOX_PAL_BOX == SpotlightBufferLayout.OFF_VOX_PAL_META + 16,
                "voxPalBox 紧接 voxPalMeta(偏移 " + SpotlightBufferLayout.OFF_VOX_PAL_BOX + ")");
        check(SpotlightBufferLayout.OFF_VOX_DATA
                        == SpotlightBufferLayout.OFF_VOX_PAL_BOX + ShapePalette.TOTAL_FLOATS * 4,
                "voxData 紧接盒区(偏移 " + SpotlightBufferLayout.OFF_VOX_DATA + ")");
        check(SpotlightBufferLayout.VOX_MAX_UINTS == 128 * 128 * 128 / 4,
                "8bit 打包:VOX_MAX_UINTS = 128³/4 = " + SpotlightBufferLayout.VOX_MAX_UINTS);
        check(SpotlightBufferLayout.HEAD_STAGE_BYTES == SpotlightBufferLayout.OFF_VOX_PAL_BOX,
                "每帧上传头段止于 voxPalMeta(= " + SpotlightBufferLayout.HEAD_STAGE_BYTES + " B;盒区与体素另传)");

        String core = Files.readString(Path.of("pack/shaders/lib/taclight_core.glsl"));
        // 源码里宏为对齐留了多空格 ⇒ 比对前把空白折叠成单空格(否则会假红:
        // 判据钉的是"值对不对",不是"缩进漂不漂亮")。
        String coreN = core.replaceAll("\\s+", " ");
        check(core.contains("float voxPalBox[" + ShapePalette.TOTAL_FLOATS + "]"),
                "GLSL 盒区数组长度 = " + ShapePalette.TOTAL_FLOATS + "(与 Java 同值)");
        check(core.contains("float voxPalBox[" + ShapePalette.TOTAL_FLOATS + "];")
                        && core.contains("uint  voxData[];"),
                "std430 合法性:定长数组在前、运行时长数组仍在末尾");
        check(coreN.contains("#define TACLIGHT_VOX_PAL_SLOT_FLOATS " + ShapePalette.SLOT_STRIDE),
                "GLSL 槽步长宏 = " + ShapePalette.SLOT_STRIDE);
        check(coreN.contains("#define TACLIGHT_VOX_PAL_BOX0 " + ShapePalette.SLOT_BOX0),
                "GLSL 盒0 偏移宏 = " + ShapePalette.SLOT_BOX0);
        check(coreN.contains("#define TACLIGHT_VOX_PAL_UNION " + ShapePalette.SLOT_UNION),
                "GLSL 并集盒偏移宏 = " + ShapePalette.SLOT_UNION);
        check(coreN.contains("#define TACLIGHT_VOX_PAL_MAX_BOXES " + ShapePalette.MAX_BOXES),
                "GLSL 单形状盒数宏 = " + ShapePalette.MAX_BOXES);

        // ---- 8bit 解包 + 分支顺序:两条防线都必须逐字在场 ----
        check(core.contains("(voxData[idx >> 2] >> uint((idx & 3) * 8)) & 255u"),
                "GLSL 体素解包 = 8bit/字内 4 槽(>>2 / (idx&3)*8 / &255u)");
        int palAt = core.indexOf("else if (code >= 16u)");
        int plateAt = core.indexOf("else if (code >= 4u");
        check(palAt > 0 && plateAt > 0 && palAt < plateAt,
                "调色板分支在薄板分支**之前**(否则 ≥16 会被当薄板 ⇒ (code−3)/8 假遮挡)");
        check(core.contains("code >= 4u && code < 16u"),
                "薄板分支另带上界 code<16u(纵深防御:顺序被颠倒了也不至于把调色板码当薄板)");
        // 建表路径(第二个函数)同样要有调色板分支
        int tableAt = core.indexOf("float taclight_vox_hit_dist(");
        check(tableAt > 0 && core.indexOf("else if (code >= 16u)", tableAt) > 0,
                "建表路径 taclight_vox_hit_dist 也有调色板分支(否则 route S 表模式把 ≥16 读成薄板)");
        // 两条路径都必须消费调色板(改对文件 ≠ 改对生效的那份:Complementary 走前向精简)
        String tpl = Files.readString(Path.of("src/main/java/dev/taclight/interop/TemplateLibrary.java"));
        check(tpl.contains("voxPalBox[palBase]") && tpl.contains("taclight_vox_box_span"),
                "前向精简桩也消费调色板盒区 + 用同一条盒相交 kernel(两条路径镜像)");
        check(tpl.contains("idx / 4") && tpl.contains("% 256u"),
                "前向取数按 8bit 解包(/4 + %256u,与主核 >>2/&255u 等价)");
        check(tpl.contains("palBase + 7 + b * 6"),
                "前向路径的盒偏移字面量 = " + ShapePalette.SLOT_BOX0 + "+b*6(与 Java SLOT_BOX0 对齐)");
        int fpal = tpl.indexOf("else if (code >= 16.0)");
        int fplate = tpl.indexOf("else if (code >= 4.0");
        check(fpal > 0 && fplate > 0 && fpal < fplate,
                "前向桩里调色板分支也在薄板分支之前(前向同样是 code>=4.0 会吞 ≥16)");
    }

    private static float[] slab(float lo, float hi) {
        return new float[]{0f, lo, 0f, 1f, hi, 1f};
    }

    private static void checkQuiet(boolean ok, String what) {
        if (!ok) {
            checks++;
            fails++;
            System.out.println("  FAIL " + what);
        } else {
            checks++;
        }
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (ok) {
            System.out.println("  PASS " + what);
        } else {
            fails++;
            System.out.println("  FAIL " + what);
        }
    }

    private ShapePaletteContract() {}
}

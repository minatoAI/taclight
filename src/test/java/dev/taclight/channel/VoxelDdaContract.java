package dev.taclight.channel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 体素 DDA 边/角 crossing、端点豁免、材质透射和 GLSL 同步契约。
 *
 * <p>2026-09-02 根因轮:实机四臂消融证明 tie 单轴误访只占伪影一部分(修复后
 * A1 p90 残差 34.07→27.54,仍为对照臂 2.4-3 倍且与 |bob| 同步)。残余机制 =
 * 实心格硬 0/1 遮挡在影子轮廓(墙顶/平台边)的亚像素翻转:掠边射线在
 * "实心/空气"间跳变 → 条纹随 bob 节奏闪烁。修复 = 按射线在实心格内的
 * 穿透长度软化(≥ 带宽仍严格 T=0 保住墙后遮挡基线,掠边得部分透射)。
 */
public class VoxelDdaContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        List<VoxelDda.Cell> xy = VoxelDda.traceIntermediate(
                0.5, 0.5, 0.5, 3.5, 3.5, 0.5);
        check(xy.equals(List.of(
                new VoxelDda.Cell(1, 1, 0),
                new VoxelDda.Cell(2, 2, 0))),
                "双轴 tie 只访问真正穿入的对角格");
        check(!xy.contains(new VoxelDda.Cell(1, 0, 0))
                        && !xy.contains(new VoxelDda.Cell(2, 1, 0)),
                "双轴 tie 不访问擦边侧邻格");

        List<VoxelDda.Cell> xyz = VoxelDda.traceIntermediate(
                0.5, 0.5, 0.5, 3.5, 3.5, 3.5);
        check(xyz.equals(List.of(
                new VoxelDda.Cell(1, 1, 1),
                new VoxelDda.Cell(2, 2, 2))),
                "三轴 tie 只访问真正穿入的对角格");

        List<VoxelDda.Cell> negative = VoxelDda.traceIntermediate(
                3.5, 3.5, 0.5, 0.5, 0.5, 0.5);
        check(negative.equals(List.of(
                new VoxelDda.Cell(2, 2, 0),
                new VoxelDda.Cell(1, 1, 0))),
                "负方向双轴 tie 对称");

        List<VoxelDda.Cell> faceStart = VoxelDda.traceIntermediate(
                2.0, 0.5, 0.5, 0.5, 0.5, 0.5);
        check(faceStart.equals(List.of(new VoxelDda.Cell(1, 0, 0))),
                "负方向从格面起步不重复或跳过中间格");
        check(VoxelDda.traceIntermediate(
                        0.5, 0.5, 0.5, 1.5, 0.5, 0.5).isEmpty(),
                "相邻端点格豁免");

        // ---- 穿透长度软化(2026-09-02 根因轮) ----
        // 深穿透(整格):与旧硬语义完全一致,T=0。
        check(VoxelDda.transmit(0.5, 0.5, 0.5, 2.5, 0.5, 0.5,
                        cell -> cell.x() == 1 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY) == 0.0,
                "深穿透实心格仍一票否决 T=0");
        // 掠边(穿透 0.061 格 < 带宽):大部分透射,不再硬翻转;范围随带宽推导,
        // 不硬编码,防调参时契约与实现脱节。
        // ★ 2026-10-06:实心格从 y==0 改为 x==1 —— 灯原点在 (0.912,0.5,0.5) 的格子里,
        // 而 y==0 的实心平面**正好包含灯所在格**;起点格判定生效后那是"灯埋在方块里",
        // 合法地 T=0(见下方"起点格"一节),本项要测的是**中间格**的掠边软化,故把实心面
        // 挪到不包含灯的 x==1:射线几何与穿透长度(0.0612 / 0.0306 格)完全不变。
        double graze = VoxelDda.transmit(0.912, 0.5, 0.5, 1.112, 1.5, 0.5,
                cell -> cell.x() == 1 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY);
        // 该射线的几何穿透长度 ≈0.0612 格,与带宽无关;由 (1-T)×带宽 反推应守恒。
        double grazePen = (1.0 - graze) * VoxelDda.FUZZ_BLOCKS;
        check(graze < 1.0 && Math.abs(grazePen - 0.0612) < 2e-3,
                "掠边实心格得部分透射且穿透长度守恒(实测 T=" + graze + ",穿透 "
                        + String.format("%.5f", grazePen) + " 格,带宽 " + VoxelDda.FUZZ_BLOCKS + ")");
        double grazeShallower = VoxelDda.transmit(0.906, 0.5, 0.5, 1.106, 1.5, 0.5,
                cell -> cell.x() == 1 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY);
        check(grazeShallower > graze,
                "越浅的掠边透射越高(实测 " + grazeShallower + " > " + graze + ")");
        // 树叶/植被保持整格语义(穿透≥带宽时系数不变)。
        check(close(VoxelDda.transmit(0.5, 0.5, 0.5, 3.5, 0.5, 0.5,
                        cell -> cell.x() == 1 ? VoxelField.CODE_LEAF
                                : cell.x() == 2 ? VoxelField.CODE_VEG
                                : VoxelField.CODE_EMPTY), 0.30),
                "树叶与软植被整格透射相乘=0.40×0.75");
        check(close(VoxelDda.transmit(0.5, 0.5, 0.5, 3.5, 0.5, 0.5,
                        cell -> cell.x() == 2 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY), 0.0),
                "任一深穿透实心中间格透射为零");

        // ---- 薄板高度感知(2026-09-25 细雪层穿光根因) ----
        // 雪 1 层 = 底薄板 [0, 0.125]:穿过板体 ⇒ 全挡;从板上方掠过 ⇒ 完全放行。
        int snow1 = VoxelField.slabBottomCode(1);
        check(VoxelDda.transmit(0.5, 0.05, 0.5, 2.5, 0.05, 0.5,
                        cell -> cell.x() == 1 ? snow1 : VoxelField.CODE_EMPTY) == 0.0,
                "水平射线穿 1 层雪板(格内 y=0.05 < 0.125)⇒ T=0(细雪层必须挡)");
        check(VoxelDda.transmit(0.5, 0.5, 0.5, 2.5, 0.5, 0.5,
                        cell -> cell.x() == 1 ? snow1 : VoxelField.CODE_EMPTY) == 1.0,
                "水平射线从板上方掠过(格内 y=0.5 > 0.125)⇒ T=1(不得假遮挡)");
        check(VoxelDda.transmit(1.5, 1.9, 0.5, 1.5, -0.1, 0.5,
                        cell -> cell.y() == 0 ? snow1 : VoxelField.CODE_EMPTY) == 0.0,
                "竖直射线穿过 1 层雪板 ⇒ T=0");
        // 顶薄板 [0.5, 1]:从下半格穿过 ⇒ 放行;穿板体 ⇒ 挡。
        int topSlab = VoxelField.slabTopCode(4);
        check(VoxelDda.transmit(0.5, 0.25, 0.5, 2.5, 0.25, 0.5,
                        cell -> cell.x() == 1 ? topSlab : VoxelField.CODE_EMPTY) == 1.0,
                "水平射线从顶薄板下方穿过(格内 y=0.25 < 0.5)⇒ T=1");
        check(VoxelDda.transmit(0.5, 0.75, 0.5, 2.5, 0.75, 0.5,
                        cell -> cell.x() == 1 ? topSlab : VoxelField.CODE_EMPTY) == 0.0,
                "水平射线穿顶薄板(格内 y=0.75 ∈ [0.5,1])⇒ T=0");
        // 高薄板(雪 7 层 = [0, 0.875]):掠过板顶(y=0.95)⇒ 放行 —— 高度感知相对"整格近似"的关键差别
        int snow7 = VoxelField.slabBottomCode(7);
        check(VoxelDda.transmit(0.5, 0.95, 0.5, 2.5, 0.95, 0.5,
                        cell -> cell.x() == 1 ? snow7 : VoxelField.CODE_EMPTY) == 1.0,
                "水平射线掠过 7 层雪板顶部(格内 y=0.95 > 0.875)⇒ T=1");
        check(VoxelDda.transmit(0.5, 0.5, 0.5, 2.5, 0.5, 0.5,
                        cell -> cell.x() == 1 ? snow7 : VoxelField.CODE_EMPTY) == 0.0,
                "同一 7 层雪板:格内 y=0.5 在板内 ⇒ T=0(高度决定挡不挡,而非整格一刀切)");

        // ---- 薄板软化容差(2026-10-03 R20;用户实测"半砖影子比满方块凸出去一点") ----
        // 旧语义:薄板"格内 y 区间与板区间相交即 return 0.0"(不套软化),而实心格按
        // 穿透长度软化(穿透 <0.35 部分放行)⇒ 同一足印下**薄板比满方块更硬**,
        // 影子向外多出最多 0.35 格。现改为 band = min(FUZZ, 板厚)。
        // 阳性对照:掠着半砖顶面切过的射线(几何重叠仅 0.0025 格)旧语义当场 T=0,
        // 新语义必须给**严格部分透射** —— 这条断言在旧实现上必红。
        int halfSlab = VoxelField.slabBottomCode(4);      // 顶高 0.5 ⇒ band = min(0.35, 0.5) = 0.35
        double grazeSlab = VoxelDda.transmit(0.5, 0.49, 0.5, 2.5, 0.52, 0.5,
                cell -> cell.x() == 1 ? halfSlab : VoxelField.CODE_EMPTY);
        check(grazeSlab > 0.0 && grazeSlab < 1.0,
                "掠半砖顶面的射线得严格部分透射(旧语义必为 0.0)实测 T="
                        + String.format("%.4f", grazeSlab));
        // 同一射线:满方块仍全挡 ⇒ 薄板不再"比满方块更硬"(旧语义下两者都是 0,无法区分)
        double grazeSolid = VoxelDda.transmit(0.5, 0.49, 0.5, 2.5, 0.52, 0.5,
                cell -> cell.x() == 1 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY);
        check(grazeSolid == 0.0 && grazeSlab > grazeSolid,
                "同一掠边射线:满方块全挡、半砖部分放行(实测 " + grazeSolid + " vs "
                        + String.format("%.4f", grazeSlab) + ")⇒ 半砖影子不再外凸");
        // 负控:软化**不得**把薄雪层放行 —— 穿满整个板厚仍必须 T=0。
        double snow1Through = VoxelDda.transmit(1.5, 1.9, 0.5, 1.5, -0.1, 0.5,
                cell -> cell.y() == 0 ? snow1 : VoxelField.CODE_EMPTY);
        check(snow1Through == 0.0,
                "穿满 1 层雪板(0.125 = band)仍 T=0(软化不重演细雪层穿光)");
        double snow1Side = VoxelDda.transmit(0.5, 0.05, 0.5, 2.5, 0.05, 0.5,
                cell -> cell.x() == 1 ? snow1 : VoxelField.CODE_EMPTY);
        check(snow1Side == 0.0,
                "格内 y=0.05 水平穿 1 层雪板(盒内路径 1.0 ≫ band)仍 T=0");
        // 半砖"完全穿透"与满方块逐位一致:band 都取 0.35 ⇒ 没有引入新的不对称。
        double halfThrough = VoxelDda.transmit(0.5, 0.25, 0.5, 2.5, 0.25, 0.5,
                cell -> cell.x() == 1 ? halfSlab : VoxelField.CODE_EMPTY);
        double solidThrough = VoxelDda.transmit(0.5, 0.25, 0.5, 2.5, 0.25, 0.5,
                cell -> cell.x() == 1 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY);
        check(halfThrough == 0.0 && solidThrough == 0.0,
                "水平完全穿透:半砖与满方块同为 T=0(带宽同为 0.35,无新不对称)");
        // 盒内穿透长度守恒(与实心格同一条换算):掠边透射 = 1 − 盒内路径/band。
        double penFromT = (1.0 - grazeSlab) * VoxelDda.FUZZ_BLOCKS;
        check(Math.abs(penFromT - 0.1667) < 3e-3,
                "掠半砖的盒内穿透长度守恒(实测 " + String.format("%.4f", penFromT)
                        + " 格,几何解析 0.1667)");

        List<VoxelDda.Cell> boundary = VoxelDda.traceIntermediate(
                0.5, 0.5, 0.5, 127.5, 127.5, 127.5);
        check(boundary.size() == 126
                        && boundary.get(0).equals(new VoxelDda.Cell(1, 1, 1))
                        && boundary.get(boundary.size() - 1).equals(new VoxelDda.Cell(126, 126, 126)),
                "128³ 对角线在 384 guard 内完整遍历且终点豁免");

        // interop 核心剥离(v1.0):坐标换算/DDA 本体已迁至 lib/taclight_core.glsl,
        // 契约断言跟代码走(文本不变,只改路径);分层边界归 ShaderCoreContract。
        String shader = Files.readString(Path.of("pack/shaders/lib/taclight_core.glsl"));
        // ---- 坐标换算契约(2026-09-02 bob 跳位根因轮) ----
        // gbufferModelView 含 bob 平移(R·T);mat3/transpose-only 换算丢平移,
        // 给世界/视图坐标注入 ±bob 位移的假偏移 = 影子/光锥随步频跳位。
        // 实机差分:影界-石柱相对摆动 bob开 7.2-7.4px vs bob关 2.3-2.7px(噪声底)。
        check(shader.contains("(gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz"),
                "view→world 用全矩阵逆(平移被正确抵消)");
        check(shader.contains("uniform mat4 gbufferModelViewInverse;"),
                "显式声明 gbufferModelViewInverse(Iris 只注入已声明的 uniform)");
        check(!shader.contains("transpose(mat3(gbufferModelView))"),
                "view→world 禁止 transpose(mat3) 形式(丢弃 bob 平移=假偏移)");
        check(shader.contains("(gbufferModelView * vec4(scenePos, 1.0)).xyz"),
                "scene→view 用全矩阵(与光栅化几何同含 bob 平移,差分才可抵消)");
        check(!shader.contains("mat3(gbufferModelView) * scenePos"),
                "scene→view 禁止 mat3-only 形式(与带平移的 fragView 相减=步频抖动)");
        check(shader.contains("float tExit = min(tMax.x, min(tMax.y, tMax.z));"),
                "GLSL 使用统一 crossing time");
        check(shader.contains("bvec3 tied = lessThanEqual(abs(tMax - vec3(tEntry)), vec3(tieEps));"),
                "GLSL 用距离比例容差识别全部 tied axes(tie 基准 = 本轮格入口 tEntry)");
        check(shader.contains("cell += istep * ivec3(tied);"),
                "GLSL 同时推进全部 tied axes");
        check(!shader.contains("cell[axis] += istep[axis]"),
                "GLSL 不再按单轴分轮访问擦边格");
        check(shader.contains("#define TACLIGHT_VOX_FUZZ 0.35"),
                "GLSL 定义穿透软化带宽 0.35 格(0.20 仍随步行 bob 在硬影缘闪烁,加宽给真实半影)");
        check(shader.contains("float tExit = min(tMax.x, min(tMax.y, tMax.z));"),
                "GLSL 按出格时间计算实心格穿透长度");
        check(shader.contains("clamp(penLen / TACLIGHT_VOX_FUZZ"),
                "GLSL 掠边实心格按穿透长度软化");
        check(!shader.contains("if (code == 3u) return 0.0;"),
                "GLSL 不再对实心格无条件硬消光");

        // ---- 同轴快速通道契约(2026-09-02 自灯影子回归轮,坑58) ----
        // 旧版在表面照明用视图域 dot(lightView,lightView)<0.25 判"灯≈相机"
        // 并直接 vis=1(跳过遮挡)。坑57 修复后 lightView 含 bob 平移(±0.1),自灯
        // 锚点(手持 0.44/枪灯 ~0.6)恰在 0.5 阈值两侧,随步频翻转 → 影子"消失+闪烁"。
        // 且灯≈相机时体素 DDA 依然有效(世界空间射线;终点格豁免自遮,起点格几何照判),
        // 跳过 DDA = 自灯影子整体丢失;同轴豁免只应用于 DDA 无效时的屏幕空间回退。
        // interop 剥离后该分流逻辑位于 core 的 taclight_surface_lighting。
        String surface = Files.readString(Path.of("pack/shaders/composite.fsh"));
        check(!surface.contains("dot(lightView, lightView) < 0.25")
                        && !shader.contains("dot(lightView, lightView) < 0.25"),
                "同轴判定禁止视图域距离(含 bob 平移,随步频跨阈值=影子闪烁)");
        check(shader.contains("dot(lightScene, lightScene) < 0.25"),
                "同轴判定用场景域距离(world−camera,无 bob,恒定)");
        check(shader.indexOf("taclight_vox_transmit") >= 0
                        && shader.indexOf("taclight_vox_transmit")
                                < shader.indexOf("dot(lightScene, lightScene) < 0.25"),
                "体素 DDA 在同轴判定之前无条件执行(灯≈相机时 DDA 依然有效,跳过=自灯影子丢失)");
        check(shader.contains("} else if (dot(lightScene, lightScene) < 0.25) {"),
                "同轴豁免只作为 DDA 无效(-1)时的回退分支(热修12 的 SSO 退化只属于屏幕空间路径)");

        // ---- ★ 2026-10-06 起点格(灯所在格)几何必须参与遮挡 ----
        // 用户实测缺陷:灯靠方块太近时,方块另一侧出现"该方块影子形状的亮区"(漏光)。
        // 根因:起点格整格豁免,前提是"灯在空气格里" —— 而手持灯原点 = 眼位 + look*0.35
        // ⇒ 贴墙站着灯原点就落进墙格,墙自己完全不遮挡(旧码此处 T=1.0)。
        VoxelDda.Classifier wall1 = c -> c.x() == 0 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY;
        check(VoxelDda.transmit(0.5, 5.5, 5.5, 2.5, 5.5, 5.5, wall1) == 0.0,
                "[旧码必红] 灯原点在 1 格厚墙的格内 ⇒ 墙后 T=0(旧码整格豁免 ⇒ T=1 漏光)");
        check(VoxelDda.transmit(0.4, 5.5, 5.5, 2.5, 6.5, 5.5, wall1) == 0.0,
                "[旧码必红] 灯在墙格内斜射 ⇒ 同样 T=0");
        check(VoxelDda.transmit(-0.5, 5.5, 5.5, 2.5, 5.5, 5.5, wall1) == 0.0,
                "对照:灯在墙前空气格 ⇒ T=0(确认修的只是起点格豁免)");
        check(VoxelDda.transmit(0.5, 5.5, 5.5, 1.5, 5.5, 5.5,
                        c -> VoxelField.CODE_EMPTY) == 1.0,
                "对照:起点格与终点格都是空气 ⇒ T=1(不引入假遮挡)");
        // 薄板:埋在板里必须挡;但"同一格、板在脚下/上方"不许被误挡 —— 这是过度修复的红线。
        VoxelDda.Classifier slab = c -> c.x() == 0 ? VoxelField.slabBottomCode(4) : VoxelField.CODE_EMPTY;
        check(VoxelDda.transmit(0.5, 0.2, 5.5, 2.5, 0.2, 5.5, slab) == 0.0,
                "[旧码必红] 灯埋在薄板(0..0.5)内 ⇒ T=0");
        check(VoxelDda.transmit(0.5, 0.8, 5.5, 2.5, 0.8, 5.5, slab) == 1.0,
                "★ 红线:灯在该格薄板**上方** ⇒ 板本来就没挡住 ⇒ T=1(不许一刀切「起点格有几何就封」)");
        check(VoxelDda.transmit(0.5, 0.2, 5.5, 0.9, 0.2, 5.5, slab) == 0.0,
                "灯与受光面同格(终点=起点格)且灯埋在板内 ⇒ T=0");
        check(VoxelDda.transmit(0.5, 5.5, 5.5, 0.9, 5.5, 5.5,
                        c -> c.x() == 0 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY) == 0.0,
                "灯与受光面同格且灯埋在满方块内 ⇒ T=0(旧码会越过终点继续步进)");
        // 植被/树叶:起点格仍豁免(体积填充语义;不做"走进草丛整体变暗"这种行为变化)。
        check(close(VoxelDda.transmit(0.5, 5.5, 5.5, 2.5, 5.5, 5.5,
                        c -> c.x() == 0 ? VoxelField.CODE_VEG : VoxelField.CODE_EMPTY), 1.0),
                "起点格软植被仍豁免(与旧行为一致)");
        check(close(VoxelDda.transmit(0.5, 5.5, 5.5, 2.5, 5.5, 5.5,
                        c -> c.x() == 1 ? VoxelField.CODE_VEG : VoxelField.CODE_EMPTY), 0.75),
                "中间格软植被照旧衰减(豁免只限起点格)");

        System.out.println("VoxelDdaContract: ALL PASS (" + checks + " checks)");
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

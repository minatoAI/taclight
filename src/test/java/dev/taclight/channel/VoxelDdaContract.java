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
        double graze = VoxelDda.transmit(0.912, 0.5, 0.5, 1.112, 1.5, 0.5,
                cell -> cell.y() == 0 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY);
        // 该射线的几何穿透长度 ≈0.0612 格,与带宽无关;由 (1-T)×带宽 反推应守恒。
        double grazePen = (1.0 - graze) * VoxelDda.FUZZ_BLOCKS;
        check(graze < 1.0 && Math.abs(grazePen - 0.0612) < 2e-3,
                "掠边实心格得部分透射且穿透长度守恒(实测 T=" + graze + ",穿透 "
                        + String.format("%.5f", grazePen) + " 格,带宽 " + VoxelDda.FUZZ_BLOCKS + ")");
        double grazeShallower = VoxelDda.transmit(0.906, 0.5, 0.5, 1.106, 1.5, 0.5,
                cell -> cell.y() == 0 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY);
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

        List<VoxelDda.Cell> boundary = VoxelDda.traceIntermediate(
                0.5, 0.5, 0.5, 127.5, 127.5, 127.5);
        check(boundary.size() == 126
                        && boundary.get(0).equals(new VoxelDda.Cell(1, 1, 1))
                        && boundary.get(boundary.size() - 1).equals(new VoxelDda.Cell(126, 126, 126)),
                "128³ 对角线在 384 guard 内完整遍历且终点豁免");

        String shader = Files.readString(Path.of("pack/shaders/lib/taclight_common.glsl"));
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
        check(shader.contains("float tNext = min(tMax.x, min(tMax.y, tMax.z));"),
                "GLSL 使用统一 crossing time");
        check(shader.contains("bvec3 tied = lessThanEqual(abs(tMax - vec3(tNext)), vec3(tieEps));"),
                "GLSL 用距离比例容差识别全部 tied axes");
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
        // 旧版在 composite.fsh 用视图域 dot(lightView,lightView)<0.25 判"灯≈相机"
        // 并直接 vis=1(跳过遮挡)。坑57 修复后 lightView 含 bob 平移(±0.1),自灯
        // 锚点(手持 0.44/枪灯 ~0.6)恰在 0.5 阈值两侧,随步频翻转 → 影子"消失+闪烁"。
        // 且灯≈相机时体素 DDA 依然有效(世界空间射线,起点/终点格双豁免),
        // 跳过 DDA = 自灯影子整体丢失;同轴豁免只应用于 DDA 无效时的屏幕空间回退。
        String composite = Files.readString(Path.of("pack/shaders/composite.fsh"));
        check(!composite.contains("dot(lightView, lightView) < 0.25"),
                "同轴判定禁止视图域距离(含 bob 平移,随步频跨阈值=影子闪烁)");
        check(composite.contains("dot(lightScene, lightScene) < 0.25"),
                "同轴判定用场景域距离(world−camera,无 bob,恒定)");
        check(composite.indexOf("taclight_vox_transmit") >= 0
                        && composite.indexOf("taclight_vox_transmit")
                                < composite.indexOf("dot(lightScene, lightScene) < 0.25"),
                "体素 DDA 在同轴判定之前无条件执行(灯≈相机时 DDA 依然有效,跳过=自灯影子丢失)");
        check(composite.contains("} else if (dot(lightScene, lightScene) < 0.25) {"),
                "同轴豁免只作为 DDA 无效(-1)时的回退分支(热修12 的 SSO 退化只属于屏幕空间路径)");

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

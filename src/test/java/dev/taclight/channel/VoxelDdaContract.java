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
        // 掠边(穿透 0.061 格 < 带宽 0.20):大部分透射,不再硬翻转。
        double graze = VoxelDda.transmit(0.912, 0.5, 0.5, 1.112, 1.5, 0.5,
                cell -> cell.y() == 0 ? VoxelField.CODE_SOLID : VoxelField.CODE_EMPTY);
        check(graze > 0.6 && graze < 0.8,
                "掠边实心格得部分透射(实测 " + graze + ")");
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
        check(shader.contains("float tNext = min(tMax.x, min(tMax.y, tMax.z));"),
                "GLSL 使用统一 crossing time");
        check(shader.contains("bvec3 tied = lessThanEqual(abs(tMax - vec3(tNext)), vec3(tieEps));"),
                "GLSL 用距离比例容差识别全部 tied axes");
        check(shader.contains("cell += istep * ivec3(tied);"),
                "GLSL 同时推进全部 tied axes");
        check(!shader.contains("cell[axis] += istep[axis]"),
                "GLSL 不再按单轴分轮访问擦边格");
        check(shader.contains("#define TACLIGHT_VOX_FUZZ 0.20"),
                "GLSL 定义穿透软化带宽 0.20 格");
        check(shader.contains("float tExit = min(tMax.x, min(tMax.y, tMax.z));"),
                "GLSL 按出格时间计算实心格穿透长度");
        check(shader.contains("clamp(penLen / TACLIGHT_VOX_FUZZ"),
                "GLSL 掠边实心格按穿透长度软化");
        check(!shader.contains("if (code == 3u) return 0.0;"),
                "GLSL 不再对实心格无条件硬消光");

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

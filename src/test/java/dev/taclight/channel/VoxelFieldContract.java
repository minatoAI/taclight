package dev.taclight.channel;

import java.util.List;

/**
 * 体素遮挡栅格纯逻辑契约(2026-09-01 深夜④,DDA 立项:用户实测墙后地面漏光)。
 * 钉死:盒计算/钳制、2bit 打包位序(与 GLSL voxData 消费公式逐位一致)、快照语义。
 * GLSL DDA 本体无法 JVM 测试,位序一致性由本契约 + 真机 A/B 共同守护。
 */
public class VoxelFieldContract {
    public static void main(String[] args) {
        // ---- 1. 单灯覆盖盒:pos±radius 对齐方块格(floor/ceil) ----
        SpotlightData L = SpotlightData.spot(10.6f, 64.4f, 20.3f, 18f,
                1f, 0.96f, 0.88f, 6f, 0f, 0f, -1f, 0.848f, 0.951f);
        VoxelField.Box box = VoxelField.boxFor(List.of(L));
        check((int) Math.floor(10.6 - 18.0) == box.ox, "ox=floor(minX)");
        check((int) Math.floor(64.4 - 18.0) == box.oy, "oy=floor(minY)");
        check((int) Math.ceil(10.6 + 18.0) - (int) Math.floor(10.6 - 18.0) == box.dx, "dx=ceil(max)-floor(min)");
        check(box.dx <= VoxelField.MAX_DIM && box.dy <= VoxelField.MAX_DIM && box.dz <= VoxelField.MAX_DIM,
                "dims≤MAX_DIM");
        check(box.ox <= 10 && 10 < box.ox + box.dx && box.oz <= 20 && 20 < box.oz + box.dz, "灯心在盒内");

        // ---- 2. 双灯远距(跨度>128)→ 钳到 MAX_DIM,不越界 ----
        SpotlightData L2 = SpotlightData.spot(300.6f, 64.4f, 20.3f, 18f,
                1f, 0.96f, 0.88f, 6f, 0f, 0f, -1f, 0.848f, 0.951f);
        VoxelField.Box box2 = VoxelField.boxFor(List.of(L, L2));
        check(box2.dx == VoxelField.MAX_DIM, "远灯 x 轴钳到 128");
        check(box2.dy <= VoxelField.MAX_DIM && box2.dz <= VoxelField.MAX_DIM, "其余轴仍≤128");

        // ---- 3. 2bit 打包位序 = GLSL 镜像:idx=x+y*dx+z*dx*dy;word=idx>>4;bit=(idx&15)*2 ----
        int[] data = new int[VoxelField.VOX_MAX_UINTS];
        VoxelField.pack(box2, 3, 2, 5, VoxelField.CODE_SOLID, data);
        int idx = 3 + 2 * box2.dx + 5 * box2.dx * box2.dy;
        check(((data[idx >> 4] >> ((idx & 15) * 2)) & 3) == VoxelField.CODE_SOLID, "GLSL 位序读回=CODE_SOLID");
        check(VoxelField.unpack(box2, 3, 2, 5, data) == VoxelField.CODE_SOLID, "unpack=CODE_SOLID");
        check(VoxelField.unpack(box2, 4, 2, 5, data) == VoxelField.CODE_EMPTY, "相邻体素不受污染");
        VoxelField.pack(box2, 4, 2, 5, VoxelField.CODE_LEAF, data);
        check(VoxelField.unpack(box2, 3, 2, 5, data) == VoxelField.CODE_SOLID
                && VoxelField.unpack(box2, 4, 2, 5, data) == VoxelField.CODE_LEAF, "同字双体素独立");
        VoxelField.pack(box2, 4, 2, 5, VoxelField.CODE_VEG, data);
        check(VoxelField.unpack(box2, 4, 2, 5, data) == VoxelField.CODE_VEG, "重写覆盖旧码");

        // ---- 4. Snapshot 语义 ----
        VoxelField.Snapshot snap = new VoxelField.Snapshot(box.ox, box.oy, box.oz,
                box.dx, box.dy, box.dz, data, 42L);
        check(snap.ox() == box.ox && snap.dx() == box.dx && snap.version() == 42L, "snapshot 字段直传");
        check(snap.usedUints() == (box.dx * box.dy * box.dz + 15) / 16, "usedUints=ceil(voxels/16)");

        // ---- 5. 小半径灯不产生退化盒 ----
        SpotlightData L0 = SpotlightData.spot(5f, 5f, 5f, 0.5f,
                1f, 0.96f, 0.88f, 6f, 0f, 0f, -1f, 0.848f, 0.951f);
        VoxelField.Box box3 = VoxelField.boxFor(List.of(L0));
        check(box3.dx >= 1 && box3.dy >= 1 && box3.dz >= 1, "小半径盒非退化");

        // ---- 6. 出实心钳制(2026-09-05 贴墙穿墙根因):灯落实心格 → 沿 −dir 退到首个非实心格 ----
        VoxelField.Box wb = new VoxelField.Box(0, 0, 0, 8, 8, 8);
        int[] wdata = new int[VoxelField.VOX_MAX_UINTS];
        VoxelField.pack(wb, 0, 0, 5, VoxelField.CODE_SOLID, wdata); // 墙格 z=5
        double[] out = VoxelField.clampOutOfSolid(wb, wdata, 0.2, 0.5, 5.5, 0, 0, 1);
        check((int) Math.floor(out[2]) == 4, "墙内灯头退到墙前空气格(z=5.x→4.x)");
        check(Math.abs(out[0] - 0.2) < 1e-9 && Math.abs(out[1] - 0.5) < 1e-9, "只沿 −dir 回退,x/y 不动");
        double[] free = VoxelField.clampOutOfSolid(wb, wdata, 0.2, 0.5, 4.5, 0, 0, 1);
        check(free[2] == 4.5, "空气格灯位原样(fail-safe 零回归)");
        double[] zero = VoxelField.clampOutOfSolid(wb, wdata, 0.2, 0.5, 5.5, 0, 0, 0);
        check(zero[2] == 5.5, "零方向原样(防除零,无 NaN)");
        VoxelField.Box deep = new VoxelField.Box(0, 0, 0, 64, 8, 8);
        int[] ddata = new int[VoxelField.VOX_MAX_UINTS];
        for (int i = 0; i < 64; i++) VoxelField.pack(deep, i, 0, 0, VoxelField.CODE_SOLID, ddata);
        double[] stuck = VoxelField.clampOutOfSolid(deep, ddata, 32.5, 0.5, 0.5, 1, 0, 0);
        check(stuck[0] == 32.5, "2m 无出路原样(fail-safe,行为与今日一致)");
        double[] veg = VoxelField.clampOutOfSolid(wb, wdata, 0.2, 0.5, 5.5, 0, 0, 1);
        VoxelField.pack(wb, 0, 0, 5, VoxelField.CODE_LEAF, wdata);
        double[] leaf = VoxelField.clampOutOfSolid(wb, wdata, 0.2, 0.5, 5.5, 0, 0, 1);
        check(leaf[2] == 5.5, "树叶格不管(单格透射误差小,保持 scope 最小)");
        check(veg[2] < 5.0, "对照:同坐标实心格确被钳制(排除测试本身假阳性)");

        System.out.println("VoxelFieldContract: ALL PASS (21 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

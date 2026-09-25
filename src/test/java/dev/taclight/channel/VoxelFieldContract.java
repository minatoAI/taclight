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

        // ---- 3. 4bit 打包位序 = GLSL 镜像:idx=x+y*dx+z*dx*dy;word=idx>>3;bit=(idx&7)*4 ----
        int[] data = new int[VoxelField.VOX_MAX_UINTS];
        VoxelField.pack(box2, 3, 2, 5, VoxelField.CODE_SOLID, data);
        int idx = 3 + 2 * box2.dx + 5 * box2.dx * box2.dy;
        check(((data[idx >> 3] >> ((idx & 7) * 4)) & 15) == VoxelField.CODE_SOLID, "GLSL 位序读回=CODE_SOLID");
        check(VoxelField.unpack(box2, 3, 2, 5, data) == VoxelField.CODE_SOLID, "unpack=CODE_SOLID");
        check(VoxelField.unpack(box2, 4, 2, 5, data) == VoxelField.CODE_EMPTY, "相邻体素不受污染");
        VoxelField.pack(box2, 4, 2, 5, VoxelField.CODE_LEAF, data);
        check(VoxelField.unpack(box2, 3, 2, 5, data) == VoxelField.CODE_SOLID
                && VoxelField.unpack(box2, 4, 2, 5, data) == VoxelField.CODE_LEAF, "同字双体素独立");
        VoxelField.pack(box2, 4, 2, 5, VoxelField.CODE_VEG, data);
        check(VoxelField.unpack(box2, 4, 2, 5, data) == VoxelField.CODE_VEG, "重写覆盖旧码");
        // 2026-09-25 高度感知:薄板码(4..15)必须能原样存取 —— 这正是 2bit→4bit 的目的
        int slab = VoxelField.slabBottomCode(1);
        VoxelField.pack(box2, 5, 2, 5, slab, data);
        check(VoxelField.unpack(box2, 5, 2, 5, data) == slab, "薄板码(雪 1 层=4)原样存取");
        check(VoxelField.unpack(box2, 3, 2, 5, data) == VoxelField.CODE_SOLID,
                "同字内 4bit 槽独立:5 号写入不污染 3 号");
        check(VoxelField.isSlab(slab) && VoxelField.slabLow(slab) == 0.0 && VoxelField.slabHigh(slab) == 0.125,
                "薄板码 4 ⇒ 区间 [0, 0.125]");
        int topSlab = VoxelField.slabTopCode(4);
        check(VoxelField.slabLow(topSlab) == 0.5 && VoxelField.slabHigh(topSlab) == 1.0,
                "顶薄板码 12 ⇒ 区间 [0.5, 1]");

        // ---- 4. Snapshot 语义 ----
        VoxelField.Snapshot snap = new VoxelField.Snapshot(box.ox, box.oy, box.oz,
                box.dx, box.dy, box.dz, data, 42L);
        check(snap.ox() == box.ox && snap.dx() == box.dx && snap.version() == 42L, "snapshot 字段直传");
        check(snap.usedUints() == (box.dx * box.dy * box.dz + VoxelField.VOXELS_PER_UINT - 1) / VoxelField.VOXELS_PER_UINT,
                "usedUints=ceil(voxels/8,4bit 打包)");

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

        // ---- 7. 盒收缩系数(2026-09-25 性能轮,证据 docs/evidence/2026-09-25-voxel-box/) ----
        // 目的:灯自身衰减在 0.5r 处只剩 12.5%、0.8r 处 2.6% ⇒ 盒外那段"本来就没多少光",
        // 缩盒的漏判代价有上界。契约钉死:默认零行为变更 + 收缩单调 + 灯心恒在盒内 + 非法值回 1.0。
        VoxelField.Box full = VoxelField.boxFor(List.of(L));
        VoxelField.Box same = VoxelField.boxFor(List.of(L), 1.0f);
        check(full.dx == same.dx && full.dy == same.dy && full.dz == same.dz
                        && full.ox == same.ox && full.oy == same.oy && full.oz == same.oz,
                "fraction=1.0 与旧签名逐字段一致(默认零行为变更)");
        VoxelField.Box half = VoxelField.boxFor(List.of(L), 0.5f);
        check(half.dx < full.dx && half.dy < full.dy && half.dz < full.dz, "fraction=0.5 三轴都缩小");
        // 红对照:若 fraction 被忽略,half == full ⇒ 本条必红。
        check(half.dx * half.dy * half.dz * 4 < full.dx * full.dy * full.dz,
                "fraction=0.5 体积 < 1/4(fraction 若被忽略必红)");
        check(half.ox <= 10 && 10 < half.ox + half.dx && half.oz <= 20 && 20 < half.oz + half.dz,
                "fraction=0.5 灯心仍在盒内(收缩不把灯挤出去)");
        check(VoxelField.boxFor(List.of(L), Float.NaN).dx == full.dx, "NaN ⇒ 回 1.0(旧行为)");
        VoxelField.Box zeroBox = VoxelField.boxFor(List.of(L), 0f);
        check(zeroBox.dx >= 1 && zeroBox.dx <= full.dx, "0 ⇒ 钳到下限且非退化");
        check(VoxelField.boxFor(List.of(L), 2.0f).dx == full.dx, ">1 ⇒ 回 1.0");
        VoxelField.Box minBox = VoxelField.boxFor(List.of(L), 0.01f);
        check(minBox.dx >= 1 && minBox.dx < half.dx, "0.01 ⇒ 钳到 MIN_BOX_FRACTION(更小且非退化)");
        check(minBox.ox <= 10 && 10 < minBox.ox + minBox.dx, "下限档灯心仍在盒内");

        // ---- 8. 锥形盒(2026-09-25 性能轮第二步) ----
        // 目的:只覆盖光锥 ⇒ 被照到的片元(必在锥内)遮挡判定与全尺寸球盒逐格一致;
        // 盒外只可能是照不到的方向。均匀缩盒做不到这点(实测远景地面变亮,已否)。
        SpotlightData coneL = SpotlightData.spot(10.6f, 64.4f, 20.3f, 18f,
                1f, 0.96f, 0.88f, 6f, 1f, 0f, 0f, 0.99f, 0.997f);
        VoxelField.Box cone = VoxelField.boxForCones(List.of(coneL), VoxelField.CONE_BOX_MARGIN);
        VoxelField.Box sphere = VoxelField.boxFor(List.of(coneL));
        int coneVol = cone.dx * cone.dy * cone.dz, sphereVol = sphere.dx * sphere.dy * sphere.dz;
        // 红对照:若 boxForCones 退化成球盒,本条必红。
        check(coneVol * 8 < sphereVol, "窄锥盒体积 < 球盒的 1/8(实测约 1/18)");
        check(cone.ox <= 10 && 10 < cone.ox + cone.dx && cone.oy <= 64 && 64 < cone.oy + cone.dy
                && cone.oz <= 20 && 20 < cone.oz + cone.dz, "锥尖(灯位)在盒内");
        double coneTan = Math.sqrt(1 - 0.99 * 0.99) / 0.99;
        double coneBaseX = 10.6 + 18.0;
        check(coneBaseX < cone.ox + cone.dx, "锥底心在盒内");
        double coneEdgeY = 64.4 + 18.0 * coneTan;
        check(coneEdgeY < cone.oy + cone.dy, "锥底边缘(垂直向 y)在盒内");
        double coneEdgeZ = 20.3 + 18.0 * coneTan;
        check(coneEdgeZ < cone.oz + cone.dz, "锥底边缘(垂直向 z)在盒内");
        double retreat = 10.6 - 2.0; // pos − dir×DESOLIDIFY_MAX
        check(cone.ox <= retreat && retreat < cone.ox + cone.dx,
                "灯位−dir×2.0 回退通道在盒内(clampOutOfSolid 不失效)");
        SpotlightData noDir = SpotlightData.spot(10.6f, 64.4f, 20.3f, 18f,
                1f, 0.96f, 0.88f, 6f, 0f, 0f, 0f, 0.99f, 0.997f);
        VoxelField.Box nb = VoxelField.boxForCones(List.of(noDir), VoxelField.CONE_BOX_MARGIN);
        check(nb.dx * nb.dy * nb.dz >= sphereVol, "方向退化 ⇒ 退回球盒(不缩,安全侧)");
        SpotlightData wideCone = SpotlightData.spot(10.6f, 64.4f, 20.3f, 18f,
                1f, 0.96f, 0.88f, 6f, 1f, 0f, 0f, 0.05f, 0.02f);
        VoxelField.Box wcb = VoxelField.boxForCones(List.of(wideCone), VoxelField.CONE_BOX_MARGIN);
        check(wcb.dx * wcb.dy * wcb.dz >= sphereVol, "锥过宽(cosOuter=0.05)⇒ 退回球盒");
        SpotlightData backCone = SpotlightData.spot(10.6f, 64.4f, 20.3f, 18f,
                1f, 0.96f, 0.88f, 6f, -1f, 0f, 0f, 0.99f, 0.997f);
        VoxelField.Box bothCone = VoxelField.boxForCones(List.of(coneL, backCone), VoxelField.CONE_BOX_MARGIN);
        check(bothCone.ox <= 10 && 10 < bothCone.ox + bothCone.dx && bothCone.dx > cone.dx,
                "两盏反向灯 ⇒ 盒同时含两灯且更宽");
        check(VoxelField.DEFAULT_CONE_BOX, "锥形盒为默认(2026-09-25 性能轮定案,见证据 README)");

        // ---- 9. 宽锥不得倒退(2026-09-25 晚,重场景轮实测:45° 锥的 AABB 比球盒还大) ----
        double cos45 = Math.cos(Math.toRadians(45));
        double sin45 = Math.sin(Math.toRadians(45));
        SpotlightData wide45 = SpotlightData.spot(10.6f, 64.4f, 20.3f, 18f,
                1f, 0.96f, 0.88f, 6f, 1f, 0f, 0f, (float) cos45, (float) cos45);
        VoxelField.Box w45 = VoxelField.boxForCones(List.of(wide45), VoxelField.CONE_BOX_MARGIN);
        VoxelField.Box w45sphere = VoxelField.boxFor(List.of(wide45));
        // 红对照:若不与"该灯的球盒"求交,45° 锥 AABB 为 43³ > 球盒 37³ ⇒ 本条必红。
        check(w45.dx <= w45sphere.dx && w45.dy <= w45sphere.dy && w45.dz <= w45sphere.dz
                        && w45.dx * w45.dy * w45.dz <= w45sphere.dx * w45sphere.dy * w45sphere.dz,
                "45° 宽锥盒不超过球盒(求交;否则宽锥会比重建优化前更差)");
        double hex = 10.6 + 18.0 * cos45;              // 锥面沿轴 L·cosθ
        double hey = 64.4 + 18.0 * sin45;              // 垂直向 L·sinθ(该点距灯恰为 r)
        check(w45.ox <= hex && hex < w45.ox + w45.dx && w45.oy <= hey && hey < w45.oy + w45.dy,
                "有效区极值点(锥面上距离恰=r)在盒内");

        // ---- 10. 转动节流决策(2026-09-25;真机实测转动时逐帧重建 60/s,静止 20/s) ----
        final float LAG = 12f, SHIFT = VoxelField.MAX_BOX_SHIFT_BLOCKS;
        // 红对照:把下面第 3 条的 maxLagDeg 当 0 用(即旧的"盒变就重建")⇒ 该条必红。
        check(VoxelField.shouldRebuild(true, false, 0f, 0f, LAG, SHIFT),
                "tick 到点必重建(50ms 安全网)");
        check(!VoxelField.shouldRebuild(false, false, 90f, 0f, LAG, SHIFT),
                "盒没变就不重建(方向转了也一样,盒=方向的函数)");
        check(!VoxelField.shouldRebuild(false, true, LAG - 0.5f, 0f, LAG, SHIFT),
                "转动未满阈值不重建(旧逻辑此处会重建 ⇒ 逐帧重建)");
        check(VoxelField.shouldRebuild(false, true, LAG, 0f, LAG, SHIFT),
                "转动满阈值必重建");
        check(VoxelField.shouldRebuild(false, true, 0f, SHIFT, LAG, SHIFT),
                "灯位置瞬时位移>=阈值必重建(瞬移兜底,即使没转动;不能用盒原点——转动也移动盒原点)");

        // ---- 11. 精确锥 AABB(2026-09-25 晚修正):张开量在轴 i 上的分量是 tan·√(1-n_i²) ----
        // 入射方向按实测的斜向(归一化),45° 锥:旧式(d_i±tan)会给出"向后 16 格"的假延伸。
        SpotlightData diag45 = SpotlightData.spot(10.6f, 64.4f, 20.3f, 18f,
                1f, 0.96f, 0.88f, 6f, 0.815f, -0.158f, -0.558f, (float) cos45, (float) cos45);
        VoxelField.Box dbox = VoxelField.boxForCones(List.of(diag45), VoxelField.CONE_BOX_MARGIN);
        // 独立算一遍"解析精确 AABB"(再与球盒求交),断言盒不超出它 ±1 格取整。
        double[] n = {0.815, -0.158, -0.558};
        double[] pos = {10.6, 64.4, 20.3};
        String[] ax = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            double t = Math.tan(Math.toRadians(45)) * Math.sqrt(Math.max(0.0, 1.0 - n[i] * n[i]));
            double lo = pos[i] + 18.0 * Math.min(0.0, n[i] - t) - VoxelField.CONE_BOX_MARGIN;
            double hi = pos[i] + 18.0 * Math.max(0.0, n[i] + t) + VoxelField.CONE_BOX_MARGIN;
            lo = Math.max(lo, pos[i] - 18.0);
            hi = Math.min(hi, pos[i] + 18.0);
            int o = i == 0 ? dbox.ox : i == 1 ? dbox.oy : dbox.oz;
            int d = i == 0 ? dbox.dx : i == 1 ? dbox.dy : dbox.dz;
            check(o >= (int) Math.floor(lo) - 1 && o + d <= (int) Math.ceil(hi) + 1,
                    "斜向 45° 锥的盒不超出解析精确 AABB@" + ax[i] + "(旧式 tan 会把向后延伸算大)");
        }

        System.out.println("VoxelFieldContract: ALL PASS (55 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

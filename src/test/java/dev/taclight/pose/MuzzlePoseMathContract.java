package dev.taclight.pose;

/** MuzzlePoseMath 契约:列主序矩阵 → origin/forward/up,含非法输入拒绝。 */
public class MuzzlePoseMathContract {
    public static void main(String[] args) {
        // 单位矩阵:origin=(0,0,0), forward=-Z, up=+Y
        float[] identity = new float[16];
        identity[0] = 1; identity[5] = 1; identity[10] = 1; identity[15] = 1;
        var r = MuzzlePoseMath.derive(identity);
        check(r.valid(), "identity valid");
        check(close(r.pose().ox(), 0) && close(r.pose().oz(), 0), "identity origin zero");
        check(close(r.pose().fx(), 0) && close(r.pose().fz(), -1), "identity forward = -Z");
        check(close(r.pose().uy(), 1), "identity up = +Y");

        // 平移 + X 轴旋转 90°:forward 应指向 -Y(绕 X 转 90°: -Z 变 -Y? 按右手系,Z→Y)
        // 验证正交性与长度归一化
        float[] rot = identity.clone();
        rot[12] = 1.25f; rot[13] = 2.5f; rot[14] = -0.75f;
        var r2 = MuzzlePoseMath.derive(rot);
        check(r2.valid(), "translated matrix valid");
        check(close(r2.pose().ox(), 1.25f) && close(r2.pose().oy(), 2.5f), "origin translation extracted");
        float len = (float) Math.sqrt(r2.pose().fx() * r2.pose().fx() + r2.pose().fy() * r2.pose().fy() + r2.pose().fz() * r2.pose().fz());
        check(close(len, 1.0f), "forward normalized");

        // 非法:坏矩阵(长度错)/退化(零向量)
        check(!MuzzlePoseMath.derive(new float[9]).valid(), "wrong length rejected");
        float[] degenerate = identity.clone();
        degenerate[8] = 0; degenerate[9] = 0; degenerate[10] = 0;
        check(!MuzzlePoseMath.derive(degenerate).valid(), "degenerate rejected");
        // 节点名约定
        check(MuzzlePoseMath.supportedNodeName("laser_beam"), "laser_beam supported");
        check(MuzzlePoseMath.supportedNodeName("laser_beam_2"), "laser_beam_2 supported");
        check(!MuzzlePoseMath.supportedNodeName("scope"), "other node rejected");

        // ---- 枪渲染空间(GL 视图,-Z 前)→ 世界(2026-09-02 坑60 标定)----
        // 相机 yaw=180 pitch=25(=09-02 DIAG 实测参数);vanilla 构造 rotationYXZ(-yaw, pitch, 0)。
        // 旧 conjugate 实验值 = -look(y/z 双翻);正确值 = 玩家视线 (0,-sin25,-cos25)。
        var camQ = new org.joml.Quaternionf().rotationYXZ(
                (float) Math.toRadians(180), (float) Math.toRadians(25), 0f);
        var d = MuzzlePoseMath.gunViewDirToWorld(0f, 0f, -1f, camQ);
        check(close(d.x(), 0f), "view fwd->world x=0");
        check(close(d.y(), -(float) Math.sin(Math.toRadians(25))), "view fwd->world y=-sin25(非 +)");
        check(close(d.z(), -(float) Math.cos(Math.toRadians(25))), "view fwd->world z=-cos25(非 +)");
        // GL 视图右 (1,0,0) -> 面北(yaw180)时世界右 = 东(+X)
        var rt = MuzzlePoseMath.gunViewDirToWorld(1f, 0f, 0f, camQ);
        check(close(rt.x(), 1f) && close(rt.y(), 0f) && close(rt.z(), 0f), "view right->world east(+X)");
        // GL 视图上 (0,1,0) -> 俯视 25° 时上向应后仰(z 分量为负)
        var upv = MuzzlePoseMath.gunViewDirToWorld(0f, 1f, 0f, camQ);
        check(close(upv.y(), (float) Math.cos(Math.toRadians(25))), "view up->world y=cos25");
        check(close(upv.z(), -(float) Math.sin(Math.toRadians(25))), "view up->world z=-sin25(后仰)");

        // ---- 第三人称换算(2026-09-02 里程碑②实机标定:与坑60 FP 同构 Q·Ry180)----
        // 实测样本(09-02 10:39 DIAG-TP):camQ=(0.383,0,0,0.924)(B 机位 yRot=0,xRot=45,
        // 日志 3 位舍入),offRaw=(-7.41,-1.03,-3.95)。Q·v 落点偏 16.9 格;唯 Q·Ry180
        // 距枪口线 1.0 格(tools/tp-space-solve.js 可复算)。
        var camQ45 = new org.joml.Quaternionf(0.383f, 0f, 0f, 0.924f);
        var tw = MuzzlePoseMath.muzzleViewDirToWorldTP(-7.41f, -1.03f, -3.95f, camQ45);
        // 期望值 = joml rotate(归一化真旋转)输出;与脚本单位假设式差 0.05%(|q|²=1.000465,
        // 3 位舍入 camQ 所致),物理位置差 7mm 级,判别余量 12 格。
        check(close3(tw.x, 7.410f) && close3(tw.y, -3.522f) && close3(tw.z, 2.063f),
                "TP = Q·Ry180·v(09-02 DIAG-TP 实测样本钉死)");
        // 结构一致:两路捕获空间与换算完全同构(YP180 翻转源自 level 渲染栈,FP/TP 共享)
        var viaFp = MuzzlePoseMath.gunViewDirToWorld(0f, 0f, -1f, camQ);
        var viaTp = MuzzlePoseMath.muzzleViewDirToWorldTP(0f, 0f, -1f, camQ);
        check(close(viaFp.x(), viaTp.x()) && close(viaFp.y(), viaTp.y()) && close(viaFp.z(), viaTp.z()),
                "FP == TP(共享 level 栈 Ry180 标定)");
        // 方向样本:束骨 +Z 列 = -(旧 deepNegZ 日志值)归一(束沿骨局部 +Z 拉伸)
        // → 与 look=(0.845,-0.171,-0.507) 夹角 28.8°(TaCZ 腰射持枪下垂,合理窗内;
        // 成对差值方向 73° 已退役)
        var col2n = new org.joml.Vector3f(-0.475f, -0.264f, -0.146f).normalize();
        var dw = MuzzlePoseMath.muzzleViewDirToWorldTP(col2n.x, col2n.y, col2n.z, camQ45);
        float cosLook = dw.x * 0.845f + dw.y * -0.171f + dw.z * -0.507f;
        check(Math.abs(cosLook - 0.876f) < 0.01f, "TP 束向与 look 夹角 28.8°(实测钉死)");
        // ---- 束向量成对捕获的方向归一(2026-09-02 TP 实机标定:深浅平移之差=束向量)----
        float[] bd = MuzzlePoseMath.normalizeBeamDelta(0f, 0f, -5f);
        check(bd != null && close(bd[0], 0f) && close(bd[1], 0f) && close(bd[2], -1f),
                "束向量归一:模长 5 的 -Z → 单位 -Z");
        float[] d2 = MuzzlePoseMath.normalizeBeamDelta(1f, 1f, 1f);
        check(d2 != null && close(d2[0], (float) (1 / Math.sqrt(3)))
                && close(d2[1], (float) (1 / Math.sqrt(3))) && close(d2[2], (float) (1 / Math.sqrt(3))),
                "束向量归一:对角向量");
        check(MuzzlePoseMath.normalizeBeamDelta(0f, 0f, 0f) == null, "零向量 → null(放弃捕获)");
        check(MuzzlePoseMath.normalizeBeamDelta(Float.NaN, 0f, 1f) == null, "NaN → null");
        check(MuzzlePoseMath.normalizeBeamDelta(Float.POSITIVE_INFINITY, 0f, 1f) == null, "Inf → null");
        // ---- 束方向离体校正(2026-09-02 里程碑②:实机反平行翻转,灯照持枪者本人)----
        // 物理不变式:束从枪口向外延伸,dot(fwd, 枪口−眼睛) < 0 必为翻转,取反。
        float[] a1 = MuzzlePoseMath.alignBeamAway(0.5f, 0f, 0.5f, 1f, 0f, 0f);
        check(a1[0] == 0.5f && a1[1] == 0f && a1[2] == 0.5f, "离体校正:已向外,原样");
        float[] a2 = MuzzlePoseMath.alignBeamAway(-0.5f, 0f, -0.5f, 1f, 0f, 0f);
        check(a2[0] == 0.5f && a2[1] == 0f && a2[2] == 0.5f, "离体校正:反平行,取反(实机 155° 样本语义)");
        float[] a3 = MuzzlePoseMath.alignBeamAway(0f, 1f, 0f, 1f, 0f, 0f);
        check(a3[0] == 0f && a3[1] == 1f && a3[2] == 0f, "离体校正:垂直(dot=0),原样");
        float[] a4 = MuzzlePoseMath.alignBeamAway(1f, 0f, 0f, 0f, 0f, 0f);
        check(a4[0] == 1f && a4[1] == 0f && a4[2] == 0f, "离体校正:零锚向量,原样");
        // 实机复现场景:翻转束 (−0.629,0.507,0.589),枪口−眼 (0.7,−0.39,−0.6) → 取反
        float[] a5 = MuzzlePoseMath.alignBeamAway(-0.629f, 0.507f, 0.589f, 0.7f, -0.39f, -0.6f);
        check(a5[0] > 0 && a5[1] < 0 && a5[2] < 0, "离体校正:09-02 实机翻转样本取正");
        System.out.println("MuzzlePoseMathContract: ALL PASS (31 checks)");
    }

    private static boolean close(float a, float b) { return Math.abs(a - b) < 1e-5f; }
    /** 实测样本钉死用:日志值 3 位舍入,容差放宽到 1e-3。 */
    private static boolean close3(float a, float b) { return Math.abs(a - b) < 1e-3f; }
    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

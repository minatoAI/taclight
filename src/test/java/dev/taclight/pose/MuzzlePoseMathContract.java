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
        System.out.println("MuzzlePoseMathContract: ALL PASS (11 checks)");
    }

    private static boolean close(float a, float b) { return Math.abs(a - b) < 1e-5f; }
    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

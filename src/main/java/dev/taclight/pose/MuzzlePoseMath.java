package dev.taclight.pose;

/**
 * 枪口姿态纯数学:从列主序 4x4 矩阵抽取 origin / forward(-Z) / up(+Y),
 * 带有限性与正交性校验(继承旧项目 G2LaserPoseMath 的标定约定)。
 */
public final class MuzzlePoseMath {
    public record Pose(float ox, float oy, float oz, float fx, float fy, float fz, float ux, float uy, float uz) {}
    public record Result(boolean valid, String reason, Pose pose) {
        public static Result invalid(String r) { return new Result(false, r, new Pose(0, 0, 0, 0, 0, 0, 0, 0, 0)); }
    }

    private static final float MIN_AXIS_LENGTH = 1e-6f;
    private static final float MAX_ORTHOGONAL_DOT = 1e-3f;

    private MuzzlePoseMath() {}

    public static boolean supportedNodeName(String name) {
        return name != null && name.matches("laser_beam(?:_\\d+)?");
    }

    public static Result derive(float[] m) {
        if (m == null || m.length != 16) {
            return Result.invalid("MISSING_MATRIX");
        }
        for (float c : m) {
            if (!Float.isFinite(c)) return Result.invalid("NON_FINITE_MATRIX");
        }
        double fx = -m[8], fy = -m[9], fz = -m[10];
        double ux = m[4], uy = m[5], uz = m[6];
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (fl <= MIN_AXIS_LENGTH || ul <= MIN_AXIS_LENGTH) {
            return Result.invalid("DEGENERATE_AXES");
        }
        double dot = (fx * ux + fy * uy + fz * uz) / (fl * ul);
        if (!Double.isFinite(dot) || Math.abs(dot) > MAX_ORTHOGONAL_DOT) {
            return Result.invalid("NON_ORTHOGONAL_AXES");
        }
        Pose p = new Pose(m[12], m[13], m[14],
                (float) (fx / fl), (float) (fy / fl), (float) (fz / fl),
                (float) (ux / ul), (float) (uy / ul), (float) (uz / ul));
        return new Result(true, "OK", p);
    }

    /**
     * 枪渲染空间(GL 视图空间,-Z 为前)→ 世界方向/偏移(2026-09-02 坑60 标定)。
     * Camera.rotation() 采用 MC 约定(+Z 为前),故先绕 Y 翻 180° 对齐两套前向,
     * 再按相机旋转到世界。旧上传器实现用共轭,产出恒为 -look(y/z 双翻)——
     * DIAG L0 实测钉死:dir=(0.003,0.434,0.901) vs look=(0,-0.423,-0.906)。
     * 换算 = Q_cam · Ry(180°) · v(joml mul 语义:右侧先作用)。
     */
    public static org.joml.Vector3f gunViewDirToWorld(float vx, float vy, float vz,
                                                      org.joml.Quaternionf camRotation) {
        org.joml.Quaternionf q = new org.joml.Quaternionf(camRotation)
                .mul(new org.joml.Quaternionf().rotationY((float) Math.PI));
        return new org.joml.Vector3f(vx, vy, vz).rotate(q);
    }

    /**
     * 第三人称捕获(level 渲染 PoseStack,相机空间、含实体平移)→ 世界方向/偏移
     * (2026-09-02 里程碑②实机标定):与第一人称坑60 同构 —— YP180 翻转源自 level
     * 渲染栈而非手部渲染私有,换算同为 Q_cam · Ry(180°) · v。DIAG-TP 实测钉死:
     * Q·v 落点偏 16.9 格,唯 Q·Ry180 距枪口线 1.0 格(tools/tp-space-solve.js 可复算)。
     * 轴向语义:TP 捕获 origin=束起点平移、forward=束骨局部 +Z 列归一(与 FP 的
     * -Z 前向约定相反,符号由捕获侧负责,本函数只做空间映射)。
     */
    public static org.joml.Vector3f muzzleViewDirToWorldTP(float vx, float vy, float vz,
                                                           org.joml.Quaternionf camRotation) {
        return gunViewDirToWorld(vx, vy, vz, camRotation);
    }

    /**
     * 束向量成对捕获的方向归一(2026-09-02 TP 标定):renderLaserBeam 外层 HEAD 与深处
     * (束骨遍历后)两次矩阵平移之差 = 视空间束方向向量(模长=束长)。非有限或长度
     * 退化(<1e-3 视空间)→ null,调用方放弃本次捕获(灯回退近似锚点)。
     */
    public static float[] normalizeBeamDelta(float dx, float dy, float dz) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(dz)) {
            return null;
        }
        double len = Math.sqrt(dx * (double) dx + dy * (double) dy + dz * (double) dz);
        if (len < 1e-3) {
            return null;
        }
        return new float[]{(float) (dx / len), (float) (dy / len), (float) (dz / len)};
    }

    /**
     * 束方向离体校正(2026-09-02 里程碑②实机标定):捕获的束骨 +Z 列在世界中偶发
     * 反平行翻转(远程步行动画状态下,位置链不受影响),灯会照到持枪者本人(实机
     * 截图:亮斑在头/胸)。物理不变式:束从枪口(into=枪口−眼睛)向外延伸,故
     * dot(fwd, into) < 0 时取反;垂直或已向外则原样返回。零向量 into 无法判定,
     * 原样返回。纯函数,FP/TP 通用。
     */
    public static float[] alignBeamAway(float fx, float fy, float fz,
                                        float ax, float ay, float az) {
        double dot = fx * (double) ax + fy * (double) ay + fz * (double) az;
        if (dot < 0) {
            return new float[]{-fx, -fy, -fz};
        }
        return new float[]{fx, fy, fz};
    }
}

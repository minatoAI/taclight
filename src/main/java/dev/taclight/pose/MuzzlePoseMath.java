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
}

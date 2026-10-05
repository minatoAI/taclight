package dev.taclight.channel;

/**
 * S4a 自渲 shadow map 的纯数学核(2026-10-05,零 MC 依赖、JVM 可测)。
 *
 * <p>约定(与包侧 {@code taclight_shadowdepth_vis} 逐式镜像,改一处必须改另一处,
 * 由 {@link ShadowDepthBakerContract} 的往返测试钉死):</p>
 * <ol>
 *   <li>锥体 = 以灯位为顶点、灯向为轴、半角 = 外锥角({@code acos(cosOuter)})的正透视锥;
 *       近平面 {@link #NEAR}、远平面 = 灯半径。</li>
 *   <li>texel→方向:{@code nx = ((tx+0.5)/SIZE)*2-1},{@code ny = 1-((ty+0.5)/SIZE)*2}
 *       (ty=0 为上);{@code dir = norm(fwd + right*nx*tanHalf + up*ny*tanHalf)}。</li>
 *   <li>点→texel 浮坐标是上式的逆:{@code fx = ((nx+1)/2)*SIZE-0.5},
 *       {@code fy = ((1-ny)/2)*SIZE-0.5}。包侧 PCF 用 <b>同一式</b>
 *       (不是 {@code *(SIZE-1)} 缩放——差半个 texel,错了孔洞会对不齐)。</li>
 *   <li>depth 编码 = 沿射线距离的线性归一化 {@code (dist-near)/(far-near)};
 *       未命中 = 1.0(远平面 ⇒ 片元恒 {@code fragD <= 1+bias} ⇒ 亮,正确)。</li>
 *   <li>头段 20 float = 包侧 {@code sdMeta0..4}(5×vec4)逐字对应,见 {@link #writeHead}。</li>
 * </ol>
 */
public final class ShadowDepthBaker {
    /** depth 图边长(正方形)。S4a 离线一次,512×512 = 262144 条 ray。 */
    public static final int SIZE = 512;
    /** 自有 SSBO 绑定号(binding=7 是灯+体素,8 是数值探针,9 给 depth)。 */
    public static final int BINDING = 9;
    /** 头段 float 数(sdMeta0..4)。 */
    public static final int HEAD_FLOATS = 20;
    /** depth 区 float 数。 */
    public static final int DEPTH_FLOATS = SIZE * SIZE;
    /** 缓冲总 float 数(头 + depth)。 */
    public static final int TOTAL_FLOATS = HEAD_FLOATS + DEPTH_FLOATS;
    /** 近平面(灯头自遮挡豁免,格)。 */
    public static final float NEAR = 0.3f;

    private ShadowDepthBaker() {}

    /**
     * 灯向 → 正交基(9 double:[fx,fy,fz, rx,ry,rz, ux,uy,uz])。
     * fwd 接近 ±Y 时参考轴换成 +X(手电朝正下方是最常用的姿态,不断言会炸)。
     */
    public static double[] basis(double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1e-9)) return new double[]{0, 0, -1, 1, 0, 0, 0, 1, 0};
        double fx = dx / len, fy = dy / len, fz = dz / len;
        double tx = 0, ty = 1, tz = 0;
        if (Math.abs(fy) > 0.99) { tx = 1; ty = 0; tz = 0; }
        // right = norm(fwd × tmp)
        double rx = fy * tz - fz * ty, ry = fz * tx - fx * tz, rz = fx * ty - fy * tx;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl; ry /= rl; rz /= rl;
        // up = right × fwd(已正交归一,无需再归一)
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        return new double[]{fx, fy, fz, rx, ry, rz, ux, uy, uz};
    }

    /** 半视场角正切 = tan(acos(cosOuter))。 */
    public static double tanHalf(double cosOuter) {
        double c = Math.min(1.0, Math.max(0.0, cosOuter));
        if (c < 1e-6) return 1e6;
        double s = Math.sqrt(Math.max(0.0, 1.0 - c * c));
        return s / c;
    }

    /**
     * texel 中心 → 世界射线方向(归一化)。
     *
     * @param b {@link #basis} 的 9 double
     */
    public static double[] rayDirForTexel(int tx, int ty, double[] b, double tanHalf) {
        double nx = ((tx + 0.5) / SIZE) * 2.0 - 1.0;
        double ny = 1.0 - ((ty + 0.5) / SIZE) * 2.0;
        double dx = b[0] + b[3] * nx * tanHalf + b[6] * ny * tanHalf;
        double dy = b[1] + b[4] * nx * tanHalf + b[7] * ny * tanHalf;
        double dz = b[2] + b[5] * nx * tanHalf + b[8] * ny * tanHalf;
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{dx / l, dy / l, dz / l};
    }

    /**
     * 世界点(相对灯位 rel=world-pos) → {fx, fy, t}(texel 浮坐标 + 沿 fwd 距离)。
     * 锥外 / 近平面后 / 远平面外 ⇒ null(调用方按"不覆盖"处理,包侧同语义返回 -1)。
     */
    public static double[] texelFloatForPoint(double rx, double ry, double rz,
                                              double[] b, double tanHalf,
                                              double near, double far) {
        double t = rx * b[0] + ry * b[1] + rz * b[2];
        if (!(t >= near) || !(t <= far)) return null;
        double ox = rx - b[0] * t, oy = ry - b[1] * t, oz = rz - b[2] * t;
        double r = t * tanHalf;
        if (!(r > 1e-9)) return null;
        double nx = (ox * b[3] + oy * b[4] + oz * b[5]) / r;
        double ny = (ox * b[6] + oy * b[7] + oz * b[8]) / r;
        if (Math.abs(nx) > 1.0 || Math.abs(ny) > 1.0) return null;
        double fx = ((nx + 1.0) * 0.5) * SIZE - 0.5;
        double fy = ((1.0 - ny) * 0.5) * SIZE - 0.5;
        return new double[]{fx, fy, t};
    }

    /** 距离 → 编码(钳 0..1;未命中由调用方写 {@code MISS=1.0},不在此分支)。 */
    public static float encode(double dist, double near, double far) {
        double d = (dist - near) / Math.max(far - near, 1e-6);
        if (!(d >= 0.0)) return 0.0f;
        if (!(d <= 1.0)) return 1.0f;
        return (float) d;
    }

    /**
     * 头段 20 float(包侧 sdMeta0..4 逐字对应):
     * [0]=valid(1/0) [1]=size [2]=near [3]=far [4]=tanHalf [5..7]=pos
     * [8..10]=fwd [11..13]=right [14..16]=up [17..19]=0 保留。
     */
    public static void writeHead(float[] buf, boolean valid,
                                 double px, double py, double pz,
                                 double[] b, double tanHalf,
                                 double near, double far) {
        buf[0] = valid ? 1.0f : 0.0f;
        buf[1] = SIZE;
        buf[2] = (float) near;
        buf[3] = (float) far;
        buf[4] = (float) tanHalf;
        buf[5] = (float) px; buf[6] = (float) py; buf[7] = (float) pz;
        buf[8] = (float) b[0]; buf[9] = (float) b[1]; buf[10] = (float) b[2];
        buf[11] = (float) b[3]; buf[12] = (float) b[4]; buf[13] = (float) b[5];
        buf[14] = (float) b[6]; buf[15] = (float) b[7]; buf[16] = (float) b[8];
        buf[17] = 0; buf[18] = 0; buf[19] = 0;
    }
}

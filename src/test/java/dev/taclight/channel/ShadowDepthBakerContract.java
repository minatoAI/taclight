package dev.taclight.channel;

/**
 * S4a 数学核契约(2026-10-05):钉死"Java 烘焙 ↔ GLSL 采样"必须逐式一致的三件事
 * (错半个 texel,孔洞就对不齐,而画面不会报错——与调色板布局错位同类静默坏)。
 *
 * <ol>
 *   <li>基正交归一(含朝正下方手电姿态不断言)。</li>
 *   <li>往返一致:texel→方向→点→texel 浮坐标,误差 &lt; 1e-6(种子固定,非随机)。</li>
 *   <li>头段自洽:writeHead 写后按 sdMeta 语义读回逐值一致;encode 单调且端点精确。</li>
 * </ol>
 */
public final class ShadowDepthBakerContract {
    private static int checks;
    private static int fails;

    public static void main(String[] args) {
        basisOrtho();
        centerAndRoundtrip();
        edgeAndOutside();
        encodeAndHead();
        System.out.println("ShadowDepthBakerContract: " + (fails == 0 ? "ALL PASS" : "FAILED")
                + " (" + checks + " checks, fails=" + fails + ")");
        if (fails > 0) throw new AssertionError("ShadowDepthBakerContract FAILED: " + fails + "/" + checks);
    }

    private static void check(boolean cond, String why) {
        checks++;
        if (!cond) {
            fails++;
            System.out.println("  FAIL: " + why);
        }
    }

    private static void basisOrtho() {
        double[][] dirs = {{0.3, -0.8, 0.5}, {0, -1, 0}, {0, 1, 0}, {1, 0.2, 0.1}, {-0.5, 0.5, -0.7}};
        for (double[] d : dirs) {
            double[] b = ShadowDepthBaker.basis(d[0], d[1], d[2]);
            double lf = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
            double lr = Math.sqrt(b[3] * b[3] + b[4] * b[4] + b[5] * b[5]);
            double lu = Math.sqrt(b[6] * b[6] + b[7] * b[7] + b[8] * b[8]);
            double dfr = b[0] * b[3] + b[1] * b[4] + b[2] * b[5];
            double dfu = b[0] * b[6] + b[1] * b[7] + b[2] * b[8];
            double dru = b[3] * b[6] + b[4] * b[7] + b[5] * b[8];
            check(Math.abs(lf - 1) < 1e-12 && Math.abs(lr - 1) < 1e-12 && Math.abs(lu - 1) < 1e-12
                            && Math.abs(dfr) < 1e-12 && Math.abs(dfu) < 1e-12 && Math.abs(dru) < 1e-12,
                    "基正交归一 dir=(" + d[0] + "," + d[1] + "," + d[2] + ")");
        }
        double[] z = ShadowDepthBaker.basis(0, 0, 0);
        check(z[2] == -1.0, "零向量不断言(退化保护)");
    }

    private static void centerAndRoundtrip() {
        double[] b = ShadowDepthBaker.basis(0.3, -0.8, 0.5);
        double tanHalf = ShadowDepthBaker.tanHalf(0.9659); // ≈15° 外锥
        double near = ShadowDepthBaker.NEAR, far = 24.0;
        // 中心 2×2 texel 的平均方向 ≈ 灯向(偶数 SIZE 无正中心,用平均)
        double[] acc = {0, 0, 0};
        int c = ShadowDepthBaker.SIZE / 2;
        for (int ty = c - 1; ty <= c; ty++) {
            for (int tx = c - 1; tx <= c; tx++) {
                double[] d = ShadowDepthBaker.rayDirForTexel(tx, ty, b, tanHalf);
                acc[0] += d[0]; acc[1] += d[1]; acc[2] += d[2];
            }
        }
        double l = Math.sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2]);
        double cos = (acc[0] / l) * b[0] + (acc[1] / l) * b[1] + (acc[2] / l) * b[2];
        check(cos > 0.999999, "中心 4 texel 平均方向 ≈ 灯向 cos=" + cos);
        // 往返:固定种子点集(非随机,逐次一致)
        long s = 0x54A5EEDL;
        double maxErr = 0;
        for (int k = 0; k < 200; k++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int tx = (int) ((s >>> 33) % ShadowDepthBaker.SIZE);
            s = s * 6364136223846793005L + 1442695040888963407L;
            int ty = (int) ((s >>> 33) % ShadowDepthBaker.SIZE);
            s = s * 6364136223846793005L + 1442695040888963407L;
            double frac = ((s >>> 11) & 0x1FFFFF) / (double) 0x1FFFFF;
            double[] d = ShadowDepthBaker.rayDirForTexel(tx, ty, b, tanHalf);
            double dist = near + (far - near) * (0.05 + 0.9 * frac);
            double[] back = ShadowDepthBaker.texelFloatForPoint(
                    d[0] * dist, d[1] * dist, d[2] * dist, b, tanHalf, near, far);
            check(back != null, "往返不应落锥外 tx=" + tx + " ty=" + ty);
            if (back != null) {
                maxErr = Math.max(maxErr, Math.abs(back[0] - tx));
                maxErr = Math.max(maxErr, Math.abs(back[1] - ty));
                // back[2] 是沿 fwd 投影 t(不是沿射线长度 dist):期望值 = dist*(d·fwd)
                double wantT = dist * (d[0] * b[0] + d[1] * b[1] + d[2] * b[2]);
                maxErr = Math.max(maxErr, Math.abs(back[2] - wantT) / wantT);
            }
        }
        check(maxErr < 1e-6, "200 点往返最大误差 < 1e-6,实测 " + maxErr);
    }

    private static void edgeAndOutside() {
        double[] b = ShadowDepthBaker.basis(0, -1, 0); // 朝正下方(最常用姿态)
        double tanHalf = ShadowDepthBaker.tanHalf(0.9659);
        double near = ShadowDepthBaker.NEAR, far = 24.0;
        // 边中 texel 方向与灯向夹角 ≈ 半视场角(差半个 texel 以内)
        int c = ShadowDepthBaker.SIZE / 2;
        double[] de = ShadowDepthBaker.rayDirForTexel(ShadowDepthBaker.SIZE - 1, c, b, tanHalf);
        double cosE = de[0] * b[0] + de[1] * b[1] + de[2] * b[2];
        double half = Math.acos(0.9659), got = Math.acos(Math.min(1.0, cosE));
        check(Math.abs(got - half) < half / ShadowDepthBaker.SIZE * 2,
                "边中夹角 ≈ 半视场角(差<2 texel) got=" + got + " want=" + half);
        // 锥外点 ⇒ null(包侧同语义返回 -1 ⇒ 不覆盖)
        check(ShadowDepthBaker.texelFloatForPoint(10 * far, 0, 0, b, tanHalf, near, far) == null,
                "锥外点 ⇒ null");
        check(ShadowDepthBaker.texelFloatForPoint(0, -0.1, 0, b, tanHalf, near, far) == null,
                "近平面后 ⇒ null");
        check(ShadowDepthBaker.texelFloatForPoint(0, -(far + 5), 0, b, tanHalf, near, far) == null,
                "远平面外 ⇒ null");
        // 阳性对照:同一次执行里 null 与非 null 同时出现(判据非恒真/恒假)
        check(ShadowDepthBaker.texelFloatForPoint(0, -5, 0, b, tanHalf, near, far) != null,
                "锥内点 ⇒ 非 null(阳性对照)");
    }

    private static void encodeAndHead() {
        double near = ShadowDepthBaker.NEAR, far = 24.0;
        check(ShadowDepthBaker.encode(near, near, far) == 0.0f, "encode(near)=0");
        check(ShadowDepthBaker.encode(far, near, far) == 1.0f, "encode(far)=1");
        check(ShadowDepthBaker.encode(near - 1, near, far) == 0.0f, "近前钳 0");
        check(ShadowDepthBaker.encode(far + 99, near, far) == 1.0f, "远后钳 1");
        float a = ShadowDepthBaker.encode(5, near, far);
        float c = ShadowDepthBaker.encode(10, near, far);
        check(a > 0 && c > a && c < 1, "单调且开区间");
        float[] buf = new float[ShadowDepthBaker.TOTAL_FLOATS];
        double[] b = ShadowDepthBaker.basis(0.3, -0.8, 0.5);
        ShadowDepthBaker.writeHead(buf, true, 1, 2, 3, b, 0.27, near, far);
        check(buf[0] == 1.0f && buf[1] == ShadowDepthBaker.SIZE
                        && buf[2] == (float) near && buf[3] == (float) far && buf[4] == 0.27f
                        && buf[5] == 1.0f && buf[6] == 2.0f && buf[7] == 3.0f
                        && buf[8] == (float) b[0] && buf[11] == (float) b[3] && buf[14] == (float) b[6]
                        && buf[17] == 0 && buf[18] == 0 && buf[19] == 0,
                "头段 20 float 与 sdMeta0..4 逐字对应");
        check(ShadowDepthBaker.TOTAL_FLOATS
                        == ShadowDepthBaker.HEAD_FLOATS + ShadowDepthBaker.DEPTH_FLOATS,
                "总量程 = 头 + depth(1MB 级,上传口径)");
    }
}

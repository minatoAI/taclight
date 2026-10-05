package dev.taclight.pose;

import org.joml.Vector3d;

/**
 * MuzzlePoseModel 契约(2026-09-03 方案B,CPU 侧枪口姿态模型 v1=方向族,纯 JVM):
 *
 * 背景(用户实机两现象 + 313 帧 fresh 捕获拟合,evidence/2026-09-02-tp-walkin-jump
 * s0001+s0003):屏外 fallback 方向=头部视线,而真枪口移动族俯仰在视线下方 6.0°
 * (步行/疾跑同族,rms 0.35°)、静止族=2.0°;hold 判破瞬间 fallbackDir 在族间跳变
 * (8m 处光池 ~0.9m 摆动)= "入场跳变/屏外光晕钉在头部前方"的主体之一。模型把
 * fallback 方向升级为"视线+姿态族俯仰偏移",屏内外方向族连续,入场交接差量缩到
 * 捕获摆动残差(~0.35°)。
 *
 * <b>俯仰约定 = MC xRot(2026-09-03 实机钉死,首版反相修复)</b>:输入 pitch 为
 * MC 实体 X 旋转——<b>正=低头,负=抬头</b>(Vec3.directionFromRotation 同系);
 * "枪口在视线下方"= xRot 正增量。首版误按"正=抬头"+y=sin(p) 合成,实机
 * G 行(s0010):livePitch=+8.4°(低头)时束向 pitch=+6.4°(朝上)= 用户
 * "往上看灯朝下照地面"的直接根因。合成公式必须为
 * dir=(−sin(yaw)·cos(p), −sin(p), cos(yaw)·cos(p)),p=xRot+族偏移。
 *
 * 偏航摆 ±1.76°@8.75rad/s(R²=0.91)不建模:自由振荡器无法与动画锁相,
 * 平均收益为负。位置族差 ≤0.04 格,由 DEFAULT 先验+捕获自校准覆盖,模型不碰位置。
 */
public class MuzzlePoseModelContract {
    private static int n = 0;

    public static void main(String[] args) {
        // ---- 标定常数钉死(MC xRot 增量语义:正=枪口压到视线下方;改动必须重跑拟合) ----
        check(MuzzlePoseModel.PITCH_IDLE_DEG == 2.0f, "静止族俯仰偏移钉死 +2.0°(xRot 增量,枪口低于视线 2°,n=113)");
        check(MuzzlePoseModel.PITCH_MOVE_DEG == 6.0f, "移动族俯仰偏移钉死 +6.0°(walk n=131 + sprint n=51 同族,rms 0.35°)");
        check(MuzzlePoseModel.ENV_MIN_SPEED == 0.3f && MuzzlePoseModel.ENV_MAX_SPEED == 1.2f,
                "移动包络速度窗钉死 0.3→1.2 m/s");

        // ---- 移动包络:smoothstep 单调,边界饱和 ----
        check(MuzzlePoseModel.moveEnvelope(0.0f) == 0f, "包络(0m/s)=0(静止)");
        check(MuzzlePoseModel.moveEnvelope(0.3f) == 0f, "包络(下限)=0");
        check(MuzzlePoseModel.moveEnvelope(1.2f) == 1f, "包络(上限)=1");
        check(MuzzlePoseModel.moveEnvelope(5.6f) == 1f, "包络(疾跑)=1(饱和)");
        check(Math.abs(MuzzlePoseModel.moveEnvelope(0.75f) - 0.5f) < 1e-6, "包络(中点 0.75)=0.5");
        float prev = -1f;
        boolean mono = true;
        for (int i = 0; i <= 40; i++) {
            float v = MuzzlePoseModel.moveEnvelope(0.3f + i * 1.0f / 40f);
            if (v < prev) mono = false;
            prev = v;
        }
        check(mono, "包络单调不减(0.3→1.3 扫描)");

        // ---- 俯仰偏移:族间线性(xRot 增量,正=往下压) ----
        check(Math.abs(MuzzlePoseModel.pitchOffsetDeg(0f) - 2.0f) < 1e-6, "俯仰偏移(env=0)=+2.0°");
        check(Math.abs(MuzzlePoseModel.pitchOffsetDeg(1f) - 6.0f) < 1e-6, "俯仰偏移(env=1)=+6.0°");
        check(Math.abs(MuzzlePoseModel.pitchOffsetDeg(0.5f) - 4.0f) < 1e-6, "俯仰偏移(env=0.5)=+4.0°");

        // ---- 方向合成:MC 约定 yaw0=+z,90=−x;xRot 正=低头(look.y=−sin(xRot)) ----
        // 纯数学基线:dir=(−sin(yaw)·cos(p), −sin(p), cos(yaw)·cos(p)),p=xRot+族偏移
        Vector3d dIdle = MuzzlePoseModel.dirFromModel(-90f, 0f, 0f, false);
        double c2 = Math.cos(Math.toRadians(2.0));
        check(Math.abs(dIdle.x() - c2) < 1e-6 && Math.abs(dIdle.y() + Math.sin(Math.toRadians(2.0))) < 1e-6
                && Math.abs(dIdle.z()) < 1e-6, "面东平视静止方向=(cos2°,−sin2°,0)(枪口低于视线 2°)");

        Vector3d dMove = MuzzlePoseModel.dirFromModel(-90f, 0f, 1f, false);
        double c6 = Math.cos(Math.toRadians(6.0));
        check(Math.abs(dMove.x() - c6) < 1e-6 && Math.abs(dMove.y() + Math.sin(Math.toRadians(6.0))) < 1e-6
                && Math.abs(dMove.z()) < 1e-6, "面东平视移动方向=(cos6°,−sin6°,0)(枪口低于视线 6°)");

        // 首版反相回归(用户实机):抬头(xRot=−30°)移动 → 枪口朝上 −24°(y=+sin24°)
        Vector3d dUp = MuzzlePoseModel.dirFromModel(0f, -30f, 1f, false);
        double p24 = Math.toRadians(24.0);
        check(Math.abs(dUp.y() - Math.sin(p24)) < 1e-6 && Math.abs(dUp.z() - Math.cos(p24)) < 1e-6,
                "抬头 30° 移动:束向朝上 24°(往上看灯朝上——首版反相回归用例)");

        // 低头(xRot=+30°)移动 → 枪口朝下 −36°
        Vector3d dDown = MuzzlePoseModel.dirFromModel(0f, 30f, 1f, false);
        double p36 = Math.toRadians(36.0);
        check(Math.abs(dDown.y() + Math.sin(p36)) < 1e-6 && Math.abs(dDown.z() - Math.cos(p36)) < 1e-6,
                "低头 30° 移动:束向朝下 36°(视线 30°+族 6°)");

        Vector3d dAim = MuzzlePoseModel.dirFromModel(-90f, 0f, 1f, true);
        check(Math.abs(dAim.x() - 1.0) < 1e-6 && Math.abs(dAim.y()) < 1e-6 && Math.abs(dAim.z()) < 1e-6,
                "瞄准方向=精确视线(族偏移不适用 ADS)");

        // ---- fallbackDir 胶水(上传侧同源):speed=|Δeye|/dt → env → 方向 ----
        // 静止:眼位不动 → env=0 → 静止族(y=−sin2°)
        Vector3d still = MuzzlePoseModel.fallbackDir(-90f, 0f, false,
                100, 64, 50, 100, 64, 50, 16_000_000L);
        check(Math.abs(still.y() + Math.sin(Math.toRadians(2.0))) < 1e-6, "fallbackDir(静止)=静止族");

        // 步行:1.3 格 / 16ms ≈ 4.06m/s(>1.2 饱和)→ 移动族(y=−sin6°)
        Vector3d walk = MuzzlePoseModel.fallbackDir(-90f, 0f, false,
                100, 64, 50, 101.3, 64, 50, 16_000_000L);
        check(Math.abs(walk.y() + Math.sin(Math.toRadians(6.0))) < 1e-6, "fallbackDir(步行 4.06m/s)=移动族");

        // 首帧 dt=0:无速度信息 → 静止族(保守)
        Vector3d first = MuzzlePoseModel.fallbackDir(-90f, 0f, false,
                100, 64, 50, 105, 64, 50, 0L);
        check(Math.abs(first.y() + Math.sin(Math.toRadians(2.0))) < 1e-6, "fallbackDir(dt=0 首帧)=静止族");

        // 传送:24 格 / 16ms → 饱和 env=1(移动族,不崩)
        Vector3d tp = MuzzlePoseModel.fallbackDir(-90f, 0f, false,
                100, 64, 50, 124, 64, 50, 16_000_000L);
        check(Math.abs(tp.y() + Math.sin(Math.toRadians(6.0))) < 1e-6, "fallbackDir(传送 37.5m/s)=移动族");

        // 方向单位化
        check(Math.abs(dMove.length() - 1.0) < 1e-9 && Math.abs(walk.length() - 1.0) < 1e-9,
                "模型方向恒为单位向量");

        System.out.println("MuzzlePoseModelContract: " + n + " checks ALL PASS");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        n++;
        System.out.println("  PASS " + what);
    }
}

package dev.taclight.channel;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 体积光时间复用契约(2026-09-06,用户批准立项)。
 *
 * 背景:composite1 全屏 64 步 raymarch 每帧对每像素重复计算(相邻帧画面变化极小,
 * 同一束光被重复积分)。用户批准的优化组合:①遮挡查表 bilinear 化修条纹(见
 * OcclTableContract);②本立项 = 时间复用:步数 64→32 + IGN 抖动逐帧旋转(帧间
 * 去相关)+ 上一帧历史重投影混合。半分辨率路线已被本包史否决(v0.10.0 热修:
 * Oculus 1.8.0 上 buffer 全屏假设不成立,composite2.fsh 头注释),故为全分辨率减步。
 *
 * 管线设计(与 GLSL 逐条对应,源码钉负责抓失配):
 *   composite1 输出 MRT:colortex4(rgb=混合后光束, a=逐像素置信度)
 *                    + colortex9 历史(rgb=同值, a=march 终点视图距离/256,clear=false);
 *   混合权重 w = TACLIGHT_TM_WEIGHT(0.75) × conf,conf = 贡献灯的
 *   vlParams.w 逐灯置信度最小值(Java LightMotionConf 按灯位姿帧间差分指数衰减,
 *   首帧/灯开关/瞬移 → 0 = 全新鲜,防拖影);
 *   有效性门:历史 a>0 且非 NaN + 重投影 uv 出界拒用 + 终点距离差 ≥2m 拒用。
 *   静态场景有效步数 = 32/(1−0.75) = 128;tm off = 64 步全新鲜(逐位旧行为)。
 */
public class TemporalReuseContract {
    static int checks;

    // ---- 与 GLSL/Java 同源常数(改动必须两侧同步,源码钉负责抓失配)----
    static final double TM_WEIGHT = 0.75;
    static final int STEPS = 64, STEPS_TM = 32;
    static final double DEPTH_TOL = 2.0;
    static final double HIST_DIST_SCALE = 256.0;
    static final int FLAG_TEMPORAL = 32;

    public static void main(String[] args) throws Exception {
        confOracle();
        qualityProperties();
        glslPins();
        javaPins();
        knobBehavior();
        System.out.println("TemporalReuseContract: ALL PASS (" + checks + " checks)");
    }

    // =====================================================================
    // ① JVM oracle:LightMotionConf 置信度语义
    // =====================================================================

    static void confOracle() {
        LightMotionConf.reset();
        // 首帧(键不存在)= 0 = 全新鲜(防历史拖影:灯刚出现/刚打开)
        check(LightMotionConf.conf("self:hand", 10, 64, 10, 0, 0, 1) == 0.0f,
                "置信度:首帧 = 0(全新鲜)");
        // 静止灯 = 1(全额历史权重,性能主力场景)
        check(Math.abs(LightMotionConf.conf("self:hand", 10, 64, 10, 0, 0, 1) - 1.0f) < 1e-6,
                "置信度:静止灯 = 1");
        // 位移衰减 = exp(-K_POS·d):步行 0.072 格/帧
        double walk = LightMotionConf.conf("self:hand", 10.072, 64, 10, 0, 0, 1);
        check(close(walk, Math.exp(-LightMotionConf.K_POS * 0.072), 1e-5),
                "置信度:位移衰减 = exp(-K_POS·d)(步行 0.072 → " + fmt(walk) + ")");
        // 转角衰减 = exp(-K_DIR·Δ):甩头 0.157 rad/帧(540°/s @60fps),与位移相乘
        double ang = Math.toRadians(9.0);   // 9°/帧
        LightMotionConf.reset();
        LightMotionConf.conf("self:hand", 10, 64, 10, 0, 0, 1);
        double rot = LightMotionConf.conf("self:hand", 10.072, 64, 10,
                Math.sin(ang), 0, Math.cos(ang));
        check(close(rot, Math.exp(-LightMotionConf.K_POS * 0.072) * Math.exp(-LightMotionConf.K_DIR * ang), 1e-5),
                "置信度:转角衰减与位移相乘(甩头 → " + fmt(rot) + ")");
        // 瞬移 10 格 → ≈0(历史作废)
        double tp = LightMotionConf.conf("self:hand", 20, 64, 10, 0, 0, 1);
        check(tp < 0.001, "置信度:瞬移 10 格 → <0.001(实测 " + fmt(tp) + ")");
        // 置信度单调:位移越大越低
        LightMotionConf.reset();
        LightMotionConf.conf("r:1:hand", 0, 0, 0, 0, 0, 1);
        double near = LightMotionConf.conf("r:1:hand", 0.05, 0, 0, 0, 0, 1);
        LightMotionConf.reset();
        LightMotionConf.conf("r:1:hand", 0, 0, 0, 0, 0, 1);
        double far = LightMotionConf.conf("r:1:hand", 0.5, 0, 0, 0, 0, 1);
        check(near > far, "置信度:位移越大衰减越狠(" + fmt(near) + " > " + fmt(far) + ")");
        // endFrame 逐出:灯缺席一整个帧周期后重开 = 首帧语义(0)
        LightMotionConf.reset();
        LightMotionConf.conf("r:2:gun", 1, 1, 1, 0, 0, 1);
        LightMotionConf.conf("r:2:gun", 1, 1, 1, 0, 0, 1);   // 静止 → 1
        LightMotionConf.endFrame();                            // 帧 N 结束(灯在场)
        LightMotionConf.endFrame();                            // 帧 N+1 灯缺席 → 被逐出
        check(LightMotionConf.conf("r:2:gun", 1, 1, 1, 0, 0, 1) == 0.0f,
                "置信度:endFrame 逐出缺席灯(重开=首帧语义)");
        // 零向量方向不炸(除零守卫)
        LightMotionConf.reset();
        LightMotionConf.conf("z", 0, 0, 0, 0, 0, 0);
        double z = LightMotionConf.conf("z", 0, 0, 0, 0, 0, 0);
        check(z == 1.0f, "置信度:零方向向量守卫(=1,实测 " + fmt(z) + ")");
    }

    // =====================================================================
    // ② 质量性质(常数联动)
    // =====================================================================

    static void qualityProperties() {
        check(TM_WEIGHT > 0.5 && TM_WEIGHT < 0.9, "历史权重在 (0.5,0.9)(过小无收益,过大拖影)");
        double effective = STEPS_TM / (1.0 - TM_WEIGHT);
        check(effective >= 96.0, "有效步数 ≥96(STEPS_TM/(1−W)=" + fmt(effective) + ",不低于 09-05 定案 64 的 1.5×)");
        check(DEPTH_TOL >= 0.5 && DEPTH_TOL <= 4.0, "终点距离容差 0.5..4 格(视差余量 vs disocclusion 拖影平衡)");
        check(HIST_DIST_SCALE == 256.0, "历史距离归一尺度 256(0..96m 终点 + unorm16 ≈4mm 精度)");
        check(STEPS_TM * 2 == STEPS, "tm 步数 = 全新鲜步数的一半(成本减半的钉)");
        check(FLAG_TEMPORAL == 1 << 5, "FLAG_TEMPORAL = bit5(位 0-4 已被占用)");
    }

    // =====================================================================
    // ③ 源码钉(GLSL / Java / 工具)
    // =====================================================================

    static void glslPins() throws Exception {
        String core = Files.readString(Path.of("pack/shaders/lib/taclight_core.glsl"));
        check(core.contains("#define TACLIGHT_FLAG_TEMPORAL") && core.contains("32u"),
                "GLSL:core 定义 bit5=32u(时间复用,位 0-4 已被占用)");
        check(core.contains("vec2 taclight_reproject_prev_uv(vec3 endView)"),
                "GLSL:core 重投影函数(跨包接口,矩阵链在 core 不在消费 pass)");
        check(core.contains("gbufferPreviousModelView") && core.contains("gbufferPreviousProjection")
                        && core.contains("previousCameraPosition"),
                "GLSL:core 上一帧三 uniform(全矩阵链,铁律 3:必须用全逆抵消 bob)");
        check(core.contains("if (clipPrev.w <= 0.0) return vec2(-1.0);"),
                "GLSL:core 上一帧相机背后的点返回哨兵(消费侧 uv 界检查拒用)");

        String comp = Files.readString(Path.of("pack/shaders/composite.fsh"));
        check(comp.contains("const int colortex9Format = RGBA16"), "GLSL:composite 声明 colortex9 格式");
        check(comp.contains("const bool colortex9Clear = false"),
                "GLSL:composite colortex9 不清帧(跨帧历史,同 colortex7 曝光/colortex6 bloom2 机制)");

        String comp1 = Files.readString(Path.of("pack/shaders/composite1.fsh"));
        check(comp1.contains("/* DRAWBUFFERS:49 */"), "GLSL:composite1 MRT 输出 colortex4+colortex9");
        check(comp1.contains("layout(location = 1) out vec4 taclightHistOut"),
                "GLSL:composite1 location=1 历史输出(与 composite/composite3 MRT 同款家规)");
        check(comp1.contains("uniform sampler2D colortex9"), "GLSL:composite1 读历史缓冲");
        check(comp1.contains("(flags & TACLIGHT_FLAG_TEMPORAL) != 0u"), "GLSL:composite1 tm 门 = 头部 bit5");
        check(comp1.contains("int vlSteps = tmOn ? TACLIGHT_VL_STEPS_TM : TACLIGHT_VL_STEPS;"),
                "GLSL:composite1 动态步数(tm 开 32 / off 64)");
        check(comp1.contains("fract(jitter + 0.6180340f * float(frameCounter))"),
                "GLSL:composite1 IGN 抖动逐帧旋转(帧间去相关,时间累积才能回质量)");
        check(comp1.contains("lightConf = min(lightConf, L.vlParams.w);"),
                "GLSL:composite1 逐灯置信度取贡献灯最小(任一贡献灯动 → 该像素降权)");
        check(comp1.contains("float conf = contributed ? lightConf : 0.0;"),
                "GLSL:composite1 无贡献像素置信度=0(灯关瞬间光束即灭,不靠历史衰减尾巴)");
        check(comp1.contains("if (tmOn && conf > 0.0)"), "GLSL:composite1 混合只在有贡献像素执行(无灯像素零成本)");
        check(comp1.contains("taclight_reproject_prev_uv(endView)"), "GLSL:composite1 重投影用 march 终点");
        // 2026-10-04 R24:上面那条只钉了「调了重投影函数」。**调了 ≠ 用了** —— 实测旧码把返回值
        // 只用于门控、取样仍按 texcoord(重投影写了不用 ⇒ 等于没重投影),而上面那条照样是绿的。
        // 判据必须钉**语义**:取样坐标 = 重投影后的 uv。
        check(comp1.contains("texture(colortex9, clamp(uvPrev, vec2(0.0), vec2(1.0)))"),
                "GLSL:composite1 历史按**重投影后**的 uv 取样(uvPrev 必须真的参与取样,不是只做门控)");
        check(!comp1.contains("texture(colortex9, texcoord)"),
                "GLSL:composite1 不得按当前像素 texcoord 取历史(那就等于没重投影)");
        check(comp1.contains("vec2 uvPrev = taclight_reproject_prev_uv(endView);"),
                "GLSL:composite1 uvPrev 无条件求出(取样保持无条件:texture() 走隐式导数,不可放进发散分支)");
        check(comp1.contains("abs(hist.a * TACLIGHT_TM_HISTORY_DIST_SCALE - maxDist) < TACLIGHT_TM_DEPTH_TOL"),
                "GLSL:composite1 终点距离一致性门(disocclusion 拒用历史)");
        check(comp1.contains("float wHist = ok ? TACLIGHT_TM_WEIGHT * conf : 0.0;"),
                "GLSL:composite1 混合权重 = 0.75 × 逐灯置信度");
        check(comp1.contains("beam = mix(vl, hist.rgb, wHist);"), "GLSL:composite1 历史-新鲜线性混合");
        check(comp1.contains("taclightVL = vec4(beam, conf);"),
                "GLSL:composite1 colortex4.a = 逐像素置信度(诊断可读)");
        check(comp1.contains("taclightHistOut = vec4(beam, maxDist / TACLIGHT_TM_HISTORY_DIST_SCALE);"),
                "GLSL:composite1 历史写回混合后光束 + 终点距离");
        check(!comp1.contains("#define TACLIGHT_VL_STEPS_TM 64"),
                "GLSL:composite1 tm 步数不得等于 64(成本减半的钉)");
    }

    static void javaPins() throws Exception {
        check(SpotlightBufferLayout.FLAG_TEMPORAL == FLAG_TEMPORAL,
                "Java:FLAG_TEMPORAL = 32(与 GLSL 32u 逐位镜像)");

        String layout = Files.readString(Path.of(
                "src/main/java/dev/taclight/channel/SpotlightBufferLayout.java"));
        check(layout.contains("FLAG_TEMPORAL = 1 << 5"), "Java:布局类 bit5 常量(1<<5)");

        String data = Files.readString(Path.of(
                "src/main/java/dev/taclight/channel/SpotlightData.java"));
        check(data.contains("public SpotlightData withVlReservedW(float"),
                "Java:SpotlightData 暴露 vlParams.w 写入口(置信度槽位)");

        String motion = Files.readString(Path.of(
                "src/main/java/dev/taclight/channel/LightMotionConf.java"));
        check(motion.contains("public static final float K_POS"), "Java:置信度位移衰减常数");
        check(motion.contains("public static final float K_DIR"), "Java:置信度转角衰减常数");
        check(motion.contains("public static void endFrame()"), "Java:逐帧逐出入口");
        check(motion.contains("retainAll(SEEN)"), "Java:endFrame 逐出本帧缺席灯键");

        String tune = Files.readString(Path.of(
                "src/main/java/dev/taclight/channel/LightTuneOverride.java"));
        check(tune.contains("private static volatile boolean temporalActive = true;"),
                "Java:!tm 默认开(用户批准,重启回默认)");
        check(tune.contains("public static String configureTemporal(String arg)"), "Java:configureTemporal 入口存在");
        check(tune.contains("public static boolean temporal()"), "Java:onFrame 读取口存在");

        String uploader = Files.readString(Path.of(
                "src/main/java/dev/taclight/channel/ClientSpotlightUploader.java"));
        check(uploader.contains("extraFlags |= SpotlightBufferLayout.FLAG_TEMPORAL"),
                "Java:头部 flags OR bit5");
        check(uploader.contains("applyTemporalConfidence(lights, slotKeys)"),
                "Java:上传前写逐灯置信度");
        check(uploader.contains("slotKeys.add(\"self:hand\")")
                        && uploader.contains("slotKeys.add(\"self:gun\")")
                        && uploader.contains("\":hand\");") && uploader.contains("\":gun\");"),
                "Java:灯身份键覆盖自身手持/枪与远程逐实体(索引换位免疫)");
        check(uploader.contains("LightMotionConf.endFrame();"),
                "Java:每帧末尾逐出失效键(灯关/离开后重开=首帧语义)");
        check(uploader.contains("if (tmOn) {") && uploader.contains("LightTuneOverride.temporal()"),
                "Java:tm off 不写置信度(槽位 0=全新鲜),但 endFrame 照常");

        String relay = Files.readString(Path.of(
                "src/main/java/dev/taclight/client/DebugCommandRelay.java"));
        check(relay.contains("startsWith(\"!tm\")"), "Java:中继 !tm 分发");
        check(relay.contains("configureTemporal(arg)"), "Java:中继接 configureTemporal");

        String knob = Files.readString(Path.of("tools/knob.ps1"));
        check(knob.contains("|occl|tm|scat|"), "工具:knob.ps1 白名单含 tm(裸词自动补 !)");
        check(knob.contains("!tm"), "工具:knob.ps1 帮助文本含 !tm");
    }

    // =====================================================================
    // ④ 旋钮行为(内存覆盖层语义,与 occl 同族)
    // =====================================================================

    static void knobBehavior() {
        check(LightTuneOverride.temporal(), "旋钮:默认开(初始态)");
        String s1 = LightTuneOverride.configureTemporal("");
        check(s1.contains("tm=on"), "旋钮:无参=status(on)");
        String s2 = LightTuneOverride.configureTemporal("off");
        check(s2.contains("tm=off") && !LightTuneOverride.temporal(), "旋钮:off 关闭并回显");
        String s3 = LightTuneOverride.configureTemporal("on");
        check(s3.contains("tm=on") && LightTuneOverride.temporal(), "旋钮:on 开启并回显");
        String s4 = LightTuneOverride.configureTemporal("wat");
        check(s4.startsWith("bad arg"), "旋钮:坏参数报错不变更状态");
        LightTuneOverride.configureTemporal("on");
    }

    static boolean close(double a, double b, double eps) {
        return Math.abs(a - b) < eps;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 1000.0) / 1000.0);
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
    }
}

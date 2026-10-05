package dev.taclight.channel;

import dev.taclight.channel.ClientSpotlightUploader.LightParams;

/**
 * 手电八旋钮覆盖层契约(2026-09-04 三旋钮,2026-09-05 加 !knee/!beam/!beamonly/!scat/!beamcap/!cone):
 * {@code !bright} 直接亮度(绝对强度) / {@code !dist} 绝对照距 /
 * {@code !atten} 衰减系数 K(经 SSBO cone.z 逐灯透传) /
 * {@code !knee} 近场软肩 G(经 SSBO cone.w 逐灯透传;2026-09-05 用户实测"光路内
 * 反光刺眼"定案默认开:off=回默认 G=2.0,不再有恒等档) /
 * {@code !beam} 体积光密度(经 SSBO vlParams.y 直接换值,0=完全关光束,off=回 config 默认) /
 * {@code !beamonly} 只看光束(头部 flags bit2,跳过 M1 表面照明,composite1 体积束照常) /
 * {@code !scat} 体积光轴向底亮份额 f(经 SSBO vlParams.x 直接换值,GLSL 侧面相位
 * phase = NORM·(f + (1−f)·sin²θ):正侧 90° 最亮=丁达尔效应服务旁观者,正对/沿轴
 * 只剩 f 份额防叠加刺眼;0=纯侧面,off=回编译期默认 BEAM_SIDE_FLOOR=0.04) /
 * {@code !beamcap} 体积光重叠软上限倍率 m(经 SSBO vlParams.z 透传,GLSL cap=2.0×m:
 * 低于半帽点恒等=单灯观感零变化,多灯重叠亮度指数肩部渐近 cap 不许无限叠加;
 * 0.25=压得最狠,8≈基本不限,off=回 m=1 默认)。
 * {@code !cone} 锥角(2026-09-05 用户定案"接近平行光"):外锥半角(度),内锥=外×0.5,
 * Java 侧直改 cosOuter/cosInner(SSBO/GLSL 零改动);0 哨兵=直通 config 默认 外8/内4。
 * 旋钮默认全关 = 零覆盖行为(beamcap 关=m=1,GLSL 默认 cap=2.0 恒生效,属功能本身;
 * knee 例外:默认即开 G=2.0,off 也是回 2.0);越界/非法拒绝不污染;收尾全关(零残留)。
 */
public class LightTuneContract {
    public static void main(String[] args) {
        LightTuneOverride.configureBright("off");
        LightTuneOverride.configureDist("off");
        LightTuneOverride.configureAtten("off");
        LightTuneOverride.configureKnee("off");
        LightTuneOverride.configureBeam("off");
        LightTuneOverride.configureBeamonly("off");

        // ---- 默认:各路直通 ----
        check(Math.abs(LightTuneOverride.brightnessFor(6.0f) - 6.0f) < 1e-6, "默认亮度直通");
        check(Math.abs(LightTuneOverride.radiusFor(36.0f, 96.0f) - 36.0f) < 1e-6, "默认半径直通");
        check(LightTuneOverride.attenK() == 0.0f, "默认 attenK=0(GLSL 回退编译期默认)");

        // ---- bright:绝对强度 ----
        String b12 = LightTuneOverride.configureBright("12");
        check(b12.contains("12"), "bright 12 回显: " + b12);
        check(Math.abs(LightTuneOverride.brightnessFor(6.0f) - 12.0f) < 1e-6, "bright 覆盖生效(与基值无关)");
        String bBad = LightTuneOverride.configureBright("99");
        check(bBad.startsWith("range"), "bright 越界拒绝: " + bBad);
        check(Math.abs(LightTuneOverride.brightnessFor(6.0f) - 12.0f) < 1e-6, "越界不污染当前值");
        String bNaN = LightTuneOverride.configureBright("abc");
        check(bNaN.startsWith("bad arg"), "bright 非法输入拒绝: " + bNaN);
        String bSt = LightTuneOverride.configureBright("status");
        check(bSt.contains("12"), "bright status 回显当前值: " + bSt);

        // ---- dist:绝对照距 ----
        String d24 = LightTuneOverride.configureDist("24");
        check(d24.contains("24"), "dist 24 回显: " + d24);
        check(Math.abs(LightTuneOverride.radiusFor(50.9f, 96.0f) - 24.0f) < 1e-6, "dist 覆盖生效( bypass √耦合)");
        String dBad = LightTuneOverride.configureDist("200");
        check(dBad.startsWith("range"), "dist 越界拒绝: " + dBad);
        String dNaN = LightTuneOverride.configureDist("far");
        check(dNaN.startsWith("bad arg"), "dist 非法输入拒绝: " + dNaN);

        // ---- atten:逐灯 K ----
        String a1 = LightTuneOverride.configureAtten("1.0");
        check(a1.contains("1.0"), "atten 1.0 回显: " + a1);
        check(Math.abs(LightTuneOverride.attenK() - 1.0f) < 1e-6, "atten 覆盖生效");
        String aBad = LightTuneOverride.configureAtten("0.01");
        check(aBad.startsWith("range"), "atten 越界拒绝: " + aBad);
        check(Math.abs(LightTuneOverride.attenK() - 1.0f) < 1e-6, "越界不污染当前值");

        // ---- knee:逐灯近场软肩 G(2026-09-05 第四旋钮,SSBO cone.w 透传) ----
        // 2026-09-05 用户实测"站在光路内反光刺眼"定案:默认开 G=2.0(与编译期标定
        // TACLIGHT_KNEE_GAIN 同值),off=回默认(不再有恒等档;近场 HDR 值不再直通 bloom)。
        check(Math.abs(LightTuneOverride.kneeG() - 2.0f) < 1e-6, "默认 kneeG=2.0(近场软膝默认开,治正对眩光)");
        String k1 = LightTuneOverride.configureKnee("3.0");
        check(k1.contains("3.0"), "knee 3.0 回显: " + k1);
        check(Math.abs(LightTuneOverride.kneeG() - 3.0f) < 1e-6, "knee 覆盖生效");
        String kBad = LightTuneOverride.configureKnee("20");
        check(kBad.startsWith("range"), "knee 越界拒绝: " + kBad);
        check(Math.abs(LightTuneOverride.kneeG() - 3.0f) < 1e-6, "越界不污染当前值");
        String kNaN = LightTuneOverride.configureKnee("soft");
        check(kNaN.startsWith("bad arg"), "knee 非法输入拒绝: " + kNaN);
        check(LightTuneOverride.configureKnee("off").contains("2.0"), "knee off 回显默认 G=2.0");
        check(Math.abs(LightTuneOverride.kneeG() - 2.0f) < 1e-6, "knee off 生效(回默认 2.0,off≠恒等)");
        LightTuneOverride.configureKnee("3.0");   // 供后续集成段断言 cone.w=3.0 透传

        // ---- beam:体积光密度(2026-09-05 第五旋钮,SSBO vlParams.y 直接换值) ----
        // 0=完全关光束(A/B 开关对比);off=回 config 默认 0.05。密度是消费值本身,
        // 与 atten/knee 的 0 哨兵语义不同:显式 0 就是关,不回退。
        check(Math.abs(LightTuneOverride.beamDensityOr(0.05f) - 0.05f) < 1e-6, "默认 beamDensity 直通(config 值)");
        String bm0 = LightTuneOverride.configureBeam("0");
        check(bm0.contains("beam=0.0"), "beam 0 回显: " + bm0);
        check(LightTuneOverride.beamDensityOr(0.05f) == 0.0f, "beam 0 = 完全关闭光束(显式零≠回默认)");
        String bm35 = LightTuneOverride.configureBeam("0.35");
        check(bm35.contains("0.35"), "beam 0.35 回显: " + bm35);
        check(Math.abs(LightTuneOverride.beamDensityOr(0.05f) - 0.35f) < 1e-6, "beam 覆盖生效(与 config 值无关)");
        String bmHi = LightTuneOverride.configureBeam("2.0");
        check(bmHi.startsWith("range"), "beam 越界拒绝(>1): " + bmHi);
        check(Math.abs(LightTuneOverride.beamDensityOr(0.05f) - 0.35f) < 1e-6, "越界不污染当前值");
        String bmNeg = LightTuneOverride.configureBeam("-0.1");
        check(bmNeg.startsWith("range"), "beam 越界拒绝(<0): " + bmNeg);
        String bmNaN = LightTuneOverride.configureBeam("fog");
        check(bmNaN.startsWith("bad arg"), "beam 非法输入拒绝: " + bmNaN);

        // ---- beamonly:只看光束(2026-09-05,头部 flags bit2 → GLSL 跳过 M1 表面照明) ----
        check(!LightTuneOverride.beamOnly(), "默认 beamonly=false(零行为变化)");
        String bo1 = LightTuneOverride.configureBeamonly("on");
        check(bo1.contains("on"), "beamonly on 回显: " + bo1);
        check(LightTuneOverride.beamOnly(), "beamonly on 生效");
        String boSt = LightTuneOverride.configureBeamonly("status");
        check(boSt.contains("on"), "beamonly status 回显当前态: " + boSt);
        String bo0 = LightTuneOverride.configureBeamonly("off");
        check(bo0.contains("off"), "beamonly off 回显: " + bo0);
        check(!LightTuneOverride.beamOnly(), "beamonly off 生效");
        String boBad = LightTuneOverride.configureBeamonly("yes");
        check(boBad.startsWith("bad arg"), "beamonly 非法输入拒绝: " + boBad);
        check(!LightTuneOverride.beamOnly(), "非法输入不污染当前态");
        // bit2 空闲无冲突(布局常量守卫:动 flags 位必须先过这关)
        check(dev.taclight.channel.SpotlightBufferLayout.FLAG_BEAM_ONLY == 4
                        && (SpotlightBufferLayout.FLAG_BEAM_ONLY
                            & (SpotlightBufferLayout.FLAG_HAS_DATA
                               | SpotlightBufferLayout.FLAG_DEBUG
                               | SpotlightBufferLayout.FLAG_TIMING_PROBE)) == 0,
                "FLAG_BEAM_ONLY=bit2(4)与既有 flags 无冲突");

        // ---- scat:体积光轴向底亮份额 f(2026-09-05 侧面相位定案,SSBO vlParams.x 直接换值) ----
        // 语义与 !beam 同族(换消费值本身):GLSL phase = NORM·(f + (1−f)·sin²θ),
        // 显式 0=纯侧面(正对光源零体积叠加),off=回编译期默认 BEAM_SIDE_FLOOR=0.04
        // (用户定案:体积光只为侧面视角服务,正面调低防与表面光叠加刺眼;
        //  2026-09-05 实测定标:NORM 0.4 → 侧视空中光束项 +~32/255(旧 HG 同法 +7.7),
        //  正对残留 0.4×0.04=0.016 仍低于旧 HG 后向瓣 0.029)。
        check(SpotlightData.BEAM_SIDE_FLOOR == 0.04f, "编译期默认 BEAM_SIDE_FLOOR=0.04(侧面相位底亮)");
        check(LightTuneOverride.configureScat("status").contains("floor 0.04"),
                "scat 默认 off 回显: " + LightTuneOverride.configureScat("status"));
        check(LightTuneOverride.scatOr(0.04f) == 0.04f, "默认 scat 直通(编译期默认 floor 0.04)");
        String sc03 = LightTuneOverride.configureScat("0.3");
        check(sc03.contains("scat=0.3"), "scat 0.3 回显: " + sc03);
        check(Math.abs(LightTuneOverride.scatOr(0.04f) - 0.3f) < 1e-6, "scat 覆盖生效");
        check(LightTuneOverride.configureScat("0").contains("scat=0.0"), "scat 0 回显(显式纯侧面)");
        check(LightTuneOverride.scatOr(0.04f) == 0.0f, "scat 0 = 纯侧面(显式零≠回默认)");
        check(LightTuneOverride.configureScat("0.95").startsWith("range"), "scat 越界拒绝(>0.9): "
                + LightTuneOverride.configureScat("0.95"));
        check(Math.abs(LightTuneOverride.scatOr(0.04f) - 0.0f) < 1e-6, "scat 越界不污染当前值");
        check(LightTuneOverride.configureScat("-0.1").startsWith("range"), "scat 越界拒绝(<0)");
        check(LightTuneOverride.configureScat("abc").startsWith("bad arg"), "scat 非法输入拒绝");
        check(LightTuneOverride.configureScat("off").contains("floor 0.04"), "scat off 回显");
        check(LightTuneOverride.scatOr(0.04f) == 0.04f, "scat off 生效(回编译期默认)");
        LightTuneOverride.configureScat("0.1");

        // ---- beamcap:体积光重叠软上限倍率(2026-09-05,SSBO vlParams.z 透传) ----
        // GLSL cap = 2.0 × m(m 默认 1):低于半帽点恒等(单灯观感零变化),多灯重叠
        // 亮度指数肩部渐近 cap(用户需求:两灯同照不许亮度无限叠加刺眼)。
        // m=0 非法即未激活哨兵(上传侧保持槽位默认 1.0),故显式 0 走 range 拒绝。
        check(LightTuneOverride.configureBeamcap("status").contains("off"),
                "beamcap 默认 off 回显: " + LightTuneOverride.configureBeamcap("status"));
        check(LightTuneOverride.beamCapM() == 0.0f, "默认 beamCapM=0(未激活,槽位保持 1.0)");
        String bc2 = LightTuneOverride.configureBeamcap("2.0");
        check(bc2.contains("beamcap=2.0"), "beamcap 2.0 回显: " + bc2);
        check(Math.abs(LightTuneOverride.beamCapM() - 2.0f) < 1e-6, "beamcap 覆盖生效(m=2,cap=4)");
        check(LightTuneOverride.configureBeamcap("0.25").contains("beamcap=0.25"), "beamcap 0.25 回显");
        check(Math.abs(LightTuneOverride.beamCapM() - 0.25f) < 1e-6, "beamcap 0.25 生效(cap=0.5 最压制)");
        check(LightTuneOverride.configureBeamcap("9").startsWith("range"), "beamcap 越界拒绝(>8)");
        check(Math.abs(LightTuneOverride.beamCapM() - 0.25f) < 1e-6, "beamcap 越界不污染当前值");
        check(LightTuneOverride.configureBeamcap("0.1").startsWith("range"), "beamcap 越界拒绝(<0.25)");
        check(LightTuneOverride.configureBeamcap("0").startsWith("range"), "beamcap 0 拒绝(0=哨兵不可显式设)");
        check(LightTuneOverride.configureBeamcap("abc").startsWith("bad arg"), "beamcap 非法输入拒绝");
        check(LightTuneOverride.configureBeamcap("off").contains("off"), "beamcap off 回显");
        check(LightTuneOverride.beamCapM() == 0.0f, "beamcap off 生效(回默认槽位 1.0)");
        LightTuneOverride.configureBeamcap("2.0");

        // ---- cone:锥角收窄(2026-09-05 用户定案"接近平行光",第八旋钮) ----
        // 外锥半角(度)直改 cosOuter,内锥=外×0.5;旧默认 32/18 在 30m 外光斑半径
        // ≈18.7m(远距离范围过大)。0 哨兵=未激活(直通 config 默认 外8/内4)。
        check(LightTuneOverride.coneDeg() == 0.0f, "默认 coneDeg=0(直通 config 外8/内4)");
        check(LightTuneOverride.configureCone("status").contains("off"), "cone 默认 off 回显: "
                + LightTuneOverride.configureCone("status"));
        String cn8 = LightTuneOverride.configureCone("8");
        check(cn8.contains("cone=8.0"), "cone 8 回显: " + cn8);
        check(Math.abs(LightTuneOverride.coneDeg() - 8.0f) < 1e-6, "cone 覆盖生效");
        check(LightTuneOverride.configureCone("60").startsWith("range"), "cone 越界拒绝(>45): "
                + LightTuneOverride.configureCone("60"));
        check(LightTuneOverride.configureCone("1").startsWith("range"), "cone 越界拒绝(<2)");
        check(Math.abs(LightTuneOverride.coneDeg() - 8.0f) < 1e-6, "cone 越界不污染当前值");
        check(LightTuneOverride.configureCone("wide").startsWith("bad arg"), "cone 非法输入拒绝");
        check(LightTuneOverride.configureCone("off").contains("off"), "cone off 回显");
        check(LightTuneOverride.coneDeg() == 0.0f, "cone off 生效(直通 config)");
        LightTuneOverride.configureCone("6");

        try {
            String core = java.nio.file.Files.readString(
                    java.nio.file.Path.of("pack/shaders/lib/taclight_core.glsl"));
            check(core.contains("#define TACLIGHT_FLAG_BEAM_ONLY"), "GLSL:taclight_core 定义 BEAM_ONLY 位");
            String comp = java.nio.file.Files.readString(
                    java.nio.file.Path.of("pack/shaders/composite.fsh"));
            check(comp.contains("(flags & TACLIGHT_FLAG_BEAM_ONLY) == 0u"),
                    "GLSL:composite M1 分支有 beamonly 门(跳过表面照明)");
            String comp1 = java.nio.file.Files.readString(
                    java.nio.file.Path.of("pack/shaders/composite1.fsh"));
            check(comp1.contains("TACLIGHT_VL_STEPS 64"),
                    "GLSL:composite1 步数 64(细锥采样,侧视可见性,32 步会跨过细锥)");
            check(comp1.contains("L.vlParams.x"),
                    "GLSL:composite1 侧面相位消费 vlParams.x(scat 底亮透传消费点)");
            check(comp1.contains("#define TACLIGHT_BEAM_NORM 0.4"),
                    "GLSL:composite1 侧面相位归一 0.4(实测定标:空中光束项 +~32/255,侧面提亮定案)");
            check(comp1.contains("1.0 - cosT * cosT"),
                    "GLSL:composite1 sin²θ 侧面剖面(正侧最亮,正对/沿轴只剩底亮份额)");
            check(comp1.contains("#define TACLIGHT_BEAM_CAP 2.0"),
                    "GLSL:composite1 软上限默认 cap=2.0 线性");
            check(comp1.contains("#define TACLIGHT_BEAM_GAIN 1.0"),
                    "GLSL:composite1 总增益 1.0(侧视轮廓亮度,2026-09-05 由 0.5 翻倍)");
            check(comp1.contains("lights[0].vlParams.z"),
                    "GLSL:composite1 软上限倍率消费 vlParams.z(beamcap 透传消费点)");
            check(comp1.contains("(1.0 - exp("),
                    "GLSL:composite1 肩部为指数渐近(软压缩,非硬截断)");
            check(comp1.contains("taclight_vox_transmit(L.posRadius.xyz, spWorld)"),
                    "GLSL:composite1 体积采样灯侧体素遮挡(灯→采样点 DDA,与表面照明同栅格;穿墙漏光修复)");
            check(comp1.contains("visVox") && comp1.contains("visVox <= 0.003"),
                    "GLSL:composite1 遮挡透射率乘入体积累加(-1=栅格无效回退可见)");
            String cfgSrc = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/java/dev/taclight/config/TacLightConfig.java"));
            check(cfgSrc.contains("\"coneOuterDeg\", 8.0"),
                    "config 锥角默认外半角 8°(接近平行光,旧 32 远距离光斑过大)");
            check(cfgSrc.contains("\"coneInnerDeg\", 4.0"),
                    "config 锥角默认内半角 4°(=外×0.5,与 !cone 旋钮同比例)");
        } catch (Exception e) {
            throw new AssertionError("FAIL GLSL 源读取: " + e);
        }

        // ---- buildSpotBeam 集成 ----
        LightParams p = new LightParams(36.0f, 96.0f, 6.0f, 0.848f, 0.951f, 0.05f);
        SpotlightData tuned = ClientSpotlightUploader.buildSpotBeam(
                0, 0, 0, 0, 0, -1, p, 1.0f);
        check(Math.abs(tuned.intensity() - 12.0f) < 1e-4, "集成:强度=bright 绝对值 12");
        check(Math.abs(tuned.radius() - 24.0f) < 1e-4, "集成:半径=dist 绝对值 24(不跟亮度走)");
        check(Math.abs(tuned.coneReservedZ() - 1.0f) < 1e-6, "集成:cone.z=atten K 1.0 透传");
        check(Math.abs(tuned.coneReservedW() - 3.0f) < 1e-6, "集成:cone.w=knee G 3.0 透传");
        check(Math.abs(tuned.density() - 0.35f) < 1e-4, "集成:vlParams.y=beam 密度 0.35 覆盖(config 0.05 被换)");
        check(Math.abs(tuned.sideFloor() - 0.1f) < 1e-6, "集成:vlParams.x=scat 轴向底亮 0.1 覆盖(0.04 被换)");
        check(Math.abs(tuned.beam() - 2.0f) < 1e-6, "集成:vlParams.z=beamcap 倍率 2.0 覆盖(默认 1.0 被换)");
        float cos6 = (float) Math.cos(Math.toRadians(6.0));
        float cos3 = (float) Math.cos(Math.toRadians(3.0));
        check(Math.abs(tuned.cosOuter() - cos6) < 1e-5, "集成:cosOuter=cone 覆盖外半角 6°");
        check(Math.abs(tuned.cosInner() - cos3) < 1e-5, "集成:cosInner=cone×0.5=3°(内外同比例收窄)");
        java.nio.ByteBuffer buf = SpotlightBufferLayout.newBuffer(1);
        SpotlightBufferLayout.writeLight(buf, 0, tuned);
        SpotlightData read = SpotlightBufferLayout.readLight(buf, 0);
        check(Math.abs(read.coneReservedZ() - 1.0f) < 1e-6, "cone.z 经 SSBO 读写回环");
        check(Math.abs(read.coneReservedW() - 3.0f) < 1e-6, "cone.w 经 SSBO 读写回环");
        check(Math.abs(read.sideFloor() - 0.1f) < 1e-6, "vlParams.x(轴向底亮)经 SSBO 读写回环");
        check(Math.abs(read.beam() - 2.0f) < 1e-6, "vlParams.z(beamcap 倍率)经 SSBO 读写回环");

        // ---- 默认灯 cone.z=0(GLSL 回退) ----
        SpotlightData plain = SpotlightData.spot(1f, 2f, 3f, 24f,
                1f, 0.96f, 0.88f, 6f, 0f, 0f, -1f, 0.848f, 0.951f);
        check(plain.coneReservedZ() == 0.0f && plain.coneReservedW() == 0.0f, "spot() 默认 cone.z/w=0(保留槽)");

        // ---- 收尾全关(零残留) ----
        LightTuneOverride.configureBright("off");
        LightTuneOverride.configureDist("off");
        LightTuneOverride.configureAtten("off");
        LightTuneOverride.configureKnee("off");
        LightTuneOverride.configureBeam("off");
        LightTuneOverride.configureBeamonly("off");
        LightTuneOverride.configureScat("off");
        LightTuneOverride.configureBeamcap("off");
        LightTuneOverride.configureCone("off");
        SpotlightData clean = ClientSpotlightUploader.buildSpotBeam(
                0, 0, 0, 0, 0, -1, p, 1.0f);
        check(Math.abs(clean.intensity() - 6.0f) < 1e-4
                        && Math.abs(clean.radius() - 36.0f) < 1e-4
                        && clean.coneReservedZ() == 0.0f
                        && Math.abs(clean.coneReservedW() - 2.0f) < 1e-6,
                "收尾关闭:强度/半径/atten 回默认,knee 回默认 G=2.0(默认即开)");
        check(Float.compare(clean.cosOuter(), p.cosOuter) == 0
                        && Float.compare(clean.cosInner(), p.cosInner) == 0,
                "收尾关闭:cone off 锥角直通 LightParams(外8/内4 来自 config)");
        check(Math.abs(clean.density() - 0.05f) < 1e-4, "收尾关闭:density 回 config 默认 0.05");
        check(Math.abs(clean.sideFloor() - SpotlightData.BEAM_SIDE_FLOOR) < 1e-6,
                "收尾关闭:vlParams.x 回编译期默认 floor 0.04");
        check(Math.abs(clean.beam() - 1.0f) < 1e-6, "收尾关闭:vlParams.z 回默认倍率 1.0");

        System.out.println("LightTuneContract: ALL PASS (113 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

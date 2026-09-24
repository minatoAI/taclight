package dev.taclight.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 光影包自检契约(2026-09-25)。
 *
 * <p><b>它防的是什么</b>:用户实测(2026-09-19 20:19 会话)用
 * {@code ComplementaryReimagined_r5.9.3.zip},日志 58 行
 * {@code interop injected ... known-good:anchors} 全部成功,
 * 而聊天栏收到 {@code ✘ 当前包 ... 无 TacLight 注入} 并被告知去换包。
 * 根因:自检只在<b>磁盘包内容</b>里找物理标记,而 interop 是运行时内存注入 ⇒ 结构性误报。</p>
 *
 * <p>分两层,如实标注:</p>
 * <ul>
 *   <li><b>内容层(纯逻辑,可判定)</b>:{@link ShaderPackDiagLogic#decide} 的六态映射
 *       与 {@link ShaderPackDiagLogic#message} 的用户文案。含"旧码必红"锚点:
 *       {@code 注入成功 + 磁盘无标记} 必须 <b>不</b> 落到 {@code ORIGINAL_PACK}。</li>
 *   <li><b>接线层(源码文本级,非运行时)</b>:{@code ShaderPackDiag} 确实读
 *       {@code RuntimePackInjector.lastOutcome()};{@code ClientEvents} 确实用纯类文案,
 *       且不再自己写死"无 TacLight 注入"。这是"改漏一处 = 假绿"的防线。</li>
 * </ul>
 *
 * <p><b>未验证(必须由真机补)</b>:真机上"注入成功 ⇒ 聊天栏打 ✔"的端到端观感;
 * 本契约只证明判定/文案/接线正确。</p>
 */
public class ShaderPackDiagContract {
    private static int checks;

    private static final String COMP = "ComplementaryReimagined_r5.9.3.zip";
    private static final String DERIVED = "iterationT 3.2.0 (taclight)";

    public static void main(String[] args) throws Exception {
        contentLayer();
        wiringLayer();
        System.out.println("ShaderPackDiagContract: ALL PASS (" + checks + " checks)");
    }

    private static void contentLayer() {
        var S = ShaderPackDiagLogic.Status.class;
        // —— decide:六态映射 ——
        check(ShaderPackDiagLogic.decide(false, COMP, false, true, true) == ShaderPackDiagLogic.Status.NO_PACK,
                "未在用光影包 ⇒ NO_PACK(注入结果不改变它)");
        check(ShaderPackDiagLogic.decide(true, null, null, false, false) == ShaderPackDiagLogic.Status.UNKNOWN,
                "在用包但读不到包名 ⇒ UNKNOWN");
        check(ShaderPackDiagLogic.decide(true, "   ", null, false, false) == ShaderPackDiagLogic.Status.UNKNOWN,
                "包名全空白 ⇒ UNKNOWN");
        check(ShaderPackDiagLogic.decide(true, COMP, Boolean.TRUE, false, false) == ShaderPackDiagLogic.Status.TACLIGHT_PACK,
                "磁盘含物理标记 ⇒ TACLIGHT_PACK(自研/派生包)");
        check(ShaderPackDiagLogic.decide(true, COMP, Boolean.TRUE, true, true) == ShaderPackDiagLogic.Status.TACLIGHT_PACK,
                "磁盘标记优先:同时有运行时注入也仍报 TACLIGHT_PACK");
        // ↓↓↓ 本 bug 的核心断言(旧码在此为 ORIGINAL_PACK ⇒ 必红) ↓↓↓
        check(ShaderPackDiagLogic.decide(true, COMP, Boolean.FALSE, true, true) == ShaderPackDiagLogic.Status.INTEROP_INJECTED,
                "[旧码必红] 磁盘无标记 + 运行时注入成功 ⇒ INTEROP_INJECTED(不是 ORIGINAL_PACK)");
        check(ShaderPackDiagLogic.decide(true, COMP, Boolean.FALSE, true, true) != ShaderPackDiagLogic.Status.ORIGINAL_PACK,
                "[旧码必红] 注入成功绝不许落到 ORIGINAL_PACK");
        check(ShaderPackDiagLogic.decide(true, COMP, Boolean.FALSE, false, true) == ShaderPackDiagLogic.Status.INTEROP_FAILED,
                "命中模板但注入失败 ⇒ INTEROP_FAILED(与'无模板'区分)");
        check(ShaderPackDiagLogic.decide(true, COMP, Boolean.FALSE, false, false) == ShaderPackDiagLogic.Status.ORIGINAL_PACK,
                "无标记 + 无注入 + 无模板 ⇒ ORIGINAL_PACK(真·无注入)");
        check(ShaderPackDiagLogic.decide(true, COMP, null, true, true) == ShaderPackDiagLogic.Status.UNKNOWN,
                "包内容不可读 ⇒ UNKNOWN(不拿注入结果猜)");
        check(ShaderPackDiagLogic.decide(true, COMP, Boolean.FALSE, true, false) == ShaderPackDiagLogic.Status.INTEROP_INJECTED,
                "injected 优先于 templateMatched 判定");

        // —— message:用户可见文案 ——
        String mInterop = ShaderPackDiagLogic.message(ShaderPackDiagLogic.Status.INTEROP_INJECTED, COMP, DERIVED);
        check(mInterop.contains("\u2714"), "INTEROP_INJECTED 文案带 ✔(不再是 ✘)");
        check(mInterop.contains("已注入到"), "INTEROP_INJECTED 文案明确说'已注入到'");
        check(mInterop.contains(COMP), "INTEROP_INJECTED 文案带当前包名");
        check(!mInterop.contains("无 TacLight 注入"), "[旧码必红] INTEROP_INJECTED 文案不得出现'无 TacLight 注入'");
        check(mInterop.contains("L=手电筒开关"), "INTEROP_INJECTED 文案给出 L 键用法");
        String mOrig = ShaderPackDiagLogic.message(ShaderPackDiagLogic.Status.ORIGINAL_PACK, "X.zip", DERIVED);
        check(mOrig.contains("无 TacLight 注入") && mOrig.contains(DERIVED), "ORIGINAL_PACK 文案仍给换包指引");
        String mFail = ShaderPackDiagLogic.message(ShaderPackDiagLogic.Status.INTEROP_FAILED, COMP, DERIVED);
        check(mFail.contains("注入失败") && !mFail.contains("无 TacLight 注入"),
                "INTEROP_FAILED 文案说'注入失败',不误报'无注入'");
        check(ShaderPackDiagLogic.message(ShaderPackDiagLogic.Status.NO_PACK, null, DERIVED).contains("未激活光影包"),
                "NO_PACK 文案不变");
        check(ShaderPackDiagLogic.message(ShaderPackDiagLogic.Status.UNKNOWN, null, DERIVED).contains("无法判定"),
                "UNKNOWN 文案不变");
        check(ShaderPackDiagLogic.message(ShaderPackDiagLogic.Status.TACLIGHT_PACK, COMP, DERIVED).contains("配套包已激活"),
                "TACLIGHT_PACK 文案不变");
        java.util.Set<String> msgs = new java.util.HashSet<>();
        for (var st : ShaderPackDiagLogic.Status.values()) msgs.add(ShaderPackDiagLogic.message(st, COMP, DERIVED));
        check(msgs.size() == ShaderPackDiagLogic.Status.values().length,
                "六个状态文案互不相同(实际 " + msgs.size() + "/" + ShaderPackDiagLogic.Status.values().length + ")");
        check(msgs.stream().noneMatch(String::isBlank), "文案均非空");

        // —— 换包建议只给真·无注入 ——
        check(ShaderPackDiagLogic.shouldAdvisePackSwitch(ShaderPackDiagLogic.Status.ORIGINAL_PACK),
                "只有 ORIGINAL_PACK 才建议换包");
        check(!ShaderPackDiagLogic.shouldAdvisePackSwitch(ShaderPackDiagLogic.Status.INTEROP_INJECTED),
                "注入成功时不得建议换包(用户实测就是被这句话误导)");
        check(!ShaderPackDiagLogic.shouldAdvisePackSwitch(ShaderPackDiagLogic.Status.TACLIGHT_PACK)
                        && !ShaderPackDiagLogic.shouldAdvisePackSwitch(ShaderPackDiagLogic.Status.INTEROP_FAILED),
                "配套包/注入失败均不适用'换包'这条建议");
        check(S.toString().contains("Status"), "状态枚举可用(防纯类被误删)");
    }

    /** 接线层:源码文本级(非运行时行为验证)。 */
    private static void wiringLayer() throws Exception {
        Path diag = Path.of("src/main/java/dev/taclight/client/ShaderPackDiag.java");
        Path logic = Path.of("src/main/java/dev/taclight/client/ShaderPackDiagLogic.java");
        Path events = Path.of("src/main/java/dev/taclight/client/ClientEvents.java");
        Path injector = Path.of("src/main/java/dev/taclight/interop/RuntimePackInjector.java");
        check(Files.isRegularFile(diag) && Files.isRegularFile(logic)
                        && Files.isRegularFile(events) && Files.isRegularFile(injector),
                "找到 ShaderPackDiag / ShaderPackDiagLogic / ClientEvents / RuntimePackInjector 源文件");
        if (!Files.isRegularFile(diag) || !Files.isRegularFile(logic)
                || !Files.isRegularFile(events) || !Files.isRegularFile(injector)) return;

        String diagSrc = Files.readString(diag, StandardCharsets.UTF_8);
        String logicSrc = Files.readString(logic, StandardCharsets.UTF_8);
        String evSrc = Files.readString(events, StandardCharsets.UTF_8);
        String injSrc = Files.readString(injector, StandardCharsets.UTF_8);

        check(diagSrc.contains("ShaderPackDiagLogic.decide"), "接线①:ShaderPackDiag 调用纯判定 decide");
        check(diagSrc.contains("RuntimePackInjector.lastOutcome()"), "接线②:ShaderPackDiag 读运行时注入结果");
        check(diagSrc.contains("shaders/shaders.properties"), "接线③:磁盘标记口径未被悄悄改掉");
        check(evSrc.contains("ShaderPackDiagLogic.message"), "接线④:ClientEvents 用纯类统一文案");
        // 判据修正(2026-09-25,本契约自己被咬过一次):不能直接对源码做子串判定——
        // 注释里提到旧文案(说明历史)不是违规。必须先去掉注释再判。
        check(!stripComments(evSrc).contains("无 TacLight 注入"),
                "接线⑤:ClientEvents 代码里不再写死旧文案(注释除外;文案已收口到纯类)");
        check(injSrc.contains("record Outcome") && injSrc.contains("lastOutcome ="),
                "接线⑥:RuntimePackInjector 发布结构化 Outcome");
        // 2026-09-25 真机轮实测缺陷:同包内个别文件失败会覆盖 lastOutcome ⇒ 注入成功却报失败。
        // 自检必须读"粘性成功";下面两条锁死这个修法(退回 lastOutcome ⇒ 必红)。
        check(injSrc.contains("if (injected) stickyInjected = lastOutcome"),
                "接线⑧:成功结果写入粘性字段(不被后续单文件失败覆盖)");
        check(diagSrc.contains("stickyInjectedOutcome()"),
                "接线⑨:ShaderPackDiag 读粘性成功,而不是最近一次结果");
        check(!logicSrc.contains("net.minecraft") && !logicSrc.contains("irisshaders"),
                "接线⑦:纯类不引用 MC/Iris(离线契约才加载得起来)");
    }

    /** 去掉块注释与行注释后再做文本断言:注释里提到旧文案(交代历史)不算违规。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

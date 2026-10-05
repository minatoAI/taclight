package dev.taclight.client;

/**
 * 光影包自检的<b>纯判定与文案</b>(2026-09-25)。
 *
 * <p><b>为什么单列纯类</b>:{@link ShaderPackDiag} 引用 {@code Minecraft}/{@code IrisApi},
 * 离线契约(纯 JVM,无 MC classpath)无法加载它。判定与文案是本 bug 的实质,
 * 必须可离线断言 ⇒ 抽到本类(与 {@code TuneService}/{@code RuntimePackInjector.formatStatus} 同一手法)。</p>
 *
 * <p><b>修的 bug(用户实测 2026-09-19 20:19 会话)</b>:用户用
 * {@code ComplementaryReimagined_r5.9.3.zip},日志 58 行
 * {@code interop injected ... known-good:anchors} 全成功,
 * 但聊天栏收到 {@code ✘ 当前包 ... 无 TacLight 注入}。
 * 根因:{@link ShaderPackDiag} 只在<b>磁盘包内容</b>里找标记
 * ({@code shaders/shaders.properties} 的 {@code TACLIGHT_PATCH_BEGIN}),
 * 而 interop 是<b>运行时在内存里改着色器源码</b>的 ⇒ 磁盘上永远找不到该标记 ⇒
 * 结构性误报"无注入",并把用户推向换包。判定必须同时看<b>运行时注入结果</b>。</p>
 */
public final class ShaderPackDiagLogic {

    /**
     * 活动包状态。
     *
     * <p>新增 {@code INTEROP_INJECTED}/{@code INTEROP_FAILED}(2026-09-25):
     * 前者=第三方包已由运行时 interop 通道成功注入;后者=命中模板但注入失败。
     * 旧三态对这两者<b>都</b>只能落到 {@code ORIGINAL_PACK},这是本 bug 的病根。</p>
     */
    public enum Status { NO_PACK, ORIGINAL_PACK, TACLIGHT_PACK, INTEROP_INJECTED, INTEROP_FAILED, UNKNOWN }

    /**
     * 用户可见文案里的按键标签(纯类不能引用 {@code KeyBindings},所以这里放常量)。
     *
     * <p><b>2026-09-26 task-16</b>:开灯键默认由 L 改为 <b>J</b>(L 与原版 {@code key.advancements}
     * 撞车 ⇒ 按 L 弹成就界面)。这两个常量必须与 {@code KeyBindings} 里的默认绑定一致 ——
     * {@code HandheldGateContract} 用"常量 == {@code KeyMapping.getDefaultKey().getDisplayName()}"钉死,
     * 谁改键不改文案(或反过来)都会变红。</p>
     */
    public static final String FLASHLIGHT_KEY_LABEL = "J";
    /** 霓虹调试键标签(见 {@link #FLASHLIGHT_KEY_LABEL})。 */
    public static final String DEBUG_KEY_LABEL = "K";

    private ShaderPackDiagLogic() {}

    /**
     * 纯判定(离线可测)。输入全部来自调用方,本类不读文件、不碰 MC。
     *
     * @param shaderPackInUse        Iris 是否正在使用某个光影包
     * @param packName               {@code config/oculus.properties} 的 {@code shaderPack} 原始值
     * @param diskMarker             {@code TRUE}=磁盘包内含我们的标记;{@code FALSE}=确认没有;
     *                               {@code null}=无法判定(包不可读/格式不认识)
     * @param interopInjected        运行时 interop 通道对<b>当前这个包</b>是否注入成功
     * @param interopTemplateMatched 运行时是否为<b>当前这个包</b>命中了模板(用于区分"注入失败"与"无模板")
     */
    public static Status decide(boolean shaderPackInUse, String packName, Boolean diskMarker,
                                boolean interopInjected, boolean interopTemplateMatched) {
        if (!shaderPackInUse) return Status.NO_PACK;
        if (packName == null || packName.isBlank()) return Status.UNKNOWN;
        // 磁盘注入优先:自研包/派生包是物理带标记的,无论运行时结果如何都算"配套包已激活"。
        if (Boolean.TRUE.equals(diskMarker)) return Status.TACLIGHT_PACK;
        if (diskMarker == null) return Status.UNKNOWN;          // 包内容不可读 ⇒ 不猜
        if (interopInjected) return Status.INTEROP_INJECTED;     // ← 本 bug 的修复点
        if (interopTemplateMatched) return Status.INTEROP_FAILED;
        return Status.ORIGINAL_PACK;
    }

    /**
     * 用户可见文案(纯函数)。{@code derivedPack} 由调用方传入,避免纯类依赖
     * {@code ClientEvents.DERIVED_PACK} 常量。
     */
    public static String message(Status st, String pack, String derivedPack) {
        return switch (st) {
            case TACLIGHT_PACK -> "[TacLight] \u2714 配套包已激活: " + FLASHLIGHT_KEY_LABEL + "=手电筒开关, "
                    + DEBUG_KEY_LABEL + "=霓虹调试";
            case INTEROP_INJECTED -> "[TacLight] \u2714 已注入到 '" + pack
                    + "' (运行时 interop 注入)";
            case INTEROP_FAILED -> "[TacLight] \u2718 当前包 '" + pack
                    + "' 命中注入模板但注入失败(见日志 interop 行)。可先在光影界面选 '" + derivedPack + "' 绕过";
            case ORIGINAL_PACK -> "[TacLight] \u2718 当前包 '" + pack + "' 无 TacLight 注入。请到选项>视频设置>光影(shaders)选择 '"
                    + derivedPack + "', 然后按 K";
            case NO_PACK -> "[TacLight] \u2718 未激活光影包: 锥光仅为视觉模式, K 霓虹无效(需要光影包)";
            default -> "[TacLight] ? 无法判定当前光影包。若按 K 无反应, 请在光影选择界面选 '" + derivedPack + "'";
        };
    }

    /** 只有这两种状态才该提示用户"去换包"(旧码对 INTEROP_INJECTED 也提示,即本 bug)。 */
    public static boolean shouldAdvisePackSwitch(Status st) {
        return st == Status.ORIGINAL_PACK;
    }
}

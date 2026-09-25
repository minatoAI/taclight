package dev.taclight.channel;

/**
 * {@code !light} 参数语义(纯逻辑,零 MC 依赖)。
 *
 * <p><b>为什么单独抽出来(2026-09-26 task-14)</b>:旧实现 {@code !light} <b>忽略一切参数</b>、永远 toggle,
 * 而交接/轮次脚本里写的是 {@code !light off}(本意 = 置 OFF)⇒ 实际发生的是"切换",
 * 于是"先置 OFF 再加按键"这类因果链会**悄悄错位**(测试同事 task-10 报的仪器缺陷之一)。
 * 现在:参数显式解析,{@code on/off} = <b>置位(幂等)</b>、{@code toggle} 或无参 = 切换、
 * {@code status} = 只读;**不认识的参数 ⇒ {@link #ACTION_NONE},中继报 usage 且不改任何状态**。</p>
 *
 * <p>回执必须能一眼看出"这次是切换还是置位" ⇒ {@link #describe} 的措辞里带 {@code toggle}/{@code set}。</p>
 */
public final class LightCommand {
    /** 参数不认识(调用方必须报 usage 并且不改状态)。 */
    public static final int ACTION_NONE = -1;
    /** 无参 / {@code toggle}:切换(旧行为,保持向后兼容)。 */
    public static final int ACTION_TOGGLE = 0;
    /** {@code on}:置 ON(幂等)。 */
    public static final int ACTION_ON = 1;
    /** {@code off}:置 OFF(幂等)。 */
    public static final int ACTION_OFF = 2;
    /** {@code status}:只读回显,不改状态。 */
    public static final int ACTION_STATUS = 3;

    private LightCommand() {}

    /** 参数 → 动作(大小写不敏感;空/null = 切换;未知名 = {@link #ACTION_NONE})。 */
    public static int action(String arg) {
        if (arg == null) return ACTION_TOGGLE;
        String a = arg.trim();
        if (a.isEmpty() || a.equalsIgnoreCase("toggle")) return ACTION_TOGGLE;
        if (a.equalsIgnoreCase("on")) return ACTION_ON;
        if (a.equalsIgnoreCase("off")) return ACTION_OFF;
        if (a.equalsIgnoreCase("status")) return ACTION_STATUS;
        return ACTION_NONE;
    }

    /** 是否是"置位"动作({@code on}/{@code off})。 */
    public static boolean isSet(int action) {
        return action == ACTION_ON || action == ACTION_OFF;
    }

    /** 是否会改状态(置位/切换 true;{@code status}/{@code NONE} false)。 */
    public static boolean mutates(int action) {
        return isSet(action) || action == ACTION_TOGGLE;
    }

    /** 动作 + 原状态 ⇒ 目标状态({@code status}/{@code NONE} 原样返回,调用方不应使用)。 */
    public static boolean nextState(int action, boolean before) {
        if (action == ACTION_ON) return true;
        if (action == ACTION_OFF) return false;
        if (action == ACTION_TOGGLE) return !before;
        return before;
    }

    /** 回执串:**必须能区分"切换"与"置位"**(task-14 的验收要求)。 */
    public static String describe(int action, boolean before, boolean after) {
        String from = before ? "ON" : "OFF";
        String to = after ? "ON" : "OFF";
        switch (action) {
            case ACTION_TOGGLE:
                return "light toggle: " + from + " -> " + to + "(切换;置位请用 !light on|off)";
            case ACTION_ON:
                return "light set ON(置位,幂等;原 " + from + ")";
            case ACTION_OFF:
                return "light set OFF(置位,幂等;原 " + from + ")";
            case ACTION_STATUS:
                return "light status: " + to + "(开关偏好;实际亮不亮还要过持物门)";
            default:
                return "light: 无法解析的参数; " + usage();
        }
    }

    /** 用法串(报错时回显)。 */
    public static String usage() {
        return "usage: !light [on|off|toggle|status]  (无参 = toggle 切换;on/off = 置位且幂等;status 只读)";
    }
}

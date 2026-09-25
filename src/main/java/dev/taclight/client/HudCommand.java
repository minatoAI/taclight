package dev.taclight.client;

/**
 * {@code !hud off|on|status} 的**纯语义核心**(零 MC 依赖;2026-09-26 task-51,dev-only 中继命令)。
 *
 * <p><b>用户诉求</b>:A3 的截图判据一直被**固定覆盖层**(聊天框 / 命令回执)污染 ⇒ 需要一个
 * **可判定**的"把 UI 藏起来"的手段(用户的授权原话:模拟注入**或**直接代码注入)。本命令走
 * **代码注入**这条路(dev-only,挂在已被证明存活的中继 tick 路径上)。</p>
 *
 * <p><b>语义(照 {@code !light} 的教训定稿)</b>:</p>
 * <ul>
 *   <li>{@code off}/{@code on} = **幂等置位** {@code mc.options.hideGui}(true/false);</li>
 *   <li>{@code status} = **只读回显**(不改任何状态);</li>
 *   <li>无参 / 未知参数 ⇒ **用法**,且**不改任何状态**({@link #ACTION_NONE});</li>
 *   <li><b>本命令不提供 toggle</b> —— {@code !light} 当初就是"无参=翻转 + on|off=置位"混在一起,
 *       让"先置 OFF 再验证"这类因果链悄悄错位 ⇒ 这里从根上摘掉歧义。</li>
 * </ul>
 *
 * <p><b>回执唯一真源</b>:{@link #describe(boolean)} —— 逐字可判,形如
 * {@code hud=off(hideGui=true)} / {@code hud=on(hideGui=false)}。</p>
 */
public final class HudCommand {
    /** 无参 / 未知参数:给用法,**不改状态**。 */
    public static final int ACTION_NONE = 0;
    /** 幂等置位 {@code hideGui = true}。 */
    public static final int ACTION_OFF = 1;
    /** 幂等置位 {@code hideGui = false}。 */
    public static final int ACTION_ON = 2;
    /** 只读回显。 */
    public static final int ACTION_STATUS = 3;

    private HudCommand() {}

    /** 解析参数(大小写不敏感、忽略首尾空白);无参/未知 ⇒ {@link #ACTION_NONE}(不猜)。 */
    public static int action(String arg) {
        if (arg == null) return ACTION_NONE;
        switch (arg.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off": return ACTION_OFF;
            case "on": return ACTION_ON;
            case "status": return ACTION_STATUS;
            default: return ACTION_NONE;
        }
    }

    /** 是否"幂等置位"动作(off/on)。 */
    public static boolean isSet(int action) {
        return action == ACTION_OFF || action == ACTION_ON;
    }

    /** 是否**会改状态**。{@link #ACTION_NONE}/{@link #ACTION_STATUS} 一律 false(契约钉死)。 */
    public static boolean mutates(int action) {
        return isSet(action);
    }

    /** off ⇒ 目标 hideGui = true;on ⇒ false(仅对置位动作有意义)。 */
    public static boolean targetHideGui(int action) {
        return action == ACTION_OFF;
    }

    /** 回执文本唯一真源:逐字可判(测试拿它当"确实生效"的证据)。 */
    public static String describe(boolean hideGui) {
        return "hud=" + (hideGui ? "off" : "on") + "(hideGui=" + hideGui + ")";
    }

    public static String usage() {
        return "usage: !hud off|on|status";
    }
}

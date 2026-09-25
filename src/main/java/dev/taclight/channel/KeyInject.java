package dev.taclight.channel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 中继合成按键注入的<b>纯逻辑核心</b>(2026-09-26 待办 A5:{@code !key})。
 * <b>零 MC 依赖</b>(谁去动 {@code KeyMapping}/GLFW 由 dev 中继
 * {@code DebugCommandRelay.KeyInjectRuntime} 做,发布包整类剔除)。
 *
 * <p><b>为什么需要</b>:harness 缺"按键注入",连"用 TacLight 按键后能拍到光斑"这条验收都做不到
 * (历史只能靠 {@code !sweep} 这类程序化激励绕开)。dev 中继已能把命令送进客户端 tick 线程
 * ⇒ 在 mod 侧做合成按键是最省事的闭环。</p>
 *
 * <p><b>★ 两类名字的消费路径不同(一手依据,决定注入走哪条路)</b>:</p>
 * <ul>
 *   <li><b>{@link #VANILLA}({@code use/attack/jump/sneak/sprint/hotbar.1..9})</b>:消费端在
 *       <b>tick 路径</b> —— {@code Minecraft.handleKeybinds()} 读 {@code keyHotbarSlots[i].isDown()}
 *       与 {@code keyUse/keyAttack.consumeClick()/isDown()},移动键由 {@code KeyboardInput} 读
 *       {@code isDown()}。{@code path = "tick"}。</li>
 *   <li><b>{@link #TACLIGHT}({@code flashlight/gunlight/debug/diag/bench/snapshot} = L/M/K/N/B/F9)</b>:
 *       消费端挂在 <b>{@code InputEvent.Key}</b>({@code ClientEvents.onKeyInput}、
 *       {@code TacSnapshotKeys.onKeyInput},都是 {@code while (mapping.consumeClick()) …})。
 *       {@code consumeClick()} 读的是 {@code clickCount},**只有真实按下路径才递增** ⇒
 *       注入必须走"能 set + click + fire {@code InputEvent.Key}"的那条路,只 {@code setDown}
 *       会**静默无效**。{@code path = "event"}。</li>
 * </ul>
 *
 * <p><b>★ 焦点无关</b>:上面两类都由 dev 中继在**进程内**驱动
 * ({@code KeyboardHandler.keyPress} 的首行只校验 {@code window == mc.getWindow().getWindow()},
 * 与窗口焦点无关)⇒ 窗口不聚焦的轮次(run-round.ps1 暂态 {@code pauseOnLostFocus=false})照样生效。</p>
 *
 * <p>纯 JVM 可测:名字表、{@code hotbar.N} 解析、参数解析、<b>毫秒自动抬起状态机</b>与
 * {@link Tracker} 全在这里,由 {@code KeyInjectContract} 钉(每条断言都有红对照)。</p>
 */
public final class KeyInject {
    /** 原版 {@code mc.options} 映射(tick 路径消费):5 个动作键 + 9 个 hotbar 槽位。 */
    public static final String[] VANILLA = {
            "use", "attack", "jump", "sneak", "sprint",
            "hotbar.1", "hotbar.2", "hotbar.3", "hotbar.4", "hotbar.5",
            "hotbar.6", "hotbar.7", "hotbar.8", "hotbar.9",
    };

    /** TacLight 自己的按键(InputEvent.Key 路径消费;名字 = 语义名,顺序同 {@link #TACLIGHT_EQUIV})。 */
    public static final String[] TACLIGHT = {"flashlight", "gunlight", "debug", "diag", "bench", "snapshot"};

    /** 与 {@link #TACLIGHT} 一一对应的程序化等价命令(顺序必须一致;由契约钉)。 */
    public static final String[] TACLIGHT_EQUIV = {"!light", "!gun", "!neon", "!diag", "!bench", "!snap"};

    /** 全部可注入名字(顺序 = {@code !key list} 输出顺序)。 */
    public static final String[] INJECTABLE = concat(VANILLA, TACLIGHT);

    /** 参数不是合法动作/时长。 */
    public static final int ACTION_NONE = -1;
    /** {@code down}:按下并保持(等价物理按住;到 {@link #SAFETY_HOLD_MS} 兜底抬起)。 */
    public static final int ACTION_DOWN = 0;
    /** {@code up}:抬起。 */
    public static final int ACTION_UP = 1;
    /** {@code <毫秒>}:物理按一下(click + down),到点自动 up。 */
    public static final int ACTION_TAP = 2;

    /** 消费路径:tick 路径({@code Minecraft.handleKeybinds}/{@code KeyboardInput})。 */
    public static final String PATH_TICK = "tick";
    /** 消费路径:{@code InputEvent.Key}(consumeClick/clickCount)。 */
    public static final String PATH_EVENT = "event";
    /** 未知名。 */
    public static final String PATH_UNKNOWN = "unknown";

    /** tap 时长下界(ms;0 非法 —— 用 {@code up} 表达"抬起")。 */
    public static final int MIN_TAP_MS = 1;
    /** tap 时长上界(ms):超过即报错(不静默 clamp,便于发现脚本写错)。 */
    public static final int MAX_TAP_MS = 60_000;
    /** 按住硬上限(ms):任何情况下超过它就当"已抬起" ⇒ 防"忘了抬起"/时钟异常留下卡键。 */
    public static final long SAFETY_HOLD_MS = 60_000;

    private KeyInject() {}

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean in(String[] arr, String name) {
        if (name == null) return false;
        for (String s : arr) {
            if (s.equals(name)) return true;
        }
        return false;
    }

    /** 是否是可注入名字(未知名 ⇒ false;调用方必须报错并列出 {@link #INJECTABLE})。 */
    public static boolean isInjectable(String name) {
        return in(INJECTABLE, name);
    }

    /** 是否是原版映射({@code path = "tick"})。 */
    public static boolean isVanilla(String name) {
        return in(VANILLA, name);
    }

    /** 是否是 TacLight 自己的按键({@code path = "event"})。 */
    public static boolean isTacLightKey(String name) {
        return in(TACLIGHT, name);
    }

    /** {@code "hotbar.N"}(N=1..9)⇒ 槽位下标 0..8;其余(含 hotbar.0/hotbar.10/别的名字)⇒ -1。 */
    public static int hotbarIndex(String name) {
        if (name == null || !name.startsWith("hotbar.")) return -1;
        String n = name.substring("hotbar.".length());
        if (n.length() != 1 || n.charAt(0) < '1' || n.charAt(0) > '9') return -1;
        return n.charAt(0) - '1';
    }

    /** 该名字的消费路径({@link #PATH_TICK}/{@link #PATH_EVENT}/{@link #PATH_UNKNOWN})。 */
    public static String consumptionPath(String name) {
        if (isVanilla(name)) return PATH_TICK;
        if (isTacLightKey(name)) return PATH_EVENT;
        return PATH_UNKNOWN;
    }

    /** {@link #TACLIGHT} 里 {@code name} 对应的等价命令;不是 ⇒ null。 */
    public static String taclightEquivalent(String name) {
        for (int i = 0; i < TACLIGHT.length; i++) {
            if (TACLIGHT[i].equals(name)) return TACLIGHT_EQUIV[i];
        }
        return null;
    }

    /**
     * 动作解析(纯函数):{@code "down"} ⇒ {@link #ACTION_DOWN};{@code "up"} ⇒ {@link #ACTION_UP};
     * 纯数字且在 {@code 1..MAX_TAP_MS} ⇒ {@link #ACTION_TAP};其余(含 0/负数/超界/非数字/空)⇒
     * {@link #ACTION_NONE}(调用方据此回 usage,**不**静默猜测)。
     */
    public static int actionFor(String arg) {
        if (arg == null) return ACTION_NONE;
        String a = arg.trim();
        if (a.equals("down")) return ACTION_DOWN;
        if (a.equals("up")) return ACTION_UP;
        return tapMs(a) > 0 ? ACTION_TAP : ACTION_NONE;
    }

    /** tap 时长(ms):合法范围 {@code 1..MAX_TAP_MS};否则 {@code -1}(非法)。 */
    public static int tapMs(String arg) {
        if (arg == null) return -1;
        String a = arg.trim();
        if (a.isEmpty() || a.length() > 5) return -1;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) < '0' || a.charAt(i) > '9') return -1;
        }
        int v;
        try {
            v = Integer.parseInt(a);
        } catch (NumberFormatException e) {
            return -1;
        }
        return (v >= MIN_TAP_MS && v <= MAX_TAP_MS) ? v : -1;
    }

    /**
     * 内部按住时长的安全钳制:{@code durMs} 先按 {@code >= 1} 兜底,再**封顶** {@link #SAFETY_HOLD_MS}
     * ⇒ 即使有人绕过 {@link #tapMs} 的校验直接塞巨大值,也不会留下永久卡键。
     */
    public static long clampHoldMs(long durMs) {
        if (durMs < MIN_TAP_MS) return MIN_TAP_MS;
        return Math.min(durMs, SAFETY_HOLD_MS);
    }

    /**
     * <b>毫秒自动抬起状态机</b>(纯函数,判据即契约):{@code t0} 按下、{@code durMs} 后抬起
     * ⇒ 区间 {@code [t0, t0+durMs)} 内为 down。{@code now < t0}(时钟回拨) 也视为 up(安全侧)。
     */
    public static boolean isDown(long t0, long durMs, long now) {
        if (durMs <= 0) return false;
        long dt = now - t0;
        return dt >= 0 && dt < durMs;
    }

    /** {@link #isDown} 的<b>带兜底</b>版本:时长先过 {@link #clampHoldMs}(封顶 60s)。 */
    public static boolean held(long t0, long durMs, long now) {
        return isDown(t0, clampHoldMs(durMs), now);
    }

    /** {@code !key} 用法串(报错时回显;所有可选项都在里面)。 */
    public static String usage() {
        return "usage: !key <name> <down|up|ms> | !key list | !key clear"
                + "   (可注入 " + INJECTABLE.length + " 个: " + String.join(" ", INJECTABLE)
                + ";ms " + MIN_TAP_MS + ".." + MAX_TAP_MS + ")";
    }

    /** 未知名/坏参数的报错串(必须列出可选项,供测试当负对照)。 */
    public static String unknownName(String name) {
        return "unknown key '" + name + "'; " + usage();
    }

    /** {@code !key list} 第一行:可注入名字 + 各自消费路径。 */
    public static String injectableLine() {
        return "injectable(" + INJECTABLE.length + "): vanilla/" + PATH_TICK + " = " + String.join(" ", VANILLA)
                + " | taclight/" + PATH_EVENT + " = " + String.join(" ", TACLIGHT);
    }

    /** {@code !key list}:TacLight 按键的名字 → 程序化等价命令。 */
    public static String taclightLine() {
        StringBuilder sb = new StringBuilder("taclight keys(InputEvent.Key 路径;等价通道兜底): ");
        for (int i = 0; i < TACLIGHT.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(TACLIGHT[i]).append("→").append(TACLIGHT_EQUIV[i]);
        }
        return sb.toString();
    }

    /**
     * "当前按下中"的状态机(纯数据,零 MC):记下 {@code name} 从 {@code now} 起按住 {@code durMs},
     * 到点(或超 {@link #SAFETY_HOLD_MS})由调用方抬起。
     *
     * <p><b>无卡键保证</b>:{@link #dueAt} 必在窗口结束后返回该名字 ⇒ 每 tick 调一次就不可能出现
     * "永远按住";{@code durMs <= 0} 直接不记录(等价立即抬起)。</p>
     */
    public static final class Tracker {
        /** name ⇒ {t0, dur}(插入序保留,便于 {@code !key list} 输出稳定)。 */
        private final LinkedHashMap<String, long[]> held = new LinkedHashMap<>();

        /** 记下"按下 name 持续 durMs";{@code durMs <= 0} ⇒ 取消记录(立即抬起语义)。 */
        public void hold(String name, long durMs, long now) {
            if (name == null) return;
            if (durMs <= 0) {
                held.remove(name);
                return;
            }
            held.put(name, new long[]{now, clampHoldMs(durMs)});
        }

        /** 该名字此刻还在按住窗口内(含兜底)。 */
        public boolean isHeld(String name, long now) {
            long[] v = held.get(name);
            return v != null && KeyInject.held(v[0], v[1], now);
        }

        /** 到点(或超兜底)该抬起的名字,按记录顺序;抬起后调用 {@link #forget}。 */
        public List<String> dueAt(long now) {
            List<String> out = new ArrayList<>();
            for (var e : held.entrySet()) {
                if (!KeyInject.held(e.getValue()[0], e.getValue()[1], now)) out.add(e.getKey());
            }
            return out;
        }

        /** 当前记录着的名字(按记录顺序)。 */
        public List<String> tracked() {
            return new ArrayList<>(held.keySet());
        }

        /** 抬起后忘掉该名字。 */
        public void forget(String name) {
            held.remove(name);
        }

        /** 全部忘掉(配合 {@code KeyMapping.releaseAll()} 用)。 */
        public void clear() {
            held.clear();
        }

        public int size() {
            return held.size();
        }
    }
}

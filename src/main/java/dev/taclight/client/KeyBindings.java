package dev.taclight.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class KeyBindings {
    /**
     * 开灯键(默认 <b>J</b>;2026-09-26 task-16 由 L 迁移过来)。
     *
     * <p><b>为什么换(普查依据,不是凭印象)</b>:旧默认 <b>L</b> 与原版 {@code key.advancements}
     * 默认同键(反编译依据:vanilla {@code Options} 构造里
     * {@code new KeyMapping("key.advancements", 76, "key.categories.misc")},76 = GLFW_KEY_L)
     * ⇒ 玩家按 L 开灯会**同时弹出成就界面**,且界面开着时下一次按键被 MC 吞掉(task-10 真机实测)。</p>
     *
     * <p><b>2026-09-26 键位普查</b>:① 原版 1.20.1 默认字母键占用 =
     * W A S D E F Q T P <b>L</b> C X(另有 1..9 / SPACE / LSHIFT / LCTRL / TAB / SLASH / F2 / F5 / F7 / F11);
     * ② TacLight 自己 = L(手电,本次改掉) M(枪灯) K(霓虹) N(诊断) B(基准) F9(快照);
     * ③ 旗舰集成 TaCZ 1.1.8 默认 = R(换弹) H(检视) G(开火模式) V(近战+变焦) C(匍匐) Z(改枪) O(交互) T(配置)
     * —— **因此 Lead 建议的 V/H/G/R 四个候选恰好全被 TaCZ 占用,都不可用**;
     * ④ 实例内 Embeddium/Oculus 不注册任何按键。
     * ⇒ 从未占用集合 {I,J,U,Y,…} 中选 <b>J</b>:右手 home row(与旧键 L 同一只手,肌肉记忆迁移最小)、
     * 单键无修饰、原版 / TacLight / TaCZ 三方都未占用。</p>
     *
     * <p><b>迁移策略与实测(只改默认值)</b>:语义名 {@code key.taclight.flashlight_toggle} <b>不变</b>
     * ⇒ {@code !key flashlight}、快照、文档里的语义名都不受影响。本模组**没有自己的按键配置文件**;
     * 唯一候选真源是 Minecraft 的 {@code options.txt}。</p>
     *
     * <p><b>★ 实测结论与"以用户配置为准"相反</b>:测试实例的 {@code options.txt}
     * (mtime 2026-09-19,**早于** task-10 轮次)里确实存着
     * {@code key_key.taclight.flashlight_toggle:key.keyboard.k},而 task-10 r2 的注入
     * (注入键码 = 该映射的**有效**绑定 {@code getKey().getValue()})却打开了原版成就界面 ⇒
     * **有效绑定仍是代码默认 L,存档的 K 被忽略**(即"默认值胜出")。
     * ⇒ 只陈述观测,<b>不写未验证的机制</b>:这里**不能假定老实例会保留存档值**。
     * {@code options.txt} 里 {@code key_*} 行与 mod 按键注册的先后、以及"改键后重启是否持久"
     * 尚未查清 ⇒ 已作为独立问题上报(候选机制 + 判定实验见
     * {@code docs/evidence/2026-09-27-defaultkey-dev/README.md},观测手段是
     * {@code !key list} 每行新增的"有效键名"字段)。</p>
     */
    public static final KeyMapping FLASHLIGHT_TOGGLE = new KeyMapping(
            "key.taclight.flashlight_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "key.categories.taclight");


    /**
     * M:枪灯手动开关(TaCZ 原版无激光/灯开关,见 options 键表:inspect/reload/shoot/
     * interact/fire_select/aim/crawl/refit/zoom/melee 均无 laser 位)。与 !gun 共用
     * GunControl 状态机;翻转即立手动旗(探针不再覆盖),!gun auto 可清旗回探针。
     */
    public static final KeyMapping GUNLIGHT_TOGGLE = new KeyMapping(
            "key.taclight.gunlight_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.taclight");



    private KeyBindings() {}
}

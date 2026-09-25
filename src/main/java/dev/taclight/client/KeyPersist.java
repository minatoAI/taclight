package dev.taclight.client;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按键存档值的<b>纯解析</b>(只读不写;2026-09-26 task-32)。
 *
 * <p><b>为什么需要它</b>:实测(测试同事 task-21)现象 —— 同一份 {@code options.txt} 里
 * <b>原版键</b>的存档值生效,而 <b>TacLight 键</b>的存档值被忽略(落回代码默认)⇒ 玩家在
 * "控制设置"里改我们的键,<b>重进游戏会失效</b>(面向玩家的真 bug)。</p>
 *
 * <p><b>结构性证据</b>(反编译 mapped jar):存档键位的应用点在
 * {@code Options.processOptionsForge(...)},它按 {@code KeyMapping.getName()} <b>字符串匹配</b>后
 * {@code setKeyModifierAndCode(...)};而映射是<b>构造时</b>登记进 {@code KeyMapping.ALL}/{@code KeyMappingLookup}
 * 的 ⇒ 若本模组映射在应用那一刻还不存在,存档行就<b>无处落地</b>。至于本模组的注册与
 * {@code Options.load()} 谁先谁后,在 123 个 forge jar 里<b>查不到</b>
 * {@code ForgeHooksClient.onRegisterKeyMappings} 的调用点 ⇒ <b>如实说"未知"</b>。</p>
 *
 * <p>因此修法走<b>与机制无关</b>的路线:注册之后由我们**自己**把存档值应用到本模组映射
 * (见 {@code ClientEvents.ModBus.applySavedKeybindingsOnce()},只在初始化阶段跑一次)。
 * 本类只负责"从文本里读出值",不做任何 IO、不碰 MC ⇒ 可离线契约 + 红对照。</p>
 */
public final class KeyPersist {
    /** options.txt 里键位行的前缀(形如 {@code key_key.taclight.flashlight_toggle:key.keyboard.j})。 */
    public static final String PREFIX = "key_";

    private KeyPersist() {}

    /**
     * 从 {@code options.txt} 文本里解析出 {@code names} 中每个名字的存档值。
     *
     * <p>口径(全部由契约钉死):</p>
     * <ul>
     *   <li>只认 {@code key_<name>:<value>} 形式;行首尾空白忽略;空行与 {@code #} 注释行跳过;</li>
     *   <li><b>精确名字匹配</b> —— 只挑 {@code names} 里有的名字(原版键位行一律忽略);</li>
     *   <li>没有冒号 / 冒号前没有名字 / 值为空 ⇒ 跳过该行(不猜);</li>
     *   <li><b>同名多行 ⇒ 最后一行生效</b>(与"逐行写入/覆盖"的直觉一致)。</li>
     * </ul>
     *
     * @param optionsTxtText {@code options.txt} 的全文(null ⇒ 空结果)
     * @param names          本模组映射的语义名(如 {@code key.taclight.flashlight_toggle})
     * @return 名字 → 值(如 {@code key.keyboard.j});没有存档值的名字<b>不会</b>出现在结果里
     */
    public static Map<String, String> parse(String optionsTxtText, Collection<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        if (optionsTxtText == null || names == null || names.isEmpty()) return out;
        for (String raw : optionsTxtText.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!line.startsWith(PREFIX)) continue;
            int colon = line.indexOf(':');
            if (colon <= PREFIX.length()) continue;              // 没有名字(或整个就是 "key_")
            String name = line.substring(PREFIX.length(), colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.isEmpty() || value.isEmpty()) continue;      // 不猜:名字/值缺一不可
            if (!names.contains(name)) continue;                  // 只认本模组名字
            out.put(name, value);                                // 后一行覆盖前一行
        }
        return out;
    }
}

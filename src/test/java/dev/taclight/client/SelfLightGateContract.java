package dev.taclight.client;

/**
 * 自身灯总闸契约(2026-09-03 用户需求:枪灯测试单变量观察)。
 * 语义:SELF_LIGHT_ENABLED=false 时本客户端不上传自身两盏灯(手持+枪),
 * 远程灯收集照常 —— 旁观端画面只剩远程枪灯,零自身光干扰。
 * 运行时 !selflight 可覆写翻转(覆写 null=跟随配置)。
 */
public class SelfLightGateContract {
    public static void main(String[] args) {
        // 默认:无覆写 → 跟随配置;测试环境配置缺省取 true(零行为变化)
        check(ClientLightState.selfLightEnabled(), "默认跟随配置=开(零行为变化)");
        // 翻转关 → 自身灯禁上传
        check(!ClientLightState.toggleSelfLight(), "toggle 关 → selfLightEnabled=false");
        check(!ClientLightState.selfLightEnabled(), "覆写关保持");
        // 再翻转开 → 恢复
        check(ClientLightState.toggleSelfLight(), "toggle 开 → selfLightEnabled=true");
        check(ClientLightState.selfLightEnabled(), "覆写开保持");
        // 复位覆写,免污染后续契约/运行
        try {
            var f = ClientLightState.class.getDeclaredField("selfLightOverride");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Throwable t) {
            throw new AssertionError("FAIL 覆写复位 reflection: " + t);
        }
        check(ClientLightState.selfLightEnabled(), "覆写复位后跟随配置=开");
        System.out.println("SelfLightGateContract: ALL PASS (6 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

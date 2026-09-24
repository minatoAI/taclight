package dev.taclight.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 手持灯持物门契约(2026-09-25,用户报的 bug)。
 *
 * <p><b>现象</b>:手里拿着枪时,<b>枪灯与手持灯同时亮</b>;把手电筒/手上物品换掉,手持灯也不灭。
 * 根因:上传器只查开关({@code ClientLightState.isOn()}),不查手里拿的是什么;
 * 而枪灯早就有"开关 × 持枪探针"的乘法门。</p>
 *
 * <p><b>用户口述的设计</b>:①先看手里是不是拿着 {@code taclight:flashlight};
 * ②再看开关;③从手里移除后自动关上。</p>
 *
 * <p>分两层:内容层=纯函数 {@code effective}/{@code autoClear} 的真值表(离线可判定);
 * 接线层=源码级,确认上传器 / 按键 / 中继 / 快照 / Iris 物品光源**五处口径一致**
 * (改漏一处 = 假绿,这是本项目反复被咬的地方)。</p>
 */
public class HandheldGateContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        contentLayer();
        wiringLayer();
        System.out.println("HandheldGateContract: ALL PASS (" + checks + " checks)");
    }

    private static void contentLayer() {
        // effective = 开关 × (持物门 ∪ 霓虹旁路):8 种组合全覆盖
        for (boolean on : new boolean[] { false, true }) {
            for (boolean holding : new boolean[] { false, true }) {
                for (boolean neon : new boolean[] { false, true }) {
                    boolean expected = on && (holding || neon);
                    check(ClientLightState.effective(on, holding, neon) == expected,
                            "effective(switch=" + on + ", holding=" + holding + ", neon=" + neon + ")=" + expected);
                }
            }
        }
        // ↓↓↓ 旧码必红锚点:旧实现只查开关 ⇒ 未持物时也会亮(即用户报的"两盏灯同时开") ↓↓↓
        check(!ClientLightState.effective(true, false, false),
                "[旧码必红] 未持手电筒 + 开关开 ⇒ 不亮(旧码在此为亮)");
        check(ClientLightState.effective(true, true, false), "持手电筒 + 开关开 ⇒ 亮");
        check(!ClientLightState.effective(false, true, false), "开关关 ⇒ 不亮(与持物无关)");
        check(ClientLightState.effective(true, false, true),
                "霓虹调试豁免持物门:它的用途是证明 SSBO 通道,与手里拿什么无关");

        // autoClear = 离手自动关
        check(ClientLightState.autoClear(true, true, false, false), "原本持有→现在不持有 ⇒ 自动关");
        check(!ClientLightState.autoClear(true, false, false, false), "本来就没持有 ⇒ 不触发(防误报)");
        check(!ClientLightState.autoClear(true, true, true, false), "仍持有 ⇒ 不触发");
        check(!ClientLightState.autoClear(false, true, false, false), "开关本就是关 ⇒ 不触发");
        check(!ClientLightState.autoClear(true, true, false, true), "霓虹调试期间不自动关(否则一放手就灭)");
    }

    /** 接线层:源码文本级(非运行时行为验证)。 */
    private static void wiringLayer() throws Exception {
        Path uploader = Path.of("src/main/java/dev/taclight/channel/ClientSpotlightUploader.java");
        Path events = Path.of("src/main/java/dev/taclight/client/ClientEvents.java");
        Path relay = Path.of("src/main/java/dev/taclight/client/DebugCommandRelay.java");
        Path snapshot = Path.of("src/main/java/dev/taclight/client/DebugSnapshotter.java");
        Path item = Path.of("src/main/java/dev/taclight/item/FlashlightItemIris.java");
        check(Files.isRegularFile(uploader) && Files.isRegularFile(events) && Files.isRegularFile(relay)
                        && Files.isRegularFile(snapshot) && Files.isRegularFile(item),
                "找到五个受影响源文件");
        if (!Files.isRegularFile(uploader)) return;
        String up = Files.readString(uploader, StandardCharsets.UTF_8);
        String ev = Files.readString(events, StandardCharsets.UTF_8);
        String rl = Files.readString(relay, StandardCharsets.UTF_8);
        String sn = Files.readString(snapshot, StandardCharsets.UTF_8);
        String it = Files.readString(item, StandardCharsets.UTF_8);

        check(up.contains("ClientLightState.handheldEffective()"), "接线①:上传器用手持灯有效值");
        check(!up.contains("selfOn && ClientLightState.isOn()"), "[旧码必红] 上传器不再只看开关");
        check(ev.contains("ClientLightState.setHandheldProbe("), "接线②:tick 覆写持物探针");
        check(ev.contains("ClientLightState.autoClear("), "接线③:tick 执行离手自动关");
        check(ev.contains("holdingFlashlight("), "接线④:L 键走同一判据");
        check(rl.contains("ClientEvents.holdingFlashlight(mc.player)"), "接线⑤:中继 !light 走同一判据");
        check(sn.contains("\"handheldEffective\""), "接线⑥:快照暴露有效值(与 gunEffective 对称)");
        check(it.contains("ClientLightState.handheldEffective()"), "接线⑦:Iris 物品光源用同一口径");
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

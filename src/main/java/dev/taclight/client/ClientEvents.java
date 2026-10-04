package dev.taclight.client;

import dev.taclight.TacLightMod;
import dev.taclight.tacz.GunLaserReader;
import dev.taclight.tacz.TaczClientLightProbe;
import dev.taclight.tacz.TaczCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT)
public class ClientEvents {
    private static GunLaserReader.Status lastGunStatus = GunLaserReader.Status.NONE;
    private static boolean lastSentEffective = false;
    /** 手持灯有效值上次上报值(与枪灯分开判变化:开关现在挂在**物品**上,换手就会变)。 */
    private static boolean lastSentHandheld = false;
    private static int e2eTick;
    private static boolean e2eLogged;
    /** tune 持久化恢复(一次性,首个世界 tick;config 此时必已加载,比 MOD setup 更稳)。 */
    private static boolean tuneRestored;

    /** 端到端探针回读(仅 TACLIGHT_PROBE=1 时启用;诊断用,默认静默)。 */
    private static void probeIfEnabled() {
        if (!"1".equals(System.getenv("TACLIGHT_PROBE"))) return;
        if (++e2eTick % 60 != 0) return;
        int bits = dev.taclight.channel.LightBuffer.readReserved();
        TacLightMod.LOGGER.info("[TacLight] E2E-PROBE reserved=0x{} {}", Integer.toHexString(bits), decodeProbe(bits));
        TacLightMod.LOGGER.info("[TacLight] GPU-READBACK {}", dev.taclight.channel.LightBuffer.dumpLight0());
        if (!e2eLogged && (bits & 3) == 3) { e2eLogged = true; }
    }

    /** 解码 GLSL 探针位:1=surface 进入,2=门通过,4=光已贡献,8=debug 标志已见,0x10=beam,0x20=specular。 */
    private static String decodeProbe(int bits) {
        StringBuilder sb = new StringBuilder();
        if ((bits & 1) != 0) sb.append(" |surface-entered");
        if ((bits & 2) != 0) sb.append(" |gate-passed");
        if ((bits & 4) != 0) sb.append(" |light-contributed");
        if ((bits & 8) != 0) sb.append(" |debug-flag-seen");
        if ((bits & 16) != 0) sb.append(" |beam-entered");
        if ((bits & 32) != 0) sb.append(" |specular-entered");
        if ((bits & 1024) != 0) sb.append(" |slot0-sees-our-data");
        if ((bits & 2048) != 0) sb.append(" |slot1-sees-our-data");
        if ((bits & 4096) != 0) sb.append(" |slot8-sees-our-data");
        return sb.length() == 0 ? "(none)" : sb.toString().substring(1);
    }
    private static int probeTick;
    private static boolean probeConfirmed;
    private static int diagTick;
    private static ShaderPackDiagLogic.Status lastDiagStatus;
    private static boolean diagAutoApplied;
    private static int boardTick;
    private static final String DERIVED_PACK = "iterationT 3.2.0 (taclight)";

    /**
     * 应用逻辑的容器(2026-09-26 task-32 v2)。
     *
     * <p><b>它不再是 MOD 总线订阅类</b>:注册已搬到顶层 {@link KeyBindingsModBus}(原来挂在这里的
     * {@code @Mod.EventBusSubscriber(bus = MOD)} 已移除)。理由见 {@link KeyBindingsModBus}:
     * 复验实测"注册处理器没有产生任何可观测副作用",而机制无法从测试侧判定 ⇒ 不再把关键逻辑
     * 挂在"未验证会被触发的事件"上。</p>
     */
    static final class ModBus {
        /** 首 tick 兜底应用的 once-only 守卫(幂等;应用后玩家在同一会话里的即时改动不受影响)。 */
        private static boolean appliedSavedKeys;

        /** 由 {@code onClientTick}(FORGE 总线,**已被中继/tick 证明存活**)在首个 tick 调用一次。 */
        static void applySavedKeysOnceAtFirstTick() {
            if (appliedSavedKeys) return;
            appliedSavedKeys = true;
            applySavedKeybindingsOnce();
        }

        /**
         * 把 {@code options.txt} 里本模组的存档键位应用到映射(2026-09-26 task-32)。
         *
         * <p><b>为什么放在这里</b>:实测算术上"存档值不生效"(原版键生效、我们的键落回代码默认)。
         * 结构性证据 = 应用点 {@code Options.processOptionsForge} 按 {@code KeyMapping.getName()}
         * 字符串匹配,而映射是构造时登记 ⇒ 只要本模组映射在那一刻尚未存在,存档行就无处落地。
         * 本模组的注册/加载先后在 forge 产物里查不到(123 个 jar 都没有
         * {@code onRegisterKeyMappings} 的调用点)⇒ 机制**未定论**,所以修法走**与机制无关**的路线:
         * 注册完成后由我们自己把存档值读出来应用一次。</p>
         *
         * <p><b>调用点 = 首个客户端 tick</b>({@code onClientTick} → {@code ModBus.applySavedKeysOnceAtFirstTick()},
         * 有 once-only 守卫)⇒ 与"注册事件是否被投递"无关;{@code Options.load()} 在 Minecraft 构造期、
         * <b>早于首 tick</b> ⇒ 应用必然发生在加载之后。之后玩家在"控制设置"里的即时改动
         * <b>不会</b>被我们覆盖(唯一调用点 + 幂等)。无存档值 ⇒ 保持 task-16 的新默认 {@code J}。</p>
         *
         * <p><b>可观测行</b>(供 task-34 复验捞取;两条日志各自记账,不许互相冒领):
         * 本方法只报<b>它自己实际改了几条</b>({@code changed=})、以及"目标值本来就已经是这样"的条数
         * ({@code already=})⇒ 若 {@code changed=0, already>0} 说明是**注册路径**已经生效;若
         * {@code changed>0} 说明是**本兜底路径**在救场。</p>
         */
        private static void applySavedKeybindingsOnce() {
            try {
                java.util.Map<String, net.minecraft.client.KeyMapping> ours = new java.util.LinkedHashMap<>();
                ours.put("key.taclight.flashlight_toggle", KeyBindings.FLASHLIGHT_TOGGLE);
                ours.put("key.taclight.gunlight_toggle", KeyBindings.GUNLIGHT_TOGGLE);
                ours.put("key.taclight.debug_toggle", KeyBindings.DEBUG_TOGGLE);
                ours.put("key.taclight.diag_dump", KeyBindings.DIAG_DUMP);
                ours.put("key.taclight.bench", KeyBindings.BENCH);
                String before = describeKeys(ours);
                java.nio.file.Path file = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                        .resolve("options.txt");
                boolean exists = java.nio.file.Files.isRegularFile(file);
                String text = exists
                        ? new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
                        : "";
                java.util.Map<String, String> saved = KeyPersist.parse(text, ours.keySet());
                int changed = 0;
                int already = 0;
                for (java.util.Map.Entry<String, String> e : saved.entrySet()) {
                    try {
                        net.minecraft.client.KeyMapping m = ours.get(e.getKey());
                        if (m == null) continue;
                        com.mojang.blaze3d.platform.InputConstants.Key want =
                                com.mojang.blaze3d.platform.InputConstants.getKey(e.getValue());
                        if (m.getKey().equals(want)) {
                            // 当前绑定已经是存档值 ⇒ 不是本路径的功劳(多半是注册路径已生效)⇒ 不计入 changed
                            already++;
                            continue;
                        }
                        m.setKey(want);
                        changed++;
                    } catch (Throwable bad) {
                        // 单个值坏(例如 key.keyboard.unknown 之外的怪值)不影响其它映射
                        TacLightMod.LOGGER.warn("[TacLight] keybind persist: 值 '{}= {}' 应用失败: {}",
                                e.getKey(), e.getValue(), bad.toString());
                    }
                }
                TacLightMod.LOGGER.info(
                        "[TacLight] keybind persist: file={} exists={} saved={} changed={} already={} before={} after={}",
                        file, exists, saved.size(), changed, already, before, describeKeys(ours));
            } catch (Throwable t) {
                // 失败一律不改默认(task-16 的 J 保住),只留日志
                TacLightMod.LOGGER.warn("[TacLight] keybind persist: 应用存档键位失败(保持代码默认): {}", t.toString());
            }
        }

        /** 把 5 个映射的当前键压成一行(诊断用;键名去掉 {@code key.taclight.} 前缀)。 */
        private static String describeKeys(java.util.Map<String, net.minecraft.client.KeyMapping> ours) {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, net.minecraft.client.KeyMapping> e : ours.entrySet()) {
                if (sb.length() > 0) sb.append(',');
                String n = e.getKey();
                sb.append(n.startsWith("key.taclight.") ? n.substring("key.taclight.".length()) : n)
                        .append('=').append(e.getValue().getKey().getName());
            }
            return sb.toString();
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        while (KeyBindings.FLASHLIGHT_TOGGLE.consumeClick()) {
            // 持物门(2026-09-25 用户设计):①先看手里是不是拿着手电筒 ②再看开关。
            // 未持有且不在霓虹调试时**不改状态**,只给可操作提示(避免"关了但看着还亮"的假成功)。
            if (!holdingFlashlight(Minecraft.getInstance().player) && !ClientLightState.debugMode()) {
                Minecraft mcL = Minecraft.getInstance();
                if (mcL.player != null) {
                    // 键名不写死:从 KeyMapping 现取(2026-09-26 task-16 默认键由 L 改 J,
                    // 写死文案会随下次改键再次过期)。
                    mcL.player.displayClientMessage(Component.literal(
                            "[TacLight] 未手持手电筒(taclight:flashlight):先拿在手上再按 "
                                    + KeyBindings.FLASHLIGHT_TOGGLE.getTranslatedKeyMessage().getString()), false);
                }
                TacLightMod.LOGGER.info("[TacLight] handheld toggle ignored (not holding flashlight)");
                continue;
            }
            // 2026-10-04:开关写进**手上那支电筒自己的 NBT**(旧版写全局 static)。
            // 未手持(仅霓虹调试可到这)时保留旧行为:翻全局偏好,让调试能点灯。
            net.minecraft.world.item.ItemStack heldFlash =
                    dev.taclight.item.FlashlightItem.heldStack(Minecraft.getInstance().player);
            boolean now;
            if (!heldFlash.isEmpty()) {
                now = dev.taclight.item.FlashlightItem.toggleOn(heldFlash);
                ClientLightState.setHandheld(now);
                // 2026-10-04 R55:**把意图上报服务端**,由服务端写进**它自己那份** ItemStack(权威)。
                // 旧实现只写客户端那份 ⇒ 服务端(与存档)从来没有 taclight_on ⇒
                // 槽同步/容器/维度/重进都会把它抹掉(用户实测"切回来自己关了";
                // R54 用 `/data get entity <p> SelectedItem` 服务端读数实测确认,BACKLOG §2.164/§2.165)。
                net.minecraft.client.player.LocalPlayer pl = Minecraft.getInstance().player;
                boolean off = pl == null || !pl.getMainHandItem()
                        .is(dev.taclight.registry.ModItems.FLASHLIGHT.get());
                dev.taclight.network.TacLightNetwork.sendToggleItemLight(off, now);
            } else {
                ClientLightState.toggle();
                now = ClientLightState.isOn();
            }
            // M5:开关上报纸服务端(SynchedEntityData 真源),其他玩家客户端可见
            dev.taclight.network.TacLightNetwork.sendSetLight(
                    ClientLightState.handheldEffective(), ClientLightState.gunLightEffective());
            TacLightMod.LOGGER.info("[TacLight] handheld flashlight {} (per-item tag={})",
                    now ? "ON" : "OFF", !heldFlash.isEmpty());
        }
        while (KeyBindings.GUNLIGHT_TOGGLE.consumeClick()) {
            // 2026-10-04 R95(⑦):开关写到**手上那支枪自己的 NBT**上(per-ItemStack,与 ② 手持电筒同构),
            // 并上报服务端 ⇒ 服务端写**它自己那份**主手栈(权威;换槽/重进世界都保留)。
            // 旧实现调 GunControl.toggleGunManual() 写全局静态布尔 ⇒ 两把枪共用一个开关(用户实测)。
            net.minecraft.client.player.LocalPlayer gp = Minecraft.getInstance().player;
            net.minecraft.world.item.ItemStack gun = gp == null
                    ? net.minecraft.world.item.ItemStack.EMPTY : gp.getMainHandItem();
            boolean next;
            if (!gun.isEmpty()) {
                next = dev.taclight.item.FlashlightItem.toggleOn(gun);
                ClientLightState.clearGunManual();
                ClientLightState.setGunLight(next);
                dev.taclight.network.TacLightNetwork.sendToggleItemLight(false, next);
            } else {
                next = dev.taclight.client.GunControl.toggleGunManual();   // 空手:保留旧全局偏好(仅调试路径)
            }
            // M5:上报有效灯(开关×持枪门);空手按 M 只存偏好不亮灯,切回枪即复
            boolean eff = ClientLightState.gunLightEffective();
            dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.handheldEffective(), eff);
            TacLightMod.LOGGER.info("[TacLight] gun light {} (key, per-gun tag, effective={})", next ? "ON" : "OFF", eff ? "ON" : "OFF");
        }
        while (KeyBindings.DEBUG_TOGGLE.consumeClick()) {
            ClientLightState.toggleDebug();
            boolean dbg = ClientLightState.debugMode();
            boolean autoOn = false;
            if (dbg && !ClientLightState.isOn()) {
                ClientLightState.forceHandheldOn();
                autoOn = true;
            }
            TacLightMod.LOGGER.info("[TacLight] debug neon mode {}{}", dbg ? "ON" : "OFF", autoOn ? " (auto-ON flashlight)" : "");
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[TacLight] 霓虹调试 "
                        + (dbg ? "ON —— 应看到绿色锥形光(=我们的SSBO通道)" : "OFF") + (autoOn ? "(手电筒已自动开启)" : "")), false);
            }
        }
        while (KeyBindings.DIAG_DUMP.consumeClick()) {
            dumpDiag();
        }
        while (KeyBindings.BENCH.consumeClick()) {
            startBench();
        }
    }

    /**
     * 持物门判据:主手或副手是否持 {@code taclight:flashlight}。
     * 按键、中继({@code !light})、tick 探针**共用同一判据**,不允许三处各写一套。
     */
    public static boolean holdingFlashlight(net.minecraft.world.entity.player.Player p) {
        if (p == null) return false;
        try {
            net.minecraft.world.item.Item item = dev.taclight.registry.ModItems.FLASHLIGHT.get();
            return p.getMainHandItem().is(item) || p.getOffhandItem().is(item);
        } catch (Throwable t) {
            return false; // 注册表未就绪等异常一律视为"未持有":宁可不亮,不误亮
        }
    }

    /** N 键:一行结构化诊断(调试自动化 grep 用;字段顺序=契约,tools/session 依赖;新增字段只追加尾部)。 */
    static void dumpDiag() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { TacLightMod.LOGGER.info("[TacLight] DIAG no-world"); return; }
        var cam = mc.gameRenderer.getMainCamera();
        var p = cam.getPosition();
        // 客户端命令树取证(计划文档坑位 9:P0 发现聊天框命令全被客户端预览拒绝,疑树为空)
        String cmdtree = "no-conn";
        if (mc.getConnection() != null) {
            var root = mc.getConnection().getCommands().getRoot();
            cmdtree = root.getChildren().size() + " taclight=" + (root.getChild("taclight") != null);
        }
        TacLightMod.LOGGER.info("[TacLight] DIAG {} | cam=({}) yRot={} xRot={} flash={} neon={} pack={}({}) | ssbo {} | cmdtree {}",
                dev.taclight.TacLightMod.VERSION,
                String.format("%.2f,%.2f,%.2f", p.x, p.y, p.z),
                String.format("%.1f", mc.player.getYRot()), String.format("%.1f", mc.player.getXRot()),
                ClientLightState.isOn(), ClientLightState.debugMode(),
                ShaderPackDiag.activeStatus(), ShaderPackDiag.activePackName(),
                dev.taclight.channel.LightBuffer.dumpLight0(),
                cmdtree);
        // 08-31 远程同步探针:读侧(syncReady/accessor id/各玩家标志)一行一玩家
        if (mc.level != null) {
            TacLightMod.LOGGER.info("[TacLight] DIAG-REMOTE syncReady={} accIds=(flash={},gun={})",
                    dev.taclight.sync.PlayerLightAccess.syncReady,
                    dev.taclight.sync.PlayerLightAccess.FLASHLIGHT.getId(),
                    dev.taclight.sync.PlayerLightAccess.GUNLIGHT.getId());
            // 里程碑①排障插桩(2026-09-02):白名单匹配的决定性证据
            try {
                var ak47 = new net.minecraft.resources.ResourceLocation("tacz", "ak47");
                var hk = new net.minecraft.resources.ResourceLocation("tacz", "hk416d");
                var att = new net.minecraft.resources.ResourceLocation("taclight", "gun_light");
                var prov = (com.tacz.guns.resource.ICommonResourceProvider)
                        com.tacz.guns.resource.CommonAssetsManager.get();
                var tagsAk = prov.getAllowAttachmentTags(ak47);
                var tagsHk = prov.getAllowAttachmentTags(hk);
                TacLightMod.LOGGER.info(
                        "[TacLight] DIAG-ALLOW ak47tags={} hk416dtags={} matchAk={} matchHk={}",
                        tagsAk == null ? "null" : tagsAk.size(),
                        tagsHk == null ? "null" : tagsHk.size(),
                        com.tacz.guns.util.AllowAttachmentTagMatcher.match(ak47, att),
                        com.tacz.guns.util.AllowAttachmentTagMatcher.match(hk, att));
            } catch (Throwable t) {
                TacLightMod.LOGGER.info("[TacLight] DIAG-ALLOW error: {}", t.toString());
            }
            for (var pl : mc.level.players()) {
                TacLightMod.LOGGER.info("[TacLight] DIAG-REMOTE player={} self={} flash={} gun={} pos=({})",
                        pl.getGameProfile().getName(), pl == mc.player,
                        dev.taclight.sync.PlayerLightAccess.flashlight(pl),
                        dev.taclight.sync.PlayerLightAccess.gunLight(pl),
                        String.format("%.1f,%.1f,%.1f", pl.getX(), pl.getY(), pl.getZ()));
                // 里程碑②:第三人称枪口捕获状态;09-02 屏外连续性:直接读上传器解析
                // 摘要(单一真源,含 state/weight/age/模式/原样读数;诊断只读不消费)。
                if (pl != mc.player && dev.taclight.sync.PlayerLightAccess.gunLight(pl)) {
                    var plLook = pl.getLookAngle();
                    String probe = dev.taclight.channel.ClientSpotlightUploader.TP_PROBE.get(pl.getId());
                    if (probe != null) {
                        TacLightMod.LOGGER.info("[TacLight] DIAG-TP player={} look=({}) {} tpCount={}",
                                pl.getGameProfile().getName(),
                                String.format("%.3f,%.3f,%.3f", plLook.x, plLook.y, plLook.z),
                                probe,
                                dev.taclight.client.MuzzlePoseCapture.tpCapturedCount());
                    } else {
                        TacLightMod.LOGGER.info("[TacLight] DIAG-TP player={} capture=never tpCount={}",
                                pl.getGameProfile().getName(),
                                dev.taclight.client.MuzzlePoseCapture.tpCapturedCount());
                    }
                }
            }
        }
    }

    // ---- B 键:3 秒帧率基准(帧间 nanoTime 差;采样在渲染线程,开销为零) ----
    private static boolean benchActive;
    private static long benchStartNanos;
    private static long benchLastFrameNanos;
    private static final java.util.List<Long> BENCH_SAMPLES = new java.util.ArrayList<>();
    private static final long BENCH_DURATION_NANOS = 3_000_000_000L;

    static void startBench() {
        BENCH_SAMPLES.clear();
        benchStartNanos = System.nanoTime();
        benchLastFrameNanos = 0;
        benchActive = true;
        TacLightMod.LOGGER.info("[TacLight] BENCH start (3s)");
    }

    private static void benchTickFrame() {
        if (!benchActive) return;
        long now = System.nanoTime();
        if (benchLastFrameNanos > 0) BENCH_SAMPLES.add(now - benchLastFrameNanos);
        benchLastFrameNanos = now;
        if (now - benchStartNanos < BENCH_DURATION_NANOS) return;
        benchActive = false;
        if (BENCH_SAMPLES.isEmpty()) { TacLightMod.LOGGER.info("[TacLight] BENCH no-samples"); return; }
        double[] sorted = new double[BENCH_SAMPLES.size()];
        double total = 0;
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = BENCH_SAMPLES.get(i) / 1e9;
            total += sorted[i];
        }
        java.util.Arrays.sort(sorted); // 升序:尾部=最慢帧
        double avg = sorted.length / total;
        double p1Low = percentileFps(sorted, 0.01);
        double min = 1.0 / sorted[sorted.length - 1];
        TacLightMod.LOGGER.info("[TacLight] BENCH frames={} avgFPS={} onePctLow={} minFPS={}",
                sorted.length, String.format("%.1f", avg), String.format("%.1f", p1Low), String.format("%.1f", min));
    }

    /** worstFrac 比例的最慢帧的调和平均(1% low 惯例)。sorted 升序帧时长。 */
    private static double percentileFps(double[] sortedAsc, double worstFrac) {
        int n = Math.max(1, (int) Math.ceil(sortedAsc.length * worstFrac));
        double sum = 0;
        for (int i = 0; i < n; i++) sum += sortedAsc[sortedAsc.length - 1 - i];
        return n / sum;
    }


    /** 每 5 秒检查活动光影包;状态变化时聊天+日志提示(选错包是 90% 的问题)。 */
    private static void checkShaderPackDiag(Minecraft mc) {
        if (++diagTick % 100 != 0) return;
        ShaderPackDiagLogic.Status st = ShaderPackDiag.activeStatus();
        if (st == lastDiagStatus) return;
        lastDiagStatus = st;
        String pack = ShaderPackDiag.activePackName();
        // 判定与文案同源(纯类):2026-09-25 之前 INTEROP_INJECTED 这类状态不存在,
        // 注入成功也被判成 ORIGINAL_PACK ⇒ 聊天栏 ✘ "无 TacLight 注入"(用户实测 bug)。
        String msg = ShaderPackDiagLogic.message(st, pack, DERIVED_PACK);
        TacLightMod.LOGGER.info("[TacLight] diag: {}", msg);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
    }

    /** Number of screenshot encodes still pending; scheduling pressure is recorded as D rows. */
    private static final java.util.concurrent.atomic.AtomicInteger MCAP_PENDING =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Recorder run root is initialized once per client process. */
    static {
        dev.taclight.channel.FrameRecorder.setBaseDir(
                net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toFile());
    }

    /** Frame-end screenshot execution. Reservation writes P synchronously; callback writes S/F by token. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            dev.taclight.channel.MotionCapture.shutdown("world-unload");
            return;
        }
        if (!dev.taclight.channel.MotionCapture.armed()) return;
        long nano = System.nanoTime();
        long renderFrame = dev.taclight.channel.ClientSpotlightUploader.currentRenderFrame();
        dev.taclight.channel.FrameRecorder.ShotToken token =
                dev.taclight.channel.MotionCapture.reserveShot(nano, renderFrame);
        if (token == null) return;
        MCAP_PENDING.incrementAndGet();
        try {
            net.minecraft.client.Screenshot.grab(token.sessionDir(), token.filename(), mc.getMainRenderTarget(), component -> {
                try {
                    boolean success = token.expectedFile().isFile();
                    dev.taclight.channel.FrameRecorder.completeShot(token, success,
                            success ? "ok" : "expected-file-missing");
                    if (success) TacLightMod.LOGGER.info("[TacLight] REC shot {}", token.expectedFile());
                    else TacLightMod.LOGGER.error("[TacLight] REC shot missing {} callback={}",
                            token.expectedFile(), component.getString());
                } finally {
                    MCAP_PENDING.decrementAndGet();
                }
            });
        } catch (Throwable t) {
            MCAP_PENDING.decrementAndGet();
            dev.taclight.channel.FrameRecorder.completeShot(token, false,
                    "grab-throw:" + t.getClass().getSimpleName());
            TacLightMod.LOGGER.error("[TacLight] REC grab failed for {}", token.expectedFile(), t);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        // SSBO 数据必须按"渲染帧"刷新(v0.9.0 热修:原挂在 ClientTick 仅 20Hz,
        // 转视角时灯位滞后相机最多 50ms,肉眼可见拖拽)。AFTER_LEVEL 时相机已是
        // 本帧终值,且先于 Iris composite 执行;社区同型案例共识 = 每帧更新数据。
        dev.taclight.channel.ClientSpotlightUploader.onFrame();
        dev.taclight.channel.LightBuffer.rebindBase();
        dev.taclight.channel.PerfStats.tickFrame();
        benchTickFrame();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // task-32 v2:按键存档值**兜底应用**(首个客户端 tick 一次,once-only/幂等)——
        // 不依赖"MOD 总线注册事件是否被投递";Options.load() 在 Minecraft 构造期、早于首 tick
        // ⇒ 应用必然发生在加载之后。放在 world-null 提前返回**之前**,保证没进世界也会执行。
        ModBus.applySavedKeysOnceAtFirstTick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            // 退出世界后可信关闭录制并停摆 SSBO；shutdown 幂等，可被多个 world-null hook 调用。
            dev.taclight.channel.MotionCapture.shutdown("world-unload");
            dev.taclight.channel.LightBuffer.upload(java.util.List.of());
            return;
        }

        if (!tuneRestored) {
            tuneRestored = true;
            // /taclight tune 写回的 toml 在重启后回填覆盖层(新增键 atten/knee/scat/beamcap/
            // voxel 的消费者只认覆盖层;既有键 bright/dist/beam/cone 直读 config,恢复层不碰)。
            try {
                String r = dev.taclight.tune.TunePersist.restore(
                        dev.taclight.tune.TunePersist.forgeSink(), TuneClientGate.GATE,
                        (knob, arg) -> switch (knob) {
                            case "atten" -> dev.taclight.channel.LightTuneOverride.configureAtten(arg);
                            case "knee" -> dev.taclight.channel.LightTuneOverride.configureKnee(arg);
                            case "scat" -> dev.taclight.channel.LightTuneOverride.configureScat(arg);
                            case "beamcap" -> dev.taclight.channel.LightTuneOverride.configureBeamcap(arg);
                            default -> "bad arg " + arg + " (restore only handles persisted knobs)";
                        });
                TacLightMod.LOGGER.info("[TacLight] TUNE {}", r);
            } catch (Throwable t) {
                TacLightMod.LOGGER.warn("[TacLight] TUNE restore failed: {}", t.toString());
            }
        }

        if (!diagAutoApplied && "1".equals(System.getenv("TACLIGHT_DIAG"))) {
            diagAutoApplied = true;
            ClientLightState.setDebug(true);
            TacLightMod.LOGGER.info("[TacLight] DIAG auto: debug neon ON at login");
        }
        // 手持灯持物门 + **每支手电筒自己的开关**(2026-10-04 用户定案):
        //   开关状态存在**物品自己的 NBT**里(FlashlightItem.TAG_ON),不在玩家身上。
        //   每 tick 从"手上那支电筒"读出来镜像进 ClientLightState ——
        //   渲染 / HUD / 上传器 / Iris 光源都继续读 ClientLightState,口径不变。
        //   ★ 取代旧设计的两条:① 全局 static 开关;② "离手自动关"(autoClear)把开关清掉
        //   ⇒ 用户实测"切走再切回来还得再开一次"。现在切走只是不亮(持物门),
        //   **开关偏好留在物品上**,切回来即复。
        //   霓虹调试期间不镜像:调试要能自己把灯点着(forceHandheldOn),否则手里没电筒就灭。
        net.minecraft.world.item.ItemStack heldFlash =
                dev.taclight.item.FlashlightItem.heldStack(mc.player);
        boolean holdingNow = !heldFlash.isEmpty();
        ClientLightState.setHandheldProbe(holdingNow);
        if (!ClientLightState.debugMode()) {
            ClientLightState.setHandheld(holdingNow
                    && dev.taclight.item.FlashlightItem.isOn(heldFlash));
        }

        GunLaserReader.Status status = GunLaserReader.Status.NONE;
        String detail = "no-tacz";
        if (TaczCompat.present()) {
            try {
                var probe = TaczClientLightProbe.probe(mc.player);
                if (probe != null) {
                    status = GunLaserReader.classify(probe.gunId(), probe.laserAttachmentId());
                    detail = probe.gunId() + " | " + probe.laserAttachmentId();
                } else {
                    detail = "no-gun";
                }
            } catch (Throwable t) {
                detail = "probe-error:" + t.getClass().getSimpleName();
            }
        }

        boolean probeOn = (status == GunLaserReader.Status.OUR_LIGHT);
        ClientLightState.setGunProbe(probeOn);
        // 2026-10-04 R95(⑦ 用户报"两把枪的灯共用一个开关"):枪灯开关的**真源改为手上那支枪自己的 NBT**
        // (FlashlightItem.TAG_ON;缺标签=开,见 FlashlightSwitch.resolveTag),不再写全局静态布尔。
        // 这样两把枪各记各的:切到 A 亮/灭只看 A,切到 B 只看 B。
        ClientLightState.setGunLight(probeOn
                && dev.taclight.item.FlashlightItem.isOn(mc.player.getMainHandItem()));
        // Gun state remains tick-driven above; SSBO collection/upload occurs only in onRenderLevel.
        probeIfEnabled();
        checkShaderPackDiag(mc);
        if ("1".equals(System.getenv("TACLIGHT_PROBE")) && ++boardTick % 60 == 0) {
            TacLightMod.LOGGER.info("[TacLight] BOARD pack={} debug={} binding7={} reserved=0x{}",
                    dev.taclight.client.ShaderPackDiag.activeStatus(),
                    ClientLightState.debugMode(),
                    dev.taclight.channel.LightBuffer.binding7(),
                    Integer.toHexString(dev.taclight.channel.LightBuffer.readReserved()));
        }
        if (status != lastGunStatus) {
            TacLightMod.LOGGER.info("[TacLight] gun light {} ({})",
                    status == GunLaserReader.Status.OUR_LIGHT ? "ON" : "OFF", detail);
            lastGunStatus = status;
        }
        // M5:有效灯变化即同步服务端真源(含手动期切走/切回:偏好不变但有效翻转)。
        // 2026-10-04:手持灯也纳入判变化 —— 开关挂在物品上后,换手/换电筒都会让有效值翻转,
        // 只盯枪灯会在"切到另一支电筒"时漏报(服务端实体数据陈旧 → 其他玩家看不到变化)。
        boolean effNow = ClientLightState.gunLightEffective();
        boolean handheldNow = ClientLightState.handheldEffective();
        if (effNow != lastSentEffective || handheldNow != lastSentHandheld) {
            lastSentEffective = effNow;
            lastSentHandheld = handheldNow;
            dev.taclight.network.TacLightNetwork.sendSetLight(handheldNow, effNow);
        }
    }
}

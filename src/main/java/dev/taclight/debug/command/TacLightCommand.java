package dev.taclight.debug.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import dev.taclight.TacLightMod;
import dev.taclight.registry.ModItems;
import dev.taclight.tacz.TaczCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * /taclight 调试命令组:
 *  kit —— 一键发放验收套件:手电筒 + HK416D(预装战术枪灯,TaCZ 官方 API;无 TaCZ 时仅给手电筒)。
 *  cam here / save <name> / goto <name> —— 确定性机位(注册表 run/config/taclight-cams.json)。
 *  scene <preset> —— 场景区程序化布景(计划 = ScenePresets 纯数据,执行 = SceneExecutor)。
 * 全组 hasPermission(0):调试工具,SP 场景使用;结果同时入日志(自动化 grep 依赖)。
 * snap —— 一键调试快照(2026-09-19 最小闭环,与 F9/!snap 同一入口;专用服回仅单人)。
 * tune —— 八旋钮正式调参(2026-09-19 由文件命令中继晋升,发布包可用):
 * /taclight tune &lt;bright/dist/atten/knee/beam/scat/beamcap/cone/voxel&gt; [&lt;值&gt;|status|off],
 * 无参=九旋钮全状态。覆盖层即时生效 + 写回 config/taclight-client.toml 重启保留;
 * 专用服无本地调参目标,直接回显"仅单人/客户端生效"不假成功。
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID)
public class TacLightCommand {
    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        // 诊断:root 子节点数(含原版命令)——服务端分发器是否有货的决定性证据
        TacLightMod.LOGGER.info("[TacLight] CMDS RegisterCommandsEvent firing, root children={} (vanilla+mods 已入树)",
                event.getDispatcher().getRoot().getChildren().size());
        event.getDispatcher().register(Commands.literal("taclight")
                .then(Commands.literal("kit")
                        .requires(s -> s.hasPermission(0))
                        .executes(ctx -> giveKit(ctx.getSource()))
                        // 里程碑①(2026-09-02):可选枪械 id(默认包 index 名,如 ak47)——
                        // 白名单实测用:任意枪发下来即预装 gun_light。
                        .then(Commands.argument("gunId", StringArgumentType.word())
                                .executes(ctx -> giveKit(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "gunId")))))
                .then(Commands.literal("cam")
                        .requires(s -> s.hasPermission(0))
                        .then(Commands.literal("here")
                                .executes(ctx -> camHere(ctx.getSource())))
                        .then(Commands.literal("save")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(ctx -> camSave(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(ctx -> camGoto(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("scene")
                        .requires(s -> s.hasPermission(0))
                        .executes(ctx -> sceneList(ctx.getSource()))
                        .then(Commands.literal("verify")
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .executes(ctx -> sceneVerify(ctx.getSource(), StringArgumentType.getString(ctx, "preset")))))
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .executes(ctx -> sceneBuild(ctx.getSource(), StringArgumentType.getString(ctx, "preset")))))
                .then(Commands.literal("light")
                        .requires(s -> s.hasPermission(0))
                        .executes(ctx -> lightStatus(ctx.getSource()))
                        .then(Commands.literal("status")
                                .executes(ctx -> lightStatus(ctx.getSource())))
                        .then(Commands.argument("state", StringArgumentType.word())
                                .executes(ctx -> lightSet(ctx.getSource(), StringArgumentType.getString(ctx, "state"), null))
                                .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                        .executes(ctx -> lightSet(ctx.getSource(), StringArgumentType.getString(ctx, "state"),
                                                net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("snap")
                        .requires(s -> s.hasPermission(0))
                        .executes(ctx -> snapRun(ctx.getSource())))
                // tune(2026-09-19):八旋钮正式入口。name/value 用 string() 而非 word()
                // —— 数值 "0.35"/"-0.1" 含 '.',word() 拒收(同 cam name 含 '@' 的教训)。
                .then(Commands.literal("tune")
                        .requires(s -> s.hasPermission(0))
                        .executes(ctx -> tuneAll(ctx.getSource()))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> tuneRun(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), null))
                                .then(Commands.argument("value", StringArgumentType.string())
                                        .executes(ctx -> tuneRun(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "value")))))));
        // 注:cam name 用 string() 而非 word() —— 内置机位名含 '@'(bloom@inside),
        // word() 只收 [A-Za-z0-9_+-],实机 2026-08-29 "Incorrect argument" 实锤。
    }

    private static int camHere(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        String pose = String.format("x=%.2f y=%.2f z=%.2f yaw=%.1f pitch=%.1f",
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("[TacLight] cam here: " + pose
                + " (保存: /taclight cam save <name>)"), false);
        TacLightMod.LOGGER.info("[TacLight] CAM here {}", pose);
        return 1;
    }

    private static int camSave(CommandSourceStack source, String name) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        CamStore.Cam cam = new CamStore.Cam(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
        cam.dim = player.level().dimension().location().toString();
        CamStore.upsert(name, cam);
        source.sendSuccess(() -> Component.literal("[TacLight] cam saved '" + name + "'"), false);
        TacLightMod.LOGGER.info("[TacLight] CAM save {} x={} y={} z={} yaw={} pitch={} dim={}",
                name, String.format("%.2f", cam.x), String.format("%.2f", cam.y), String.format("%.2f", cam.z),
                String.format("%.1f", cam.yaw), String.format("%.1f", cam.pitch), cam.dim);
        return 1;
    }

    private static int camGoto(CommandSourceStack source, String name) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        CamStore.Cam cam = CamStore.load().get(name);
        if (cam == null) {
            source.sendFailure(Component.literal("[TacLight] cam '" + name + "' 不存在(用 cam here 看当前,save 存)"));
            TacLightMod.LOGGER.info("[TacLight] CAM goto {} MISS", name);
            return 0;
        }
        // 跨维度:机位带维度记录(QuickPlay 会从上次退出维度进世界 —— camGoto 传当前维度
        // 会把玩家传到同名异界坐标,实机 2026-08-29 踩坑)
        var key = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation(cam.dim));
        var level = player.server.getLevel(key);
        if (level == null) {
            source.sendFailure(Component.literal("[TacLight] cam '" + name + "' 维度无效: " + cam.dim));
            TacLightMod.LOGGER.info("[TacLight] CAM goto {} BAD-DIM {}", name, cam.dim);
            return 0;
        }
        player.teleportTo(level, cam.x, cam.y, cam.z, (float) cam.yaw, (float) cam.pitch);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.fallDistance = 0.0f;
        source.sendSuccess(() -> Component.literal("[TacLight] cam -> '" + name + "'"), false);
        TacLightMod.LOGGER.info("[TacLight] CAM goto {} x={} y={} z={} yaw={} pitch={} dim={}",
                name, String.format("%.2f", cam.x), String.format("%.2f", cam.y), String.format("%.2f", cam.z),
                String.format("%.1f", cam.yaw), String.format("%.1f", cam.pitch), cam.dim);
        return 1;
    }

    private static int sceneList(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[TacLight] scene presets: "
                + String.join(", ", ScenePresets.names())), false);
        TacLightMod.LOGGER.info("[TacLight] SCENE list {}", ScenePresets.names());
        return 1;
    }

    private static int sceneBuild(CommandSourceStack source, String preset) {
        return SceneExecutor.apply(source, preset);
    }

    private static int sceneVerify(CommandSourceStack source, String preset) {
        return SceneExecutor.verify(source, preset);
    }

    // ---- M5 多人灯状态:状态真源 = Player SynchedEntityData(服务端写,原版同步) ----

    private static int lightStatus(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        String msg = "[TacLight] light status: handheld=" + dev.taclight.sync.PlayerLightAccess.flashlight(player)
                + " gun=" + dev.taclight.sync.PlayerLightAccess.gunLight(player)
                + " (切换: /taclight light <on|off|toggle> [player])";
        source.sendSuccess(() -> Component.literal(msg), false);
        TacLightMod.LOGGER.info("[TacLight] LIGHT status {} handheld={} gun={}",
                player.getGameProfile().getName(),
                dev.taclight.sync.PlayerLightAccess.flashlight(player),
                dev.taclight.sync.PlayerLightAccess.gunLight(player));
        return 1;
    }

    /** on|off|toggle [player];走服务端写侧 + S2C 回发(本人客户端跟随真源)。 */
    private static int lightSet(CommandSourceStack source, String state, net.minecraft.server.level.ServerPlayer target)
            throws CommandSyntaxException {
        var player = target != null ? target : source.getPlayerOrException();
        boolean cur = dev.taclight.sync.PlayerLightAccess.flashlight(player);
        boolean next;
        switch (state.toLowerCase()) {
            case "on" -> next = true;
            case "off" -> next = false;
            case "toggle" -> next = !cur;
            default -> {
                source.sendFailure(Component.literal("[TacLight] light state 只支持 on|off|toggle,收到 '" + state + "'"));
                return 0;
            }
        }
        dev.taclight.network.TacLightNetwork.serverApply(player, next,
                dev.taclight.sync.PlayerLightAccess.gunLight(player),
                "cmd:" + state + (target != null ? "@target" : "@self"));
        source.sendSuccess(() -> Component.literal("[TacLight] light " + next + " -> "
                + player.getGameProfile().getName()), false);
        return 1;
    }

    // ---- snap:一键调试快照(2026-09-19 最小闭环,与 F9/!snap 同一 saveSnapshot 入口) ----

    /**
     * MP 守卫(snap):专用服无客户端可拍(截图/pose/覆盖层全在客户端;且 client 类在
     * 专用服加载即炸,故守卫必须在任何 client 引用之前,同 tuneMpGuard 的类加载安全)。
     * 集成服(SP/LAN 主机同 JVM,gameDir 与客户端同根):放行。
     */
    private static boolean snapMpGuard(CommandSourceStack source) {
        var server = source.getServer();
        if (server != null && !server.isDedicatedServer()) return false;
        String msg = "[TacLight] snap 仅单人/客户端生效:专用服务器无截图与客户端状态目标,本次未执行。"
                + "联机客机请在各自客户端按 F9 或写 !snap 文件命令。";
        source.sendFailure(Component.literal(msg));
        TacLightMod.LOGGER.info("[TacLight] SNAP mp-guard reject (dedicated or no-server)");
        return true;
    }

    /** /taclight snap —— 调同一 saveSnapshot(命令线程;截图若上下文不在位由其 try/catch 接住记 outcome)。 */
    private static int snapRun(CommandSourceStack source) throws CommandSyntaxException {
        if (snapMpGuard(source)) return 0;
        source.getPlayerOrException(); // 单人玩家存在性校验(与 light 分支同规)
        java.nio.file.Path dir = dev.taclight.client.DebugSnapshotter.saveSnapshot("cmd");
        if (dir == null) {
            source.sendFailure(Component.literal("[TacLight] snap 失败(见日志)"));
            TacLightMod.LOGGER.warn("[TacLight] SNAP cmd FAILED");
            return 0;
        }
        String msg = "[TacLight] snap -> " + dir;
        source.sendSuccess(() -> Component.literal(msg), false);
        TacLightMod.LOGGER.info("[TacLight] SNAP cmd {}", dir);
        return 1;
    }

    // ---- tune:八旋钮正式调参(2026-09-19,发布包可用;中继 !bright/... 保持内存对照) ----

    /** 生产覆盖层入口(与 TuneService 契约的假入口恒等;voxel 不走这里,走客户端门)。 */
    private static final dev.taclight.tune.TunePersist.KnobApplier TUNE_APPLIER = (knob, arg) -> switch (knob) {
        case "bright" -> dev.taclight.channel.LightTuneOverride.configureBright(arg);
        case "dist" -> dev.taclight.channel.LightTuneOverride.configureDist(arg);
        case "atten" -> dev.taclight.channel.LightTuneOverride.configureAtten(arg);
        case "knee" -> dev.taclight.channel.LightTuneOverride.configureKnee(arg);
        case "beam" -> dev.taclight.channel.LightTuneOverride.configureBeam(arg);
        case "scat" -> dev.taclight.channel.LightTuneOverride.configureScat(arg);
        case "beamcap" -> dev.taclight.channel.LightTuneOverride.configureBeamcap(arg);
        case "cone" -> dev.taclight.channel.LightTuneOverride.configureCone(arg);
        default -> "bad arg " + arg + " (unknown knob " + knob + ")";
    };

    /**
     * MP 守卫:专用服无本地调参目标(调参只改本机覆盖层 + 本机 toml),直接回显
     * "仅单人/客户端生效"并返回 0,<b>不碰</b>覆盖层/config/体素(后者含 client 引用,
     * 专用服加载即炸,故守卫必须在任何调参引用之前)。
     * 集成服(SP/LAN 主机同 JVM):直接调覆盖层 + VoxelGrid 即时生效。
     * 注意:LAN 远端客机的命令发到主机执行,生效的是主机本机——回显明确作用域,不静默假成功。
     */
    private static boolean tuneMpGuard(CommandSourceStack source) {
        var server = source.getServer();
        if (server != null && !server.isDedicatedServer()) return false;
        String msg = "[TacLight] tune 仅单人/客户端生效:专用服务器无本地调参目标"
                + "(调参只改本机覆盖层+本机 config/taclight-client.toml),本次未应用。"
                + "联机客机请在各自单人/客户端执行;LAN 远端发到主机只改主机本机。";
        source.sendFailure(Component.literal(msg));
        TacLightMod.LOGGER.info("[TacLight] TUNE mp-guard reject (dedicated or no-server)");
        return true;
    }

    /** /taclight tune —— 九旋钮全状态 + 用法(只读)。 */
    private static int tuneAll(CommandSourceStack source) {
        if (tuneMpGuard(source)) return 0;
        // 专用服已提前返回,到这里必是集成服:客户端门类加载安全。
        dev.taclight.debug.tune.TuneService.Result r = dev.taclight.debug.tune.TuneService.statusAll(
                dev.taclight.client.TuneClientGate.GATE, TUNE_APPLIER);
        String msg = r.message() + "\n(专用服/联机客机不生效:仅单人/主机本机)";
        source.sendSuccess(() -> Component.literal(msg), false);
        TacLightMod.LOGGER.info("[TacLight] TUNE status-all");
        return 1;
    }

    /** /taclight tune &lt;name&gt; [&lt;value&gt;|status|off]。 */
    private static int tuneRun(CommandSourceStack source, String name, String value) {
        if (tuneMpGuard(source)) return 0;
        dev.taclight.debug.tune.TuneService.Result r = dev.taclight.debug.tune.TuneService.tune(
                name, value,
                dev.taclight.tune.TunePersist.forgeSink(),
                dev.taclight.client.TuneClientGate.GATE, TUNE_APPLIER);
        if (r.ok()) {
            source.sendSuccess(() -> Component.literal(r.message()), false);
            TacLightMod.LOGGER.info("[TacLight] TUNE {}", singleLine(r.message()));
            return 1;
        }
        source.sendFailure(Component.literal(r.message()));
        TacLightMod.LOGGER.warn("[TacLight] TUNE reject {}", singleLine(r.message()));
        return 0;
    }

    /** 多行状态压一行记日志(自动化 grep 单行友好)。 */
    private static String singleLine(String s) {
        return s.replace('\n', '|');
    }

    private static int giveKit(CommandSourceStack source) throws CommandSyntaxException {
        return giveKit(source, "hk416d");
    }

    /** kit 发放:手电 + 指定枪械 id(默认 hk416d)预装 gun_light;白名单实测入口。 */
    private static int giveKit(CommandSourceStack source, String gunId) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        player.getInventory().add(new ItemStack(ModItems.FLASHLIGHT.get()));
        int extra = 0;
        if (TaczCompat.present()) {
            try {
            if (tryAdd(player, "tacz", "modern_kinetic_gun", gun -> {
                IGun igun = IGun.getIGunOrNull(gun);
                igun.setGunId(gun, new ResourceLocation("tacz", gunId));
                // 2026-09-02:枪灯经官方 API 预装上枪。此前 kit 发散件,需进改装 UI 手动
                // 安装——GLFW UI 后台鼠标注入无效(坑41),自动化枪姿验证一直被阻塞。
                // 附件类型 laser 由 index/attachments/gun_light.json 声明,按类型入 LASER 槽。
                ItemStack att = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("tacz", "attachment")));
                IAttachment.getIAttachmentOrNull(att).setAttachmentId(att, new ResourceLocation("taclight", "gun_light"));
                // 安装时点决定性探针(2026-09-02 installAttachment 静默失败定位):全部在
                // 服务端命令线程上测。allowed=白名单判定;slot=装后 LASER 槽读回
                // (EMPTY=静默拒装);tags=服务端数据源内该枪 allow 条目数;src=数据源类别。
                boolean allowed = igun.allowAttachment(gun, att);
                igun.installAttachment(gun, att);
                ItemStack slot = igun.getAttachment(gun,
                        com.tacz.guns.api.item.attachment.AttachmentType.LASER);
                String slotId = slot.isEmpty() ? "EMPTY"
                        : String.valueOf(IAttachment.getIAttachmentOrNull(slot).getAttachmentId(slot));
                String tags;
                try {
                    var prov = (com.tacz.guns.resource.ICommonResourceProvider)
                            com.tacz.guns.resource.CommonAssetsManager.get();
                    var t = prov.getAllowAttachmentTags(new ResourceLocation("tacz", gunId));
                    tags = prov.getClass().getSimpleName() + ":" + (t == null ? "null" : t.size());
                } catch (Throwable t2) {
                    tags = "ERR:" + t2.getClass().getSimpleName();
                }
                TacLightMod.LOGGER.info("[TacLight] KIT-INSTALL thread={} gunId={} allowed={} slot={} tags({})",
                        Thread.currentThread().getName(), gunId, allowed, slotId, tags);
            })) {
                extra++;
            }
            } catch (Throwable t) {
                TacLightMod.LOGGER.warn("[TacLight] kit TaCZ part failed: {}", t.toString());
            }
        }
        String message = "[TacLight] kit given: flashlight" + (extra > 0 ? " + " + gunId + "(预装 gun_light)" : "");
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static boolean tryAdd(net.minecraft.world.entity.player.Player player, String ns, String path,
                                  java.util.function.Consumer<ItemStack> configure) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ns, path));
        if (item == null) {
            return false;
        }
        ItemStack stack = new ItemStack(item);
        configure.accept(stack);
        player.getInventory().add(stack);
        return true;
    }

    private TacLightCommand() {}
}

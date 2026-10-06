package dev.taclight.debug.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
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
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code /taclight} 的 <b>dev-only</b> 子命令挂载器 —— kit / cam / scene / light。
 *
 * <p>2026-10-06 发布面回归修复时从 {@code TacLightCommand}(当时整族都在本包)拆出来的:
 * 发布面的 {@code tune} 搬到 {@code dev.taclight.command.TacLightCommand},dev-only 的这几支
 * 留在 {@code dev.taclight/debug/**}(随 {@code exclude 'dev/taclight/debug/**'} 离开发布件)。
 * 两者通过 {@code TacLightCommand.attachDevChildren} 的<b>反射</b>挂到同一个 root 上 ——
 * 发布件里本类不存在 ⇒ 反射失败 ⇒ 只留一行日志,命令树少掉这几支而不是崩。</p>
 *
 * <ul>
 *   <li>kit —— 一键发放验收套件:手电筒 + HK416D(预装战术枪灯,TaCZ 官方 API;无 TaCZ 时仅给手电筒)。</li>
 *   <li>cam here / save &lt;name&gt; / goto &lt;name&gt; —— 确定性机位(注册表 run/config/taclight-cams.json)。</li>
 *   <li>scene &lt;preset&gt; —— 场景区程序化布景(计划 = ScenePresets 纯数据,执行 = SceneExecutor)。</li>
 *   <li>light &lt;on|off|toggle&gt; [player] —— M5 多人灯状态(状态真源 = Player SynchedEntityData)。</li>
 * </ul>
 *
 * <p>全组 hasPermission(0):调试工具,SP 场景使用;结果同时入日志(自动化 grep 依赖)。</p>
 */
public final class DebugCommandChildren {
    /** 把 dev-only 子命令挂到已注册的 {@code /taclight} root 上(由发布面类反射调用)。 */
    public static void attach(LiteralCommandNode<CommandSourceStack> root) {
        root.addChild(Commands.literal("kit")
                .requires(s -> s.hasPermission(0))
                .executes(ctx -> giveKit(ctx.getSource()))
                // 里程碑①(2026-09-02):可选枪械 id(默认包 index 名,如 ak47)——
                // 白名单实测用:任意枪发下来即预装 gun_light。
                .then(Commands.argument("gunId", StringArgumentType.word())
                        .executes(ctx -> giveKit(ctx.getSource(),
                                StringArgumentType.getString(ctx, "gunId"))))
                .build());
        root.addChild(Commands.literal("cam")
                .requires(s -> s.hasPermission(0))
                .then(Commands.literal("here")
                        .executes(ctx -> camHere(ctx.getSource())))
                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> camSave(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("goto")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> camGoto(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .build());
        root.addChild(Commands.literal("scene")
                .requires(s -> s.hasPermission(0))
                .executes(ctx -> sceneList(ctx.getSource()))
                .then(Commands.literal("verify")
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .executes(ctx -> sceneVerify(ctx.getSource(), StringArgumentType.getString(ctx, "preset")))))
                .then(Commands.argument("preset", StringArgumentType.word())
                        .executes(ctx -> sceneBuild(ctx.getSource(), StringArgumentType.getString(ctx, "preset"))))
                .build());
        root.addChild(Commands.literal("light")
                .requires(s -> s.hasPermission(0))
                .executes(ctx -> lightStatus(ctx.getSource()))
                .then(Commands.literal("status")
                        .executes(ctx -> lightStatus(ctx.getSource())))
                .then(Commands.argument("state", StringArgumentType.word())
                        .executes(ctx -> lightSet(ctx.getSource(), StringArgumentType.getString(ctx, "state"), null))
                        .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(ctx -> lightSet(ctx.getSource(), StringArgumentType.getString(ctx, "state"),
                                        net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player")))))
                .build());
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

    private DebugCommandChildren() {}
}

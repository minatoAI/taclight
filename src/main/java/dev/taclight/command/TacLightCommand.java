package dev.taclight.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.taclight.TacLightMod;
import dev.taclight.tune.TunePersist;
import dev.taclight.tune.TuneService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /taclight} 命令树 —— <b>发布面</b>部分(2026-10-06 发布面回归修复)。
 *
 * <p>本类只注册玩家真正要用的 {@code tune} 分支:
 * {@code /taclight tune <bright/dist/atten/knee/beam/scat/beamcap/cone/voxel> [<值>|status|off]},
 * 无参 = 分层概览(help);看全部当前值用 {@code tune status}。覆盖层即时生效 + 写回
 * {@code config/taclight-client.toml} 重启保留;
 * 专用服无本地调参目标,直接回显"仅单人/客户端生效"不假成功。</p>
 *
 * <p><b>为什么单独存在</b>(这条是本类的存在理由,不是装饰):2026-09-29 R12 B1 把整族
 * {@code /taclight} 命令按"调试命令族"搬进 {@code dev.taclight.debug.command/**},于是随
 * {@code build.gradle} 的 {@code exclude 'dev/taclight/debug/**'} 一起离开了发布件 ——
 * 连 2026-09-19 明确定案为<b>发布面功能</b>的 {@code tune} 也一起没了
 * ({@code docs/tune-正式调参命令-2026-09-19.md} 首段写的是"发布包可用";用户实测
 * 发布件里敲 {@code /taclight tune} 无此命令才发现)。教训:按<b>目录</b>剔除调试面时,
 * "目录里混着发布面功能"不会被任何判据拦住 ⇒ 现在按<b>功能面</b>分文件:
 * 发布面住本包({@code dev.taclight.command/**}),dev-only 子命令住 debug 包,并由
 * {@code InteropPackagingContract} + {@code HudCommandContract} 的构件正控钉死。</p>
 *
 * <p>dev-only 子命令(kit/cam/scene/light)由 {@link #DEV_CHILDREN} 提供者
 * <b>反射挂载</b>到同一个 root 上:发布件里没有那个类 ⇒ 反射失败并记一行日志
 * (与 {@code TacLightMod.tryRegisterGunpack} 同型的软挂载);dev 构建里两个都在,
 * 命令树与 R12 之前逐字相同。</p>
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID)
public final class TacLightCommand {
    /** 可观测标记:{@code SubscriberScopeContract} 钉死(订阅站点必须有一条"它确实跑过"的日志)。 */
    public static final String MARKER = "CMDS RegisterCommandsEvent firing";

    /** dev-only 子命令提供者(发布件不含 ⇒ 反射抛 ClassNotFoundException ⇒ 跳过)。 */
    public static final String DEV_CHILDREN = "dev.taclight.debug.command.DebugCommandChildren";

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        LiteralCommandNode<CommandSourceStack> root = event.getDispatcher().register(buildRoot());
        // 诊断:root 子节点数(含原版命令)—— 服务端分发器是否有货的决定性证据。
        // 发布件里 root children 应 ≥2(taclight + 原版);dev 里还含挂上来的 kit/cam/scene/light。
        TacLightMod.LOGGER.info("[TacLight] {}, root children={} (vanilla+mods 已入树)",
                MARKER, event.getDispatcher().getRoot().getChildren().size());
        attachDevChildren(root);
    }

    /**
     * 发布面命令树(纯构建,零副作用)—— 契约可直接建树断言 {@code tune} 在树里,
     * 不必启动游戏,也不必加载 dev-only 类。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
        // tune(2026-09-19):十旋钮正式入口。name/value 用 string() 而非 word()
        // —— 数值 "0.35"/"-0.1" 含 '.',word() 拒收(同 cam name 含 '@' 的教训)。
        return Commands.literal("taclight")
                .then(Commands.literal("tune")
                        .requires(s -> s.hasPermission(0))
                        .executes(ctx -> tuneStatic(ctx.getSource(), "help", ""))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> tuneRun(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), null))
                                .then(Commands.argument("value", StringArgumentType.string())
                                        .executes(ctx -> tuneRun(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "value"))))));
    }

    /** 反射挂载 dev-only 子命令;发布件里该类不存在 = 正常路径,只记一行不报错。 */
    private static void attachDevChildren(LiteralCommandNode<CommandSourceStack> root) {
        try {
            Class.forName(DEV_CHILDREN)
                    .getMethod("attach", LiteralCommandNode.class)
                    .invoke(null, root);
            TacLightMod.LOGGER.info("[TacLight] CMDS dev-only 子命令已挂载(kit/cam/scene/light): {}", DEV_CHILDREN);
        } catch (ClassNotFoundException e) {
            TacLightMod.LOGGER.info("[TacLight] CMDS dev-only 子命令未打包(发布构建),仅 tune 可用: {}", DEV_CHILDREN);
        } catch (Throwable t) {
            // 反射签名漂移/链接错误:必须留痕,否则"命令少了一半"会静默
            TacLightMod.LOGGER.warn("[TacLight] CMDS dev-only 子命令挂载失败: {}", t.toString());
        }
    }

    // ---- tune:十旋钮正式调参(2026-09-19;发布包可用;2026-10-06 分层:help/list/<名> help) ----

    /** 生产覆盖层入口(与 TuneService 契约的假入口恒等;voxel 不走这里,走客户端门)。 */
    private static final TunePersist.KnobApplier TUNE_APPLIER = (knob, arg) -> switch (knob) {
        case "bright" -> dev.taclight.channel.LightTuneOverride.configureBright(arg);
        case "dist" -> dev.taclight.channel.LightTuneOverride.configureDist(arg);
        case "atten" -> dev.taclight.channel.LightTuneOverride.configureAtten(arg);
        case "knee" -> dev.taclight.channel.LightTuneOverride.configureKnee(arg);
        case "beam" -> dev.taclight.channel.LightTuneOverride.configureBeam(arg);
        case "scat" -> dev.taclight.channel.LightTuneOverride.configureScat(arg);
        case "beamcap" -> dev.taclight.channel.LightTuneOverride.configureBeamcap(arg);
        case "cone" -> dev.taclight.channel.LightTuneOverride.configureCone(arg);
        case "held" -> dev.taclight.channel.LightTuneOverride.configureHeld(arg);
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

    /**
     * 静态文案入口:{@code tune}(无参)/{@code tune help}/{@code tune list} —— 零客户端引用,
     * <b>先于 MP 守卫</b>:专用服上"看命令怎么用"是正当需求,不该被"仅单人/客户端生效"挡掉
     * (那条守卫管的是"改本机状态",不管"读说明")。
     */
    private static int tuneStatic(CommandSourceStack source, String name, String value) {
        TuneService.Result r = TuneService.tune(name, value, null, null, null);
        source.sendSuccess(() -> Component.literal(r.message()), false);
        TacLightMod.LOGGER.info("[TacLight] TUNE static {}", name);
        return 1;
    }

    /** /taclight tune &lt;name&gt; [&lt;value&gt;|status|clear|help]。 */
    private static int tuneRun(CommandSourceStack source, String name, String value) {
        String n = name == null ? "" : name.trim();
        String v = value == null ? "" : value.trim();
        // 静态分支(不带参的 help/list/无参):不碰客户端门,专用服也可用
        if (v.isEmpty() && (n.isEmpty() || "help".equalsIgnoreCase(n) || "?".equals(n)
                || "list".equalsIgnoreCase(n))) {
            return tuneStatic(source, n.isEmpty() ? "help" : n, "");
        }
        if (tuneMpGuard(source)) return 0;
        TuneService.Result r = TuneService.tune(
                name, value,
                TunePersist.forgeSink(),
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

    private TacLightCommand() {}
}

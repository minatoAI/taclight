package dev.taclight.client;

import dev.taclight.TacLightMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 文件命令中继(调试自动化专用):轮询 run/taclight-cmds.txt,逐行执行后清空文件。
 *
 * <p>为什么存在:本机实测(dev 实例)客户端命令树为空(登录后 ClientboundCommandsPacket
 * 未生效,聊天框输入任何命令都被本地预览拒绝),且物理/PostMessage 键注入受焦点态
 * 影响时灵时不效。文件通道三者全免:AI 直接写文件,模组客户端 tick 消费。</p>
 *
 * <p>行语法:
 *  <ul>
 *   <li>{@code /xxx} —— 作为聊天命令包直发服务端(sendCommand,服务端分发器已验证健康,
 *       CMDS root children=68);服务端执行结果经聊天回显入 latest.log。</li>
 *   <li>{@code !reload} —— 触发客户端资源重载(Oculus 会在资源重载时连带重载光影包,
 *       旧项目 T0 判据④即以 Reloading Resource 计数验证)。</li>
 *   <li>{@code !diag} —— 与 N 键等价:一行结构化诊断入日志。</li>
 *   <li>{@code !bench} —— 与 B 键等价:3 秒帧率基准。</li>
 *   <li>{@code !back} —— 程序化关界面(2026-09-04:ESC 菜单挡帧以往只能手点关,
 *       违反程序化纪律;本命令=setScreen(null),与菜单“回到游戏”同入口)。</li>
  *   <li>{@code !light} / {@code !neon} / {@code !gun} —— 手电 / 霓虹调试锥 / 枪灯开关
  *       (L/K 键的程序化等价;场景照明状态的唯一可靠控制通道)。</li>
  *   <li>{@code !bright} / {@code !dist} / {@code !atten} —— 手电三旋钮(2026-09-04,
  *       用户体感自助调参):绝对亮度 / 绝对照距 / 衰减系数 K。内存覆盖,重启清零;
  *       无参=status,{@code off}=回默认(用法见各命令日志回显)。</li>
  *   <li>{@code !beam} —— 体积光束密度第五旋钮(2026-09-05,丁达尔效果强度):
  *       0..1;0=完全关光束(开关对比),off=回 config 默认 0.05;vlParams.y 直接换值。</li>
 *   <li>{@code !beamonly} —— 只看光束(2026-09-05):头部 flags bit2,GLSL 跳过 M1 表面
 *       照明,composite1 体积束照常——单独观察体积光形态用。on/off/status。</li>
 *   <li>{@code !scat} —— 体积光散射各向异性 g 第六旋钮(2026-09-05):0..0.9,经
 *       vlParams.x 逐灯透传;0=各向同性侧视最亮,off=回默认 0.55。</li>
 *   <li>{@code !beamcap} —— 体积光重叠软上限倍率第七旋钮(2026-09-05):0.25..8,经
 *       vlParams.z 透传(GLSL cap=2.0×m);单灯恒等,多灯重叠渐近封顶。</li>
 *   <li>{@code !looktrace} / {@code !mcap} —— 消融探针 / 运动门控采集开关(09-01,
 *       布防后被观察角色朝向/位置变化自动连拍+逐帧信号,静止自停)。</li>
 *  </ul></p>
 *
 * <p>消费后立即原子清空文件(读→写空);写入方请整文件重写,勿追加并发写。</p>
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT)
public final class DebugCommandRelay {
    private static final Path CMD_FILE = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
            .resolve("taclight-cmds.txt");
    private static int cooldown;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++cooldown % 10 != 0) return; // 2Hz 轮询足够
        if (!Files.isRegularFile(CMD_FILE)) return;
        // 只在世界内消费:命令包需要活动连接;否则文件保留,进世界后自动执行
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        String content;
        try {
            content = Files.readString(CMD_FILE, StandardCharsets.UTF_8);
        } catch (Exception e) {
            TacLightMod.LOGGER.warn("[TacLight] RELAY read failed: {}", e.toString());
            return;
        }
        try {
            Files.writeString(CMD_FILE, "", StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        for (String raw : content.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            execute(line);
        }
    }

    private static void execute(String line) {
        Minecraft mc = Minecraft.getInstance();
        TacLightMod.LOGGER.info("[TacLight] RELAY exec: {}", line);
        if (line.startsWith("!shot")) {
            // 程序化截图(2026-09-02 用户要求:测试驱动弃用键鼠模拟/抢前台窗口):
            // 与 F2 同源直接读主帧缓冲落盘,零输入模拟;中继在渲染线程 tick 内执行,
            // GL 上下文在位,读到的是最近一帧。文件名 = 原版时间戳规则。
            try {
                net.minecraft.client.Screenshot.grab(
                        net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toFile(),
                        mc.getMainRenderTarget(),
                        (msg) -> TacLightMod.LOGGER.info("[TacLight] RELAY shot: {}", msg.getString()));
            } catch (Throwable t) {
                TacLightMod.LOGGER.warn("[TacLight] RELAY shot failed: {}", t.toString());
            }
            return;
        }
        if (line.startsWith("!reload")) {
            // Iris.reload() = 重载键绑(J)的最终入口,从磁盘重解析+重编译整包。
            // 反射调用:oculus 是 runtimeOnly 可选依赖;Iris 为模组自有类,方法名不经 SRG 重映射。
            // 实测教训(2026-08-29):mc.reloadResourcePacks() 的资源重载在 Oculus 1.8.0 上
            // 不重建管线(日志 Creating pipeline 不出现),必须走本通道。
            try {
                Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
                TacLightMod.LOGGER.info("[TacLight] RELAY Iris.reload() ok");
            } catch (Throwable t) {
                TacLightMod.LOGGER.warn("[TacLight] RELAY Iris.reload() failed ({}), fallback resource reload", t.toString());
                mc.reloadResourcePacks();
            }
            return;
        }
        if (line.equals("!rec") || line.startsWith("!rec ")) {
            // 一键录制(09-01 深夜⑥,与 !mcap 同一状态机):on 后本地/远程任一运动
            // 自动开录(逐帧 CSV + 60fps 截图),静止自动收窗。精确匹配防吞 !reload。
            String arg = line.length() > 4 ? line.substring(4).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY rec -> {}",
                    dev.taclight.channel.MotionCapture.configure(arg));
            return;
        }
        if (line.startsWith("!diag")) {
            ClientEvents.dumpDiag();
            return;
        }
        if (line.equals("!lan") || line.startsWith("!lan ")) {
            // 直调服务端开 LAN(2026-09-04 双端漏光环境:/publish 走客户端命令树被
            // 本地预解析拒"未知或不完整的命令";此处绕过命令分发器,直接调集成服
            // publishServer,与暂停菜单"对局域网开放"同入口;固定端口 25560,
            // 观察者 B 直连 127.0.0.1:25560。传 0 走随机端口实测 getPort()=0 未绑定)。
            try {
                var server = mc.getSingleplayerServer();
                if (server == null) {
                    TacLightMod.LOGGER.warn("[TacLight] RELAY lan: 非单人集成服,无服务端可开");
                } else if (!server.isPublished()) {
                    boolean ok = server.publishServer(
                            net.minecraft.world.level.GameType.SURVIVAL, false, 25560);
                    TacLightMod.LOGGER.info("[TacLight] RELAY lan -> {} (port={})", ok, 25560);
                } else {
                    TacLightMod.LOGGER.info("[TacLight] RELAY lan: 已开放(port={})",
                            server.getPort());
                }
            } catch (Throwable t) {
                TacLightMod.LOGGER.warn("[TacLight] RELAY lan failed: {}", t.toString());
            }
            return;
        }
        if (line.startsWith("!bench")) {
            ClientEvents.startBench();
            return;
        }
        if (line.equals("!back") || line.startsWith("!back ")) {
            // 程序化关界面:ESC/聊天/容器等任意 Screen 直接关(与手点“回到游戏”同入口
            // setScreen(null);单人未发布存档的暂停态随 PauseScreen 关闭自动解除)。
            // 踩坑补位:菜单挡帧以往只能手点(坑96)或杀进程重拉,本命令 2 秒自愈。
            mc.setScreen(null);
            TacLightMod.LOGGER.info("[TacLight] RELAY back -> screen closed");
            return;
        }
        // 灯光控制(L 键的程序化等价 —— 键注入不可靠,灯光状态走文件通道)
        if (line.startsWith("!light")) {
            ClientLightState.toggle();
            // 08-31 实测坑:L 键路径(InjectionEvent) toggle 后会 sendSetLight 上报服务端,
            // relay 必须对齐,否则服务端实体数据不变 → 其他玩家看不到开关(ssbo count 假 1)。
            dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.isOn(), ClientLightState.gunLightOn());
            TacLightMod.LOGGER.info("[TacLight] RELAY light -> {}", ClientLightState.isOn());
            return;
        }
        if (line.startsWith("!neon")) {
            ClientLightState.toggleDebug();
            TacLightMod.LOGGER.info("[TacLight] RELAY neon(debug cone) -> {}", ClientLightState.debugMode());
            return;
        }
        if (line.startsWith("!lv")) {
            // 2026-09-03 真实感调参档位(零重启体感):lv 即时覆盖 TacLightConfig 读到的
            // 亮度(ln 档 0-6 → intensity 6·2^-档),radius 按 √(I/6) 自耦合;重置=重启实例。
            // 用法:!lv 2 / !lv 3.5 / !lv status。CLIENT config 热改 toml 不回读,故走覆盖层。
            String arg = line.length() > 3 ? line.substring(3).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY lv -> {}",
                    dev.taclight.channel.LightLevelOverride.configure(arg));
            return;
        }
        if (line.startsWith("!bright")) {
            // 2026-09-04 用户体感三旋钮①:绝对亮度(与 !lv 档位互斥,后写者胜;体感只用一路)。
            // 用法:!bright 12 / !bright status / !bright off。范围 0.5..30(同 config 域)。
            String arg = line.length() > 7 ? line.substring(7).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY bright -> {}",
                    dev.taclight.channel.LightTuneOverride.configureBright(arg));
            return;
        }
        if (line.startsWith("!dist")) {
            // 2026-09-04 用户体感三旋钮②:绝对照距(格,bypass √亮度耦合,钳制 ≤96)。
            // 用法:!dist 24 / !dist status / !dist off。范围 4..96(同 config 域)。
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY dist -> {}",
                    dev.taclight.channel.LightTuneOverride.configureDist(arg));
            return;
        }
        if (line.startsWith("!atten")) {
            // 2026-09-04 用户体感三旋钮③:衰减系数 K(越小尾越长;5.0=主包标定,0.5r 处约 20%)。
            // 经 SSBO cone.z 逐灯透传(0=GLSL 回退编译期默认),零重启生效。范围 0.2..20。
            String arg = line.length() > 6 ? line.substring(6).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY atten -> {}",
                    dev.taclight.channel.LightTuneOverride.configureAtten(arg));
            return;
        }
        if (line.startsWith("!knee")) {
            // 2026-09-05 用户体感第四旋钮:近场软肩 G(越大近场压得越狠、远场几乎不动;
            // 2.0=主包标定,近暗远亮;0.2≈趋平/压缩最弱)。经 SSBO cone.w 逐灯透传
            // (0=GLSL 恒等直通=今日行为),零重启生效。范围 0.2..8。
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY knee -> {}",
                    dev.taclight.channel.LightTuneOverride.configureKnee(arg));
            return;
        }
        if (line.startsWith("!beamonly")) {
            // 2026-09-05 只看光束:头部 flags bit2(FLAG_BEAM_ONLY)→ GLSL 跳过 M1 表面
            // 照明,composite1 体积束照常——单独观察体积光形态。必须排在 !beam 之前
            // (startsWith 前缀包含)。on/off/status,重启清零。
            String arg = line.length() > 9 ? line.substring(9).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY beamonly -> {}",
                    dev.taclight.channel.LightTuneOverride.configureBeamonly(arg));
            return;
        }
        if (line.startsWith("!beamcap")) {
            // 2026-09-05 第七旋钮:体积光重叠软上限倍率 m(GLSL cap=2.0×m,低于半帽点
            // 恒等=单灯观感零变化,多灯重叠指数肩部渐近封顶,不许亮度无限叠加刺眼)。
            // 0.25=压得最狠,8≈基本不限,off=回 m=1。必须排在 !beam 之前(startsWith 前缀包含)。
            String arg = line.length() > 8 ? line.substring(8).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY beamcap -> {}",
                    dev.taclight.channel.LightTuneOverride.configureBeamcap(arg));
            return;
        }
        if (line.startsWith("!beam")) {
            // 2026-09-05 用户体感第五旋钮:体积光束密度(丁达尔效果强度)。经 SSBO
            // vlParams.y 逐灯直接换值,GLSL 零改动;0=完全关光束(A/B 开关对比),
            // off=回 config 默认 beamDensity=0.05。范围 0..1,零重启生效,重启清零。
            // 注意 !bench 前缀不冲突(分支匹配互不前缀包含)。
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY beam -> {}",
                    dev.taclight.channel.LightTuneOverride.configureBeam(arg));
            return;
        }
        if (line.startsWith("!scat")) {
            // 2026-09-05 第六旋钮:体积光散射各向异性 g(侧视丁达尔可见性主旋钮)。
            // 经 SSBO vlParams.x 逐灯直接换值,GLSL 零改动;0=完全各向同性(雾球,
            // 侧视最亮),off=回编译期默认 g=0.55(前向散射,顺轴亮/侧视暗 16~27×)。
            // 范围 0..0.9,零重启生效,重启清零。与其他 !s* 分支互不前缀包含。
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY scat -> {}",
                    dev.taclight.channel.LightTuneOverride.configureScat(arg));
            return;
        }
        if (line.startsWith("!gun")) {
            boolean next = !ClientLightState.gunLightOn();
            ClientLightState.setGunLightManual(next);
            // 手动覆写必须同步服务端真源,否则本端 SSBO 有光而对端(同步读)永远看不见
            // —— 这正是"Dev 视角切开关无变化 + B 看不见 Dev 灯"的另一半根因。
            dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.isOn(), next);
            TacLightMod.LOGGER.info("[TacLight] RELAY gunLight -> {} (manual)", next);
            return;
        }
        if (line.startsWith("!selflight")) {
            String arg = line.length() > 10 ? line.substring(10).trim() : "";
            // 无参=回显当前值(与 !rec 无参只回显同规);on/off=显式设定;空参沿用翻转。
            if (arg.equals("on") || arg.equals("off")) {
                boolean on = arg.equals("on");
                // 显式设定即清运行时覆写回到配置语义:通过 toggle 两次语义太绕,直接清覆写
                // 再按需翻转一次 —— 简单起见走 toggle-until-match(最多 1 次)。
                if (ClientLightState.selfLightEnabled() != on) {
                    ClientLightState.toggleSelfLight();
                }
                TacLightMod.LOGGER.info("[TacLight] RELAY selfLight -> {} (set)", ClientLightState.selfLightEnabled());
            } else if (arg.isEmpty()) {
                TacLightMod.LOGGER.info("[TacLight] RELAY selfLight = {} (usage: !selflight <on|off>)",
                        ClientLightState.selfLightEnabled());
            } else {
                boolean on = ClientLightState.toggleSelfLight();
                TacLightMod.LOGGER.info("[TacLight] RELAY selfLight -> {}", on);
            }
            return;
        }
        if (line.startsWith("!extrap")) {
            // 方案A 调参旋钮(客户端本地;/taclight 会发到服务端,管不到本客户端预测状态)
            String arg = line.length() > 7 ? line.substring(7).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY extrap -> {}",
                    dev.taclight.channel.RemoteLookPredictor.configure(arg));
            return;
        }
        if (line.startsWith("!looktrace")) {
            // 消融探针(09-01):逐帧角度链路记录,见 LookTrace
            String arg = line.length() > 10 ? line.substring(10).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY looktrace -> {}",
                    dev.taclight.channel.LookTrace.configure(arg));
            return;
        }
        if (line.startsWith("!mcap")) {
            // 运动门控采集开关(09-01 晚):布防后被观察角色朝向/位置变化自动连拍+信号
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY mcap -> {}",
                    dev.taclight.channel.MotionCapture.configure(arg));
            return;
        }
        if (line.startsWith("!bsnap")) {
            // snap+pred 基角总开关(09-01 深夜,用户批准):off 退回 rotLerp+方案A 旧管线(A/B 对照)
            String arg = line.length() > 6 ? line.substring(6).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY bsnap -> {}",
                    dev.taclight.channel.RemoteBaseSnap.configure(arg));
            return;
        }
        if (line.startsWith("!psnap")) {
            // snap+pred 位置链总开关(09-01 深夜③):off 退回 getEyePosition 旧管线(A/B 对照)
            String arg = line.length() > 6 ? line.substring(6).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY psnap -> {}",
                    dev.taclight.channel.RemotePosSnap.configure(arg));
            return;
        }
        if (line.startsWith("!bob")) {
            String arg = line.length() > 4 ? line.substring(4).trim() : "";
            String result = BobViewControl.configure(arg,
                    () -> mc.options.bobView().get(),
                    enabled -> mc.options.bobView().set(enabled));
            TacLightMod.LOGGER.info("[TacLight] RELAY bob -> {}", result);
            return;
        }
        if (line.startsWith("!voxel")) {
            // 体素 DDA 遮挡总开关(09-01 深夜④,墙后漏光立项):off = SSBO 无效位,GLSL 回退 SSO
            String arg = line.length() > 6 ? line.substring(6).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY voxel -> {}", VoxelGrid.configure(arg));
            return;
        }
        if (line.startsWith("!sweep")) {
            // 程序化视角扫掠(2026-09-02 打桩激励):冻结目标+相机扫掠不变性测试用
            String arg = line.length() > 6 ? line.substring(6).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY sweep -> {}", CameraSweep.configure(arg));
            return;
        }
        if (line.startsWith("!tpfb")) {
            // TP 枪灯回退模式 A/B(2026-09-02 屏外连续性):blend=hold+连续混合(默认)/hard=旧二元回退
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY tpfb -> {}", TpFallbackControl.configureFallback(arg));
            return;
        }
        if (line.startsWith("!tproe")) {
            // TP 束轴读数模式(打桩变异:!tproe row 复现坑68,验证 TP-INVARIANCE 能抓到)
            String arg = line.length() > 6 ? line.substring(6).trim() : "";
            String res;
            if (arg.isEmpty()) {
                res = "tpRowRead=" + (dev.taclight.pose.MuzzlePoseMath.isTpRowReadDebug() ? "row(坑68 复现)" : "col(正确)");
            } else if (arg.equals("row") || arg.equals("col")) {
                dev.taclight.pose.MuzzlePoseMath.setTpRowReadDebug(arg.equals("row"));
                res = "tpRowRead->" + arg;
            } else {
                res = "无法解析 '" + arg + "' (用法: !tproe <col|row>)";
            }
            TacLightMod.LOGGER.info("[TacLight] RELAY tproe -> {}", res);
            return;
        }
        if (line.startsWith("/")) {
            if (mc.getConnection() != null) {
                // 2026-09-02:dev 客户端命令树不完整(javadoc 顶部已载,原版节点缺失,
                // /fill /gamemode /time 被本地预解析拒"未知或不完整的命令");
                // sendUnsignedCommand 跳过本地校验/签名直发服务端,服务端分发器健康。
                mc.getConnection().sendUnsignedCommand(line.substring(1));
            } else {
                TacLightMod.LOGGER.warn("[TacLight] RELAY no-connection, dropped: {}", line);
            }
            return;
        }
        TacLightMod.LOGGER.warn("[TacLight] RELAY unknown line: {}", line);
    }

    private DebugCommandRelay() {}
}

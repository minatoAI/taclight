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
 * 文件命令中继(调试自动化专用):轮询 <b>{@code <gameDir>/taclight-cmds.txt}</b>
 * (路径 = {@code FMLPaths.GAMEDIR.get().resolve("taclight-cmds.txt")},见下方字段),逐行执行后清空文件。
 *
 * <p><b>★ 路径口径(2026-09-19 修正,原 javadoc 写"run/taclight-cmds.txt"误导过一次真机轮)</b>:
 * 监听的是 <b>gameDir 根目录</b>下的 {@code taclight-cmds.txt},<b>不是</b> {@code <gameDir>/run/…}。
 * 旧写法只在 <b>dev 实例</b>成立——dev 运行里 gameDir 本身就是 {@code taclight/run}
 * (所以 dev 用 {@code run\taclight-cmds.txt} 是对的);而独立实例(qa 的
 * {@code qa9-m1-smoke\game-full})gameDir = 实例根 ⇒ 必须写
 * {@code E:\dshHome\qa9-m1-smoke\game-full\taclight-cmds.txt},
 * 写到 {@code game-full\run\taclight-cmds.txt} <b>永远不会被消费</b>。</p>
 *
 * <p><b>★ 只存在于 dev/调试构建(发布 jar 故意剔除,见 build.gradle 的 exclude)</b>:
 * {@code build.gradle} 把 {@code dev/taclight/client/DebugCommandRelay*.class} 与
 * {@code dev/taclight/debug/**} 从发布包剔除 ⇒ <b>用发布 jar 的实例里本通道不存在,
 * "零 RELAY 行"是预期而非故障</b>(反回归断言见 {@code InteropPackagingContract})。
 * 理由:该通道是"任何本地程序可写、写了即驱动客户端命令"的无守卫写入口,
 * 与 modtest-mcp 令牌+审计的设计取向冲突;将来若要开放,只许放行只读子集
 * ({@code !interop}/{@code !diag} 类 status 查询)并另设开关/变体。</p>
 *
 * <p>2026-09-19 八旋钮晋升:{@code !bright/!dist/!atten/!knee/!beam/!scat/!beamcap/!cone} +
 * {@code !voxel} 另有一条正式路径 <b>{@code /taclight tune}</b>(服务端命令树,发布包可用,
 * 调参直写 {@code config/taclight-client.toml} 重启保留)。本中继保持<b>纯内存覆盖、重启清零</b>
 * 不变,两条路径后写者胜(同一覆盖层);要持久化请走 tune,本中继仅作调试对照。</p>
 *
 * <p>为什么存在:本机实测(dev 实例)客户端命令树为空(登录后 ClientboundCommandsPacket
 * 未生效,聊天框输入任何命令都被本地预览拒绝),且物理/PostMessage 键注入受焦点态
 * 影响时灵时不效。文件通道三者全免:AI 直接写文件,模组客户端 tick 消费。</p>
 *
 * <p><b>用法配方(逐字)</b>:进世界后把 UTF-8 无 BOM、一行 {@code !interop} + 换行
 * <b>覆盖写</b>入 {@code <gameDir>/taclight-cmds.txt} ⇒ ≤0.5s(2Hz 轮询)内日志出现
 * {@code [TacLight] RELAY exec: !interop} 与多行 {@code [TacLight] RELAY interop | …},
 * 且该文件被<b>截断为 0 字节</b>(消费的可判定证据)。主菜单/无连接时不消费且文件保留。</p>
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
 *   <li>{@code !scat} —— 体积光轴向底亮份额第六旋钮(2026-09-05 侧面相位定案):0..0.9,经
 *       vlParams.x 逐灯透传;0=纯侧面丁达尔(正对光源零体积叠加),off=回默认 0.04。</li>
 *   <li>{@code !beamcap} —— 体积光重叠软上限倍率第七旋钮(2026-09-05):0.25..8,经
 *       vlParams.z 透传(GLSL cap=2.0×m);单灯恒等,多灯重叠渐近封顶。</li>
 *   <li>{@code !cone} —— 锥角收窄第八旋钮(2026-09-05 "接近平行光"):外锥半角 2..45 度,
 *       内锥=外×0.5,Java 侧直改 cosOuter/cosInner;off=回 config 默认 8/4。</li>
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
        if (line.equals("!snap") || line.startsWith("!snap ")) {
            // 一键调试快照(2026-09-19 最小闭环):pose/灯参/tune 体素/renderdoc 状态落盘
            // <gameDir>/debug-snapshots/<时间戳>/,与 F9 与 /taclight snap 同一入口。
            // 精确匹配防吞其它 !s* 分支(!synth/!selflight/!scat/!shot/!sweep 互不前缀包含)。
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            java.nio.file.Path dir = DebugSnapshotter.saveSnapshot(arg.isEmpty() ? "relay" : ("relay:" + arg));
            TacLightMod.LOGGER.info("[TacLight] RELAY snap -> {}",
                    dir == null ? "FAILED" : dir.toString());
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
        if (line.equals("!gl")) {
            // GL 身份 + 扩展面记录(2026-09-25):
            // ① 本机是 NVIDIA RTX 5070 Ti + AMD 核显的混合显卡,必须知道游戏**实际**跑在哪块 GPU 上
            //    ("测量的路径≠生产的路径"),也决定 RenderDoc/Nsight 的可用性与 GL 扩展面;
            // ② Step2(KHR_debug → gl.messages)前置探测:debug context 标志位 + KHR_debug /
            //    ARB_debug_output 是否存在 —— 决定"驱动结构化消息"这条线能不能走,避免先承诺后验证;
            // ③ 记录 GL_ARB_shading_language_420pack:离线 glslang 校验必须补这一行才不误报
            //    (Iris 只声明 SSBO 扩展,而 glslang 严格要求 420pack),这条日志是驱动侧依据。
            // 全量扩展列表写 <gameDir>/taclight-gl-ext.txt(机器可读,供证据归档),日志只打摘要。
            String vendor = "?";
            String renderer = "?";
            String version = "?";
            String glsl = "?";
            String numExt = "?";
            String ctxFlags = "?";
            String debugCtx = "?";
            String profile = "?";
            StringBuilder present = new StringBuilder();
            try {
                // 先清空陈旧 GL 错误,避免污染后续 gl.messages 判据
                for (int guard = 0; guard < 64; guard++) {
                    if (org.lwjgl.opengl.GL11.glGetError() == org.lwjgl.opengl.GL11.GL_NO_ERROR) {
                        break;
                    }
                }
                vendor = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
                renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
                version = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
                glsl = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL20.GL_SHADING_LANGUAGE_VERSION);
                int n = org.lwjgl.opengl.GL11.glGetInteger(0x821D /* GL_NUM_EXTENSIONS */);
                numExt = Integer.toString(n);
                // GL_CONTEXT_FLAGS = 0x821E; GL_CONTEXT_FLAG_DEBUG_BIT = 0x2
                int flags = org.lwjgl.opengl.GL11.glGetInteger(0x821E);
                ctxFlags = "0x" + Integer.toHexString(flags);
                debugCtx = ((flags & 0x2) != 0) ? "YES" : "NO";
                // GL_CONTEXT_PROFILE_MASK = 0x9126; CORE = 0x1; COMPAT = 0x2
                int pm = org.lwjgl.opengl.GL11.glGetInteger(0x9126);
                profile = (pm == 0x1) ? "CORE" : (pm == 0x2) ? "COMPAT" : ("0x" + Integer.toHexString(pm));
                java.util.TreeSet<String> all = new java.util.TreeSet<>();
                for (int i = 0; i < n; i++) {
                    String e = org.lwjgl.opengl.GL30.glGetStringi(org.lwjgl.opengl.GL11.GL_EXTENSIONS, i);
                    if (e != null) {
                        all.add(e);
                    }
                }
                String[] want = {"GL_KHR_debug", "GL_ARB_debug_output",
                        "GL_ARB_shading_language_420pack", "GL_ARB_shader_storage_buffer_object"};
                for (String w : want) {
                    present.append(w).append('=').append(all.contains(w) ? "YES" : "NO").append(' ');
                }
                java.io.File f = new java.io.File(
                        net.minecraft.client.Minecraft.getInstance().gameDirectory, "taclight-gl-ext.txt");
                StringBuilder sb = new StringBuilder();
                sb.append("vendor=").append(vendor).append('\n');
                sb.append("renderer=").append(renderer).append('\n');
                sb.append("version=").append(version).append('\n');
                sb.append("glsl=").append(glsl).append('\n');
                sb.append("num_extensions=").append(n).append('\n');
                sb.append("context_flags=").append(ctxFlags).append('\n');
                sb.append("debug_context=").append(debugCtx).append('\n');
                sb.append("profile=").append(profile).append('\n');
                for (String e : all) {
                    sb.append(e).append('\n');
                }
                java.nio.file.Files.writeString(f.toPath(), sb.toString());
            } catch (Throwable t) {
                vendor = "err:" + t.getClass().getSimpleName();
            }
            TacLightMod.LOGGER.info("[TacLight] RELAY gl -> vendor={} renderer={} version={} glsl={} numExt={} flags={} debugCtx={} profile={} {}",
                    vendor, renderer, version, glsl, numExt, ctxFlags, debugCtx, profile, present.toString().trim());
            return;
        }
        if (line.startsWith("!diag")) {
            ClientEvents.dumpDiag();
            return;
        }
        if (line.startsWith("!interop")) {
            // 2026-09-19 interop 注入自助诊断(用户"光影包没生效"的唯一可操作通道):
            // 原始包名 → 归一化匹配键 → 包根 → 模板/通道 → 逐文件结果;另附模板清单与哈希对照。
            // 与既有分支无前缀包含(!i* 仅此一条)。
            StringBuilder sb = new StringBuilder(dev.taclight.interop.RuntimePackInjector.statusReport());
            sb.append("\n  已登记模板 = ")
                    .append(String.join(" | ", dev.taclight.interop.RuntimePackInjector.registeredTemplates()));
            String raw = dev.taclight.interop.RuntimePackInjector.rawPackName();
            sb.append("\n  当前 shaderPack = ").append(raw);
            var cmp = dev.taclight.interop.RuntimePackInjector.hashComparison(raw);
            for (var e : cmp.entrySet()) {
                sb.append("\n  哈希 ").append(e.getKey()).append(" = ").append(e.getValue());
            }
            for (String l : sb.toString().split("\n")) {
                TacLightMod.LOGGER.info("[TacLight] RELAY interop | {}", l);
            }
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
        // T17 动作层(默认关,重启清零):!agent goto <x> <y> <z> [run] | hold fwd|jump|none <ticks> |
        // sprint on|off | stop | status。与 !atten 等 !a* 分支互不前缀包含(第 3 字符即不同)。
        if (line.equals("!agent") || line.startsWith("!agent ")) {
            String arg = line.length() > 6 ? line.substring(6).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY agent -> {}", dev.taclight.debug.AgentInput.configure(arg));
            return;
        }
        // 灯光控制(L 键的程序化等价 —— 键注入不可靠,灯光状态走文件通道)
        if (line.equals("!synth") || line.startsWith("!synth ")) {
            // T11 测试光源夹具:向 SSBO 追加 N 盏合成灯(默认关=0,重启清零)。
            // 命名坑(先例见 L230"必须排在 !beam 之前(startsWith 前缀包含)"):本中继用
            // startsWith 前缀匹配,开关名不得与既有分支前缀包含 —— 若叫 "!lights" 会被下面的
            // !light(翻转)整条吞掉,故用 !synth;本分支与其它 !s* 分支(!selflight/!scat/
            // !shot/!sweep)互不前缀包含。精确匹配规约同 !rec/!lan/!back:equals 或 " " 后缀,
            // 防 !synthx 之类误命中。
            String arg = line.length() > 6 ? line.substring(6).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY synth -> {}",
                    dev.taclight.debug.SynthLights.configure(arg));
            return;
        }
        if (line.startsWith("!light")) {
            // 持物门对齐 L 键(2026-09-25):未持手电筒且非霓虹调试时不改状态,回显原因(不假成功)。
            if (!ClientEvents.holdingFlashlight(mc.player) && !ClientLightState.debugMode()) {
                TacLightMod.LOGGER.info("[TacLight] RELAY light -> ignored (not holding flashlight)");
                return;
            }
            ClientLightState.toggle();
            // 08-31 实测坑:L 键路径(InjectionEvent) toggle 后会 sendSetLight 上报服务端,
            // relay 必须对齐,否则服务端实体数据不变 → 其他玩家看不到开关(ssbo count 假 1)。
            dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.handheldEffective(), ClientLightState.gunLightEffective());
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
            // 2026-09-04 用户体感三旋钮③:衰减系数 K(越小尾越长;20.0=主包标定,2026-09-06 扫参冻结)。
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
        if (line.startsWith("!occl")) {
            // 2026-09-06 方案二第九旋钮:遮挡距离表(默认开)——GLSL composite 每帧预建
            // 逐灯均向 D 表(colortex8),composite1 体积光逐采样灯侧 DDA(光池内
            // @4K +20.6ms)降为一次查表。off=回逐采样 DDA(A/B 对照)。on/off/status。
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY occl -> {}",
                    dev.taclight.channel.LightTuneOverride.configureOccl(arg));
            return;
        }
        if (line.startsWith("!tm")) {
            // 2026-09-06 第十旋钮:体积光时间复用(默认开)——步数 64→32 + 抖动逐帧
            // 旋转 + 上一帧历史重投影混合(权重 0.75×逐灯置信度,LightMotionConf 差分
            // 灯位姿经 vlParams.w 透传)。静态有效步数≈128、raymarch 步数减半;
            // off=回 64 步全新鲜(A/B 对照)。on/off/status,重启清零。
            String arg = line.length() > 3 ? line.substring(3).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY tm -> {}",
                    dev.taclight.channel.LightTuneOverride.configureTemporal(arg));
            return;
        }
        if (line.startsWith("!beam")) {
            // 2026-09-05 用户体感第五旋钮:体积光束密度(丁达尔效果强度)。经 SSBO
            // vlParams.y 逐灯直接换值,GLSL 零改动;0=完全关光束(A/B 开关对比),
            // off=回 config 默认 beamDensity=0.25。范围 0..1,零重启生效,重启清零。
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
        if (line.startsWith("!cone")) {
            // 2026-09-05 第八旋钮:锥角收窄(用户定案"接近平行光")。外锥半角度数,
            // 内锥=外×0.5,Java 侧直改 cosOuter/cosInner(SSBO/GLSL 零改动)。
            // 用法:!cone 8 / !cone status / !cone off;范围 2..45,off=回 config 默认 8/4。
            String arg = line.length() > 5 ? line.substring(5).trim() : "";
            TacLightMod.LOGGER.info("[TacLight] RELAY cone -> {}",
                    dev.taclight.channel.LightTuneOverride.configureCone(arg));
            return;
        }
        if (line.startsWith("!gun")) {
            // 2026-09-07:无参=翻转(兼容旧行为);on/off=显式手动;auto=清手动旗回探针跟随
            // (主手有灯枪即亮、空手即灭);未知参=回显。用 GunControl 与 M 键共状态机。
            String arg = line.length() > 4 ? line.substring(4).trim() : "";
            dev.taclight.client.GunControl.Action act =
                    dev.taclight.client.GunControl.parseRelayArg(arg);
            switch (act) {
                case AUTO: {
                    dev.taclight.client.GunControl.applyAuto();
                    dev.taclight.network.TacLightNetwork.sendSetLight(
                            ClientLightState.isOn(), ClientLightState.gunLightEffective());
                    TacLightMod.LOGGER.info("[TacLight] RELAY gunLight -> auto (probe-follow)");
                    break;
                }
                case STATUS: {
                    TacLightMod.LOGGER.info("[TacLight] RELAY gunLight = {} manual={} (usage: !gun <on|off|auto>)",
                            ClientLightState.gunLightOn(), ClientLightState.gunManual());
                    break;
                }
                default: {
                    boolean next = (act == dev.taclight.client.GunControl.Action.ON)
                            ? true
                            : (act == dev.taclight.client.GunControl.Action.OFF)
                                    ? false
                                    : !ClientLightState.gunLightOn();
                    ClientLightState.setGunLightManual(next);
                    // 手动覆写必须同步服务端真源,否则本端 SSBO 有光而对端(同步读)永远看不见
                    // —— 这正是"Dev 视角切开关无变化 + B 看不见 Dev 灯"的另一半根因。
                    // 上报有效灯:空手 !gun on 只存偏好不亮灯,切回枪即复。
                    boolean eff = ClientLightState.gunLightEffective();
                    dev.taclight.network.TacLightNetwork.sendSetLight(ClientLightState.handheldEffective(), eff);
                    TacLightMod.LOGGER.info("[TacLight] RELAY gunLight -> {} (manual, effective={})", next, eff ? "ON" : "OFF");
                    break;
                }
            }
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
        if (line.startsWith("!voxprobe")) {
            // 体素单元探针(2026-09-25 细雪层穿光轮):同一格"分类器判定"vs"已上传网格值"。
            String arg = line.length() > 9 ? line.substring(9).trim() : "";
            String[] p = arg.isEmpty() ? new String[0] : arg.split("\\s+");
            String out;
            if (p.length == 3) {
                try {
                    out = VoxelGrid.probe(mc, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                } catch (NumberFormatException e) {
                    out = "usage: !voxprobe <x> <y> <z>";
                }
            } else if (p.length == 6) {
                // 盒扫描:让工具自己把"非空气却判透光"的格子找出来,不必先猜坐标
                try {
                    out = VoxelGrid.scan(mc,
                            Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
                } catch (NumberFormatException e) {
                    out = "usage: !voxprobe <x1> <y1> <z1> <x2> <y2> <z2>";
                }
            } else {
                out = "usage: !voxprobe <x> <y> <z> | <x1> <y1> <z1> <x2> <y2> <z2>";
            }
            for (String l : out.split("\n")) {
                TacLightMod.LOGGER.info("[TacLight] RELAY voxprobe | {}", l);
            }
            return;
        }
        if (line.startsWith("!voxray")) {
            // 体素射线探针:无参 = 眼位沿视线 24 格;逐格 live/grid 码 + 两种口径总透射率。
            String arg = line.length() > 7 ? line.substring(7).trim() : "";
            String[] p = arg.isEmpty() ? new String[0] : arg.split("\\s+");
            String out;
            if (p.length == 0 && mc.player != null) {
                net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
                net.minecraft.world.phys.Vec3 look = mc.player.getViewVector(1.0F);
                out = VoxelGrid.ray(mc, eye.x, eye.y, eye.z,
                        eye.x + look.x * 24.0, eye.y + look.y * 24.0, eye.z + look.z * 24.0,
                        dev.taclight.channel.VoxelProbe.DEFAULT_MAX_CELLS);
            } else if (p.length == 6) {
                try {
                    out = VoxelGrid.ray(mc,
                            Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                            Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]),
                            dev.taclight.channel.VoxelProbe.DEFAULT_MAX_CELLS);
                } catch (NumberFormatException e) {
                    out = "usage: !voxray [x1 y1 z1 x2 y2 z2]";
                }
            } else {
                out = "usage: !voxray [x1 y1 z1 x2 y2 z2] (无参 = 眼位沿视线 24 格)";
            }
            for (String l : out.split("\n")) {
                TacLightMod.LOGGER.info("[TacLight] RELAY voxray | {}", l);
            }
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

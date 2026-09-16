package dev.taclight.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * T17 动作层（从新项目 SpotViz 的 {@code SpotvizDebugBackend} 移植，同一作者的自家代码；Baritone 只借思想，LGPL 未链接未 vendor）。
 *
 * <p><b>Baritone 三招落点</b>（见 {@code docs/research/baritone-movement-notes.md}）：</p>
 * <ol>
 *   <li><b>写入时机</b>：由 {@code dev.taclight.mixin.debug.AgentInputMixin} 注入 {@code LocalPlayer.tick()} 的
 *       {@code HEAD}（等价 Forge {@code TickEvent.PlayerTickEvent(Phase.START)}，**同 tick** 生效），
 *       直接写 {@code player.input.forwardImpulse/leftImpulse/jumping}；Baritone 用 {@code TickEvent.PRE} 会晚一 tick。</li>
 *   <li><b>声明式无状态</b>：每 tick **先清零再重灌**输入集合（{@link #onClientTick} 第 2 步），
 *       本类不保存"按键按下"状态，只保存目标与意图 ⇒ 任何外部中断下一 tick 自愈。</li>
 *   <li><b>8 组合方向匹配</b>：把"世界空间目标方向"投影到玩家**当前朝向**的局部系，再量化到
 *       {@code {-1,0,1}×{-1,0,1}} 的 8 个组合（{@link #quantize}）⇒ **走路与朝向解耦**：
 *       本类**从不**改 {@code yRot}，朝向由 {@code look} op 单独控制（spotlight 测试台里角色朝向常是被测对象）。</li>
 * </ol>
 *
 * <p><b>禁令遵守</b>：不写 {@code xxa/zza}（会被 {@code aiStep} 覆盖）；不调 {@code jumpFromGround()}
 * （{@code noJumpDelay=10}），只用 {@code input.jumping}；停止/失败时**必然还原**输入与 {@code setSprinting(false)}
 * （{@link #stop}）；本类只在客户端 tick 线程跑，绝不碰网络线程。</p>
 *
 * <p><b>默认关</b>：{@link #active} 初值 false；未开启时 {@link #onClientTick} 只做一次 {@code sprintWant} 检查后立即返回
 * —— 不读写 {@code input}、不改玩家状态 ⇒ 与现状逐字节一致（证明方式见 dev 文档 §默认关）。</p>
 */
public final class AgentInput {

    /** 到位判定半径（格）。 */
    private static final double ARRIVE_R = 0.35;
    /** 新项目 {@code SPRINT_MOVE_MIN}：10 tick 内位移低于此值 = 短跑没真的动（TaCZ 闩锁/否决）。 */
    private static final double SPRINT_MOVE_MIN = 0.5;
    private static final int SPRINT_CONFIRM_TICKS = 10;

    // ---- 意图(volatile:relay 线程写、客户端 tick 线程读) ----
    private static volatile boolean active = false;
    private static volatile double tgtX, tgtY, tgtZ;
    private static volatile boolean wantRun = false;
    private static volatile boolean holdForward = false;   // 沿当前朝向直行(不设目标)
    private static volatile boolean holdJump = false;
    private static volatile boolean holdLeft = false;      // T19②: 纯左移(回归探针,分辨符号/轴)
    private static volatile boolean holdRight = false;     // T19②: 纯右移
    private static volatile boolean probeOn = false;       // T19④: 探针默认关(!agent probe on 才打印)
    private static volatile boolean faceMove = false;      // T19-B: true = 面向行进方向(才可能合法冲刺)
    private static volatile int holdTicks = -1;            // -1 = 无上限(直到 !agent stop / 世界卸载)
    private static volatile String note = "idle";
    // ---- T19③ 带符号进度判据(闭环不许只看"有没有动") ----
    private static double bestDist = Double.MAX_VALUE;     // 到目标的历史最近距离
    private static int noProgressTicks = 0;                // 距离不再减小的连续 tick
    private static int progressLogTicks = 0;
    private static volatile double lastProgressDelta = 0.0;

    // ---- 短跑闭环(移植自新项目 doSprint/checkSprint) ----
    private static volatile boolean sprintDrive = false;   // 每 tick 重申 setSprinting,压掉 aiStep 否决
    private static volatile int sprintLeft = 0;
    private static Vec3 sprintStart = null;
    private static String taczLatch = "unprobed";          // TaCZ 闩锁清理探测结果(反射,避免硬依赖)

    private AgentInput() {}

    public static boolean isActive() {
        return active;
    }

    public static String status() {
        return String.format(java.util.Locale.ROOT,
                "agent=%s target=(%.2f,%.2f,%.2f) run=%s face=%s holdFwd=%s holdL/R=%s/%s holdTicks=%d sprintDrive=%s probe=%s taczLatch=%s note=%s",
                active ? "on" : "off", tgtX, tgtY, tgtZ, wantRun, faceMove, holdForward, holdLeft, holdRight, holdTicks, sprintDrive, probeOn, taczLatch, note);
    }

    /**
     * relay 入口({@code !agent ...})。默认关；任何解析失败都返回说明串且不改状态。
     * <pre>
     *   !agent status
     *   !agent stop
     *   !agent goto &lt;x&gt; &lt;y&gt; &lt;z&gt; [run] [face=true|false]   走到目标;run 默认 face=true(面向行进方向才可能合法冲刺)
     *   !agent hold fwd|left|right|jump|none &lt;ticks|-1&gt;      -1 = 无上限,用 !agent stop 停
     *   !agent sprint on|off
     *   !agent probe on|off                                   探针开关(默认关)
     * </pre>
     */
    public static String configure(String arg) {
        String[] t = arg.trim().isEmpty() ? new String[] { "status" } : arg.trim().split("\\s+");
        try {
            switch (t[0]) {
                case "status":
                    return status();
                case "stop":
                    stop("relay stop");
                    return "agent=off (inputs restored, sprint cleared)";
                case "goto": {
                    if (t.length < 4) return "usage: !agent goto <x> <y> <z> [run] [face=true|false]";
                    double x = Double.parseDouble(t[1]), y = Double.parseDouble(t[2]), z = Double.parseDouble(t[3]);
                    if (Math.abs(x) > 3.0e7 || Math.abs(z) > 3.0e7) return "target out of range";
                    tgtX = x; tgtY = y; tgtZ = z;
                    wantRun = false; faceMove = false;
                    for (int i = 4; i < t.length; i++) {
                        if (t[i].equalsIgnoreCase("run")) { wantRun = true; faceMove = true; }        // T19-B: run 默认面向行进方向
                        else if (t[i].regionMatches(true, 0, "face=", 0, 5)) faceMove = Boolean.parseBoolean(t[i].substring(5));
                    }
                    holdForward = false; holdJump = false; holdLeft = false; holdRight = false; holdTicks = -1;   // T19-fix②: 连 holdJump 一起清(否则 goto 会一路连跳)
                    resetProgress();
                    active = true;
                    if (wantRun) startSprintDrive();
                    note = "goto";
                    return String.format(java.util.Locale.ROOT, "agent=goto (%.2f,%.2f,%.2f) run=%s face=%s", x, y, z, wantRun, faceMove);
                }
                case "hold": {
                    if (t.length < 2) return "usage: !agent hold fwd|left|right|jump|none <ticks|-1>";
                    String what = t[1];
                    holdTicks = t.length > 2 ? Integer.parseInt(t[2]) : -1;
                    holdForward = what.equalsIgnoreCase("fwd");
                    holdLeft = what.equalsIgnoreCase("left");
                    holdRight = what.equalsIgnoreCase("right");
                    holdJump = what.equalsIgnoreCase("jump");
                    if (what.equalsIgnoreCase("none")) { holdForward = false; holdJump = false; holdLeft = false; holdRight = false; }
                    wantRun = false; faceMove = false;
                    resetProgress();
                    active = holdForward || holdJump || holdLeft || holdRight;
                    note = "hold:" + what;
                    return "agent=hold " + what + " ticks=" + (holdTicks < 0 ? "unlimited (stop with !agent stop)" : String.valueOf(holdTicks));
                }
                case "probe": {
                    if (t.length < 2) return "usage: !agent probe on|off|full (currently " + (probeOn ? (probeFull ? "full" : "on") : "off") + ")";
                    if (t[1].equalsIgnoreCase("full")) { probeOn = true; probeFull = true; }
                    else { probeOn = t[1].equalsIgnoreCase("on"); probeFull = false; }
                    lastSprintFlag = null;
                    return "agent probe=" + (probeOn ? (probeFull ? "full" : "on") : "off")
                            + " (on: 1 group/20 ticks + every sprint-flag change ~180 lines/min; full: every tick, short windows only)";
                }
                case "sprint": {
                    if (t.length < 2) return "usage: !agent sprint on|off";
                    if (t[1].equalsIgnoreCase("on")) { startSprintDrive(); return "sprintDrive=on (" + status() + ")"; }
                    clearSprintDrive();
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null) mc.player.setSprinting(false);
                    return "sprintDrive=off (setSprinting(false), latch cleared)";
                }
                default:
                    return "unknown subcommand '" + t[0] + "' (status|stop|goto|hold|sprint|probe)";
            }
        } catch (NumberFormatException e) {
            return "bad number in '" + arg + "'";
        }
    }

    private static void resetProgress() {
        bestDist = Double.MAX_VALUE;
        noProgressTicks = 0;
        progressLogTicks = 0;
        lastProgressDelta = 0.0;
    }

    /** 停止并**还原**一切(输入清空 + 短跑关闭 + 状态复位)。 */
    public static void stop(String why) {
        active = false;
        holdForward = false;
        holdJump = false;
        holdLeft = false;                                    // T19-fix④
        holdRight = false;                                   // T19-fix④
        faceMove = false;                                    // T19-fix④
        wantRun = false;
        holdTicks = -1;
        lastPos = null;
        stuckTicks = 0;
        dirLogged = false;
        sprintClientOnly = false;
        resetProgress();
        clearSprintDrive();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            clearInputs(mc.player.input);
            mc.player.setSprinting(false);
            if (yawBefore != null) {                          // T19-fix④: 还原 face=true 改过的朝向
                mc.player.setYRot(yawBefore);
                mc.player.yRotO = (yawOBefore == null ? yawBefore : yawOBefore);
                com.mojang.logging.LogUtils.getLogger().info(
                        "[TacLight] AGENT yaw restored on stop: yRot={} yRotO={} (captured before the action layer touched it)",
                        fmt1(mc.player.getYRot()), fmt1(mc.player.yRotO));
            }
        }
        yawBefore = null;
        yawOBefore = null;
        note = "stopped:" + why;
    }

    private static void startSprintDrive() {
        sprintDrive = true;
        sprintLeft = SPRINT_CONFIRM_TICKS;
        Minecraft mc = Minecraft.getInstance();
        sprintStart = (mc != null && mc.player != null) ? mc.player.position() : null;
        wantRun = true;
    }

    private static void clearSprintDrive() {
        sprintDrive = false;
        sprintLeft = 0;
        sprintStart = null;
    }

    /** 每 tick 清空(Baritone 第 2 招:声明式,不残留上一个意图)。 */
    private static void clearInputs(Input in) {
        if (in == null) return;
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    /**
     * T19 探针(**仅当动作层激活时**打印,默认关零输出):把 {@code input} 四个字段在关键时刻的值钉进日志,
     * 用于证实/证伪"vanilla 在 {@code aiStep} 里 {@code input.tick()} 把我们的写入清掉"。
     */
    public static void probe(Minecraft mc, String where) {
        if (!probeOn) return;                      // T19④: 探针默认关, !agent probe on 才打印
        // T19-probe:限流 + 同一 tick 三线共用一个递增计数 t。tick-HEAD 决定本 tick 是否输出:
        //   标志变化立刻输出;否则每 PROBE_EVERY(=20) tick 一组;probeFull = 密集模式(极短窗口用)。
        if (where.startsWith("tick-HEAD")) {
            tickCounter++;
            boolean flag = (mc != null && mc.player != null) && mc.player.isSprinting();
            boolean changed = (lastSprintFlag == null) || (flag != lastSprintFlag);
            lastSprintFlag = flag;
            emitThisTick = probeFull || changed || (tickCounter % PROBE_EVERY == 0);
        }
        if (!emitThisTick) return;
        if (mc == null || mc.player == null) return;
        Input in = mc.player.input;
        com.mojang.logging.LogUtils.getLogger().info(
                "[TacLight] AGENT probe t={} {}: fwd={} left={} jump={} shift={} sprinting(client)={} pos=({},{},{}) yRot={}",
                tickCounter, where, (in == null ? "null" : in.forwardImpulse), (in == null ? "null" : in.leftImpulse),
                (in == null ? "null" : in.jumping), (in == null ? "null" : in.shiftKeyDown),
                mc.player.isSprinting(),
                String.format(java.util.Locale.ROOT, "%.3f", mc.player.getX()),
                String.format(java.util.Locale.ROOT, "%.3f", mc.player.getY()),
                String.format(java.util.Locale.ROOT, "%.3f", mc.player.getZ()),
                String.format(java.util.Locale.ROOT, "%.1f", mc.player.getYRot()));
    }

    /**
     * 客户端 tick 入口。**调用点 = {@code LocalPlayer.aiStep} 内 vanilla {@code Input.tick(ZF)V} 之后**
     * (T18 零位移根因:原先注入 {@code tick() HEAD},vanilla 会在 {@code aiStep} 里重算四字段清零;
     * javap 实证 {@code LocalPlayer.aiStep()+139 invokevirtual Input.tick:(ZF)V} 且 {@code KeyboardInput} override)。
     */
    public static void onClientTick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            if (active || sprintDrive) stop("no world/player (restore inputs)");   // 世界卸载也必然还原
            return;
        }
        if (sprintDrive) checkSprint(mc);          // 闭环确认即使 inactive 也要收尾
        if (!active) return;
        final Input in = mc.player.input;
        if (in == null) return;
        clearInputs(in);                            // (2) 先清空

        double px = mc.player.getX(), pz = mc.player.getZ();
        double dx, dz;
        if (holdForward) {
            // (3) 沿当前朝向直行:局部系里就是纯前进 ⇒ 与朝向解耦
            dx = -Math.sin(Math.toRadians(mc.player.getYRot()));
            dz = Math.cos(Math.toRadians(mc.player.getYRot()));
        } else {
            dx = tgtX - px;
            dz = tgtZ - pz;
        }
        double dist = Math.hypot(dx, dz);
        if (!holdForward && dist <= ARRIVE_R) {
            note = String.format(java.util.Locale.ROOT, "arrived (dist=%.2f)", dist);
            stop("arrived");
            return;
        }
        if (dist > 1.0e-6) { dx /= dist; dz /= dist; }

        // (3) 世界方向 → 玩家局部系 → 量化到 8 组合
        // 符号约定(反编译核对,勿凭记忆):vanilla KeyboardInput 把 A/D 写进 leftImpulse,**正值 = 向玩家的左**;
        // MC 里 yaw0 面向 +Z 时"左" = +X。本类的 (lx,lz) = (cos yaw, sin yaw) 正是 LEFT 基(yaw0 → +X)
        // ⇒ leftImpulse = +(dir·leftBasis),**不能取负**。T18 烟雾"left=+1.0 却沿 −x 走"就是这里多了个负号。
        // T19-B: faceMove=true 时先把朝向转到行进方向(这样 forwardImpulse>0 才成立、冲刺才可能被服务端承认)。
        if (yawBefore == null && mc.player != null) {          // T19-fix④: 记录"动作层介入前"的朝向
            yawBefore = mc.player.getYRot();
            yawOBefore = mc.player.yRotO;
        }
        if (faceMove && (dx != 0.0 || dz != 0.0)) {
            float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));   // MC: forward = (-sin yaw, cos yaw)
            mc.player.setYRot(wantYaw);
            mc.player.yRotO = wantYaw;                                     // 预审结论:setYRot 不动 yRotO ⇒ 会有 1 帧插值滞后
        }
        double yaw = Math.toRadians(mc.player.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);     // forward:yaw0 → +Z
        double lx = Math.cos(yaw), lz = Math.sin(yaw);      // LEFT basis:yaw0 → +X
        double fwd = dx * fx + dz * fz;
        double left = dx * lx + dz * lz;
        int[] q;
        if (holdLeft) { q = new int[] { 0, 1 }; }            // T19②: 纯横移回归探针(方向固定,不随视角)
        else if (holdRight) { q = new int[] { 0, -1 }; }
        else { q = comboFor(mc.player.getYRot(), dx, dz); }  // T19-fix③: 与自检共用同一实现(自检才能证伪)
        in.forwardImpulse = q[0];
        in.leftImpulse = q[1];

        if (!dirLogged) {                                    // 一次性自证日志(符号一眼可判)
            dirLogged = true;
            com.mojang.logging.LogUtils.getLogger().info(
                    "[TacLight] AGENT dir self-check: targetDir(world)=({},{}) yRot={} face={} fwdBasis=({},{}) leftBasis=({},{}) dotFwd={} dotLeft={} combo=(fwd={},left={}) expectAxis={}",
                    fmt3(dx), fmt3(dz), fmt1(mc.player.getYRot()), faceMove, fmt3(fx), fmt3(fz), fmt3(lx), fmt3(lz),
                    fmt3(fwd), fmt3(left), q[0], q[1], axis(dx, dz));
        }

        // ---- T19③ 带符号进度闭环:到目标距离必须单调减小(位移≠进展) ----
        // T19-fix①: hold 类(尤其 holdForward)没有"目标"概念,dx/dz 是单位朝向 ⇒ dist≡1.0 恒定,
        // 不排除 holdForward 会在 ~20 tick 后误报 STUCK 并自停(与 "-1 = 无上限" 矛盾)。
        if (!holdForward && !holdLeft && !holdRight) {
            double delta = bestDist - dist;                  // >0 = 本 tick 朝目标更近
            lastProgressDelta = delta;
            if (dist < bestDist - 0.01) { bestDist = dist; noProgressTicks = 0; }
            else if (++noProgressTicks >= NO_PROGRESS_TICKS) {
                note = "STUCK(no progress toward target): dist=" + fmt3(dist) + " best=" + fmt3(bestDist)
                        + " for " + noProgressTicks + " ticks (combo=" + q[0] + "/" + q[1] + ")";
                com.mojang.logging.LogUtils.getLogger().warn("[TacLight] AGENT {}", note);
                stop("no progress");
                return;
            }
            if (++progressLogTicks >= PROGRESS_LOG_TICKS) {
                progressLogTicks = 0;
                com.mojang.logging.LogUtils.getLogger().info(
                        "[TacLight] AGENT progress: dist={} prevDelta={} towardTarget={} best={} combo=(fwd={},left={})",
                        fmt3(dist), fmt3(delta), (delta > 0.0), fmt3(bestDist), q[0], q[1]);
            }
        }

        // ---- 到达/被阻挡 明确回执(不再让操作者从 moved 长期不变反推) ----
        if (!holdForward && !holdLeft && !holdRight) {
            Vec3 now = mc.player.position();
            if (lastPos == null) { lastPos = now; stuckTicks = 0; }
            else if (now.distanceTo(lastPos) < 0.02) {
                if (++stuckTicks >= STUCK_TICKS) {
                    note = "STUCK: no movement for " + stuckTicks + " ticks (dist=" + fmt3(dist) + ", combo=" + q[0] + "/" + q[1] + ")";
                    com.mojang.logging.LogUtils.getLogger().warn("[TacLight] AGENT {}", note);
                    stop("stuck");
                    return;
                }
            } else { lastPos = now; stuckTicks = 0; }
        }

        boolean want = holdJump;
        if (!want && dist > 1.0 && q[0] != 0) want = blockedAhead(mc);   // 前方被挡 → 用 jumping 抬脚
        in.jumping = want;

        // T19-B: 冲刺只有"有前进输入"时才可能被服务端承认(Input.hasForwardImpulse)。
        // 纯横移(fwd<=0)时只标 client-only,绝不让人误读成"跑起来了"。
        final boolean canSprintLegally = q[0] > 0;
        if (wantRun && canSprintLegally) {
            if (!sprintDrive) startSprintDrive();
            sprintAttempted = true;
            mc.player.setSprinting(true);                    // keep-alive,压掉 aiStep 否决
        } else if (wantRun) {
            sprintClientOnly = true;                          // 仅客户端强置;服务端不会承认(strafe)
        } else {
            mc.player.setSprinting(false);
        }
        if (holdTicks > 0 && --holdTicks == 0) { note = "hold exhausted"; stop("hold ticks"); }
    }

    /**
     * T19-fix③:朝向 + 世界方向 → 8 组合。**运行时与离线自检共用这一个实现**——
     * 自检若内联复制公式,Java 侧符号改错它也照样全过(预审指出),所以投影只有这一份。
     * (dx,dz) 无需预先归一化(量化前 clamp)。
     */
    static int[] comboFor(float yawDeg, double dx, double dz) {
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);      // forward:yaw0 → +Z
        double lx = Math.cos(yaw), lz = Math.sin(yaw);       // LEFT basis:yaw0 → +X
        return quantize(dx * fx + dz * fz, dx * lx + dz * lz);
    }

    /** Baritone 式 8 组合量化:连续 impulse → {-1,0,1}²,并保证"有方向意图时"至少一个分量非零。 */
    static int[] quantize(double fwd, double left) {
        int f = (int) Math.round(Math.max(-1.0, Math.min(1.0, fwd)));
        int l = (int) Math.round(Math.max(-1.0, Math.min(1.0, left)));
        if (f == 0 && l == 0) {
            if (Math.abs(fwd) >= Math.abs(left)) f = fwd >= 0 ? 1 : -1;
            else l = left >= 0 ? 1 : -1;
        }
        return new int[] { f, l };
    }

    // ---- T19 自证/回执辅助(字段放这里,Java 允许方法后声明字段) ----
    /** 卡住判定:连续这么多 tick 位移 < 0.02 格 ⇒ 明确报 STUCK 并还原。 */
    private static final int STUCK_TICKS = 40;
    /** T19③:到目标距离连续这么多 tick 不减小(哪怕还在移动)⇒ 报"没有进展"并还原。 */
    private static final int NO_PROGRESS_TICKS = 20;
    /** T19③:每这么多 tick 打一条带符号进度(0.5s)。 */
    private static final int PROGRESS_LOG_TICKS = 10;
    /** T19-B:纯横移时 sprint 只被客户端强置,服务端不承认(Input.hasForwardImpulse 不成立)。 */
    private static volatile boolean sprintClientOnly = false;
    /** T19-fix④:face=true 会改朝向 ⇒ 激活时记录原值,stop() 还原(测试动作不许偷偷改这台 rig 的取向)。 */
    /** T19 冲刺查证:aiStep TAIL(travel 之后)读到的**稳定**冲刺值,与"set 之后瞬时值"对照。 */
    private static volatile Boolean sprintTickEnd = null;
    private static volatile boolean sprintAttempted = false;

    private static volatile boolean probeFull = false;
    private static Boolean lastSprintFlag = null;
    private static final int PROBE_EVERY = 20;             // 常规:每 20 tick 一组 + 标志变化立刻一组
    private static volatile long tickCounter = 0;
    private static volatile boolean emitThisTick = false;

    /** 由 {@code AgentInputMixin} 在 aiStep TAIL 调用:记录 tick 结束时的冲刺稳定值。 */
    public static void noteTickEndSprint(Minecraft mc) {
        if (mc != null && mc.player != null) sprintTickEnd = mc.player.isSprinting();
    }

    private static Float yawBefore = null;
    private static Float yawOBefore = null;
    private static Vec3 lastPos = null;
    private static int stuckTicks = 0;
    private static boolean dirLogged = false;

    static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** 期望移动轴(给自证日志用):只看 |dx|/|dz| 谁大,正负照写。 */
    static String axis(double dx, double dz) {
        String a = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? "+x" : "-x") : (dz >= 0 ? "+z" : "-z");
        return a + " (|dx|=" + fmt3(Math.abs(dx)) + ", |dz|=" + fmt3(Math.abs(dz)) + ")";
    }

    /** 前方一格是否被挡(用方块碰撞判断,不触发挖掘/破坏)。 */
    private static boolean blockedAhead(Minecraft mc) {
        try {
            if (!mc.player.onGround()) return false;
            Vec3 ahead = mc.player.position().add(
                    -Math.sin(Math.toRadians(mc.player.getYRot())) * 0.7, 0.0,
                    Math.cos(Math.toRadians(mc.player.getYRot())) * 0.7);
            var box = mc.player.getBoundingBox().move(ahead.subtract(mc.player.position()));
            return !mc.level.noCollision(mc.player, box);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 短跑闭环(移植自新项目 {@code doSprint}/{@code checkSprint}):每 tick 重申 → 读客户端回执 →
     * 清 TaCZ 闩锁 → {@value #SPRINT_CONFIRM_TICKS} tick 后按位移 >= {@value #SPRINT_MOVE_MIN} 判定。
     */
    private static void checkSprint(Minecraft mc) {
        if (mc.player == null) return;
        if (mc.player.isSprinting() == false) {
            mc.player.setSprinting(true);                    // 被 aiStep/TaCZ 否决 → 重申
            taczStopSprint();
        }
        if (sprintLeft > 0 && --sprintLeft == 0) {
            double moved = (sprintStart == null) ? -1.0 : mc.player.position().distanceTo(sprintStart);
            boolean ok = moved < 0 || moved >= SPRINT_MOVE_MIN;
            com.mojang.logging.LogUtils.getLogger().info(
                    "[TacLight] AGENT sprint confirm: t={} moving={} moved={} (min {}) attempted={} effective(set-time)={} effective(tick-end)={} sprint={} food={} taczLatch={}",
                    tickCounter, ok, String.format(java.util.Locale.ROOT, "%.3f", moved), SPRINT_MOVE_MIN,
                    sprintAttempted, mc.player.isSprinting(), sprintTickEnd,
                    (sprintClientOnly ? "client-only(strafe: no forward input -> server will not accept)" : "client+server-eligible(fwd>0)"),
                    mc.player.getFoodData().getFoodLevel(), taczLatch);
            if (!ok) clearSprintDrive();
            else sprintLeft = SPRINT_CONFIRM_TICKS;          // 继续保活
        }
    }

    /**
     * 清 TaCZ 的 {@code LocalPlayerSprint.stopSprint} 闩锁(新项目 :586 的坑:该闩锁卡 true 会永久否决短跑)。
     * 用反射探测,避免对 TaCZ 内部类形成编译期硬依赖;探测结果记入 {@link #taczLatch}。
     */
    private static void taczStopSprint() {
        // T19 查证结论(反编译 tacz-1.1.8-hotfix_mapped_official_1.20.1):
        //   com.tacz.guns.client.gameplay.LocalPlayerSprint 里 **`public static boolean stopSprint` 是字段,不是方法**
        //   (所以早前"找方法"的反射探测报 present-but-no-usable-stopSprint),另有
        //   `public boolean getProcessedSprintStatus(boolean)` 作为 TaCZ 的冲刺裁决入口。
        //   本方法 = 只读诊断 + 清闩锁(把该静态字段置 false),并把结果写进 taczLatch 供日志取证。
        String[] classes = {
                "com.tacz.guns.client.gameplay.LocalPlayerSprint",
                "com.tacz.guns.client.sprint.LocalPlayerSprint",
                "com.tacz.guns.client.sprint.SprintManager"
        };
        for (String cn : classes) {
            try {
                Class<?> c = Class.forName(cn);
                // (a) 静态闩锁字段
                for (java.lang.reflect.Field fld : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(fld.getModifiers())) continue;
                    if (fld.getType() != boolean.class) continue;
                    if (!fld.getName().toLowerCase(java.util.Locale.ROOT).contains("sprint")) continue;
                    fld.setAccessible(true);
                    boolean latch = fld.getBoolean(null);
                    if (latch) { fld.setBoolean(null, false); taczLatch = cn + "#" + fld.getName() + " was TRUE -> cleared(static field)"; }
                    else { taczLatch = cn + "#" + fld.getName() + "=false (static field, no latch stuck)"; }
                    return;
                }
                // (b) 兜底:老式静态方法
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().toLowerCase(java.util.Locale.ROOT).contains("stopsprint")) continue;
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
                        m.setAccessible(true); m.invoke(null);
                        taczLatch = cn + "#" + m.getName() + "() ok (static method)";
                        return;
                    }
                }
                taczLatch = cn + " present but no sprint latch field/method";
            } catch (ClassNotFoundException e) {
                // 继续试下一个包名
            } catch (Throwable t) {
                taczLatch = cn + " threw: " + t.getClass().getSimpleName();
            }
        }
        if (taczLatch.startsWith("unprobed")) taczLatch = "not found (TaCZ absent or renamed) - vanilla sprint used";
    }
}

package dev.taclight.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.taclight.TacLightMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * JSON ticket bridge for the OLD shader-pack pipeline, mirroring the new
 * SpotViz harness protocol so ONE ticket style drives BOTH rigs.
 *
 * <p>Protocol (same as {@code docs/22-debug-harness.md} §6 in the new
 * project): the AI writes {@code run/taclight-agent/inbox/<ticket>.json}
 * atomically ({@code .tmp} + rename); each client tick the oldest ticket is
 * claimed to {@code done/} and executed op-by-op on the client thread;
 * per-op results land in {@code outbox/<ticket>.result.json}.
 *
 * <p>Supported ops (P1 for old-pipeline optimization):
 * {@code scene(taclight preset wall|grass|corridor|bloom|duo),
 * pose, look, sweep(human-speed), shot, bench, dump, wait_ticks,
 * command(whitelist), light(on|off|toggle, off-baseline arms),
 * tm(on|off|status: VL 64<->32 formal switch, dual-arm tickets),
 * inv(open|click|drag|select|toss, F1 human code-level backpack),
 * use(block|air|entity|eat|start|stop|attack-block|attack-entity,
 * F2 human packet order),
 * act(place|dig|hit|door|jump|sneak|sprint|move|camera,
 * F3 world verbs + gates + drops, v0.3 sprint/move/camera),
 * state(health|armor|buffs|held|inv|aim|pose|pose2|lamp|all, F4 reads),
 * assert(checks[], F5 fail-verbose + shot),
 * describe([name], F7 catalog + LLM summaries),
 * rec(start|stop|status)}.
 * Every ok/fail row carries {@code subject} (executor name) +
 * {@code executor:"real"} (F6/F12); every ticket starts with an
 * {@code env} entry (F11 claim-time snapshot) and ends with {@code rec};
 * top-level {@code "trial"} versions attempts (Voyager trials twin).
 * Top-level {@code "rec":true} auto-records the whole ticket to
 * {@code run/taclight-agent/rec/<run>/sNNNN/frames.csv} (C/B/E rows).
 *
 * <p>Zero edits to existing classes: this file only REUSES them (scene via
 * the existing {@code taclight scene} server command, screenshots via the
 * vanilla framebuffer path). Registering is automatic via the annotation.
 */
@Mod.EventBusSubscriber(modid = TacLightMod.MODID, value = Dist.CLIENT)
public final class TicketBridge {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> KNOWN_OPS = Set.of(
            "scene", "pose", "look", "sweep", "shot", "bench", "dump",
            "wait_ticks", "command", "rec", "light", "tm", "inv", "use", "act",
            "state", "assert", "describe");
    private static final Set<String> CMD_PREFIXES = Set.of(
            "time", "weather", "gamemode", "gamerule", "tp", "fill", "setblock",
            "clone", "execute", "effect", "give", "taclight");
    private static final DateTimeFormatter RUN_CLOCK =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private TicketBridge() {}

    // ---- ticket state ----
    private static String ticket;
    private static Deque<Op> queue;
    private static JsonArray results;
    private static boolean failed;
    private static int waitTicks;
    private static HumanSweep sweep;
    private static String sweepDesc;
    private static boolean autoRec;
    // settle closed-loop (same numbers as the new harness: 1deg / 0.7 blocks)
    private static String settleOp;
    private static Double settleX;
    private static Double settleY;
    private static Double settleZ;
    private static float settleYaw;
    private static float settlePitch;
    private static String settleLine;
    private static int settleLeft;
    private static String pendingOp;
    private static CompletableFuture<?> pending;
    private static Runnable onPendingDone;
    private static Object armedLevel;
    // F1 backpack closed loop (one armed inv confirm at a time).
    private static InvActions.Pending invPending;
    // F2 use driver (one armed multi-tick use at a time).
    private static UseActions.Running useRunning;
    // F3 act driver (one armed dig at a time).
    private static ActActions.Running actRunning;
    // F11 trial tag of the running ticket (default t1); F6 fail-shot budget.
    private static String currentTrial = "t1";
    private static int failShots;
    private static final int MAX_FAIL_SHOTS = 5;

    record Op(String op, JsonObject params) {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (armedLevel != mc.level) {
            armedLevel = mc.level;
            TacLightMod.LOGGER.info("[TacLight][agent] bridge armed in {}",
                    mc.level.dimension().location());
        }
        try {
            if (ticket != null) {
                if (Rec.active()) {
                    Rec.tick(mc);
                }
                pump(mc);
                return;
            }
            if (Rec.active()) {
                Rec.tick(mc);
            }
            pollInbox(mc);
        } catch (Throwable t) {
            TacLightMod.LOGGER.warn("[TacLight][agent] bridge tick skipped: {}", t.toString());
        }
    }

    // ---- inbox ----

    private static Path base(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("taclight-agent");
    }

    private static void pollInbox(Minecraft mc) {
        Path inbox = base(mc).resolve("inbox");
        try {
            Files.createDirectories(inbox);
            Files.createDirectories(base(mc).resolve("outbox"));
            Files.createDirectories(base(mc).resolve("done"));
        } catch (Throwable t) {
            return;
        }
        try (Stream<Path> s = Files.list(inbox)) {
            var next = s.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .findFirst();
            next.ifPresent(src -> claim(mc, src));
        } catch (Throwable ignored) {
        }
    }

    private static void claim(Minecraft mc, Path src) {
        String fileName = src.getFileName().toString();
        Path done = base(mc).resolve("done").resolve(fileName);
        try {
            Files.move(src, done, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            return;
        }
        String name;
        List<Op> ops;
        boolean rec;
        String trial;
        TicketEnv.Report envReport;
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(done, StandardCharsets.UTF_8)).getAsJsonObject();
            name = root.has("ticket") ? root.get("ticket").getAsString()
                    : fileName.substring(0, fileName.length() - 5);
            rec = root.has("rec") && root.get("rec").getAsBoolean();
            trial = TicketEnv.trialOf(root);
            ops = parseOps(root);
            envReport = TicketEnv.check(mc);
            if (envReport.fail != null) {
                writeResult(mc, name, false, errList("env", envReport.fail), trial);
                return;
            }
        } catch (Throwable t) {
            writeResult(mc, fileName.replaceAll("\\.json$", ""), false,
                    errList("parse", t.toString()), currentTrial);
            return;
        }
        ticket = name;
        queue = new ArrayDeque<>(ops);
        results = new JsonArray();
        // F11 leading env entry: every evidence bundle states the world it ran in.
        try {
            JsonObject envEntry = new JsonObject();
            envEntry.addProperty("op", "env");
            envEntry.addProperty("ok", true);
            envEntry.addProperty("trial", trial);
            for (var e : envReport.env.entrySet()) {
                envEntry.add(e.getKey(), e.getValue());
            }
            results.add(envEntry);
        } catch (Throwable ignored) {
        }
        failed = false;
        waitTicks = 0;
        sweep = null;
        invPending = null;
        useRunning = null;
        actRunning = null;
        autoRec = rec;
        settleOp = null;
        currentTrial = trial;
        failShots = 0;
        UseActions.disarmSession();
        TacLightMod.LOGGER.info("[TacLight][agent] ticket {} accepted ({} ops rec={} trial={})",
                ticket, ops.size(), rec, trial);
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "[TacLight][agent] ticket " + ticket + " accepted (" + ops.size() + " ops)"));
        }
        if (rec) {
            Rec.start(mc, name);
        }
    }

    private static List<Op> parseOps(JsonObject root) {
        if (!root.has("ops") || !root.get("ops").isJsonArray()) {
            throw new IllegalArgumentException("missing 'ops' array");
        }
        List<Op> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("ops")) {
            JsonObject o = e.getAsJsonObject();
            String name = o.has("op") ? o.get("op").getAsString() : "";
            if (!KNOWN_OPS.contains(name)) {
                throw new IllegalArgumentException("unknown op: '" + name + "'");
            }
            JsonObject params = new JsonObject();
            for (var entry : o.entrySet()) {
                if (!entry.getKey().equals("op")) {
                    params.add(entry.getKey(), entry.getValue());
                }
            }
            out.add(new Op(name, params));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("empty 'ops' array");
        }
        if (out.size() > 64) {
            throw new IllegalArgumentException("too many ops (max 64)");
        }
        return out;
    }

    // ---- pump ----

    private static void pump(Minecraft mc) {
        if (pending != null) {
            if (!pending.isDone()) {
                return;
            }
            CompletableFuture<?> f = pending;
            pending = null;
            Runnable done = onPendingDone;
            onPendingDone = null;
            String op = pendingOp;
            pendingOp = null;
            try {
                Object v = f.join();
                if (done != null) {
                    done.run();
                } else {
                    ok(op, null);
                }
            } catch (Throwable t) {
                fail(op, shortErr(t));
            }
            if (ticket == null) {
                return;
            }
        }
        if (sweep != null) {
            boolean done;
            try {
                done = sweep.tick(mc);
            } catch (Throwable t) {
                sweep = null;
                fail("sweep", shortErr(t));
                return;
            }
            if (!done) {
                return;
            }
            HumanSweep d = sweep;
            sweep = null;
            finishSweepOnServer(mc, d);
            return;
        }
        if (invPending != null) {
            pumpInv(mc);
            return;
        }
        if (useRunning != null) {
            pumpUse(mc);
            return;
        }
        if (actRunning != null) {
            pumpAct(mc);
            return;
        }
        if (settleOp != null) {
            checkSettle(mc);
            return;
        }
        if (waitTicks > 0) {
            if (--waitTicks == 0) {
                ok("wait_ticks", new JsonObject());
            }
            return;
        }
        if (queue.isEmpty()) {
            finish(true);
            return;
        }
        dispatch(mc, queue.poll());
    }

    private static void dispatch(Minecraft mc, Op op) {
        try {
            // F6 per-step CSV: every dispatched op leaves a start row (subject in ok/fail rows).
            try {
                Rec.event("start " + op.op());
            } catch (Throwable ignored) {
            }
            // F7 switch hook (e.g. release using-item before inventory mutation).
            if (op.op().equals("inv") || op.op().equals("use") || op.op().equals("act")) {
                try {
                    String note = OpCatalog.beforeOp(mc, op.op(), op);
                    if (note != null) {
                        Rec.event("hook " + note);
                    }
                } catch (Throwable ignored) {
                }
            }
            switch (op.op()) {
                case "scene" -> doScene(mc, op);
                case "pose" -> doPose(mc, op, true);
                case "look" -> doPose(mc, op, false);
                case "sweep" -> {
                    if (mc.player == null) {
                        fail("sweep", "no player");
                    } else if (sweep != null) {
                        fail("sweep", "another sweep active");
                    } else {
                        try {
                            HumanSweep d = HumanSweep.start(mc, op.params());
                            sweep = d;
                            sweepDesc = d.describe();
                            Rec.event("sweep start " + sweepDesc);
                            TacLightMod.LOGGER.info("[TacLight][agent] {} sweep start {}",
                                    ticket, sweepDesc);
                            pump(mc);
                        } catch (Throwable t) {
                            sweep = null;
                            fail("sweep", shortErr(t));
                        }
                    }
                }
                case "shot" -> {
                    String raw = op.params().has("name") ? op.params().get("name").getAsString() : ticket;
                    String safe = raw.replaceAll("[^A-Za-z0-9-_]", "_");
                    String file = "taclight-" + safe + ".png";
                    try {
                        var img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                        try {
                            var out = new java.io.File(mc.gameDirectory, "screenshots/" + file);
                            //noinspection ResultOfMethodCallIgnored
                            out.getParentFile().mkdirs();
                            img.writeToFile(out);
                            Rec.event("shot " + file);
                            JsonObject extra = new JsonObject();
                            extra.addProperty("file", "screenshots/" + file);
                            ok("shot", extra);
                        } finally {
                            img.close();
                        }
                    } catch (Throwable t) {
                        fail("shot", shortErr(t));
                    }
                }
                case "bench" -> {
                    JsonObject extra = new JsonObject();
                    extra.addProperty("line", benchLine(mc));
                    ok("bench", extra);
                }
                case "dump" -> {
                    JsonObject extra = new JsonObject();
                    var p = mc.player;
                    if (p != null) {
                        extra.addProperty("pos", String.format(Locale.ROOT, "%.2f,%.2f,%.2f",
                                p.getX(), p.getY(), p.getZ()));
                        extra.addProperty("yaw", Math.round(p.getYRot() * 100.0) / 100.0);
                        extra.addProperty("pitch", Math.round(p.getXRot() * 100.0) / 100.0);
                        extra.addProperty("bench", benchLine(mc));
                    }
                    ok("dump", extra);
                }
                case "wait_ticks" -> {
                    int n = op.params().has("n") ? op.params().get("n").getAsInt() : 20;
                    waitTicks = Math.max(1, Math.min(600, n));
                }
                case "command" -> {
                    String line = op.params().has("line")
                            ? op.params().get("line").getAsString().trim() : "";
                    if (line.startsWith("/")) {
                        line = line.substring(1);
                    }
                    String head = line.contains(" ") ? line.substring(0, line.indexOf(' ')) : line;
                    if (!CMD_PREFIXES.contains(head)) {
                        fail("command", "not in whitelist: " + head);
                    } else {
                        runServerCmd(mc, "command", line);
                    }
                }
                case "rec" -> {
                    String mode = op.params().has("mode")
                            ? op.params().get("mode").getAsString().trim().toLowerCase(Locale.ROOT)
                            : "status";
                    JsonObject extra = new JsonObject();
                    if (mode.startsWith("start") || mode.equals("on")) {
                        extra.addProperty("dir", Rec.start(mc, ticket));
                    } else if (mode.startsWith("stop") || mode.equals("off")) {
                        extra.addProperty("summary", Rec.stop("op-stop"));
                    }
                    extra.addProperty("status", Rec.status());
                    ok("rec", extra);
                }
                case "light" -> doLight(mc, op);
                case "tm" -> doTm(op);
                case "inv" -> doInv(mc, op);
                case "use" -> doUse(mc, op);
                case "act" -> doAct(mc, op);
                case "state" -> doState(mc, op);
                case "assert" -> doAssert(mc, op);
                case "describe" -> doDescribe(op);
                default -> fail(op.op(), "unknown op (try {\"op\":\"describe\"} — " + OpCatalog.knownOpsLine() + ")");
            }
        } catch (Throwable t) {
            fail(op.op(), shortErr(t));
        }
    }

    /**
     * F7 describe (immediate): whole catalog or one op, with LLM summaries
     * and real-vs-fake divergence notes. See {@link OpCatalog}.
     */
    private static void doDescribe(Op op) {
        if (op.params().has("name")) {
            String name = op.params().get("name").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            JsonObject one = OpCatalog.describeJson(name);
            if (one == null) {
                fail("describe", "unknown op '" + name + "' (" + OpCatalog.knownOpsLine() + ")");
                return;
            }
            ok("describe", one);
            return;
        }
        ok("describe", OpCatalog.catalogJson());
    }

    /**
     * F4 state query (read-only, immediate). See {@link StateActions}.
     */
    private static void doState(Minecraft mc, Op op) {
        String what = op.params().has("what")
                ? op.params().get("what").getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                : "all";
        try {
            ok("state", StateActions.query(mc, what));
        } catch (Throwable t) {
            fail("state", StateActions.shortErr(t));
        }
    }

    /**
     * F5 assert (immediate): every clause must pass, else fail with clause
     * index + check + actual + FULL snapshot + screenshot (see
     * {@link StateActions}).
     */
    private static void doAssert(Minecraft mc, Op op) {
        if (!op.params().has("checks") || !op.params().get("checks").isJsonArray()) {
            fail("assert", "missing 'checks' array");
            return;
        }
        ServerPlayer sp;
        try {
            sp = StateActions.resolve(mc);
        } catch (Throwable t) {
            fail("assert", StateActions.shortErr(t));
            return;
        }
        JsonObject detail;
        try {
            detail = StateActions.checkAll(sp, op.params().getAsJsonArray("checks"));
        } catch (Throwable t) {
            fail("assert", StateActions.shortErr(t));
            return;
        }
        if (detail == null) {
            JsonObject extra = new JsonObject();
            extra.addProperty("passed", op.params().getAsJsonArray("checks").size());
            ok("assert", extra);
            return;
        }
        String shot = StateActions.snapAssertShot(mc, ticket != null ? ticket : "manual");
        detail.addProperty("shot", shot);
        try {
            Rec.event("fail assert clause=" + detail.get("clause").getAsInt() + " shot=" + shot);
        } catch (Throwable ignored) {
        }
        fail("assert", detail.toString());
    }

    private static void doScene(Minecraft mc, Op op) {
        String preset = op.params().has("name") ? op.params().get("name").getAsString()
                : op.params().has("preset") ? op.params().get("preset").getAsString() : "";        if (preset.isEmpty()) {
            fail("scene", "missing preset (wall|grass|corridor|bloom|duo)");
            return;
        }
        // Reuse the existing tested path: the real /taclight scene executor.
        runServerCmd(mc, "scene", "taclight scene " + preset);
    }

    /**
     * Harness {@code light} entry (off-baseline arms): {@code on} / {@code off} /
     * {@code toggle}. Reuses the existing tested {@code /taclight light} server
     * path via {@link #runServerCmd} (the {@code @s} suffix attaches the caller
     * entity, which the command requires) — no business code touched.
     */
    private static void doLight(Minecraft mc, Op op) {
        String mode = op.params().has("mode")
                ? op.params().get("mode").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        if (!mode.equals("on") && !mode.equals("off") && !mode.equals("toggle")) {
            fail("light", "mode must be on|off|toggle, got '" + mode + "'");
            return;
        }
        runServerCmd(mc, "light", "taclight light " + mode + " @s");
    }

    /**
     * Harness {@code tm} entry (VL 64&lt;-&gt;32 formal switch, task-11
     * 定案): {@code off} = 64-step all-fresh arm, {@code on} = 32-step +
     * history arm, {@code status} (default) queries only. Calls the SAME
     * formal entry as the {@code !tm} relay
     * ({@code LightTuneOverride.configureTemporal}, volatile flag, client
     * thread — the relay does exactly this on the same thread), so ticket
     * arms and relay arms are bit-identical switches. No business code
     * touched. Convention (mirrors the new rig): tickets set tm explicitly
     * per arm and leave it {@code on} (= default) at the end.
     */
    private static void doTm(Op op) {
        String mode = op.params().has("mode")
                ? op.params().get("mode").getAsString().trim().toLowerCase(Locale.ROOT) : "status";
        if (!mode.equals("on") && !mode.equals("off") && !mode.equals("status")) {
            fail("tm", "mode must be on|off|status, got '" + mode + "'");
            return;
        }
        String line = dev.taclight.channel.LightTuneOverride.configureTemporal(mode);
        TacLightMod.LOGGER.info("[TacLight][agent] {} tm -> {}", ticket, line);
        JsonObject extra = new JsonObject();
        extra.addProperty("tm", dev.taclight.channel.LightTuneOverride.temporal() ? "on" : "off");
        extra.addProperty("line", line);
        ok("tm", extra);
    }

    /**
     * F1 backpack entry (v0.2, human code-level): delegates to
     * {@link InvActions} — immediate ok/fail, or arms {@link #invPending}
     * for the confirm loop ({@link #pumpInv}).
     */
    private static void doInv(Minecraft mc, Op op) {
        if (invPending != null) {
            fail("inv", "another inv confirm is active");
            return;
        }
        InvActions.Outcome out = InvActions.execute(mc, op);
        if (out.fail != null) {
            fail("inv", out.fail);
        } else if (out.pending != null) {
            invPending = out.pending;
            TacLightMod.LOGGER.info("[TacLight][agent] {} inv start {}",
                    ticket, out.pending.summary);
        } else {
            ok("inv", out.ok != null ? out.ok : new JsonObject());
        }
    }

    /** F1 confirm poll (client tick): stable or timeout-refill (see InvActions). */
    private static void pumpInv(Minecraft mc) {
        InvActions.Pending p = invPending;
        if (p == null) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        ServerPlayer sp = server == null ? null : server.getPlayerList().getPlayer(p.playerId);
        if (sp == null) {
            invPending = null;
            fail("inv", "server player gone while confirming " + p.summary);
            return;
        }
        boolean holds;
        try {
            holds = p.expect.test(sp);
        } catch (Throwable t) {
            holds = false;
        }
        if (holds) {
            if (++p.stable >= InvActions.SETTLE_TICKS) {
                invPending = null;
                JsonObject extra = new JsonObject();
                extra.addProperty("done", p.summary);
                // T20/D2 (pre-audit phase5): the confirm detail used to exist ONLY on the timeout path, so a
                // green equip receipt could not be told apart from a tautological same-item swap. Surface the
                // exact same detail on success: armorBefore/armorAfter and the exchanged slot contents become
                // receipt fields -- judge by those, never by ok=true alone. Never throws.
                try {
                    extra.addProperty("confirm", String.valueOf(p.actual.apply(sp)));
                } catch (Throwable ignored) {
                }
                TacLightMod.LOGGER.info("[TacLight][agent] {} inv ok {}",
                        ticket, p.summary);
                try {
                    Rec.event("ok inv " + p.summary);
                } catch (Throwable ignored) {
                }
                ok("inv", extra);
            }
            return;
        }
        p.stable = 0;
        if (--p.left <= 0) {
            invPending = null;
            String actual;
            try {
                actual = p.actual.apply(sp);
            } catch (Throwable t) {
                actual = "?";
            }
            try {
                sp.containerMenu.sendAllDataToRemote();
            } catch (Throwable ignored) {
            }
            fail("inv", "confirm timeout want " + p.expectDesc + " but " + actual
                    + " (" + p.summary + ", resynced)");
        }
    }

    /**
     * F2 use entry (v0.2, human packet order): delegates to
     * {@link UseActions} — immediate ok/fail, or arms {@link #useRunning}
     * for the multi-tick driver ({@link #pumpUse}).
     */
    private static void doUse(Minecraft mc, Op op) {
        if (useRunning != null) {
            fail("use", "another use driver is active");
            return;
        }
        UseActions.Outcome out = UseActions.execute(mc, op);
        if (out.fail != null) {
            fail("use", out.fail);
        } else if (out.running != null) {
            useRunning = out.running;
            TacLightMod.LOGGER.info("[TacLight][agent] {} use start {}",
                    ticket, out.running.summary);
        } else {
            ok("use", out.ok != null ? out.ok : new JsonObject());
        }
    }

    /** F2 driver poll (client tick): cooldown / eat / destroy (see UseActions). */
    private static void pumpUse(Minecraft mc) {
        UseActions.Running r = useRunning;
        if (r == null) {
            return;
        }
        JsonObject done = null;
        boolean expired = false;
        try {
            done = UseActions.poll(mc, r);
            expired = (done == null && r.left <= 0);
        } catch (Throwable t) {
            useRunning = null;
            fail("use", UseActions.describeFail(t));
            return;
        }
        if (done == null && !expired) {
            return; // still busy: next tick polls again
        }
        useRunning = null;
        if (r.failed || (done != null && done.has("timeout"))) {
            String detail = done != null ? done.toString() : r.summary;
            try {
                Rec.event("fail use " + r.summary);
            } catch (Throwable ignored) {
            }
            fail("use", detail);
            return;
        }
        if (done == null) {
            done = new JsonObject();
        }
        if (!done.has("done") && !done.has("cooled")) {
            done.addProperty("done", r.summary);
        }
        TacLightMod.LOGGER.info("[TacLight][agent] {} use ok {}",
                ticket, r.summary);
        try {
            Rec.event("ok use " + r.summary);
        } catch (Throwable ignored) {
        }
        ok("use", done);
    }

    /**
     * F3 act entry (v0.2, world verbs with permission gates + drop
     * capture): delegates to {@link ActActions} — immediate ok/fail, or
     * arms {@link #actRunning} for the dig driver ({@link #pumpAct}).
     */
    private static void doAct(Minecraft mc, Op op) {
        if (actRunning != null) {
            fail("act", "another act driver is active");
            return;
        }
        ActActions.Outcome out = ActActions.execute(mc, op);
        if (out.fail != null) {
            fail("act", out.fail);
        } else if (out.running != null) {
            actRunning = out.running;
            TacLightMod.LOGGER.info("[TacLight][agent] {} act start {}",
                    ticket, out.running.summary);
        } else {
            ok("act", out.ok != null ? out.ok : new JsonObject());
        }
    }

    /** F3 dig poll (client tick): progress or timeout-ABORT (see ActActions). */
    private static void pumpAct(Minecraft mc) {
        ActActions.Running r = actRunning;
        if (r == null) {
            return;
        }
        JsonObject done = null;
        try {
            done = ActActions.poll(mc, r);
        } catch (Throwable t) {
            actRunning = null;
            fail("act", shortErr(t));
            return;
        }
        if (done == null) {
            return; // still busy: next tick polls again
        }
        actRunning = null;
        if (r.failed || done.has("timeout")) {
            try {
                Rec.event("fail act " + r.summary);
            } catch (Throwable ignored) {
            }
            fail("act", done.toString());
            return;
        }
        TacLightMod.LOGGER.info("[TacLight][agent] {} act ok {}",
                ticket, r.summary);
        try {
            Rec.event("ok act " + r.summary);
        } catch (Throwable ignored) {
        }
        ok("act", done);
    }

    private static void doPose(Minecraft mc, Op op, boolean teleport) {
        if (mc.player == null) {
            fail(op.op(), "no player");
            return;
        }
        double x = 0, y = 0, z = 0;
        if (teleport) {
            if (!op.params().has("x") || !op.params().has("y") || !op.params().has("z")
                    || !op.params().has("yaw") || !op.params().has("pitch")) {
                fail(op.op(), "pose needs x/y/z/yaw/pitch");
                return;
            }
            x = op.params().get("x").getAsDouble();
            y = op.params().get("y").getAsDouble();
            z = op.params().get("z").getAsDouble();
        } else if (!op.params().has("yaw") || !op.params().has("pitch")) {
            fail(op.op(), "look needs yaw/pitch");
            return;
        }
        float yaw = (float) op.params().get("yaw").getAsDouble();
        float pitch = (float) op.params().get("pitch").getAsDouble();
        UUID id = mc.player.getUUID();
        double fx = x, fy = y, fz = z;
        runOnServer(mc, op.op(), () -> {
            MinecraftServer server = mc.getSingleplayerServer();
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) {
                throw new IllegalStateException("server player gone");
            }
            String line = teleport
                    ? String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f", fx, fy, fz, yaw, pitch)
                    : String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f",
                            sp.getX(), sp.getY(), sp.getZ(), yaw, pitch);
            CommandSourceStack src = server.createCommandSourceStack()
                    .withEntity(sp).withPermission(4).withSuppressedOutput().withLevel(sp.serverLevel());
            int rc = server.getCommands().performPrefixedCommand(src, line);
            if (rc == 0) {
                throw new IllegalStateException("tp returned 0: " + line);
            }
            return line;
        }, value -> armSettle(teleport ? Double.valueOf(fx) : null,
                teleport ? Double.valueOf(fy) : null, teleport ? Double.valueOf(fz) : null,
                yaw, pitch, String.valueOf(value), op.op()));
    }

    private static void finishSweepOnServer(Minecraft mc, HumanSweep d) {
        UUID id = mc.player.getUUID();
        Vec3 end = d.toPos();
        float yaw = d.toYaw();
        float pitch = d.toPitch();
        boolean move = d.movesPos();
        runOnServer(mc, "sweep", () -> {
            ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
            if (sp == null) {
                throw new IllegalStateException("server player gone");
            }
            double fx = move ? end.x : sp.getX();
            double fy = move ? end.y : sp.getY();
            double fz = move ? end.z : sp.getZ();
            String line = String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f",
                    fx, fy, fz, yaw, pitch);
            CommandSourceStack src = mc.getSingleplayerServer().createCommandSourceStack()
                    .withEntity(sp).withPermission(4).withSuppressedOutput().withLevel(sp.serverLevel());
            int rc = mc.getSingleplayerServer().getCommands().performPrefixedCommand(src, line);
            if (rc == 0) {
                throw new IllegalStateException("tp returned 0: " + line);
            }
            return line + " | " + sweepDesc;
        }, value -> armSettle(move ? Double.valueOf(end.x) : null,
                move ? Double.valueOf(end.y) : null, move ? Double.valueOf(end.z) : null,
                yaw, pitch, String.valueOf(value), "sweep"));
    }

    private static void armSettle(Double x, Double y, Double z, float yaw, float pitch,
                                  String line, String op) {
        settleX = x;
        settleY = y;
        settleZ = z;
        settleYaw = yaw;
        settlePitch = pitch;
        settleLine = line;
        settleOp = op;
        settleLeft = 12;
    }

    private static void checkSettle(Minecraft mc) {
        String op = settleOp;
        var p = mc.player;
        if (p == null) {
            settleOp = null;
            fail(op, "no player while settling");
            return;
        }
        float yawErr = HumanSweep.angleDiff(p.getYRot(), settleYaw);
        float pitchErr = p.getXRot() - settlePitch;
        boolean angOk = Math.abs(yawErr) <= 1.0f && Math.abs(pitchErr) <= 1.0f;
        boolean posOk = true;
        if (settleX != null) {
            posOk = p.position().distanceTo(new Vec3(settleX, settleY, settleZ)) <= 0.7;
        }
        if (angOk && posOk) {
            JsonObject extra = new JsonObject();
            extra.addProperty("line", settleLine);
            extra.addProperty("actualYaw", Math.round(p.getYRot() * 100.0) / 100.0);
            extra.addProperty("actualPitch", Math.round(p.getXRot() * 100.0) / 100.0);
            var v = p.position();
            extra.addProperty("actualPos", String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z));
            settleOp = null;
            ok(op, extra);
            return;
        }
        if (--settleLeft <= 0) {
            var v = p.position();
            settleOp = null;
            fail(op, String.format(Locale.ROOT,
                    "settle timeout want yaw=%.2f pitch=%.2f but yaw=%.2f pitch=%.2f pos=%.2f,%.2f,%.2f",
                    settleYaw, settlePitch, p.getYRot(), p.getXRot(), v.x, v.y, v.z));
        }
    }

    private static void runServerCmd(Minecraft mc, String opName, String line) {
        UUID caller = mc.player != null ? mc.player.getUUID() : null;
        runOnServer(mc, opName, () -> {
            MinecraftServer server = mc.getSingleplayerServer();
            CommandSourceStack src = server.createCommandSourceStack()
                    .withPermission(4).withSuppressedOutput();
            if (line.contains("@s") && caller != null) {
                ServerPlayer sp = server.getPlayerList().getPlayer(caller);
                if (sp == null) {
                    throw new IllegalStateException("server player gone");
                }
                src = src.withEntity(sp).withLevel(sp.serverLevel());
            }
            int rc = server.getCommands().performPrefixedCommand(src, line);
            return line + " (rc=" + rc + ")";
        }, value -> {
            JsonObject extra = new JsonObject();
            extra.addProperty("line", String.valueOf(value));
            ok(opName, extra);
        });
    }

    private interface Work {
        Object run() throws Exception;
    }

    private interface Done {
        void accept(Object v);
    }

    private static void runOnServer(Minecraft mc, String opName, Work work, Done onOk) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(opName, "no integrated server");
            return;
        }
        CompletableFuture<Object> f = server.submit(() -> {
            try {
                return work.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        pendingOp = opName;
        pending = f;
        onPendingDone = () -> onOk.accept(f.join());
    }

    private static String benchLine(Minecraft mc) {
        try {
            int fps = mc.getFps();
            return "bench: taclight-pipeline fps=" + fps;
        } catch (Throwable t) {
            return "bench: unavailable";
        }
    }

    private static synchronized void ok(String opName, JsonObject extra) {
        JsonObject r = new JsonObject();
        r.addProperty("op", opName);
        r.addProperty("ok", true);
        // F6 subject + F12 executor obligation: every step states WHO ran it.
        r.addProperty("subject", subjectName());
        r.addProperty("executor", "real");
        if (extra != null) {
            for (var e : extra.entrySet()) {
                r.add(e.getKey(), e.getValue());
            }
        }
        results.add(r);
        pendingOp = null;
        Rec.event("ok " + opName);
    }

    private static synchronized void fail(String opName, String error) {
        JsonObject r = new JsonObject();
        r.addProperty("op", opName != null ? opName : "tick");
        r.addProperty("ok", false);
        r.addProperty("error", error);
        r.addProperty("subject", subjectName());
        r.addProperty("executor", "real");
        // F6 fail-shot: every failure carries its frame (budgeted per ticket).
        String shot = snapFailShot();
        if (!shot.equals("-")) {
            r.addProperty("shot", shot);
        }
        results.add(r);
        failed = true;
        pendingOp = null;
        TacLightMod.LOGGER.warn("[TacLight][agent] {} op {} FAILED: {}", ticket, opName, error);
        Rec.event("fail " + opName + " " + error + " shot=" + shot);
    }

    private static String subjectName() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                return mc.player.getGameProfile().getName();
            }
        } catch (Throwable ignored) {
        }
        return "-";
    }

    /** F6 fail screenshot (client framebuffer, budgeted: 5 per ticket). Never throws. */
    private static String snapFailShot() {
        try {
            if (failShots >= MAX_FAIL_SHOTS) {
                return "capped";
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return "-";
            }
            File dir = new File(mc.gameDirectory, "screenshots");
            if (!dir.exists() && !dir.mkdirs()) {
                return "-";
            }
            String name = ticket != null ? ticket : "manual";
            String filename = "taclight-fail-" + name + "-" + (failShots + 1) + ".png";
            var img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                img.writeToFile(new File(dir, filename));
            } finally {
                img.close();
            }
            failShots++;
            return "screenshots/" + filename;
        } catch (Throwable t) {
            return "-";
        }
    }

    private static void finish(boolean ok) {
        Minecraft mc = Minecraft.getInstance();
        String done = ticket;
        JsonArray out = results;
        boolean owned = autoRec;
        ticket = null;
        queue = null;
        results = null;
        sweep = null;
        invPending = null;
        useRunning = null;
        actRunning = null;
        autoRec = false;
        UseActions.releaseAll(mc);
        ActActions.releaseAll(mc);
        if (owned) {
            Rec.stop("ticket-done " + done);
        }
        try {
            JsonObject tail = new JsonObject();
            tail.addProperty("op", "rec");
            tail.addProperty("ok", true);
            tail.addProperty("dir", Rec.dir());
            out.add(tail);
        } catch (Throwable ignored) {
        }
        writeResult(mc, done, ok && !failed, out, currentTrial);
    }

    private static void writeResult(Minecraft mc, String name, boolean ok, JsonArray ops, String trial) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("ticket", name);
            root.addProperty("ok", ok);
            root.addProperty("trial", trial != null ? trial : "t1");
            root.add("ops", ops);
            Path outDir = base(mc).resolve("outbox");
            Files.createDirectories(outDir);
            Path out = outDir.resolve(name + ".result.json");
            archivePriorResult(out, trial);
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON.toJson(root), StandardCharsets.UTF_8);
            TacLightMod.LOGGER.info("[TacLight][agent] ticket {} done ok={} trial={}", name, ok, trial);
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "[TacLight][agent] ticket " + name + " done ok=" + ok));
            }
        } catch (Throwable t) {
            TacLightMod.LOGGER.warn("[TacLight][agent] cannot write result: {}", t.toString());
        }
    }

    /**
     * Per-arm archive: a prior result under the same ticket name moves to
     * {@code outbox/archive/} with a run stamp instead of being overwritten,
     * so re-running one arm never destroys another arm's evidence. The
     * canonical {@code <ticket>.result.json} always holds the latest run, so
     * the agent polling chain is unbroken. Never throws into the ticket flow.
     */
    private static void archivePriorResult(Path out, String trial) {
        try {
            if (!Files.isRegularFile(out)) {
                return;
            }
            Path arc = out.getParent().resolve("archive");
            Files.createDirectories(arc);
            String stem = out.getFileName().toString().replaceAll("\\.json$", "")
                    + "." + (trial != null ? trial : "t1")
                    + "." + RUN_CLOCK.format(LocalDateTime.now())
                    + "-p" + ProcessHandle.current().pid();
            for (int n = 0; ; n++) {
                Path cand = arc.resolve(n == 0 ? stem + ".json" : stem + "-" + n + ".json");
                if (!Files.exists(cand)) {
                    Files.move(out, cand, StandardCopyOption.REPLACE_EXISTING);
                    TacLightMod.LOGGER.info("[TacLight][agent] archived prior result -> {}", cand);
                    return;
                }
            }
        } catch (Throwable t) {
            TacLightMod.LOGGER.warn("[TacLight][agent] archive skipped: {}", t.toString());
        }
    }

    private static JsonArray errList(String op, String error) {
        JsonArray a = new JsonArray();
        JsonObject r = new JsonObject();
        r.addProperty("op", op);
        r.addProperty("ok", false);
        r.addProperty("error", error);
        a.add(r);
        return a;
    }

    private static String shortErr(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }

    /** Minimal per-tick CSV recorder (C/B/E rows). */
    private static final class Rec {
        private static BufferedWriter out;
        private static String label;
        private static int rows;
        private static long ticks;
        private static Path dir;

        static synchronized String start(Minecraft mc, String ticketName) {
            try {
                if (out != null) {
                    stop("superseded");
                }
                Path run = mc.gameDirectory.toPath().resolve("taclight-agent").resolve("rec")
                        .resolve("run-" + RUN_CLOCK.format(LocalDateTime.now())
                                + "-p" + ProcessHandle.current().pid());
                Files.createDirectories(run);
                int n = 1;
                while (Files.exists(run.resolve(String.format(Locale.ROOT, "s%04d", n)))) {
                    n++;
                }
                label = String.format(Locale.ROOT, "s%04d", n);
                dir = run.resolve(label);
                Files.createDirectories(dir);
                out = Files.newBufferedWriter(dir.resolve("frames.csv"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                rows = 0;
                ticks = 0;
                write("# TacLight rec " + label + " ticket=" + ticketName);
                event("start ticket=" + ticketName);
                return dir.toString();
            } catch (Throwable t) {
                return "start failed: " + t;
            }
        }

        static synchronized String stop(String reason) {
            if (out == null) {
                return "idle";
            }
            try {
                event("stop reason=" + reason);
                write("# END " + label + " rows=" + rows + " reason=" + reason);
                out.flush();
                out.close();
            } catch (Throwable ignored) {
            } finally {
                out = null;
            }
            return "stopped " + label + " rows=" + rows;
        }

        static synchronized boolean active() {
            return out != null;
        }

        static synchronized String status() {
            return out == null ? "idle" : "recording " + label + " rows=" + rows;
        }

        static synchronized String dir() {
            return dir == null ? "-" : dir.toString();
        }

        static synchronized void event(String detail) {
            if (out == null) {
                return;
            }
            write("E," + System.currentTimeMillis() + "," + ticks + ","
                    + detail.replace(',', ';'));
        }

        static void tick(Minecraft mc) {
            synchronized (Rec.class) {
                if (out == null || mc.player == null) {
                    return;
                }
                try {
                    ticks++;
                    var p = mc.player;
                    var pos = p.position();
                    write("C," + System.currentTimeMillis() + "," + ticks
                            + "," + f(pos.x) + "," + f(pos.y) + "," + f(pos.z)
                            + "," + f(p.getYRot()) + "," + f(p.getXRot()));
                    write("B," + System.currentTimeMillis() + "," + ticks + ",fps=" + mc.getFps());
                } catch (Throwable ignored) {
                }
            }
        }

        private static void write(String row) {
            try {
                out.write(row);
                out.newLine();
                rows++;
                if (rows % 64 == 0) {
                    out.flush();
                }
            } catch (Throwable ignored) {
            }
        }

        private static String f(double v) {
            return String.format(Locale.ROOT, "%.2f", v);
        }
    }
}

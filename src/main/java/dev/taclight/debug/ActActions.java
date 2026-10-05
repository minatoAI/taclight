package dev.taclight.debug;

import com.google.gson.JsonObject;
import dev.taclight.TacLightMod;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.eventbus.api.Event;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * F3 世界交互真序 (v0.2, 权限校验 + 掉落捕获, 禁 {@code setBlock} 直写).
 *
 * <p>Verbs (all inside one {@code act} ticket op):
 * <ul>
 *   <li>{@code place {x,y,z[,face]}} — right-click that MUST end in the
 *       {@code place} tier (otherwise fail); held item only, no creative
 *       conjuring. Composes with F1 ({@code give} + {@code inv select}).</li>
 *   <li>{@code dig {x,y,z}} — survival destroy with explicit permission
 *       gates ({@code mayInteract} + {@code blockActionRestricted}) and
 *       drop capture; timeout ABORTs like a released mouse button.</li>
 *   <li>{@code hit {id[,force]}} — attack a living target; real
 *       {@code ServerPlayer} targets refused unless {@code force:true}
 *       (permission gate); hp numbers + drop capture on kill.</li>
 *   <li>{@code door {x,y,z}} — block-tier {@code state.use} ONLY (doors,
 *       levers, buttons, gates…); placement tiers never fire here, so a
 *       door can never turn into a block-place by accident. Reports the
 *       blockstate before→after.</li>
 *   <li>{@code jump} — {@code jumpFromGround} when grounded, else fail.</li>
 *   <li>{@code sneak {on}} — {@code setShiftKeyDown} latch across ops;
 *       auto-released on ticket finish (reset convention).</li>
 *   <li>{@code sprint {on}} — v0.3 sprint latch (food &gt; 6 gate, carpet
 *       mutual exclusion with sneak); auto-released on ticket finish.</li>
 *   <li>{@code move {x,y,z[,sprint]}} — v0.3 human-speed walk
 *       (4.3 m/s, sprint 5.6 m/s) with smoothstep easing, server converge
 *       + measured m/s + food delta (endurance evidence).</li>
 *   <li>{@code camera {mode}} — v0.3 client camera
 *       (first|thirdback|thirdfront) for third-person evidence shots;
 *       restored to first-person on ticket finish (reset convention).</li>
 * </ul>
 *
 * <p>Permission gates (every world-mutating verb): {@code mayInteract}
 * (spawn protection / adventure mode) first, then the specific gate
 * ({@code blockActionRestricted} for dig, event DENY for clicks, no-player
 * rule for hit). Denials fail LOUD with the gate name — never silently.
 *
 * <p>Drop capture (Create {@code CAPTURED_BLOCK_DROPS} idea, race-free
 * variant): snapshot live {@link ItemEntity} UUIDs in an 8-block box
 * before the action, diff after, report new stacks as
 * {@code id×count,…} (capped). Drops stay in the world (nothing
 * pocketed), so world state matches the natural pipeline.
 *
 * <p>NO {@code level.setBlock} anywhere in this file — placement flows
 * exclusively through {@code stack.useOn} (BlockPlaceContext), breaking
 * exclusively through the destroy pipeline. (The {@code scene} op's
 * {@code fill}/{@code setblock} go through real server COMMANDS, which
 * are the legitimate vanilla pipeline, not code cheats.)
 *
 * <p>Threading: server thread only. Never touches OS input or rendering.
 */
public final class ActActions {
    /** Dig confirm timeout (30s, same class as F2). */
    static final int DIG_TIMEOUT_TICKS = 600;
    /** Drop-capture box half-size. */
    static final double DROP_RADIUS = 8.0;
    /** Max drop stacks listed in evidence (rest folded into a count). */
    static final int MAX_DROPS_LISTED = 12;

    private ActActions() {}

    // Sneak latch (reset convention: released on ticket finish).
    private static UUID sneakOwner;
    private static boolean sneakLatched;
    // v0.3 sprint latch (same convention; mutually exclusive with sneak).
    private static UUID sprintOwner;
    private static boolean sprintLatched;
    /** Last completed move direction yaw, degrees [-180,180] (pose asserts). Null = no move yet. */
    private static Double lastMoveYaw;

    static Double moveYaw() {
        return lastMoveYaw;
    }

    /** Human walk cap (m/s, conservative) and sprint cap (walk×1.3). */
    static final double MAX_WALK_MS = 4.3;
    static final double MAX_SPRINT_MS = 5.6;
    /** Move server-converge window: 40 ticks, 0.7-block tolerance (settle class). */
    static final int MOVE_SETTLE_TICKS = 40;
    static final double MOVE_SETTLE_DIST = 0.7;

    enum Kind { DIG, MOVE }

    /** One armed driver (dig or move), polled by {@code TicketBridge.pumpAct}. */
    static final class Running {
        final UUID playerId;
        final Kind kind;
        final String summary;
        final BlockPos pos;
        final BlockState firstState;
        final Set<UUID> dropsBefore;
        float progress;
        int left = DIG_TIMEOUT_TICKS;
        boolean failed;
        // MOVE fields (walk driver + settle).
        Vec3 moveFrom;
        Vec3 moveTo;
        int moveTotal = 1;
        int moveElapsed;
        double movePhase; // 0 = walk client ticks, 1 = server settle
        int moveSettleLeft = MOVE_SETTLE_TICKS;
        boolean moveSprint;
        int moveFoodStart;
        double moveDist;

        Running(UUID playerId, String summary, BlockPos pos, BlockState firstState, Set<UUID> dropsBefore) {
            this.playerId = playerId;
            this.kind = Kind.DIG;
            this.summary = summary;
            this.pos = pos;
            this.firstState = firstState;
            this.dropsBefore = dropsBefore;
        }

        static Running move(UUID playerId, String summary, Vec3 from, Vec3 to,
                            int total, boolean sprint, int foodStart, double dist) {
            Running r = new Running(playerId, summary);
            r.moveFrom = from;
            r.moveTo = to;
            r.moveTotal = Math.max(1, total);
            r.moveSprint = sprint;
            r.moveFoodStart = foodStart;
            r.moveDist = dist;
            return r;
        }

        private Running(UUID playerId, String summary) {
            this.playerId = playerId;
            this.kind = Kind.MOVE;
            this.summary = summary;
            this.pos = null;
            this.firstState = null;
            this.dropsBefore = null;
        }
    }

    /** Outcome of one {@code act} op: immediate ok, armed driver, or immediate fail. */
    static final class Outcome {
        JsonObject ok;
        Running running;
        String fail;

        static Outcome ok(JsonObject extra) {
            Outcome o = new Outcome();
            o.ok = extra;
            return o;
        }

        static Outcome running(Running r) {
            Outcome o = new Outcome();
            o.running = r;
            return o;
        }

        static Outcome fail(String error) {
            Outcome o = new Outcome();
            o.fail = error;
            return o;
        }
    }

    /** Ticket-finish safety: release latches, restore camera. Never throws. */
    static void releaseAll(Minecraft mc) {
        boolean sneak = sneakLatched;
        boolean sprint = sprintLatched;
        UUID who = sneakOwner != null ? sneakOwner : sprintOwner;
        sneakLatched = false;
        sneakOwner = null;
        sprintLatched = false;
        sprintOwner = null;
        try {
            if (mc.player == null) {
                return;
            }
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null && who != null) {
                ServerPlayer sp = server.getPlayerList().getPlayer(who);
                if (sp != null) {
                    if (sneak) {
                        sp.setShiftKeyDown(false);
                    }
                    if (sprint) {
                        sp.setSprinting(false);
                    }
                }
            }
            // v0.3 camera reset convention: evidence shots default to first-person.
            try {
                if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                }
            } catch (Throwable ignored) {
            }
            if (sneak || sprint) {
                TacLightMod.LOGGER.info("[TacLight][agent] latches auto-released at ticket end");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Runs one {@code act} op on the server player. Never throws (errors become {@link Outcome#fail}). */
    static Outcome execute(Minecraft mc, TicketBridge.Op op) {
        if (mc.player == null) {
            return Outcome.fail("no player");
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return Outcome.fail("no integrated server");
        }
        ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (sp == null) {
            return Outcome.fail("server player gone");
        }
        String verb = op.params().has("verb")
                ? op.params().get("verb").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        try {
            switch (verb) {
                case "place": {
                    BlockPos pos = readPos(op);
                    if (pos == null) {
                        return Outcome.fail("place needs x/y/z");
                    }
                    return place(sp, pos, readFace(op));
                }
                case "dig": {
                    BlockPos pos = readPos(op);
                    if (pos == null) {
                        return Outcome.fail("dig needs x/y/z");
                    }
                    return dig(sp, pos);
                }
                case "hit": {
                    if (!op.params().has("id")) {
                        return Outcome.fail("hit needs id");
                    }
                    boolean force = op.params().has("force") && op.params().get("force").getAsBoolean();
                    return hit(sp, op.params().get("id").getAsInt(), force);
                }
                case "door": {
                    BlockPos pos = readPos(op);
                    if (pos == null) {
                        return Outcome.fail("door needs x/y/z");
                    }
                    return useBlockOnly(sp, pos);
                }
                case "jump": {
                    if (!sp.onGround()) {
                        return Outcome.fail("jump while airborne");
                    }
                    Vec3 before = sp.position();
                    sp.jumpFromGround();
                    JsonObject extra = new JsonObject();
                    extra.addProperty("from", fmtPos(before));
                    return Outcome.ok(extra);
                }
                case "sneak": {
                    boolean on = !op.params().has("on") || op.params().get("on").getAsBoolean();
                    sp.setShiftKeyDown(on);
                    sneakOwner = sp.getUUID();
                    sneakLatched = on;
                    if (on && sprintLatched) {
                        // carpet mutual exclusion: sneaking kills sprint.
                        sp.setSprinting(false);
                        sprintLatched = false;
                        sprintOwner = null;
                    }
                    JsonObject extra = new JsonObject();
                    extra.addProperty("sneaking", sp.isShiftKeyDown());
                    return Outcome.ok(extra);
                }
                case "sprint": {
                    boolean on = !op.params().has("on") || op.params().get("on").getAsBoolean();
                    if (on) {
                        if (sp.getFoodData().getFoodLevel() <= 6) {
                            return Outcome.fail("sprint needs food>6, eat first (food="
                                    + sp.getFoodData().getFoodLevel() + ")");
                        }
                        if (sneakLatched) {
                            // carpet mutual exclusion: sprinting kills sneak.
                            sp.setShiftKeyDown(false);
                            sneakLatched = false;
                            sneakOwner = null;
                        }
                    }
                    sp.setSprinting(on);
                    sprintOwner = sp.getUUID();
                    sprintLatched = on;
                    JsonObject extra = new JsonObject();
                    extra.addProperty("sprinting", sp.isSprinting());
                    extra.addProperty("food", sp.getFoodData().getFoodLevel());
                    return Outcome.ok(extra);
                }
                case "move": {
                    return move(sp, mc, op);
                }
                case "camera": {
                    return camera(mc, op);
                }
                default:
                    return Outcome.fail("verb must be place|dig|hit|door|jump|sneak|sprint|move|camera, got '"
                            + verb + "'");
            }
        } catch (Throwable t) {
            return Outcome.fail(shortErr(t));
        }
    }

    // ---- verbs ----

    private static Outcome place(ServerPlayer sp, BlockPos pos, Direction face) {
        ServerLevel level = sp.serverLevel();
        // T21 根因:D4 的 useOn=FAIL —— 原实现把"要放置的格子"同时当成**被点击的方块**和落点
        // (BlockHitResult(loc=pos 中心, face, pos)),而 BlockItem/BlockPlaceContext 的落点 =
        // clickedPos.relative(clickedFace) ⇒ 实际会放到 pos 的**隔壁一格**(或落进实体方块) ⇒
        // canPlace 失败 ⇒ InteractionResult.FAIL。正确语义:**点击支撑方块** pos.relative(face.getOpposite()),
        // 由该面朝 pos 放置;命中点取支撑方块朝向 pos 的表面(+0.5 半格)。
        BlockState target = level.getBlockState(pos);
        if (!target.canBeReplaced()) {
            return Outcome.fail("target not replaceable at " + pos.toShortString() + " (" + target + ")");
        }
        BlockPos support = pos.relative(face.getOpposite());
        if (!level.mayInteract(sp, support) || !level.mayInteract(sp, pos)) {
            return Outcome.fail("mayInteract denied at support " + support.toShortString() + " / target " + pos.toShortString());
        }
        ItemStack held = sp.getMainHandItem();
        if (held.isEmpty()) {
            return Outcome.fail("place with empty hand (give + inv select first)");
        }
        Set<UUID> before = snapshotDrops(level, Vec3.atCenterOf(pos));
        Vec3 loc = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(loc, face, support, false);
        var event = ForgeHooks.onRightClickBlock(sp, InteractionHand.MAIN_HAND, support, hit);
        if (event.getUseItem() == Event.Result.DENY) {
            return Outcome.fail("place denied by event (useItem)");
        }
        var ctx = new net.minecraft.world.item.context.UseOnContext(sp, InteractionHand.MAIN_HAND, hit);
        InteractionResult r = held.useOn(ctx);
        if (!r.consumesAction()) {
            return Outcome.fail("not placed (" + describe(held) + " into " + pos.toShortString()
                    + " via support " + support.toShortString() + "/" + face + ", useOn=" + r + ")");
        }
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.resetLastActionTime();
        JsonObject extra = new JsonObject();
        extra.addProperty("placed", describe(held) + " -> " + pos.toShortString());
        extra.addProperty("support", support.toShortString() + "/" + face);
        extra.addProperty("held", describe(sp.getMainHandItem()));
        // T21 判据(前后世界状态对):放置目标处的新方块 + 支撑方块的当前状态
        extra.addProperty("blockBefore", String.valueOf(target));
        extra.addProperty("blockAfter", String.valueOf(level.getBlockState(pos)));
        extra.addProperty("supportAfter", String.valueOf(level.getBlockState(support)));
        extra.addProperty("drops", diffDrops(level, Vec3.atCenterOf(pos), before));
        return Outcome.ok(extra);
    }

    private static Outcome dig(ServerPlayer sp, BlockPos pos) {
        ServerLevel level = sp.serverLevel();
        if (!level.mayInteract(sp, pos)) {
            return Outcome.fail("mayInteract denied at " + pos.toShortString());
        }
        if (sp.blockActionRestricted(level, pos, sp.gameMode.getGameModeForPlayer())) {
            return Outcome.fail("blockActionRestricted at " + pos.toShortString());
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getShape(level, pos).isEmpty()) {
            return Outcome.fail("nothing to dig at " + pos.toShortString());
        }
        var event = ForgeHooks.onLeftClickBlock(sp, pos, Direction.UP);
        if (event.isCanceled()) {
            return Outcome.fail("dig denied by event");
        }
        state.attack(level, pos, sp);
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.resetLastActionTime();
        Set<UUID> dropsBefore = snapshotDrops(level, Vec3.atCenterOf(pos));
        if (sp.gameMode.getGameModeForPlayer().isCreative()) {
            sp.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP, level.getMaxBuildHeight(), -1);
            JsonObject extra = new JsonObject();
            extra.addProperty("broke", pos.toShortString());
            extra.addProperty("mode", "creative");
            extra.addProperty("drops", diffDrops(level, Vec3.atCenterOf(pos), dropsBefore));
            return Outcome.ok(extra);
        }
        return Outcome.running(new Running(sp.getUUID(), "dig " + pos.toShortString(), pos, state, dropsBefore));
    }

    private static Outcome hit(ServerPlayer sp, int id, boolean force) {
        Entity entity = sp.serverLevel().getEntity(id);
        if (entity == null) {
            return Outcome.fail("no entity id " + id);
        }
        if (!(entity instanceof LivingEntity living)) {
            return Outcome.fail("entity " + id + " not living (" + entity.getType().toShortString() + ")");
        }
        if (entity instanceof Player && !force) {
            return Outcome.fail("refusing player target without force:true");
        }
        Set<UUID> before = snapshotDrops(sp.serverLevel(), entity.position());
        float hpBefore = living.getHealth();
        sp.resetAttackStrengthTicker();
        sp.attack(entity);
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.resetLastActionTime();
        JsonObject extra = new JsonObject();
        extra.addProperty("target", entity.getType().toShortString());
        extra.addProperty("hp", String.format(Locale.ROOT, "%.1f->%.1f", hpBefore, living.getHealth()));
        extra.addProperty("alive", living.isAlive());
        if (!living.isAlive()) {
            extra.addProperty("drops", diffDrops(sp.serverLevel(), entity.position(), before));
        }
        return Outcome.ok(extra);
    }

    private static Outcome useBlockOnly(ServerPlayer sp, BlockPos pos) {
        ServerLevel level = sp.serverLevel();
        if (!level.mayInteract(sp, pos)) {
            return Outcome.fail("mayInteract denied at " + pos.toShortString());
        }
        BlockState before = level.getBlockState(pos);
        if (before.isAir()) {
            return Outcome.fail("door on air at " + pos.toShortString());
        }
        Vec3 loc = Vec3.atCenterOf(pos);
        BlockHitResult hit = new BlockHitResult(loc, Direction.UP, pos, false);
        var event = ForgeHooks.onRightClickBlock(sp, InteractionHand.MAIN_HAND, pos, hit);
        if (event.getUseBlock() == Event.Result.DENY) {
            return Outcome.fail("door denied by event (useBlock)");
        }
        // Block tier ONLY — never placement, never air-use. A door stays a door.
        InteractionResult r = before.use(level, sp, InteractionHand.MAIN_HAND, hit);
        if (!r.consumesAction()) {
            return Outcome.fail("door did nothing (" + before.getBlock().toString()
                    + " at " + pos.toShortString() + ")");
        }
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.resetLastActionTime();
        BlockState after = level.getBlockState(pos);
        JsonObject extra = new JsonObject();
        extra.addProperty("changed", before.toString() + " -> " + after.toString());
        return Outcome.ok(extra);
    }

    /**
     * v0.3 human-speed walk (sweep-class motion, F1 evidence discipline).
     * Drives the client player per tick with smoothstep easing at walk
     * (4.3 m/s) or sprint (5.6 m/s) caps, then converges the integrated
     * server entity and settles like a pose. Reports measured m/s + food
     * delta (endurance) + final error. Sprint moves latch sprint on
     * (released via {@code sprint} verb or ticket finish).
     */
    private static Outcome move(ServerPlayer sp, Minecraft mc, TicketBridge.Op op) {
        if (mc.player == null) {
            return Outcome.fail("no client player");
        }
        if (!op.params().has("x") || !op.params().has("y") || !op.params().has("z")) {
            return Outcome.fail("move needs x/y/z");
        }
        boolean sprint = op.params().has("sprint") && op.params().get("sprint").getAsBoolean();
        if (sprint) {
            if (sp.getFoodData().getFoodLevel() <= 6) {
                return Outcome.fail("sprint move needs food>6, eat first (food="
                        + sp.getFoodData().getFoodLevel() + ")");
            }
            if (sneakLatched) {
                sp.setShiftKeyDown(false);
                sneakLatched = false;
                sneakOwner = null;
            }
            sp.setSprinting(true);
            sprintOwner = sp.getUUID();
            sprintLatched = true;
        }
        Vec3 from = mc.player.position();
        Vec3 to;
        try {
            to = new Vec3(op.params().get("x").getAsDouble(), op.params().get("y").getAsDouble(),
                    op.params().get("z").getAsDouble());
        } catch (Throwable t) {
            return Outcome.fail("move xyz must be numbers");
        }
        double dist = from.distanceTo(to);
        double vmax = sprint ? MAX_SPRINT_MS : MAX_WALK_MS;
        double seconds = Math.max(dist / vmax, 0.2);
        int total = Math.max(1, (int) Math.round(seconds * 20.0));
        return Outcome.running(Running.move(sp.getUUID(),
                "move " + fmtPos(from) + "->" + fmtPos(to) + (sprint ? " sprint" : ""),
                from, to, total, sprint, sp.getFoodData().getFoodLevel(), dist));
    }

    /** v0.3 client camera for third-person evidence shots (reset on finish). */
    private static Outcome camera(Minecraft mc, TicketBridge.Op op) {
        String mode = op.params().has("mode")
                ? op.params().get("mode").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        CameraType want;
        switch (mode) {
            case "first" -> want = CameraType.FIRST_PERSON;
            case "thirdback", "third", "back" -> want = CameraType.THIRD_PERSON_BACK;
            case "thirdfront", "front" -> want = CameraType.THIRD_PERSON_FRONT;
            default -> {
                return Outcome.fail("camera mode must be first|thirdback|thirdfront, got '" + mode + "'");
            }
        }
        try {
            mc.options.setCameraType(want);
        } catch (Throwable t) {
            return Outcome.fail("camera set failed: " + shortErr(t));
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("camera", want.name().toLowerCase(Locale.ROOT));
        return Outcome.ok(extra);
    }

    /** Pump driver for dig/move. Returns an ok payload when done, null while busy. */
    static JsonObject poll(Minecraft mc, Running r) {
        MinecraftServer server = mc.getSingleplayerServer();
        ServerPlayer sp = server == null ? null : server.getPlayerList().getPlayer(r.playerId);
        if (sp == null) {
            r.failed = true;
            JsonObject extra = new JsonObject();
            extra.addProperty("timeout", r.summary + " (server player gone)");
            return extra;
        }
        if (r.kind == Kind.MOVE) {
            return pollMove(mc, sp, r);
        }
        ServerLevel level = sp.serverLevel();
        BlockState now = level.getBlockState(r.pos);
        if (now.isAir() || !now.is(r.firstState.getBlock())) {
            r.failed = !now.isAir();
            JsonObject extra = new JsonObject();
            extra.addProperty(r.failed ? "changed" : "done", r.summary);
            if (!r.failed) {
                extra.addProperty("broke", r.pos.toShortString());
                extra.addProperty("drops", diffDrops(level, Vec3.atCenterOf(r.pos), r.dropsBefore));
            }
            return extra;
        }
        r.progress += now.getDestroyProgress(sp, level, r.pos);
        if (r.progress >= 1) {
            sp.gameMode.handleBlockBreakAction(r.pos,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.UP, level.getMaxBuildHeight(), -1);
            level.destroyBlockProgress(sp.getId(), r.pos, -1);
            JsonObject extra = new JsonObject();
            extra.addProperty("done", r.summary);
            extra.addProperty("broke", r.pos.toShortString());
            extra.addProperty("drops", diffDrops(level, Vec3.atCenterOf(r.pos), r.dropsBefore));
            return extra;
        }
        level.destroyBlockProgress(sp.getId(), r.pos, (int) (r.progress * 10));
        if (--r.left <= 0) {
            sp.gameMode.handleBlockBreakAction(r.pos,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    Direction.UP, level.getMaxBuildHeight(), -1);
            level.destroyBlockProgress(sp.getId(), r.pos, -1);
            r.failed = true;
            JsonObject extra = new JsonObject();
            extra.addProperty("timeout", r.summary);
            return extra;
        }
        return null;
    }

    /** MOVE driver: client-tick interpolation, then server converge + settle. */
    private static JsonObject pollMove(Minecraft mc, ServerPlayer sp, Running r) {
        if (r.movePhase == 0) {
            if (mc.player == null) {
                r.failed = true;
                JsonObject extra = new JsonObject();
                extra.addProperty("timeout", r.summary + " (client player gone)");
                return extra;
            }
            r.moveElapsed++;
            float p = Math.min(1.0f, r.moveElapsed / (float) r.moveTotal);
            float e = p * p * (3.0f - 2.0f * p);
            mc.player.setPos(
                    r.moveFrom.x + (r.moveTo.x - r.moveFrom.x) * e,
                    r.moveFrom.y + (r.moveTo.y - r.moveFrom.y) * e,
                    r.moveFrom.z + (r.moveTo.z - r.moveFrom.z) * e);
            if (r.moveElapsed < r.moveTotal) {
                return null;
            }
            // Converge the integrated server entity (fire-and-forget; settle polls it).
            Vec3 to = r.moveTo;
            MinecraftServer server = mc.getSingleplayerServer();
            UUID id = sp.getUUID();
            if (server != null) {
                server.execute(() -> {
                    ServerPlayer s = server.getPlayerList().getPlayer(id);
                    if (s != null) {
                        s.teleportTo(to.x, to.y, to.z);
                    }
                });
            }
            r.movePhase = 1;
            return null;
        }
        Vec3 cur = sp.position();
        double err = cur.distanceTo(r.moveTo);
        if (err <= MOVE_SETTLE_DIST) {
            double seconds = Math.max(r.moveElapsed, 1) / 20.0;
            double ms = r.moveDist / seconds;
            double yaw = Math.toDegrees(Math.atan2(
                    -(r.moveTo.x - r.moveFrom.x), r.moveTo.z - r.moveFrom.z));
            lastMoveYaw = yaw;
            JsonObject extra = new JsonObject();
            extra.addProperty("done", r.summary);
            extra.addProperty("ms", Math.round(ms * 100.0) / 100.0);
            extra.addProperty("err", Math.round(err * 100.0) / 100.0);
            extra.addProperty("sprinting", sp.isSprinting());
            int foodNow = sp.getFoodData().getFoodLevel();
            extra.addProperty("food", r.moveFoodStart + "->" + foodNow);
            extra.addProperty("moveYaw", Math.round(yaw * 10.0) / 10.0);
            return extra;
        }
        if (--r.moveSettleLeft <= 0) {
            r.failed = true;
            JsonObject extra = new JsonObject();
            extra.addProperty("timeout", r.summary + " err=" + Math.round(err * 100.0) / 100.0);
            return extra;
        }
        return null;
    }

    // ---- drop capture (UUID diff, drops stay in the world) ----

    static Set<UUID> snapshotDrops(ServerLevel level, Vec3 center) {
        Set<UUID> out = new HashSet<>();
        try {
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(center, center).inflate(DROP_RADIUS))) {
                out.add(e.getUUID());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static String diffDrops(ServerLevel level, Vec3 center, Set<UUID> before) {
        StringBuilder sb = new StringBuilder();
        int listed = 0;
        int extra = 0;
        try {
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(center, center).inflate(DROP_RADIUS))) {
                if (before.contains(e.getUUID())) {
                    continue;
                }
                if (listed < MAX_DROPS_LISTED) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(describe(e.getItem()));
                    listed++;
                } else {
                    extra++;
                }
            }
        } catch (Throwable t) {
            return "unavailable";
        }
        if (sb.length() == 0) {
            return extra > 0 ? "+" + extra + " more" : "none";
        }
        return extra > 0 ? sb + ",+" + extra + " more" : sb.toString();
    }

    // ---- params / evidence ----

    private static BlockPos readPos(TicketBridge.Op op) {
        if (!op.params().has("x") || !op.params().has("y") || !op.params().has("z")) {
            return null;
        }
        try {
            return new BlockPos(op.params().get("x").getAsInt(), op.params().get("y").getAsInt(),
                    op.params().get("z").getAsInt());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Direction readFace(TicketBridge.Op op) {
        if (!op.params().has("face")) {
            return Direction.UP;
        }
        String f = op.params().get("face").getAsString().trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> Direction.UP;
        };
    }

    static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return stack.getItem().toString() + "x" + stack.getCount();
    }

    private static String fmtPos(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z);
    }

    private static String shortErr(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }
}

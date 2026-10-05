package dev.taclight.debug;

import com.google.gson.JsonObject;
import dev.taclight.TacLightMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.eventbus.api.Event;

import java.util.Locale;
import java.util.UUID;

/**
 * F2 使用物品 (v0.2, 真人包序, 禁 OS 键鼠).
 *
 * <p>Census restatement (task-27 killer hunt, bytecode-proven): the
 * per-tick continuation gate ({@code updatingUsingItem} held-mismatch) is
 * the only <em>autonomous in-tick</em> killer. A {@code RELEASE_USE_ITEM}
 * packet (or any direct {@code stop/release} call) bypasses the gate
 * entirely — such kills arrive via {@code handlePlayerAction}, whose only
 * client-side constructor is {@code MultiPlayerGameMode.releaseUsingItem}
 * (see the UsingGate/UsingSend trap pair). Session-ownership discipline
 * in this file covers our own calls; ambient packets are someone else's.
 *
 * <p>Packet order (task-17 evidence, server side, in-process):
 * <ul>
 *   <li>Right-click 3 tiers (Create {@code DeployerHandler} order with
 *       Forge gates): {@code onRightClickBlock} (useBlock/useItem) →
 *       {@code stack.onItemUseFirst} → {@code state.use} (block) →
 *       {@code stack.useOn} (BlockPlaceContext placement) →
 *       {@code item.use} (air-click) → {@code finishUsingItem} /
 *       {@code stopUsingItem} tail.</li>
 *   <li>Left-click true order (Create PUNCH + carpet ATTACK-block):
 *       {@code mayInteract} → shape → {@code onLeftClickBlock} →
 *       {@code state.attack} → survival destroy-progress accumulation
 *       ({@code START/STOP/ABORT_DESTROY_BLOCK} +
 *       {@code destroyBlockProgress} stages) across ticks.</li>
 *   <li>Eat/bow/shield pairing (carpet rhythm): {@code startUsingItem} …
 *       {@code releaseUsingItem} on stop, 3-tick {@code itemUseCooldown}
 *       tail after every successful use, {@code resetLastActionTime} /
 *       {@code resetAttackStrengthTicker} around actions.</li>
 * </ul>
 *
 * <p>Deliberate deviation from carpet: NO raycast ({@code Tracer}) — ticket
 * ops carry explicit block pos / entity id, so evidence is deterministic
 * and replayable. Facing defaults to UP when omitted.
 *
 * <p>Ops (all inside one {@code use} ticket op):
 * <ul>
 *   <li>{@code {target:block,x,y,z[,face]}} — right-click the block.</li>
 *   <li>{@code {target:air[,hand]}} — right-click air with held item.</li>
 *   <li>{@code {target:entity,id[,hand]}} — interact order on the entity.</li>
 *   <li>{@code {target:eat}} — multi-tick: hold-to-eat until done.
 *       Done needs proof (consumed count/id change, food up, or ≥25
 *       polls); anything less is reported as {@code earlyDeath} with
 *       before/after evidence, never a false done.</li>
 *   <li>{@code {target:start[,hand]}} — begin using (bow/shield/food),
 *       stays active across ops until {@code stop}.</li>
 *   <li>{@code {target:stop}} — {@code releaseUsingItem}.</li>
 *   <li>{@code {target:attack-block,x,y,z}} — multi-tick survival dig.</li>
 *   <li>{@code {target:attack-entity,id}} — {@code player.attack} + hp numbers.</li>
 * </ul>
 *
 * <p>Session ownership (task-27 fix): single-shot tails
 * ({@code block}/{@code air}/{@code entity}) only stop a using session
 * THEY started — a pre-existing session (bow drawn by an earlier
 * {@code start}) is left running and noted as {@code kept-session}.
 * {@code eat}/{@code start} fail fast when a session is already active
 * instead of hijacking it. TaCZ guns fail fast with guidance (trigger
 * packets, not vanilla use-start).
 *
 * <p>Reset convention (mirrors the tm convention): the bridge releases any
 * active using session on ticket finish ({@link #releaseAll}), so no drawn
 * bow / raised shield leaks across tickets.
 *
 * <p>Threading: server thread only. Never touches OS input or rendering.
 */
public final class UseActions {
    /** Cooldown tail after a successful use (carpet itemUseCooldown). */
    static final int COOLDOWN_TICKS = 3;
    /** Eat confirm timeout (food ~32 ticks + lag margin). */
    static final int EAT_TIMEOUT_TICKS = 120;
    /**
     * Minimum EAT polls before a no-consumption finish counts as natural
     * (food ≈32 server ticks; client polls track server ticks ~1:1 here).
     */
    static final int MIN_EAT_POLLS = 25;
    /** Dig confirm timeout (30s, carpet-combat class). */
    static final int DIG_TIMEOUT_TICKS = 600;

    private UseActions() {}

    /**
     * Ticket-owned using session flag (task-27 conviction fix).
     *
     * <p>Mechanism (bytecode-proven): our rig arms sessions server-side
     * only; the using flag syncs to the client; vanilla
     * {@code Minecraft.handleKeybinds} then sees local-using=true with the
     * use key up (unattended runs, forever) and "helpfully" releases via
     * {@code MultiPlayerGameMode.releaseUsingItem} — a RELEASE packet that
     * murders every session ~1 tick after arming. While this flag is set,
     * {@code UsingReleaseGuardMixin} suppresses exactly that send path
     * (nothing else: no key states, no visuals, no packets forged).
     * Our own {@code stop} op and ticket-finish release call the server
     * session directly and never go through the guarded path.
     */
    private static volatile boolean sessionArmed;

    /** Guard read (cross-package: the client mixin polls this). */
    public static boolean isSessionArmed() {
        return sessionArmed;
    }

    private static void armSession() {
        sessionArmed = true;
    }

    /** Clears the flag (idempotent). Public for ticket lifecycle hooks. */
    public static void disarmSession() {
        sessionArmed = false;
    }

    enum Kind { EAT, DESTROY, COOL }

    /** One armed multi-tick driver, polled by {@code TicketBridge.pumpUse}. */
    static final class Running {
        final UUID playerId;
        final Kind kind;
        final String summary;
        BlockPos pos;
        int entityId = -1;
        InteractionHand hand = InteractionHand.MAIN_HAND;
        float progress;
        BlockState firstState;
        int left;
        boolean failed;
        JsonObject doneOk;
        // EAT baselines (task-27 hardening): natural finish MUST move at
        // least one of these; otherwise the session died early.
        int polls;
        String startHeldId = "";
        int startHeldCount;
        int startFood;
        int startRemaining = -1;

        Running(UUID playerId, Kind kind, String summary, int timeout) {
            this.playerId = playerId;
            this.kind = kind;
            this.summary = summary;
            this.left = timeout;
        }
    }

    /** Outcome of one {@code use} op: immediate ok, armed driver, or immediate fail. */
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

    /** Ticket-finish safety: release any active using session. Never throws. */
    static void releaseAll(Minecraft mc) {
        try {
            if (mc.player == null) {
                disarmSession();
                return;
            }
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                disarmSession();
                return;
            }
            ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (sp != null && sp.isUsingItem()) {
                sp.releaseUsingItem();
                TacLightMod.LOGGER.info("[TacLight][agent] use session auto-released at ticket end");
            }
        } catch (Throwable ignored) {
        } finally {
            disarmSession();
        }
    }

    /** Runs one {@code use} op on the server player. Never throws (errors become {@link Outcome#fail}). */
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
        String target = op.params().has("target")
                ? op.params().get("target").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        InteractionHand hand = parseHand(op);
        if (hand == null) {
            return Outcome.fail("hand must be main|off, got '"
                    + (op.params().has("hand") ? op.params().get("hand").getAsString() : "") + "'");
        }
        try {
            switch (target) {
                case "block": {
                    BlockPos pos = readPos(op);
                    if (pos == null) {
                        return Outcome.fail("block needs x/y/z");
                    }
                    Direction face = readFace(op);
                    return rightClickBlock(sp, pos, face, hand);
                }
                case "air":
                    return rightClickAir(sp, hand);
                case "entity": {
                    if (!op.params().has("id")) {
                        return Outcome.fail("entity needs id");
                    }
                    return useEntity(sp, op.params().get("id").getAsInt(), hand);
                }
                case "eat":
                    return eat(sp, hand);
                case "start": {
                    String gunRefusal = refuseTaczGun(sp, hand);
                    if (gunRefusal != null) {
                        return Outcome.fail(gunRefusal);
                    }
                    if (sp.isUsingItem()) {
                        // Never hijack another op's session (task-27 fix):
                        // report the owner instead of misattributing it.
                        return Outcome.fail("already using " + describe(sp.getUseItem())
                                + ", stop first");
                    }
                    sp.startUsingItem(hand);
                    if (!sp.isUsingItem()) {
                        return Outcome.fail("start did not begin using (item not usable?)");
                    }
                    JsonObject extra = new JsonObject();
                    extra.addProperty("using", describe(sp.getUseItem()));
                    armSession();
                    return armCooldown(sp, Outcome.ok(extra), "start " + describe(sp.getUseItem()));
                }
                case "stop": {
                    boolean was = sp.isUsingItem();
                    sp.releaseUsingItem();
                    disarmSession();
                    JsonObject extra = new JsonObject();
                    extra.addProperty("wasUsing", was);
                    return Outcome.ok(extra);
                }
                case "attack-block": {
                    BlockPos pos = readPos(op);
                    if (pos == null) {
                        return Outcome.fail("attack-block needs x/y/z");
                    }
                    return attackBlock(sp, pos);
                }
                case "attack-entity": {
                    if (!op.params().has("id")) {
                        return Outcome.fail("attack-entity needs id");
                    }
                    return attackEntity(sp, op.params().get("id").getAsInt());
                }
                default:
                    return Outcome.fail(
                            "target must be block|air|entity|eat|start|stop|attack-block|attack-entity, got '"
                                    + target + "'");
            }
        } catch (Throwable t) {
            return Outcome.fail(shortErr(t));
        }
    }

    // ---- right-click 3 tiers (Create order + Forge gates) ----

    private static Outcome rightClickBlock(ServerPlayer sp, BlockPos pos, Direction face, InteractionHand hand) {
        ServerLevel level = sp.serverLevel();
        BlockState clickedState = level.getBlockState(pos);
        ItemStack stack = sp.getItemInHand(hand);
        // Session ownership (task-27 fix): single-shot tails must only stop a
        // session THEY started. A pre-existing session (bow drawn by an
        // earlier `start`) is left running and noted, never killed here.
        boolean wasUsing = sp.isUsingItem();
        Vec3 loc = Vec3.atCenterOf(pos);
        BlockHitResult hit = new BlockHitResult(loc, face, pos, false);
        UseOnContext ctx = new UseOnContext(sp, hand, hit);

        var event = ForgeHooks.onRightClickBlock(sp, hand, pos, hit);
        Event.Result useBlock = event.getUseBlock();
        Event.Result useItem = event.getUseItem();
        String tier = "none";

        if (useItem != Event.Result.DENY) {
            InteractionResult first = stack.onItemUseFirst(ctx);
            if (first != InteractionResult.PASS) {
                return finishUse(sp, tier = "first:" + first, hand, pos.toShortString(), wasUsing);
            }
        }
        boolean holding = !sp.getMainHandItem().isEmpty();
        boolean flag1 = !(sp.isShiftKeyDown() && holding)
                || stack.doesSneakBypassUse(level, pos, sp);
        if (useBlock != Event.Result.DENY && flag1
                && clickedState.use(level, sp, hand, hit).consumesAction()) {
            return finishUse(sp, tier = "block", hand, pos.toShortString(), wasUsing);
        }
        if (stack.isEmpty()) {
            return finishUse(sp, tier, hand, pos.toShortString(), wasUsing);
        }
        if (useItem == Event.Result.DENY) {
            return finishUse(sp, tier, hand, pos.toShortString(), wasUsing);
        }
        if (stack.useOn(ctx).consumesAction()) {
            return finishUse(sp, tier = "place", hand, pos.toShortString(), wasUsing);
        }
        InteractionResultHolder<ItemStack> airUse = stack.getItem().use(level, sp, hand);
        if (airUse.getResult().consumesAction()) {
            ItemStack result = airUse.getObject();
            if (result != stack) {
                sp.setItemInHand(hand, result);
            }
            return finishUse(sp, tier = "air", hand, pos.toShortString(), wasUsing);
        }
        return finishUse(sp, tier, hand, pos.toShortString(), wasUsing);
    }

    private static Outcome rightClickAir(ServerPlayer sp, InteractionHand hand) {
        ServerLevel level = sp.serverLevel();
        ItemStack stack = sp.getItemInHand(hand);
        if (stack.isEmpty()) {
            return Outcome.fail("air with empty hand");
        }
        boolean wasUsing = sp.isUsingItem();
        InteractionResultHolder<ItemStack> airUse = stack.getItem().use(level, sp, hand);
        if (!airUse.getResult().consumesAction()) {
            return Outcome.fail("air use did nothing (" + describe(stack) + ")");
        }
        ItemStack result = airUse.getObject();
        if (result != stack) {
            sp.setItemInHand(hand, result);
        }
        return finishUse(sp, "air", hand, "-", wasUsing);
    }

    private static Outcome useEntity(ServerPlayer sp, int id, InteractionHand hand) {
        Entity entity = sp.serverLevel().getEntity(id);
        if (entity == null) {
            return Outcome.fail("no entity id " + id);
        }
        InteractionResult cancel = ForgeHooks.onInteractEntity(sp, entity, hand);
        if (cancel == InteractionResult.FAIL) {
            return Outcome.fail("interact denied by event");
        }
        boolean wasUsing = sp.isUsingItem();
        String how = "none";
        if (cancel == null) {
            if (entity.interact(sp, hand).consumesAction()) {
                how = "interact";
            } else if (entity instanceof LivingEntity living
                    && sp.getItemInHand(hand).interactLivingEntity(sp, living, hand).consumesAction()) {
                how = "interactLiving";
            } else if (sp.gameMode.useItem(sp, sp.serverLevel(), sp.getItemInHand(hand), hand).consumesAction()) {
                how = "useItem";
            }
        }
        if (how.equals("none")) {
            return Outcome.fail("entity use did nothing (" + entity.getType().toShortString() + ")");
        }
        return finishUse(sp, how, hand, "e" + id, wasUsing);
    }

    private static Outcome finishUse(ServerPlayer sp, String tier, InteractionHand hand, String where,
                                      boolean wasUsing) {
        sp.resetLastActionTime();
        sp.swing(hand, true);
        JsonObject extra = new JsonObject();
        extra.addProperty("tier", tier);
        extra.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
        extra.addProperty("where", where);
        extra.addProperty("held", describe(sp.getItemInHand(hand)));
        if (wasUsing) {
            // A session owned by an earlier op is still running: hands off,
            // note it. Only stop sessions this op started below.
            extra.addProperty("keptSession", describe(sp.getUseItem()));
        } else if (!sp.getUseItem().isEmpty()) {
            sp.stopUsingItem();
        }
        Running cool = new Running(sp.getUUID(), Kind.COOL, "cooldown " + tier, COOLDOWN_TICKS);
        // stash the ok payload on the running driver (delivered after cooldown)
        cool.doneOk = extra;
        return Outcome.running(cool);
    }

    // ---- eat / destroy drivers (multi-tick) ----

    private static Outcome eat(ServerPlayer sp, InteractionHand hand) {
        ItemStack stack = sp.getItemInHand(hand);
        if (stack.isEmpty() || !stack.isEdible()) {
            return Outcome.fail("eat needs edible held item, got " + describe(stack));
        }
        String gunRefusal = refuseTaczGun(sp, hand);
        if (gunRefusal != null) {
            return Outcome.fail(gunRefusal);
        }
        if (sp.isUsingItem()) {
            return Outcome.fail("busy using " + describe(sp.getUseItem()) + ", stop first");
        }
        sp.startUsingItem(hand);
        if (!sp.isUsingItem()) {
            return Outcome.fail("eat did not start (full hunger?)");
        }
        armSession();
        Running r = new Running(sp.getUUID(), Kind.EAT, "eat " + describe(stack), EAT_TIMEOUT_TICKS);
        r.hand = hand;
        r.startHeldId = stack.isEmpty() ? "empty" : stack.getItem().toString();
        r.startHeldCount = stack.getCount();
        r.startFood = sp.getFoodData().getFoodLevel();
        try {
            r.startRemaining = sp.getUseItemRemainingTicks();
        } catch (Throwable ignored) {
            r.startRemaining = -1;
        }
        return Outcome.running(r);
    }

    /**
     * TaCZ guns bypass vanilla {@code startUsingItem} (client-driven
     * trigger packets + {@code IGunOperator} aiming state) — a use-start
     * on them can never produce a session. Fail fast with guidance
     * instead of a mysterious instant death. String match keeps this
     * class free of a hard TaCZ dependency (soft-dep discipline).
     */
    static String refuseTaczGun(ServerPlayer sp, InteractionHand hand) {
        try {
            ItemStack stack = sp.getItemInHand(hand);
            if (stack.isEmpty()) {
                return null;
            }
            String cls = stack.getItem().getClass().getName().toLowerCase(Locale.ROOT);
            if (cls.contains("tacz")) {
                return "tacz guns don't use vanilla use-start (trigger packets + aiming state); "
                        + "drive them via game input, not this op (got " + describe(stack) + ")";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Outcome attackBlock(ServerPlayer sp, BlockPos pos) {
        ServerLevel level = sp.serverLevel();
        if (!level.mayInteract(sp, pos)) {
            return Outcome.fail("mayInteract denied at " + pos.toShortString());
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getShape(level, pos).isEmpty()) {
            return Outcome.fail("nothing to dig at " + pos.toShortString());
        }
        var event = ForgeHooks.onLeftClickBlock(sp, pos, Direction.UP);
        if (event.isCanceled()) {
            return Outcome.fail("left-click denied by event");
        }
        state.attack(level, pos, sp);
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.resetLastActionTime();
        if (sp.gameMode.getGameModeForPlayer().isCreative()) {
            sp.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP, level.getMaxBuildHeight(), -1);
            JsonObject extra = new JsonObject();
            extra.addProperty("broke", pos.toShortString());
            extra.addProperty("mode", "creative");
            return armCooldown(sp, Outcome.ok(extra), "dig creative " + pos.toShortString());
        }
        Running r = new Running(sp.getUUID(), Kind.DESTROY, "dig " + pos.toShortString(), DIG_TIMEOUT_TICKS);
        r.pos = pos;
        r.firstState = state;
        r.progress = 0;
        return Outcome.running(r);
    }

    private static Outcome attackEntity(ServerPlayer sp, int id) {
        Entity entity = sp.serverLevel().getEntity(id);
        if (entity == null) {
            return Outcome.fail("no entity id " + id);
        }
        if (!(entity instanceof LivingEntity living)) {
            return Outcome.fail("entity " + id + " not living (" + entity.getType().toShortString() + ")");
        }
        float before = living.getHealth();
        sp.resetAttackStrengthTicker();
        sp.attack(entity);
        sp.swing(InteractionHand.MAIN_HAND, true);
        sp.resetLastActionTime();
        JsonObject extra = new JsonObject();
        extra.addProperty("target", entity.getType().toShortString());
        extra.addProperty("hp", String.format(Locale.ROOT, "%.1f->%.1f", before, living.getHealth()));
        extra.addProperty("alive", living.isAlive());
        return armCooldown(sp, Outcome.ok(extra), "attack e" + id);
    }

    private static Outcome armCooldown(ServerPlayer sp, Outcome ok, String summary) {
        Running cool = new Running(sp.getUUID(), Kind.COOL, "cooldown " + summary, COOLDOWN_TICKS);
        if (ok.ok != null) {
            cool.doneOk = ok.ok;
        }
        return Outcome.running(cool);
    }

    /** Pump driver. Returns an ok payload when the running op completes, null while busy. */
    static JsonObject poll(Minecraft mc, Running r) {
        MinecraftServer server = mc.getSingleplayerServer();
        ServerPlayer sp = server == null ? null : server.getPlayerList().getPlayer(r.playerId);
        if (sp == null) {
            r.left = 0;
            r.failed = true;
            JsonObject extra = new JsonObject();
            extra.addProperty("timeout", r.summary + " (server player gone)");
            return extra;
        }
        switch (r.kind) {
            case COOL -> {
                if (--r.left <= 0) {
                    JsonObject extra = r.doneOk != null ? r.doneOk : new JsonObject();
                    extra.addProperty("cooled", r.summary);
                    return extra;
                }
                return null;
            }
            case EAT -> {
                r.polls++;
                if (!sp.isUsingItem()) {
                    // done-check triple gate (task-27 second bug fix): a bare
                    // !isUsingItem cannot tell natural finish from early
                    // death. Natural finish MUST move ≥1 of: held count down
                    // (consumed), held identity changed (bowl/remnant swap),
                    // food up, or a minimum dwell (food ≈32 server ticks).
                    ItemStack now = sp.getItemInHand(r.hand);
                    String nowId = now.isEmpty() ? "empty" : now.getItem().toString();
                    boolean consumed = now.getCount() < r.startHeldCount
                            || !nowId.equals(r.startHeldId)
                            || sp.getFoodData().getFoodLevel() > r.startFood;
                    boolean longEnough = r.polls >= MIN_EAT_POLLS;
                    JsonObject extra = new JsonObject();
                    extra.addProperty("held", describe(now));
                    extra.addProperty("food", sp.getFoodData().getFoodLevel());
                    extra.addProperty("polls", r.polls);
                    int remainingNow = -1;
                    try {
                        remainingNow = sp.getUseItemRemainingTicks();
                    } catch (Throwable ignored) {
                    }
                    extra.addProperty("remaining", remainingNow);
                    if (consumed || longEnough) {
                        disarmSession();
                        extra.addProperty("done", r.summary);
                        return extra;
                    }
                    r.failed = true;
                    disarmSession();
                    extra.addProperty("earlyDeath", r.summary);
                    extra.addProperty("was", r.startHeldId + "x" + r.startHeldCount
                            + " food=" + r.startFood + " remaining=" + r.startRemaining);
                    return extra;
                }
                if (--r.left <= 0) {
                    sp.releaseUsingItem();
                    disarmSession();
                    r.failed = true;
                    JsonObject extra = new JsonObject();
                    extra.addProperty("timeout", r.summary);
                    return extra;
                }
                return null;
            }
            case DESTROY -> {
                ServerLevel level = sp.serverLevel();
                BlockState now = level.getBlockState(r.pos);
                if (now.isAir() || !now.is(r.firstState.getBlock())) {
                    // Finished externally (or wrong block) — settle as done/failed by emptiness.
                    r.failed = !now.isAir();
                    JsonObject extra = new JsonObject();
                    extra.addProperty(r.failed ? "changed" : "done", r.summary);
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
        }
        return null;
    }

    // ---- params / evidence ----

    private static InteractionHand parseHand(TicketBridge.Op op) {
        if (!op.params().has("hand")) {
            return InteractionHand.MAIN_HAND;
        }
        String h = op.params().get("hand").getAsString().trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("off")) {
            return InteractionHand.OFF_HAND;
        }
        if (h.startsWith("main")) {
            return InteractionHand.MAIN_HAND;
        }
        return null;
    }

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

    static String describeFail(Throwable t) {
        return shortErr(t);
    }

    private static String shortErr(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }
}

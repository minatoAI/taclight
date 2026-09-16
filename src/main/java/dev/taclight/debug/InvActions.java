package dev.taclight.debug;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * F1 背包动作 (v0.2, 真人代码级, 禁 OS 键鼠).
 *
 * <p>Why code-level instead of mouse simulation: the harness drives the REAL
 * server player in-process. Real container clicks and hand swaps are plain
 * inventory mutations + broadcast sync (the carpet {@code setSlot} /
 * {@code drop} pattern, the mineflayer {@code moveSlotItem} sequence), so
 * there is nothing to gain from injecting OS input — and OS input cannot
 * run headless anyway.
 *
 * <p>Ops (all inside one {@code inv} ticket op, {@code action} selects):
 * <ul>
 *   <li>{@code open} — full sync ({@code sendAllDataToRemote}) + concise
 *       listing (non-empty slots only, {@code id×count}, no NBT).</li>
 *   <li>{@code click {slot, button}} — vanilla PICKUP click semantics
 *       against the menu cursor ({@code getCarried}): empty cursor picks
 *       up, empty slot places, same item merges, otherwise swaps.
 *       Right button = half-pickup / place-one.</li>
 *   <li>{@code drag {from, to}} — mineflayer {@code moveSlotItem}:
 *       pick up, place, put the remainder back.</li>
 *   <li>{@code select {slot 0-8}} — hotbar switch: {@code selected} +
 *       {@code ClientboundSetCarriedItemPacket} (carpet {@code setSlot}).</li>
 *   <li>{@code toss [slot]} — drop a stack ({@code player.drop}); default
 *       is the selected hotbar slot. Fresh drops get their pickup delay
 *       pinned (task-27: standing thrower would vacuum them back and drift
 *       slots; authentic play vacuums, test rig pins — stated).</li>
 * </ul>
 *
 * <p>Slot numbering is vanilla {@link Inventory} space: 0-8 hotbar, 9-35
 * main. Container (menu) indices are deliberately NOT exposed — menu clicks
 * carry crafting-grid QUICK_MOVE quirks; direct ops are deterministic and
 * evidence-friendly.
 *
 * <p>Confirm/timeout/refill (mineflayer {@code clickWindow} discipline):
 * every mutating op arms a short closed loop polled from the ticket pump —
 * expected server state must hold for {@value #SETTLE_TICKS} consecutive
 * ticks, otherwise (up to {@value #TIMEOUT_TICKS} ticks = 5s) it keeps
 * polling; on timeout the inventory is re-synced
 * ({@code sendAllDataToRemote} = 回填) and the op fails with want-vs-actual
 * numbers, never silently.
 *
 * <p>Echo suppression: results carry only changed slots
 * ({@code slot:id×count}); full listings appear solely on {@code open}.
 * (Rationale, mineflayer {@code _ensureHasSentCarriedItem}: never
 * rebroadcast what is already in sync; the live client echoes
 * {@code held_item_slot} back and vanilla absorbs it.)
 *
 * <p>Threading: server thread only (the bridge resolves the server player
 * and runs {@link #execute} there). Never touches OS input, never touches
 * rendering.
 */
public final class InvActions {
    /** Consecutive stable ticks required to accept the new state. */
    static final int SETTLE_TICKS = 4;
    /** Confirm timeout: 100 ticks = 5s (spec). */
    static final int TIMEOUT_TICKS = 100;

    private InvActions() {}

    /** One armed confirm loop, polled by {@code TicketBridge.pumpInv}. */
    static final class Pending {
        final UUID playerId;
        final String summary;
        final String expectDesc;
        final Predicate<ServerPlayer> expect;
        final java.util.function.Function<ServerPlayer, String> actual;
        int stable;
        int left = TIMEOUT_TICKS;

        Pending(UUID playerId, String summary, String expectDesc,
                Predicate<ServerPlayer> expect,
                java.util.function.Function<ServerPlayer, String> actual) {
            this.playerId = playerId;
            this.summary = summary;
            this.expectDesc = expectDesc;
            this.expect = expect;
            this.actual = actual;
        }
    }

    /** Outcome of one {@code inv} op: immediate ok, armed confirm, or immediate fail. */
    static final class Outcome {
        JsonObject ok;
        Pending pending;
        String fail;

        static Outcome ok(JsonObject extra) {
            Outcome o = new Outcome();
            o.ok = extra;
            return o;
        }

        static Outcome pending(Pending p) {
            Outcome o = new Outcome();
            o.pending = p;
            return o;
        }

        static Outcome fail(String error) {
            Outcome o = new Outcome();
            o.fail = error;
            return o;
        }
    }

    /** Runs one {@code inv} op on the server player. Never throws (errors become {@link Outcome#fail}). */
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
        String action = op.params().has("action")
                ? op.params().get("action").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        try {
            switch (action) {
                case "open": {
                    sp.containerMenu.sendAllDataToRemote();
                    JsonObject extra = new JsonObject();
                    extra.addProperty("list", listInventory(sp));
                    extra.addProperty("selected", sp.getInventory().selected);
                    extra.addProperty("carried", describe(sp.containerMenu.getCarried()));
                    return Outcome.ok(extra);
                }
                case "click": {
                    if (!op.params().has("slot")) {
                        return Outcome.fail("click needs slot");
                    }
                    int slot = op.params().get("slot").getAsInt();
                    int button = op.params().has("button") ? op.params().get("button").getAsInt() : 0;
                    if (button != 0 && button != 1) {
                        return Outcome.fail("button must be 0|1, got " + button);
                    }
                    String before = slotDescribe(sp, slot);
                    String err = clickSlot(sp, slot, button);
                    if (err != null) {
                        return Outcome.fail(err);
                    }
                    sp.containerMenu.broadcastChanges();
                    String after = slotDescribe(sp, slot);
                    String carriedAfter = describe(sp.containerMenu.getCarried());
                    return Outcome.pending(new Pending(sp.getUUID(),
                            "click s" + slot + " b" + button + " " + before + "->" + after,
                            "slot " + slot + "=" + after + " + carried stable",
                            s -> slotDescribe(s, slot).equals(after)
                                    && describe(s.containerMenu.getCarried()).equals(carriedAfter),
                            s -> slotDescribe(s, slot) + " carried="
                                    + describe(s.containerMenu.getCarried())));
                }
                case "drag": {
                    if (!op.params().has("from") || !op.params().has("to")) {
                        return Outcome.fail("drag needs from/to");
                    }
                    int from = op.params().get("from").getAsInt();
                    int to = op.params().get("to").getAsInt();
                    String err = dragSlots(sp, from, to);
                    if (err != null) {
                        return Outcome.fail(err);
                    }
                    sp.containerMenu.broadcastChanges();
                    return Outcome.pending(new Pending(sp.getUUID(),
                            "drag " + from + "->" + to + " " + slotDescribe(sp, from)
                                    + " " + slotDescribe(sp, to),
                            "carried empty",
                            s -> s.containerMenu.getCarried().isEmpty(),
                            s -> "carried=" + describe(s.containerMenu.getCarried())));
                }
                case "equip": {
                    // T21: 专用护甲 op(用户点名的四类动作之一)。slot=head|chest|legs|feet
                    // vanilla Inventory 护甲槽 = 36(feet)/37(legs)/38(chest)/39(head);背包 = 0..35。
                    if (!op.params().has("slot")) {
                        return Outcome.fail("equip needs slot=head|chest|legs|feet");
                    }
                    String where = op.params().get("slot").getAsString().trim().toLowerCase(Locale.ROOT);
                    net.minecraft.world.entity.EquipmentSlot es;
                    switch (where) {
                        case "head": es = net.minecraft.world.entity.EquipmentSlot.HEAD; break;
                        case "chest": es = net.minecraft.world.entity.EquipmentSlot.CHEST; break;
                        case "legs": es = net.minecraft.world.entity.EquipmentSlot.LEGS; break;
                        case "feet": es = net.minecraft.world.entity.EquipmentSlot.FEET; break;
                        default: return Outcome.fail("equip slot must be head|chest|legs|feet, got '" + where + "'");
                    }
                    Inventory inv = sp.getInventory();
                    int armorSlot = 36 + es.getIndex();          // 36 feet, 37 legs, 38 chest, 39 head
                    String armorBefore = describe(inv.getItem(armorSlot));
                    ItemStack armored = inv.getItem(armorSlot);
                    int found = -1;
                    for (int i = 0; i < 36; i++) {
                        ItemStack cand = inv.getItem(i);
                        if (cand.isEmpty()) continue;
                        if (cand.getItem() instanceof net.minecraft.world.item.ArmorItem ai && ai.getEquipmentSlot() == es) { found = i; break; }
                    }
                    if (found < 0) {
                        // 没有可换的护甲件:若当前槽已有护甲 ⇒ 脱下滑槽到空位(制造可观测变化),否则明确失败
                        if (armored.isEmpty()) {
                            return Outcome.fail("equip " + where + ": no " + where + " armor in backpack (0-35) and the armor slot is empty");
                        }
                        int empty = -1;
                        for (int i = 0; i < 36; i++) { if (inv.getItem(i).isEmpty()) { empty = i; break; } }
                        if (empty < 0) return Outcome.fail("equip " + where + ": no armor piece to equip and no empty backpack slot to unequip into");
                        inv.setItem(armorSlot, ItemStack.EMPTY);
                        inv.setItem(empty, armored);
                        inv.setChanged();
                        sp.containerMenu.broadcastChanges();
                        final int fEmpty = empty;
                        final String before0 = armorBefore;
                        return Outcome.pending(new Pending(sp.getUUID(),
                                "equip " + where + " UNEQUIPPED " + before0 + " -> s" + fEmpty,
                                "armor" + armorSlot + "=empty (was " + before0 + ")",
                                s -> s.getInventory().getItem(armorSlot).isEmpty() && !s.getInventory().getItem(fEmpty).isEmpty(),
                                s -> "armorBefore=" + before0 + " armorAfter=" + describe(s.getInventory().getItem(armorSlot))
                                        + " movedTo=s" + fEmpty + ":" + describe(s.getInventory().getItem(fEmpty))));
                    }
                    ItemStack candidate = inv.getItem(found);
                    String candDesc = describe(candidate);
                    inv.setItem(found, armored.isEmpty() ? ItemStack.EMPTY : armored);
                    inv.setItem(armorSlot, candidate);
                    inv.setChanged();
                    sp.containerMenu.broadcastChanges();
                    final int fFound = found;
                    final String before1 = armorBefore;
                    return Outcome.pending(new Pending(sp.getUUID(),
                            "equip " + where + ": " + candDesc + " s" + fFound + " -> armor" + armorSlot,
                            "armor" + armorSlot + "=" + candDesc,
                            // T20/D2 (pre-audit phase5): a bare "armor slot holds candDesc" test is satisfied by
                            // a same-item swap no-op. Also require the source slot to hold the piece that was
                            // displaced (or be empty when the armor slot was empty), so the exchange itself is
                            // part of the confirm predicate.
                            s -> describe(s.getInventory().getItem(armorSlot)).equals(candDesc)
                                    && describe(s.getInventory().getItem(fFound))
                                            .equals(armored.isEmpty() ? "empty" : before1),
                            s -> "armorBefore=" + before1 + " armorAfter=" + describe(s.getInventory().getItem(armorSlot))
                                    + " s" + fFound + "=" + describe(s.getInventory().getItem(fFound))));
                }
                case "select": {
                    if (!op.params().has("slot")) {
                        return Outcome.fail("select needs slot 0-8");
                    }
                    int slot = op.params().get("slot").getAsInt();
                    if (slot < 0 || slot > 8) {
                        return Outcome.fail("select slot must be 0-8, got " + slot);
                    }
                    sp.getInventory().selected = slot;
                    sp.connection.send(new ClientboundSetCarriedItemPacket(slot));
                    int want = slot;
                    return Outcome.pending(new Pending(sp.getUUID(),
                            "select " + slot, "selected=" + want,
                            s -> s.getInventory().selected == want,
                            s -> "selected=" + s.getInventory().selected));
                }
                case "toss": {
                    Inventory inv = sp.getInventory();
                    int s = op.params().has("slot") ? op.params().get("slot").getAsInt() : inv.selected;
                    if (s < 0 || s >= inv.getContainerSize()) {
                        return Outcome.fail("toss slot out of range: " + s);
                    }
                    ItemStack stack = inv.getItem(s);
                    if (stack.isEmpty()) {
                        return Outcome.fail("toss slot " + s + " is empty");
                    }
                    String what = describe(stack);
                    java.util.Set<UUID> before = ActActions.snapshotDrops(
                            sp.serverLevel(), sp.position());
                    inv.removeItemNoUpdate(s);
                    sp.drop(stack, false);
                    sp.containerMenu.broadcastChanges();
                    // task-27 vacuum fix: freshly tossed stacks landing at the
                    // thrower's feet are vacuumed back within ~10 ticks, which
                    // refills slots mid-confirm and drifts evidence. Pin the
                    // fresh drops' pickup delay for their whole lifespan.
                    // (Authentic play DOES vacuum — this pin is test-rig
                    // determinism, stated here, not physics.)
                    int pinned = pinFreshDrops(sp, before);
                    int fs = s;
                    return Outcome.pending(new Pending(sp.getUUID(),
                            "toss s" + s + " " + what + " pinned=" + pinned, "slot " + fs + " empty",
                            p -> p.getInventory().getItem(fs).isEmpty(),
                            p -> slotDescribe(p, fs)));
                }
                default:
                    return Outcome.fail("action must be open|click|drag|select|toss, got '" + action + "'");
            }
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            return Outcome.fail(c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : ""));
        }
    }

    // ---- primitives (server thread, direct inventory mutation + broadcast) ----

    /** Vanilla PICKUP click against the menu cursor. Returns null on success, else a reason. */
    static String clickSlot(ServerPlayer sp, int slot, int button) {
        Inventory inv = sp.getInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) {
            return "slot out of range 0-" + (inv.getContainerSize() - 1) + ": " + slot;
        }
        ItemStack carried = sp.containerMenu.getCarried();
        ItemStack inSlot = inv.getItem(slot);
        if (button == 0) {
            if (carried.isEmpty()) {
                sp.containerMenu.setCarried(inSlot.copy());
                inv.setItem(slot, ItemStack.EMPTY);
            } else if (inSlot.isEmpty()) {
                inv.setItem(slot, carried.copy());
                sp.containerMenu.setCarried(ItemStack.EMPTY);
            } else if (ItemStack.isSameItemSameTags(carried, inSlot)
                    && inSlot.getCount() < inSlot.getMaxStackSize()) {
                int move = Math.min(carried.getCount(), inSlot.getMaxStackSize() - inSlot.getCount());
                inSlot.grow(move);
                carried.shrink(move);
                if (carried.isEmpty()) {
                    sp.containerMenu.setCarried(ItemStack.EMPTY);
                }
            } else {
                inv.setItem(slot, carried.copy());
                sp.containerMenu.setCarried(inSlot.copy());
            }
        } else {
            if (carried.isEmpty()) {
                if (inSlot.isEmpty()) {
                    return "right-click on empty slot with empty cursor";
                }
                int half = (inSlot.getCount() + 1) / 2;
                sp.containerMenu.setCarried(inSlot.copyWithCount(half));
                inSlot.shrink(half);
                if (inSlot.isEmpty()) {
                    inv.setItem(slot, ItemStack.EMPTY);
                }
            } else if (inSlot.isEmpty()
                    || (ItemStack.isSameItemSameTags(carried, inSlot)
                    && inSlot.getCount() < inSlot.getMaxStackSize())) {
                ItemStack one = carried.copyWithCount(1);
                if (inSlot.isEmpty()) {
                    inv.setItem(slot, one);
                } else {
                    inSlot.grow(1);
                }
                carried.shrink(1);
                if (carried.isEmpty()) {
                    sp.containerMenu.setCarried(ItemStack.EMPTY);
                }
            } else {
                return "right-click cannot place (occupied by other item)";
            }
        }
        inv.setChanged();
        return null;
    }

    /** mineflayer moveSlotItem: pick up, place, put the remainder back. */
    static String dragSlots(ServerPlayer sp, int from, int to) {
        Inventory inv = sp.getInventory();
        int n = inv.getContainerSize();
        if (from < 0 || from >= n || to < 0 || to >= n) {
            return "drag range 0-" + (n - 1) + ": " + from + "->" + to;
        }
        if (from == to) {
            return "drag from==to";
        }
        if (!sp.containerMenu.getCarried().isEmpty()) {
            return "cursor busy, click it empty first";
        }
        String err = clickSlot(sp, from, 0);
        if (err != null) {
            return err;
        }
        err = clickSlot(sp, to, 0);
        if (err != null) {
            return err;
        }
        if (!sp.containerMenu.getCarried().isEmpty()) {
            err = clickSlot(sp, from, 0);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    /**
     * task-27 vacuum fix: pin fresh drops so the standing thrower cannot
     * vacuum them back mid-confirm (slot drift). Delay covers the whole
     * entity lifespan; floor trash stays visible for recon. Never throws.
     *
     * @return number of entities pinned
     */
    static int pinFreshDrops(ServerPlayer sp, Set<UUID> before) {
        int pinned = 0;
        try {
            for (ItemEntity e : sp.serverLevel().getEntitiesOfClass(ItemEntity.class,
                    sp.getBoundingBox().inflate(ActActions.DROP_RADIUS))) {
                if (before.contains(e.getUUID())) {
                    continue;
                }
                try {
                    e.setPickUpDelay(6000);
                    pinned++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return pinned;
    }

    // ---- evidence helpers (concise: changed slots only) ----

    static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return stack.getItem().toString() + "x" + stack.getCount();
    }

    static String slotDescribe(ServerPlayer sp, int slot) {
        Inventory inv = sp.getInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) {
            return "s" + slot + "=OOR";
        }
        return "s" + slot + "=" + describe(inv.getItem(slot));
    }

    static String listInventory(ServerPlayer sp) {
        Inventory inv = sp.getInventory();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(i).append(':').append(describe(s));
            }
        }
        sb.append(" off=").append(describe(sp.getOffhandItem()));
        return sb.toString();
    }
}

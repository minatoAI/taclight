package dev.taclight.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.taclight.TacLightMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.util.Locale;

/**
 * F4/F5 状态读取 + 断言 (v0.2).
 *
 * <p>{@code state} op — read-only queries (immediate, never mutates):
 * {@code health|armor|buffs|held|inv|aim|pose|pose2|lamp|using|all}.
 * <ul>
 *   <li>{@code health} — hp / max / absorption.</li>
 *   <li>{@code armor} — armor value / toughness.</li>
 *   <li>{@code buffs} — active effects {@code id:amp:durationTicks}.</li>
 *   <li>{@code held} — main/off hand stacks.</li>
 *   <li>{@code inv} — concise backpack listing (reuses
 *       {@link InvActions#listInventory}, non-empty only).</li>
 *   <li>{@code aim} — server-side crosshair raycast ({@code OUTLINE},
 *       reach 4.5 / 5 creative): {@code block id@pos} / {@code entity
 *       type#id} / {@code miss}, with hit vec + distance.</li>
 *   <li>{@code pose} — feet pos + yaw/pitch (standalone {@code dump}).</li>
 *   <li>{@code pose2} — v0.3 posture block: sprinting/sneaking/onGround +
 *       yaw + last move direction yaw (gun-pose asserts).</li>
 *   <li>{@code lamp} — TacLight light state (handheld/gun, read-only).</li>
 *   <li>{@code using} — using flag + useItem (session liveness probe).</li>
 *   <li>{@code all} — everything below in one snapshot (also the
 *       assert-fail dump shape).</li>
 * </ul>
 *
 * <p>{@code assert} op — {@code {"checks":[...]}} evaluated against the
 * live snapshot. Check shape:
 * {@code {"what":…, "op":…[, "value":…]}}. Numeric whats
 * ({@code health,maxhealth,food,saturation,armor,toughness,air,level}):
 * {@code eq|ne|ge|gt|le|lt} + number. String whats
 * ({@code heldMain,heldOff,lamp,aimKind,aimBlock,aimEntity}): {@code
 * eq|ne|contains} + string ({@code lamp} wants {@code on|off};
 * {@code aimKind} wants {@code block|entity|miss}). Boolean whats
 * ({@code using,sprinting,sneaking,onGround}): boolean value. Numeric
 * whats also include {@code moveYaw} (last move direction, degrees). Set whats:
 * {@code buff} (contains effect id), {@code inv} (contains item id,
 * optional {@code value} = min count).
 *
 * <p>Fail discipline (败吐全量 + 图 + 行号): the FIRST failing clause
 * aborts the op with clause index (行号), the check JSON, want-vs-actual,
 * the FULL state snapshot (全量), plus a screenshot (图,
 * {@code screenshots/assert-<ticket>-<n>.png}) taken on the client
 * thread at failure time — same framebuffer path as the {@code shot} op.
 *
 * <p>Threading: client tick (dispatch context) for the screenshot half,
 * server player resolved the usual way. Read-only throughout — no world
 * mutation, no inventory mutation, no OS input.
 */
public final class StateActions {
    private static int assertSeq;

    private StateActions() {}

    /** Runs one {@code state} query. Returns the payload or throws with a reason. */
    static JsonObject query(Minecraft mc, String what) throws IllegalArgumentException {
        ServerPlayer sp = resolve(mc);
        JsonObject extra = new JsonObject();
        switch (what) {
            case "health" -> {
                extra.addProperty("hp", round1(sp.getHealth()));
                extra.addProperty("max", round1(sp.getMaxHealth()));
                extra.addProperty("absorption", round1(sp.getAbsorptionAmount()));
            }
            case "armor" -> {
                extra.addProperty("armor", sp.getArmorValue());
                extra.addProperty("toughness", round1(sp.getAttributeValue(Attributes.ARMOR_TOUGHNESS)));
            }
            case "buffs" -> extra.addProperty("buffs", buffsLine(sp));
            case "held" -> {
                extra.addProperty("main", InvActions.describe(sp.getMainHandItem()));
                extra.addProperty("off", InvActions.describe(sp.getOffhandItem()));
            }
            case "inv" -> {
                extra.addProperty("list", InvActions.listInventory(sp));
                extra.addProperty("selected", sp.getInventory().selected);
            }
            case "aim" -> extra.add("aim", aimJson(sp));
            case "pose" -> {
                Vec3 v = sp.position();
                extra.addProperty("pos", String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z));
                extra.addProperty("yaw", Math.round(sp.getYRot() * 100.0) / 100.0);
                extra.addProperty("pitch", Math.round(sp.getXRot() * 100.0) / 100.0);
            }
            case "lamp" -> {
                extra.addProperty("handheld", dev.taclight.sync.PlayerLightAccess.flashlight(sp));
                extra.addProperty("gun", dev.taclight.sync.PlayerLightAccess.gunLight(sp));
            }
            case "pose2" -> {
                // v0.3 posture block: stance + facing + last move direction.
                extra.addProperty("sprinting", sp.isSprinting());
                extra.addProperty("sneaking", sp.isShiftKeyDown());
                extra.addProperty("onGround", sp.onGround());
                extra.addProperty("yaw", Math.round(sp.getYRot() * 100.0) / 100.0);
                Double moveYaw = ActActions.moveYaw();
                if (moveYaw != null) {
                    extra.addProperty("moveYaw", Math.round(moveYaw * 10.0) / 10.0);
                }
            }
            case "using" -> {
                extra.addProperty("using", sp.isUsingItem());
                extra.addProperty("useItem", InvActions.describe(sp.getUseItem()));
                // Remaining-ticks trajectory (killer-trap instrument): a
                // normally ticking session decrements this every server
                // tick; frozen-at-full + death = server never ticked it
                // (dispatch visibility), decrement-then-death = active kill.
                try {
                    extra.addProperty("useRemaining", sp.getUseItemRemainingTicks());
                } catch (Throwable ignored) {
                }
            }
            case "all" -> {
                return snapshot(sp);
            }
            default -> throw new IllegalArgumentException(
                    "what must be health|armor|buffs|held|inv|aim|pose|pose2|lamp|using|all, got '"
                            + what + "'");
        }
        return extra;
    }

    /** Full state snapshot (also the assert-fail dump shape). */
    static JsonObject snapshot(ServerPlayer sp) {
        JsonObject root = new JsonObject();
        root.addProperty("hp", round1(sp.getHealth()));
        root.addProperty("max", round1(sp.getMaxHealth()));
        root.addProperty("absorption", round1(sp.getAbsorptionAmount()));
        root.addProperty("food", sp.getFoodData().getFoodLevel());
        root.addProperty("saturation", round1(sp.getFoodData().getSaturationLevel()));
        root.addProperty("air", sp.getAirSupply());
        root.addProperty("armor", sp.getArmorValue());
        root.addProperty("toughness", round1(sp.getAttributeValue(Attributes.ARMOR_TOUGHNESS)));
        root.addProperty("level", sp.experienceLevel);
        root.addProperty("buffs", buffsLine(sp));
        root.addProperty("main", InvActions.describe(sp.getMainHandItem()));
        root.addProperty("off", InvActions.describe(sp.getOffhandItem()));
        root.addProperty("inv", InvActions.listInventory(sp));
        root.addProperty("selected", sp.getInventory().selected);
        Vec3 v = sp.position();
        root.addProperty("pos", String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z));
        root.addProperty("yaw", Math.round(sp.getYRot() * 100.0) / 100.0);
        root.addProperty("pitch", Math.round(sp.getXRot() * 100.0) / 100.0);
        root.add("aim", aimJson(sp));
        root.addProperty("using", sp.isUsingItem());
        root.addProperty("useItem", InvActions.describe(sp.getUseItem()));
        root.addProperty("sprinting", sp.isSprinting());
        root.addProperty("sneaking", sp.isShiftKeyDown());
        root.addProperty("onGround", sp.onGround());
        Double moveYaw = ActActions.moveYaw();
        if (moveYaw != null) {
            root.addProperty("moveYaw", Math.round(moveYaw * 10.0) / 10.0);
        }
        try {
            root.addProperty("useRemaining", sp.getUseItemRemainingTicks());
        } catch (Throwable ignored) {
        }
        try {
            root.addProperty("lampHandheld", dev.taclight.sync.PlayerLightAccess.flashlight(sp));
            root.addProperty("lampGun", dev.taclight.sync.PlayerLightAccess.gunLight(sp));
        } catch (Throwable t) {
            root.addProperty("lamp", "unavailable");
        }
        return root;
    }

    /** Evaluates assert checks. Returns null when all pass, else a fail detail object. */
    static JsonObject checkAll(ServerPlayer sp, JsonArray checks) {
        int i = 0;
        for (JsonElement e : checks) {
            JsonObject c = e.getAsJsonObject();
            String what = c.has("what") ? c.get("what").getAsString() : "";
            String op = c.has("op") ? c.get("op").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            String fail = evalOne(sp, what, op, c.has("value") ? c.get("value") : null);
            if (fail != null) {
                JsonObject detail = new JsonObject();
                detail.addProperty("clause", i);
                detail.add("check", c);
                detail.addProperty("actual", fail);
                detail.add("snapshot", snapshot(sp));
                return detail;
            }
            i++;
        }
        return null;
    }

    /** Returns null on pass, else the actual-value string. */
    static String evalOne(ServerPlayer sp, String what, String op, JsonElement value) {
        switch (what) {
            case "health":
                return cmpNum(sp.getHealth(), op, num(value));
            case "maxhealth":
                return cmpNum(sp.getMaxHealth(), op, num(value));
            case "food":
                return cmpNum(sp.getFoodData().getFoodLevel(), op, num(value));
            case "saturation":
                return cmpNum(sp.getFoodData().getSaturationLevel(), op, num(value));
            case "armor":
                return cmpNum(sp.getArmorValue(), op, num(value));
            case "toughness":
                return cmpNum(sp.getAttributeValue(Attributes.ARMOR_TOUGHNESS), op, num(value));
            case "air":
                return cmpNum(sp.getAirSupply(), op, num(value));
            case "level":
                return cmpNum(sp.experienceLevel, op, num(value));
            case "heldMain":
                return cmpStr(idOf(sp.getMainHandItem()), op, str(value));
            case "heldOff":
                return cmpStr(idOf(sp.getOffhandItem()), op, str(value));
            case "lamp": {
                boolean on;
                try {
                    on = dev.taclight.sync.PlayerLightAccess.flashlight(sp)
                            || dev.taclight.sync.PlayerLightAccess.gunLight(sp);
                } catch (Throwable t) {
                    return "unavailable";
                }
                return cmpStr(on ? "on" : "off", op, str(value));
            }
            case "aimKind":
                return cmpStr(aimKind(sp), op, str(value));
            case "aimBlock":
                return cmpStr(aimBlock(sp), op, str(value));
            case "aimEntity":
                return cmpStr(aimEntity(sp), op, str(value));
            case "buff":
                return containsOp(buffsLine(sp), op, str(value));
            case "using": {
                // Boolean what: value true/false (killer-trap queries).
                boolean want = true;
                try {
                    if (value != null && !value.isJsonNull()) {
                        want = value.getAsBoolean();
                    }
                } catch (Throwable t) {
                    return "using wants boolean value";
                }
                boolean actual = sp.isUsingItem();
                if (actual == want) {
                    return null;
                }
                return "using=" + actual + " useItem=" + InvActions.describe(sp.getUseItem());
            }
            case "sprinting":
                return cmpBool(sp.isSprinting(), value);
            case "sneaking":
                return cmpBool(sp.isShiftKeyDown(), value);
            case "onGround":
                return cmpBool(sp.onGround(), value);
            case "moveYaw": {
                Double yaw = ActActions.moveYaw();
                if (yaw == null) {
                    return "no move yet";
                }
                return cmpNum(yaw, op, num(value));
            }
            case "inv": {
                String list = InvActions.listInventory(sp);
                String want = str(value);
                if (!list.contains(want)) {
                    return list;
                }
                if (value != null && value.isJsonObject() && value.getAsJsonObject().has("min")) {
                    // {"id":…, "min":n} object form
                    int min = value.getAsJsonObject().get("min").getAsInt();
                    int have = countInInv(sp, want);
                    if (have < min) {
                        return want + "x" + have;
                    }
                }
                return null;
            }
            default:
                return "unknown what '" + what + "'";
        }
    }

    // ---- readers ----

    static ServerPlayer resolve(Minecraft mc) {
        if (mc.player == null) {
            throw new IllegalStateException("no player");
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("no integrated server");
        }
        ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (sp == null) {
            throw new IllegalStateException("server player gone");
        }
        return sp;
    }

    private static String buffsLine(ServerPlayer sp) {
        StringBuilder sb = new StringBuilder();
        for (MobEffectInstance eff : sp.getActiveEffects()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(eff.getEffect().toString())
                    .append(':').append(eff.getAmplifier())
                    .append(':').append(eff.getDuration());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static JsonObject aimJson(ServerPlayer sp) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", aimKind(sp));
        o.addProperty("block", aimBlock(sp));
        o.addProperty("entity", aimEntity(sp));
        HitResult hit = raycast(sp);
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            Vec3 l = hit.getLocation();
            o.addProperty("at", String.format(Locale.ROOT, "%.2f,%.2f,%.2f", l.x, l.y, l.z));
            o.addProperty("dist", Math.round(sp.getEyePosition().distanceTo(l) * 100.0) / 100.0);
        }
        return o;
    }

    private static String aimKind(ServerPlayer sp) {
        HitResult hit = raycast(sp);
        if (hit == null) {
            return "miss";
        }
        return switch (hit.getType()) {
            case BLOCK -> "block";
            case ENTITY -> "entity";
            default -> "miss";
        };
    }

    private static String aimBlock(ServerPlayer sp) {
        HitResult hit = raycast(sp);
        if (hit instanceof BlockHitResult block) {
            return sp.serverLevel().getBlockState(block.getBlockPos()).getBlock().toString()
                    + "@" + block.getBlockPos().toShortString();
        }
        return "-";
    }

    private static String aimEntity(ServerPlayer sp) {
        HitResult hit = raycast(sp);
        if (hit instanceof EntityHitResult entity) {
            Entity e = entity.getEntity();
            return e.getType().toShortString() + "#" + e.getId();
        }
        return "-";
    }

    private static HitResult raycast(ServerPlayer sp) {
        try {
            double reach = sp.gameMode.isCreative() ? 5.0 : 4.5;
            Vec3 eye = sp.getEyePosition();
            Vec3 look = sp.getLookAngle();
            Vec3 to = eye.add(look.scale(reach));
            BlockHitResult block = sp.serverLevel().clip(
                    new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, sp));
            double dist = block.getType() == HitResult.Type.MISS ? reach
                    : eye.distanceTo(block.getLocation());
            Entity found = null;
            double best = dist;
            for (Entity e : sp.serverLevel().getEntities(sp,
                    sp.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0))) {
                if (!(e instanceof LivingEntity) || e.isSpectator()) {
                    continue;
                }
                AABB box = e.getBoundingBox().inflate(0.3);
                var clip = box.clip(eye, to);
                if (clip.isPresent()) {
                    double d = eye.distanceTo(clip.get());
                    if (d < best) {
                        best = d;
                        found = e;
                    }
                }
            }
            if (found != null) {
                return new EntityHitResult(found);
            }
            return block;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countInInv(ServerPlayer sp, String id) {
        int n = 0;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && idOf(s).contains(id)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static String idOf(ItemStack s) {
        return s == null || s.isEmpty() ? "empty" : s.getItem().toString();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double num(JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return Double.NaN;
        }
        try {
            return v.getAsDouble();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private static String str(JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return "";
        }
        if (v.isJsonObject() && v.getAsJsonObject().has("id")) {
            return v.getAsJsonObject().get("id").getAsString();
        }
        try {
            return v.getAsString();
        } catch (Throwable t) {
            return v.toString();
        }
    }

    /** Null = pass. NaN value = unusable check (fail loud, not silent). */
    private static String cmpNum(double actual, String op, double want) {
        String a = fmtNum(actual);
        if (Double.isNaN(want)) {
            return a + " (no numeric value)";
        }
        boolean pass = switch (op) {
            case "eq" -> actual == want;
            case "ne" -> actual != want;
            case "ge" -> actual >= want;
            case "gt" -> actual > want;
            case "le" -> actual <= want;
            case "lt" -> actual < want;
            default -> false;
        };
        if (!pass && !(op.equals("eq") || op.equals("ne") || op.equals("ge")
                || op.equals("gt") || op.equals("le") || op.equals("lt"))) {
            return a + " (unknown op '" + op + "')";
        }
        return pass ? null : a;
    }

    private static String cmpStr(String actual, String op, String want) {
        boolean pass = switch (op) {
            case "eq" -> actual.equals(want);
            case "ne" -> !actual.equals(want);
            case "contains" -> actual.contains(want);
            default -> false;
        };
        if (!pass && !(op.equals("eq") || op.equals("ne") || op.equals("contains"))) {
            return actual + " (unknown op '" + op + "')";
        }
        return pass ? null : actual;
    }

    private static String cmpBool(boolean actual, com.google.gson.JsonElement value) {
        boolean want = true;
        try {
            if (value != null && !value.isJsonNull()) {
                want = value.getAsBoolean();
            }
        } catch (Throwable t) {
            return actual + " (wants boolean value)";
        }
        return actual == want ? null : String.valueOf(actual);
    }

    private static String containsOp(String haystack, String op, String needle) {
        if (!op.equals("contains") && !op.equals("eq")) {
            return haystack + " (op must be contains|eq)";
        }
        boolean pass = op.equals("eq") ? haystack.equals(needle) : haystack.contains(needle);
        return pass ? null : haystack;
    }

    private static String fmtNum(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    /** Screenshot for assert-fail evidence (client framebuffer, same path as shot). Returns file ref or "-". */
    static String snapAssertShot(Minecraft mc, String ticket) {
        try {
            File dir = new File(mc.gameDirectory, "screenshots");
            if (!dir.exists() && !dir.mkdirs()) {
                return "-";
            }
            String filename = "taclight-assert-" + ticket + "-" + (++assertSeq) + ".png";
            var img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                img.writeToFile(new File(dir, filename));
                return "screenshots/" + filename;
            } finally {
                img.close();
            }
        } catch (Throwable t) {
            return "-";
        }
    }

    /** For bridge diagnostics. */
    static String shortErr(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }
}

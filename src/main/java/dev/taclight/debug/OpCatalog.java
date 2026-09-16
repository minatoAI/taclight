package dev.taclight.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.taclight.TacLightMod;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F7 op 自描述 + 切换钩子 + LLM 摘要, F12 真人 vs 假人对照表 (v0.2).
 *
 * <p>Shape borrowed from {@code IMaidTask} (task-18 evidence):
 * every op carries a UID, a human description, enable/prepare hooks and
 * an English one-liner summary for LLM consumption
 * ({@code getMaidActionSummary} twin).
 *
 * <p>Switch hooks ({@link #beforeOp}): run once when the ticket pump
 * switches INTO an op, before it executes. The one real hook today:
 * entering an inventory-mutating op ({@code inv select/click/drag})
 * while an item is being used releases it first — vanilla silently
 * drops the using session on held-item change ({@code heldItemChanged}),
 * which would otherwise contaminate evidence without a trace.
 *
 * <p>F12 divergence table ({@link #divergence}): per-op known
 * real-human vs fake-player differences. Our rig drives the REAL
 * server player, so most rows read "N/A (real)"; the table exists as a
 * standing obligation — any future fake-player path must consult it,
 * and {@code describe} always prints it next to the op.
 */
public final class OpCatalog {
    /** One op descriptor. */
    static final class Desc {
        final String params;
        final String desc;
        final String summary;
        final String divergence;

        Desc(String params, String desc, String summary, String divergence) {
            this.params = params;
            this.desc = desc;
            this.summary = summary;
            this.divergence = divergence;
        }
    }

    private static final Map<String, Desc> OPS = new LinkedHashMap<>();

    static {
        put("scene", "name[,x,y,z]", "Build a taclight scene preset via the real /taclight scene executor.",
                "Build taclight scene preset wall/grass/corridor/bloom/duo.",
                "N/A (real): fake players cannot run scene commands (no permission context).");
        put("pose", "x,y,z,yaw,pitch", "Teleport + rotate via vanilla /tp with settle closed-loop (1deg/0.7).",
                "Teleport player to exact pose with settle verification.",
                "N/A (real): fake players lack client camera; settle reads would be server-only.");
        put("look", "yaw,pitch", "Rotate in place via vanilla /tp with settle closed-loop.",
                "Rotate view direction with settle verification.",
                "N/A (real): same as pose.");
        put("sweep", "yaw[,pitch,seconds,mode,ease,x,y,z]", "Human-speed interpolated move (4.3m/s, 150deg/s, smoothstep).",
                "Move/look at human speed with easing and clamps.",
                "N/A (real): fake players have no client camera to converge.");
        put("shot", "name", "Screenshot to screenshots/<file> with P/S/F CSV tokens.",
                "Capture screenshot evidence PNG.",
                "FAKE-DIVERGENT: fake players render nothing client-side; shots would be blank/wrong view.");
        put("bench", "-", "One bench line (fps) into the result.",
                "Record performance bench line.",
                "N/A (real).");
        put("dump", "-", "Position/yaw/pitch/bench snapshot into the result.",
                "Dump pose and bench snapshot.",
                "N/A (real).");
        put("wait_ticks", "[n]", "Idle n client ticks (settle windows).",
                "Wait N client ticks.",
                "N/A (real).");
        put("command", "line", "Whitelisted vanilla/taclight server command (@s gets caller entity).",
                "Run whitelisted server command.",
                "N/A (real): fake players may lack permission/claimed-chunk rights (cf. Create owner-spoof).");
        put("rec", "mode", "Session recorder start/stop/status (C/B/E CSV rows).",
                "Control session CSV recording.",
                "N/A (real).");
        put("light", "mode=on|off|toggle", "TacLight lamp via /taclight light @s (off-baseline arms).",
                "Switch TacLight lamp on/off/toggle.",
                "N/A (real).");
        put("tm", "mode=on|off|status", "VL 64<->32 formal switch (temporal reuse flag).",
                "Switch volumetric temporal-reuse 64-step vs 32-step arms.",
                "N/A (real).");
        put("inv", "action= open|click|drag|select|equip|toss …", "F1 human code-level backpack ops with confirm loop.",
                "Backpack ops: open/click/drag/select/equip/toss with server confirm. equip slot=head|chest|legs|feet moves an armor piece from backpack 0-35 into vanilla armor slot 36-39 (or unequips into an empty slot) and echoes armorBefore/armorAfter.",
                "FAKE-DIVERGENT: fake players have no client inventory screen; containerMenu sync semantics differ.");
        put("use", "target= block|air|entity|eat|start|stop|attack-block|attack-entity …",
                "F2 human packet-order item use with cooldown and drivers.",
                "Use item: right-click tiers, eat, start/stop, attack with evidence.",
                "FAKE-DIVERGENT: entity.interact paths check player type (cf. maid FakePlayer refusal).");
        put("act", "verb= place|dig|hit|door|jump|sneak|sprint|move|camera …",
                "F3 world verbs with permission gates + drop capture; v0.3 sprint/move/camera.",
                "World verbs with permission gates, drop capture, sprint/move/camera.",
                "FAKE-DIVERGENT: mayInteract/claims treat fake players differently; drops need inventory (has it).");
        put("state", "what= health|armor|buffs|held|inv|aim|pose|pose2|lamp|using|all",
                "F4 read-only state query (no mutation; using/useItem = session liveness probe; pose2 = v0.3 posture).",
                "Query player/world/lamp/using/posture state read-only.",
                "N/A (real).");
        put("assert", "checks[]", "F5 clause checks; first failure dumps snapshot + screenshot.",
                "Assert state clauses with verbose failure evidence.",
                "FAKE-DIVERGENT: screenshots meaningless without a real client view.");
        put("describe", "[name]", "This catalog: op list or single-op detail + LLM summaries.",
                "Describe ticket ops for agents and LLMs.",
                "N/A (real).");
    }

    private static void put(String name, String params, String desc, String summary, String divergence) {
        OPS.put(name, new Desc(params, desc, summary, divergence));
    }

    /** Full catalog as JSON (describe op, LLM summaries included). */
    static JsonObject catalogJson() {
        JsonObject root = new JsonObject();
        JsonArray list = new JsonArray();
        for (Map.Entry<String, Desc> e : OPS.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("op", e.getKey());
            o.addProperty("params", e.getValue().params);
            o.addProperty("desc", e.getValue().desc);
            o.addProperty("summary", e.getValue().summary);
            o.addProperty("divergence", e.getValue().divergence);
            list.add(o);
        }
        root.add("ops", list);
        root.addProperty("executor", "real");
        return root;
    }

    /** Single-op detail, or null when unknown. */
    static JsonObject describeJson(String name) {
        Desc d = OPS.get(name);
        if (d == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("op", name);
        o.addProperty("params", d.params);
        o.addProperty("desc", d.desc);
        o.addProperty("summary", d.summary);
        o.addProperty("divergence", d.divergence);
        return o;
    }

    static String knownOpsLine() {
        return String.join("|", OPS.keySet());
    }

    /**
     * Switch hook: runs when the pump switches INTO an op, before dispatch.
     * Today: entering held-changing inv ops while using an item releases
     * it first (vanilla drops using state on held change — silent evidence
     * contamination otherwise). Covered: select/click/drag (cursor + slots)
     * AND toss (emptying the held slot kills the session just as dead;
     * task-27 gap fix). Open is read-only and excluded. Returns a note
     * for the log, or null.
     */
    static String beforeOp(Minecraft mc, String opName, TicketBridge.Op op) {
        if (!opName.equals("inv")) {
            return null;
        }
        String action = "";
        try {
            action = op.params().has("action") ? op.params().get("action").getAsString() : "";
        } catch (Throwable ignored) {
        }
        String a = action.trim().toLowerCase(java.util.Locale.ROOT);
        if (!a.equals("select") && !a.equals("click") && !a.equals("drag") && !a.equals("toss")) {
            return null;
        }
        try {
            if (mc.player == null || mc.getSingleplayerServer() == null) {
                return null;
            }
            ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            if (sp != null && sp.isUsingItem()) {
                sp.releaseUsingItem();
                TacLightMod.LOGGER.info("[TacLight][agent] switch hook: released using item before inv {}",
                        action);
                return "using-released-before-inv-" + action;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

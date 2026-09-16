package dev.taclight.debug;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * F11 收票口环境自检 + 票据试次版本 (v0.2).
 *
 * <p>VPT {@code validate_env} twin: before a ticket is accepted, the
 * environment it assumes is checked and snapshotted. Hard gates fail the
 * claim LOUD (dead player, vanished server player); soft facts
 * (dimension, time, weather, difficulty, gamemode, pose) are recorded
 * into the result as the leading {@code env} entry, so every evidence
 * bundle states the world it ran in.
 *
 * <p>Trial versions (Voyager {@code trial1/2/3} twin): an optional
 * top-level {@code "trial"} names the attempt. Results carry it, and the
 * outbox archive stamps it — re-running the same ticket under a new
 * trial never destroys older arms (task-9 archive), and trials stay
 * comparable.
 */
public final class TicketEnv {
    private TicketEnv() {}

    /** Result of the claim-time check: {@code fail} set, or {@code env} snapshot. */
    static final class Report {
        String fail;
        JsonObject env;
    }

    /** Hard gates + soft snapshot. Never throws. */
    static Report check(Minecraft mc) {
        Report r = new Report();
        try {
            if (mc.player == null || mc.level == null) {
                r.fail = "not in world";
                return r;
            }
            if (!mc.player.isAlive()) {
                r.fail = "player is dead (respawn first)";
                return r;
            }
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                r.fail = "no integrated server";
                return r;
            }
            ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (sp == null) {
                r.fail = "server player gone";
                return r;
            }
            if (sp.isSpectator()) {
                r.fail = "spectator cannot drive mutating ops (switch gamemode first)";
                return r;
            }
            ServerLevel level = sp.serverLevel();
            JsonObject env = new JsonObject();
            env.addProperty("dimension", level.dimension().location().toString());
            env.addProperty("time", level.getDayTime() % 24000L);
            env.addProperty("raining", level.isRaining());
            env.addProperty("thundering", level.isThundering());
            env.addProperty("difficulty", level.getDifficulty().getDisplayName().getString());
            env.addProperty("gamemode", sp.gameMode.getGameModeForPlayer().getName());
            var p = sp.position();
            env.addProperty("pos", String.format(Locale.ROOT, "%.2f,%.2f,%.2f", p.x, p.y, p.z));
            env.addProperty("executor", "real");
            r.env = env;
            return r;
        } catch (Throwable t) {
            r.fail = shortErr(t);
            return r;
        }
    }

    /** Top-level {@code "trial"} or the default first trial. */
    static String trialOf(com.google.gson.JsonObject root) {
        try {
            if (root.has("trial") && root.get("trial").isJsonPrimitive()) {
                String t = root.get("trial").getAsString().trim();
                if (!t.isEmpty()) {
                    return t.replaceAll("[^A-Za-z0-9-_]", "_");
                }
            }
        } catch (Throwable ignored) {
        }
        return "t1";
    }

    private static String shortErr(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }
}

package dev.taclight.debug;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Human-speed sweep interpolator, backported from the new SpotViz harness
 * ({@code com.spotviz.debug.SweepDriver}) so the OLD shader-pack pipeline can
 * be driven by the SAME tickets.
 *
 * <p>Why not reuse the old {@code CameraSweep}: it is render-frame linear with
 * no speed limit and no easing, so a 90-degree ticket snaps through in a way
 * no human hand ever moves (exactly the flicker source the old project spent
 * September chasing). This driver clamps to walk 4.3 m/s + turn 150 deg/s,
 * extends the duration instead of speeding up, and eases with smoothstep.
 *
 * <p>Client thread only. Applies directly to the client player per tick; the
 * ticket bridge issues one final server-side {@code tp} when done so the
 * integrated server entity converges.
 */
public final class HumanSweep {
    public static final double MAX_WALK_MS = 4.3;
    public static final double MAX_TURN_DEG_S = 150.0;

    private final Vec3 fromPos;
    private final Vec3 toPos;
    private final float fromYaw;
    private final float toYaw;
    private final float fromPitch;
    private final float toPitch;
    private final boolean movePos;
    private final int durationTicks;
    private final boolean smooth;
    private int tick;
    private final double requestedSeconds;
    private final double appliedSeconds;
    private final boolean clamped;

    private HumanSweep(Vec3 fromPos, Vec3 toPos, float fromYaw, float toYaw,
                       float fromPitch, float toPitch, boolean movePos,
                       int durationTicks, boolean smooth,
                       double requestedSeconds, double appliedSeconds, boolean clamped) {
        this.fromPos = fromPos;
        this.toPos = toPos;
        this.fromYaw = fromYaw;
        this.toYaw = toYaw;
        this.fromPitch = fromPitch;
        this.toPitch = toPitch;
        this.movePos = movePos;
        this.durationTicks = Math.max(1, durationTicks);
        this.smooth = smooth;
        this.tick = 0;
        this.requestedSeconds = requestedSeconds;
        this.appliedSeconds = appliedSeconds;
        this.clamped = clamped;
    }

    public static HumanSweep start(Minecraft mc, JsonObject params) {
        if (mc.player == null) {
            throw new IllegalStateException("no player");
        }
        Vec3 cur = mc.player.position();
        float curYaw = mc.player.getYRot();
        float curPitch = mc.player.getXRot();

        boolean hasX = params.has("x");
        boolean hasY = params.has("y");
        boolean hasZ = params.has("z");
        boolean hasYaw = params.has("yaw");
        boolean hasPitch = params.has("pitch");
        String modeRaw = params.has("mode")
                ? params.get("mode").getAsString().trim().toLowerCase(Locale.ROOT) : "both";
        boolean wantMove = !"look".equals(modeRaw);
        boolean wantLook = !"move".equals(modeRaw);
        boolean movePos = wantMove && (hasX || hasY || hasZ);
        boolean turnAngles = wantLook && (hasYaw || hasPitch);

        double tx = hasX && wantMove ? params.get("x").getAsDouble() : cur.x;
        double ty = hasY && wantMove ? params.get("y").getAsDouble() : cur.y;
        double tz = hasZ && wantMove ? params.get("z").getAsDouble() : cur.z;
        float tyaw = hasYaw && wantLook ? (float) params.get("yaw").getAsDouble() : curYaw;
        float tpitch = hasPitch && wantLook ? (float) params.get("pitch").getAsDouble() : curPitch;

        double seconds = params.has("seconds") ? params.get("seconds").getAsDouble() : 2.0;
        if (!Double.isFinite(seconds)) {
            throw new IllegalArgumentException("seconds must be finite");
        }
        seconds = Math.max(0.2, Math.min(30.0, seconds));
        String easeRaw = params.has("ease")
                ? params.get("ease").getAsString().trim().toLowerCase(Locale.ROOT) : "smooth";
        boolean smooth = !"linear".equals(easeRaw);

        boolean clamped = false;
        double needSeconds = seconds;
        if (movePos) {
            double dist = Math.sqrt((tx - cur.x) * (tx - cur.x)
                    + (ty - cur.y) * (ty - cur.y) + (tz - cur.z) * (tz - cur.z));
            double need = dist / MAX_WALK_MS;
            if (need > needSeconds) {
                needSeconds = need;
                clamped = true;
            }
        }
        if (turnAngles) {
            float yawD = Math.abs(angleDiff(tyaw, curYaw));
            float pitchD = Math.abs(tpitch - curPitch);
            double need = Math.max(yawD, pitchD) / MAX_TURN_DEG_S;
            if (need > needSeconds) {
                needSeconds = need;
                clamped = true;
            }
        }
        float targetYaw = curYaw + angleDiff(tyaw, curYaw);
        int ticks = Math.max(1, (int) Math.round(needSeconds * 20.0));
        return new HumanSweep(cur, new Vec3(tx, ty, tz), curYaw, targetYaw,
                curPitch, tpitch, movePos, ticks, smooth, seconds, needSeconds, clamped);
    }

    /** One client-tick step. Returns true when finished. */
    public boolean tick(Minecraft mc) {
        if (mc.player == null) {
            return true;
        }
        tick++;
        float p = Math.min(1.0f, tick / (float) durationTicks);
        float e = smooth ? p * p * (3.0f - 2.0f * p) : p;
        if (movePos) {
            mc.player.setPos(fromPos.x + (toPos.x - fromPos.x) * e,
                    fromPos.y + (toPos.y - fromPos.y) * e,
                    fromPos.z + (toPos.z - fromPos.z) * e);
        }
        mc.player.setYRot(fromYaw + (toYaw - fromYaw) * e);
        mc.player.setXRot(fromPitch + (toPitch - fromPitch) * e);
        return tick >= durationTicks;
    }

    public String describe() {
        return String.format(Locale.ROOT,
                "sweep ticks=%d req=%.2fs applied=%.2fs clamped=%s move=%s ease=%s to=%.2f,%.2f,%.2f yaw=%.2f pitch=%.2f",
                durationTicks, requestedSeconds, appliedSeconds, clamped, movePos,
                smooth ? "smooth" : "linear", toPos.x, toPos.y, toPos.z, toYaw, toPitch);
    }

    public Vec3 toPos() {
        return toPos;
    }

    public float toYaw() {
        return toYaw;
    }

    public float toPitch() {
        return toPitch;
    }

    public boolean movesPos() {
        return movePos;
    }

    static float angleDiff(float want, float actual) {
        float d = (want - actual) % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}

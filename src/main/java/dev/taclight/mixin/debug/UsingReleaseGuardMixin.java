package dev.taclight.mixin.debug;

import dev.taclight.debug.UseActions;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * task-27 conviction fix: guard the single client-side RELEASE packet
 * path while a ticket owns a using session.
 *
 * <p>Mechanism (bytecode-proven, 1.20.1): our rig arms sessions
 * server-side only; the using flag syncs to the client; vanilla
 * {@code Minecraft.handleKeybinds} then sees local-using=true with the
 * use key up (unattended runs, forever) and "helpfully" calls
 * {@code MultiPlayerGameMode.releaseUsingItem} — the ONLY client-side
 * constructor of {@code ServerboundPlayerActionPacket(RELEASE_USE_ITEM)}
 * in all of vanilla (exhaustive scan). That packet murders every session
 * ~1 tick after arming. While {@link UseActions#isSessionArmed()}, this
 * guard cancels exactly that send (nothing else: no key states touched,
 * no visuals touched, no packets forged). Our own {@code stop} op and
 * ticket-finish release call the server session directly and never pass
 * through here.
 *
 * <p>Alternatives considered and rejected (see report): holding keyUse
 * down would trip the use-DOWN branch (phantom placements — evidence
 * contamination); starting on both ends doesn't clear the key gate.
 *
 * <p>Package placement is registration-driven (see UsingGateTrapMixin);
 * behavior change is confined to ticket-armed windows (normal play:
 * zero impact — the flag is false outside tickets).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class UsingReleaseGuardMixin {
    private static final Logger GUARD_LOG = LogManager.getLogger("UsingReleaseGuard");
    private static int suppressed;

    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void taclight$guardTicketSession(Player player, CallbackInfo ci) {
        if (!UseActions.isSessionArmed()) {
            return;
        }
        suppressed++;
        if (suppressed == 1 || suppressed % 100 == 0) {
            GUARD_LOG.info("[TacLight][agent] guarded ambient client release x{} (session armed)", suppressed);
        }
        ci.cancel();
    }
}

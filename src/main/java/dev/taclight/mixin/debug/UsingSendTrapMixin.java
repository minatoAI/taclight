package dev.taclight.mixin.debug;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * task-27 killer trap, part 2: the SEND side (TEMPORARY, default OFF).
 *
 * <p>Bytecode census proved the client constructs
 * {@code ServerboundPlayerActionPacket(RELEASE_USE_ITEM)} in exactly ONE
 * place: {@code MultiPlayerGameMode.releaseUsingItem} (vanilla calls it
 * from {@code handleKeybinds} only while the LOCAL player is using; no
 * mod in this instance constructs the packet). This hook logs every call
 * with the full stack, so the next trap ticket names the SENDER
 * red-handed (vanilla input path? TaCZ client? our code?).
 *
 * <p>Pairs with {@link UsingGateTrapMixin} (server receive side). Same
 * gate flag {@code -Dtaclight.usingTrap=true}. Client-only code: lives in
 * the {@code client} mixin section (never load on dedicated servers).
 *
 * <p>REMOVE AFTER CONVICTION.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class UsingSendTrapMixin {
    private static final Logger TRAP_LOG = LogManager.getLogger("UsingSendTrap");
    private static final boolean ENABLED = Boolean.getBoolean("taclight.usingTrap");

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void taclight$trapUsingSend(Player player, CallbackInfo ci) {
        if (!ENABLED) {
            return;
        }
        String line;
        try {
            ItemStack main = player.getMainHandItem();
            line = "USING-SEND player=" + player.getGameProfile().getName()
                    + " using=" + player.isUsingItem()
                    + " main=" + (main.isEmpty() ? "empty" : main.getItem() + "x" + main.getCount())
                    + " selected=" + player.getInventory().selected
                    + " thread=" + Thread.currentThread().getName();
        } catch (Throwable t) {
            line = "USING-SEND <evidence-error " + t + "> thread=" + Thread.currentThread().getName();
        }
        TRAP_LOG.warn("{}\n{}", line, stackTrace());
    }

    private static String stackTrace() {
        StringBuilder sb = new StringBuilder("caller-chain:");
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int start = -1;
        for (int i = 0; i < st.length; i++) {
            if (st[i].getMethodName().equals("releaseUsingItem")) {
                start = i + 1;
                break;
            }
        }
        int kept = 0;
        for (int i = start >= 0 ? start : 0; i < st.length; i++) {
            StackTraceElement e = st[i];
            String cn = e.getClassName();
            if (start < 0 && (cn.contains("UsingSendTrapMixin")
                    || e.getMethodName().equals("getStackTrace"))) {
                continue;
            }
            if (cn.startsWith("org.spongepowered.asm.mixin.")) {
                continue;
            }
            sb.append("\n    at ").append(e);
            if (++kept >= 30) {
                break;
            }
        }
        if (kept == 0) {
            sb.append(" <emptyjni>");
        }
        return sb.toString();
    }
}

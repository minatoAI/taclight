package dev.taclight.mixin.debug;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * task-27 killer trap (TEMPORARY diagnostic, default OFF).
 *
 * <p>Catches EVERY {@code LivingEntity.stopUsingItem} call red-handed with
 * the full caller stack. {@code releaseUsingItem} funnels through here too
 * (verified in 1.20.1 bytecode), so this one hook covers all vanilla
 * session-kill paths. Enable with JVM flag
 * {@code -Dtaclight.usingTrap=true} (e.g. via {@code JAVA_TOOL_OPTIONS});
 * toggling needs no rebuild (mixin applies at launch, gate is runtime).
 *
 * <p>Why a mixin (and not a ticket op): the killer runs inside the vanilla
 * server tick with no Forge event — only an in-call capture can name it.
 * Package placement ({@code dev.taclight.mixin.debug}) is registration
 * driven (mixin config resolves names against {@code dev.taclight.mixin});
 * the code is agent-owned, touches no game logic, returns nothing,
 * cancels nothing. Filtered to {@link ServerPlayer} (our sessions live
 * server-side) to keep the log clean.
 *
 * <p>REMOVE AFTER CONVICTION (or keep gated-off; default costs one
 * boolean check per stop call, i.e. nothing).
 */
@Mixin(LivingEntity.class)
public abstract class UsingGateTrapMixin {
    private static final Logger TRAP_LOG = LogManager.getLogger("UsingGateTrap");
    private static final boolean ENABLED = Boolean.getBoolean("taclight.usingTrap");

    @Inject(method = "stopUsingItem", at = @At("HEAD"))
    private void taclight$trapUsingKill(CallbackInfo ci) {
        if (!ENABLED) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayer sp)) {
            return;
        }
        String line;
        try {
            line = "USING-KILL entity=" + sp.getGameProfile().getName()
                    + " using=" + sp.isUsingItem()
                    + " useItem=" + describe(sp.getUseItem())
                    + " remaining=" + sp.getUseItemRemainingTicks()
                    + " hand=" + sp.getUsedItemHand()
                    + " main=" + describe(sp.getMainHandItem())
                    + " off=" + describe(sp.getOffhandItem())
                    + " selected=" + sp.getInventory().selected
                    + " thread=" + Thread.currentThread().getName();
        } catch (Throwable t) {
            line = "USING-KILL <evidence-error " + t + "> thread=" + Thread.currentThread().getName();
        }
        TRAP_LOG.warn("{}\n{}", line, stackTrace());
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return stack.getItem().toString() + "x" + stack.getCount();
    }

    private static String stackTrace() {
        StringBuilder sb = new StringBuilder("caller-chain:");
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        // Anchor on the stopUsingItem frame itself (the injection point):
        // everything above it is callers. If JIT inlined it away, fall back
        // to keeping everything except our own + getStackTrace frames
        // (never eat the whole stack again — task-27 zero-frame lesson).
        int start = -1;
        for (int i = 0; i < st.length; i++) {
            if (st[i].getMethodName().equals("stopUsingItem")) {
                start = i + 1;
                break;
            }
        }
        int kept = 0;
        for (int i = start >= 0 ? start : 0; i < st.length; i++) {
            StackTraceElement e = st[i];
            String cn = e.getClassName();
            if (start < 0 && (cn.contains("UsingGateTrapMixin")
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

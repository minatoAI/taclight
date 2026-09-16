package dev.taclight.mixin.debug;

import dev.taclight.debug.AgentInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * T19 动作层注入点(修 T18 零位移)。
 *
 * <p><b>为什么不再注入 {@code tick()} HEAD</b>:javap 实证 {@code LocalPlayer.aiStep()+139
 * invokevirtual net/minecraft/client/player/Input.tick:(ZF)V}(其后 +147 {@code ForgeHooksClient.onMovementInputUpdate}),
 * 而 {@code KeyboardInput} **override** 了 {@code tick(boolean,float)} —— vanilla 会在 {@code aiStep} 内按真实按键
 * **重算** {@code forwardImpulse/leftImpulse/jumping/shiftKeyDown}(无按键 ⇒ 全 0),把 HEAD 处写的值清掉。
 * 这正是 Baritone 要**换掉整个 {@code LocalPlayer.input} 对象**的原因。</p>
 *
 * <p><b>修法(方案①,理由)</b>:注入到 vanilla {@code Input.tick(ZF)V} 调用**之后**
 * ({@code shift = AFTER})。相比②({@code KeyboardInput.tick} TAIL:运行期 Input 若不是 KeyboardInput 即失效)
 * 与③(换 Input 对象:最稳但要接管 tick 与还原),① 与具体 Input 实现无关、改动最小,
 * 且"先清空再重灌"正好落在 vanilla 重算之后、物理消费之前。</p>
 *
 * <p><b>三个探针</b>(T19-fix:全部只在 {@code AgentInput.probeOn} 为真时打印,默认关,
 * 由 {@code !agent probe on} 打开 —— 早前 10 s 打 210 行太吵):
 * {@code tick-HEAD} / {@code aiStep-after-vanilla-Input.tick} / {@code aiStep-TAIL}(travel 之后,看 tick 结束时的
 * 稳定值 —— 用它分辨"冲刺是被谁清掉的":闭环在 set 之后读到的瞬时 true 与 TAIL 的 false 矛盾)。</p>
 *
 * <p><b>默认关</b>:{@link AgentInput#probe} 未开探针立即返回、{@link AgentInput#onClientTick} 未激活立即返回
 * (不读不写 input、零日志) ⇒ 逐字节无差异。</p>
 */
@Mixin(LocalPlayer.class)
public class AgentInputMixin {

    /** 探针(改前):tick 开始时字段的残留值。 */
    @Inject(method = "tick", at = @At("HEAD"))
    private void taclight$agentProbePre(CallbackInfo ci) {
        AgentInput.probe(Minecraft.getInstance(), "tick-HEAD");
    }

    /** 真正的写入点:vanilla 在 aiStep 内重算输入之后。 */
    @Inject(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
    private void taclight$agentInput(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        AgentInput.probe(mc, "aiStep-after-vanilla-Input.tick(before our write)");
        AgentInput.onClientTick(mc);
        AgentInput.probe(mc, "aiStep-after-our-write");
    }

    /** T19 新增:aiStep TAIL(travel 之后、tick 结束前)读冲刺稳定值,用于定位"谁把 sprint 清掉"。 */
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void taclight$agentTickEnd(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        AgentInput.noteTickEndSprint(mc);
        AgentInput.probe(mc, "aiStep-TAIL");
    }
}

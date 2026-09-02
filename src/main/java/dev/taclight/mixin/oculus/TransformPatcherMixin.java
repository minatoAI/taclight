package dev.taclight.mixin.oculus;

import dev.taclight.interop.RuntimePackInjector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 方案C 里程碑2 注入点(计划文档 §3,字节码取证见同文档 §2):
 * Oculus(=Iris 1.7.x 移植)对 composite/deferred/final 程序的统一转换入口
 * TransformPatcher.patchComposite(public static,调用方 CompositeRenderer/FinalPassRenderer),
 * 4 个源文本入参 = 各包加载后的程序源(include 解析/option 处理后),在输入侧改写
 * = 注入文本随后走 Iris 完整 transform,与"离线派生包被正常加载"同语义。
 * plugin 门控(dev.taclight.mixin.oculus. 前缀):Oculus 不在场时本 mixin 不应用。
 * <p>2026-09-03 实机取证:类字面量 target 在 prepare 期解析失败会被静默丢弃
 * (IXPROBE:目标类已加载但无注入器,门控 true、config 已注册、零报错)——
 * 改字符串 targets + remap=false,运行期按名解析。require=1:签名漂移大声失败。
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public class TransformPatcherMixin {

    @ModifyVariable(method = "patchComposite", argsOnly = true, ordinal = 0,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropArg0(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchComposite", argsOnly = true, ordinal = 1,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropArg1(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchComposite", argsOnly = true, ordinal = 2,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropArg2(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchComposite", argsOnly = true, ordinal = 3,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropArg3(String value) {
        return RuntimePackInjector.patchSource(value);
    }
}

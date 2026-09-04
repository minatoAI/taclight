package dev.taclight.mixin.oculus;

import dev.taclight.interop.RuntimePackInjector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 方案C 里程碑2 注入点(计划文档 §3,字节码取证见同文档 §2):
 * Oculus(=Iris 1.7.x 移植)的统一 shader 转换入口 TransformPatcher:
 *  - patchComposite(4×String, ordinal 0..3)= composite/deferred/final 程序源
 *    (调用方 CompositeRenderer/FinalPassRenderer)——iterationT 族注入点;
 *  - patchSodium(6×String, ordinal 0..5)= gbuffers 地形程序源
 *    (调用方 SodiumTerrainPipeline,Embeddium/Sodium 地形必经)——Complementary
 *    前向注入点(光照在 gbuffers DoLighting 完成,composite 只做反射/雾/tonemap)。
 * 4/6 个源文本入参 = 各包加载后的程序源(include 解析/option 处理后),在输入侧改写
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

    // Complementary 前向注入(2026-09-03 实机:灯亮 SSBO 有数但画面零变化——
    // patchComposite 只覆盖 composite/deferred/final,而 Complementary 把全部光照
    // 做在 gbuffers(DoLighting 将光乘进 albedo),composite 只剩反射/雾/tonemap;
    // gbuffers 地形(Embeddium/Sodium)必经 patchSodium(6×String,取证
    // SodiumTerrainPipeline javap),故加挂。gbuffers_entities/hand 同族程序
    // (patchVanilla 路径,VanillaRenderingPipeline)暂不挂:地形锥池是验收主体,
    // 手持/实体后续按需再挂,保持混入面最小。
    @ModifyVariable(method = "patchSodium", argsOnly = true, ordinal = 0,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropSodium0(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchSodium", argsOnly = true, ordinal = 1,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropSodium1(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchSodium", argsOnly = true, ordinal = 2,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropSodium2(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchSodium", argsOnly = true, ordinal = 3,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropSodium3(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchSodium", argsOnly = true, ordinal = 4,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropSodium4(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchSodium", argsOnly = true, ordinal = 5,
            at = @At("HEAD"), remap = false, require = 1)
    private static String taclightInteropSodium5(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    // 实体/手部前向注入(2026-09-04 用户实机:灯照玩家实体有半透明感——
    // patchSodium 只覆地形,gbuffers_entities/hand 走 patchVanilla(6×String,
    // 取证上轮 javap:patchVanilla/patchDHTerrain/patchDHGeneric 皆 6×String);
    // 实体/手部与地形同式 DoLighting 调用尾,alpha 门乘 color.a——地形 alpha 恒 1
    // 行为不变,半透处锥光跟压,不再有透层感;影子由宿主 DoLighting 阴影乘子自然给出)。
    @ModifyVariable(method = "patchVanilla", argsOnly = true, ordinal = 0,
            at = @At("HEAD"), remap = false, require = 0)
    private static String taclightInteropVanilla0(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchVanilla", argsOnly = true, ordinal = 1,
            at = @At("HEAD"), remap = false, require = 0)
    private static String taclightInteropVanilla1(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchVanilla", argsOnly = true, ordinal = 2,
            at = @At("HEAD"), remap = false, require = 0)
    private static String taclightInteropVanilla2(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchVanilla", argsOnly = true, ordinal = 3,
            at = @At("HEAD"), remap = false, require = 0)
    private static String taclightInteropVanilla3(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchVanilla", argsOnly = true, ordinal = 4,
            at = @At("HEAD"), remap = false, require = 0)
    private static String taclightInteropVanilla4(String value) {
        return RuntimePackInjector.patchSource(value);
    }

    @ModifyVariable(method = "patchVanilla", argsOnly = true, ordinal = 5,
            at = @At("HEAD"), remap = false, require = 0)
    private static String taclightInteropVanilla5(String value) {
        return RuntimePackInjector.patchSource(value);
    }
}

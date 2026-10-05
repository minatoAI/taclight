package dev.taclight.builtin;

import dev.taclight.interop.PackFingerprint;

/**
 * 内置包与注入路径的自动切换判定(纯类, 离线可测, 2026-10-05).
 *
 * <p>用户要求的"自动替换"含义:Iris 同时只能装载一个包,所以当用户选了自己的
 * 第三方包时, 内置包自然被禁用(不是我们动手关的), 照明责任切到注入通道
 * ({@code RuntimePackInjector}) 或通用降级。本类把这个切换显式化,
 * 避免"注入失败被误读成内置包坝了"或"内置包被误读成在工作" 两类混淆.</p>
 *
 * <p>与 {@code ShaderPackDiagLogic} 的关系:那个类管"盘上有没有标记 +运行时注没注入",
 * 本类多管一层"是不是内置包本人" 以及"内置是否被禁用".既有逻辑不动,
 * 新判定叠在上面.</p>
 */
public final class BuiltinPackRouter {
    /** 切换后的照明责任方. */
    public enum Mode {
        /** Iris 未用包(原生中间光 +屏幕假構光冲, 见审计 08). */
        NO_PACK,
        /** 内置包在用,真锥体 +自适应曝光;注入通道息火是正常的(无内置模板). */
        BUILTIN_ACTIVE,
        /** 非内置但磁盘带标记(派生包): 物理内联照明, 不走注入. */
        PACK_WITH_MARKER,
        /** 第三方包 +运行时注入成功(内置已禁用, 切到注入路径). */
        THIRD_PARTY_INJECTED,
        /** 第三方包 +命中模板但注入失败(需要用户切内置包绕过). */
        THIRD_PARTY_FAILED,
        /** 第三方包 +无模板(通用点光降级, 无锥体无阴影). */
        THIRD_PARTY_GENERIC,
        /** 判不出(包名空或包不可读). */
        UNKNOWN
    }

    private BuiltinPackRouter() {}

    /** 是否内置包(目录名与 .zip 名都认, 同 PackFingerprint 归一规则). */
    public static boolean isBuiltinName(String packName) {
        if (packName == null) return false;
        return PackFingerprint.packMatchKey(packName)
                .equals(PackFingerprint.packMatchKey(BuiltinPackInstaller.BUILTIN_PACK_NAME));
    }

    /**
     * 纯判定.参数与 {@code ShaderPackDiagLogic.decide} 同口径, 便于并排调用.
     */
    public static Mode decide(boolean shaderPackInUse, String packName, Boolean diskMarker,
                              boolean interopInjected, boolean interopTemplateMatched) {
        if (!shaderPackInUse) return Mode.NO_PACK;
        if (packName == null || packName.isBlank()) return Mode.UNKNOWN;
        if (isBuiltinName(packName)) return Mode.BUILTIN_ACTIVE;
        if (Boolean.TRUE.equals(diskMarker)) return Mode.PACK_WITH_MARKER;
        if (diskMarker == null) return Mode.UNKNOWN;
        if (interopInjected) return Mode.THIRD_PARTY_INJECTED;
        if (interopTemplateMatched) return Mode.THIRD_PARTY_FAILED;
        return Mode.THIRD_PARTY_GENERIC;
    }

    /** 内置是否被禁用(Iris 装了别的包时定为 true, 与注入成败无关). */
    public static boolean builtinDisabled(Mode m) {
        return m == Mode.THIRD_PARTY_INJECTED || m == Mode.THIRD_PARTY_FAILED
                || m == Mode.THIRD_PARTY_GENERIC || m == Mode.PACK_WITH_MARKER;
    }

    /** 单行日志文案(切换可观测, 供 clientSetup 打印与 E2E 检索). */
    public static String statusLine(Mode m, String packName) {
        String pack = packName == null ? "(null)" : packName;
        return switch (m) {
            case NO_PACK -> "builtin-router: NO_PACK(未用包, 冲原生光)";
            case BUILTIN_ACTIVE -> "builtin-router: BUILTIN_ACTIVE(" + pack + ", 内置包在用, 注入通道息火正常)";
            case PACK_WITH_MARKER -> "builtin-router: PACK_WITH_MARKER(" + pack + ", 派生包, 内置已禁用)";
            case THIRD_PARTY_INJECTED -> "builtin-router: THIRD_PARTY_INJECTED(" + pack + ", 内置已禁用, 切到注入路径)";
            case THIRD_PARTY_FAILED -> "builtin-router: THIRD_PARTY_FAILED(" + pack + ", 内置已禁用但注入失败, 建议切内置包)";
            case THIRD_PARTY_GENERIC -> "builtin-router: THIRD_PARTY_GENERIC(" + pack + ", 内置已禁用, 通用点光降级)";
            case UNKNOWN -> "builtin-router: UNKNOWN(" + pack + ", 无法判定)";
        };
    }
}

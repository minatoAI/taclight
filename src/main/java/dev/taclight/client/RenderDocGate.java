package dev.taclight.client;

/**
 * RenderDoc 被动门(2026-09-19 调试快照最小闭环):
 * 只回答"renderdoc.dll 是否已注入本进程",供快照 note 首行记录;绝不主动加载模块、
 * 绝不初始化 RenderDoc API。无 native 环境(纯 JVM / 无 JNA / 非 Windows / 未注入)
 * 永远返回 absent,绝不抛(所有入口三层 try/catch,Error 也吞)。
 *
 * <p><b>二选一说明(任务书要求写注释)</b>:选 <b>JNA + Kernel32.GetModuleHandleW</b> ——
 * 被动查询已加载模块句柄,不触 LoadLibrary;且 JNA 经反射调用,本模组零编译依赖、
 * 无 JNA 环境直接走 absent(本仓 build.gradle 无 JNA,日常即此分支)。
 * 弃 <b>LWJGL system</b>:其 Library / SharedLibraryLoader 族 API 以主动加载为中心,
 * 被动"查已加载"需碰版本相关的内部 API,漂移面大;且 LWJGL 只在客户端运行时在位,
 * 服务端线程引用即炸(同 VoxelGrid/TuneClientGate 的教训)。</p>
 *
 * <p>注意:磁盘上找得到 renderdoc.dll 文件 ≠ 已注入本进程,抓取 API 要求模块在进程内,
 * 故文件扫描故意不做(误报会让 note 撒谎);只认已加载模块句柄非零。</p>
 *
 * <p>测试:纯 java.* 类型,native 全 mock —— 契约经包内可见的
 * {@code setTestProbe/clearTestProbe} 注入假句柄,不碰任何 native。</p>
 */
public final class RenderDocGate {
    /** 被查询的模块名(Windows RenderDoc 注入形态)。 */
    public static final String MODULE = "renderdoc.dll";
    /** 无 RenderDoc 语义:未注入 / 检测失败 / 非 Windows,一律此值。 */
    public static final String ABSENT = "absent";
    /** 无 RenderDoc 语义:模块已在进程内(句柄非零)。 */
    public static final String PRESENT = "present";

    private RenderDocGate() {}

    /**
     * 测试/生产两用的最小探针面:native 全 mock 就靠它。
     * 实现方必须自保(绝不抛);生产实例恒为 null(走反射路径)。
     */
    interface Probe {
        /** 已加载模块句柄;0 = 不在进程内。 */
        long moduleHandle(String moduleName);

        /** 触发一帧抓取;false = 不可用/失败。 */
        boolean requestCapture();
    }

    private static volatile Probe testProbe;

    /** 仅契约用:注入假探针(mock native)。生产代码永不调用。 */
    static void setTestProbe(Probe p) {
        testProbe = p;
    }

    /** 仅契约用:清掉假探针,回到生产路径。 */
    static void clearTestProbe() {
        testProbe = null;
    }

    /** 本进程是否已注入 renderdoc.dll(无环境=false,绝不抛)。 */
    public static boolean isAvailable() {
        try {
            Probe p = testProbe;
            if (p != null) {
                try {
                    return p.moduleHandle(MODULE) != 0;
                } catch (Throwable t) {
                    return false;
                }
            }
            return prodModuleHandle(MODULE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 无 RenderDoc 语义三态之一:本方法只回 "absent" 或 "present"(失败即 absent,绝不抛)。 */
    public static String getStatus() {
        try {
            return isAvailable() ? PRESENT : ABSENT;
        } catch (Throwable t) {
            return ABSENT;
        }
    }

    /**
     * 触发一次 RenderDoc 抓取(当前最小闭环:生产路径保守返回 false,见内注)。
     * 未注入=false;任何失败=false;绝不抛。
     */
    public static boolean triggerCapture() {
        try {
            Probe p = testProbe;
            if (p != null) {
                try {
                    return isAvailable() && p.requestCapture();
                } catch (Throwable t) {
                    return false;
                }
            }
            return prodRequestCapture();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 生产句柄查询:JNA(若在位)Kernel32.GetModuleHandle —— 被动查询已加载模块,
     * 不 LoadLibrary。JNA 不在位 / 非 Windows / 任何异常 → 0(absent)。
     */
    private static long prodModuleHandle(String module) {
        try {
            String os = System.getProperty("os.name", "");
            if (!os.toLowerCase(java.util.Locale.ROOT).contains("win")) {
                return 0;
            }
            Class<?> k32 = Class.forName("com.sun.jna.platform.win32.Kernel32");
            Object instance = k32.getField("INSTANCE").get(null);
            Object hmod = k32.getMethod("GetModuleHandle", String.class).invoke(instance, module);
            if (hmod == null) {
                return 0;
            }
            try {
                // HMODULE → Pointer.peer(全反射,不直引 JNA 类型;peer==0 即 NULL 语义)。
                Object pointer = hmod.getClass().getMethod("getPointer").invoke(hmod);
                Object peer = pointer.getClass().getField("peer").get(pointer);
                return ((Long) peer).longValue();
            } catch (Throwable ignored) {
                return 1; // 句柄对象非空但取不出 peer:保守按"在位"计,上层只消费布尔语义
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 生产抓取(<b>2026-09-25 接线</b>):前置<b>仍是"已注入才谈"</b>——{@code GetModuleHandleW} 非零
     * 才去碰 API,<b>绝不主动 LoadLibrary</b>。真正的调用在 {@link RenderDocApi}
     * ({@code RENDERDOC_GetAPI(1.6.0)} → 槽15 {@code TriggerCapture};槽0 版本自检)。
     * 任何失败 ⇒ {@code false},绝不抛。
     */
    private static boolean prodRequestCapture() {
        try {
            if (prodModuleHandle(MODULE) == 0) {
                return false;
            }
            return RenderDocApi.triggerCapture();
        } catch (Throwable t) {
            return false;
        }
    }
}

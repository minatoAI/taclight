package dev.taclight.client;

/**
 * RenderDoc <b>应用内 API 绑定</b>(2026-09-25):让 {@code !snap}/{@code F9} 自己触发抓帧,
 * <b>不抢焦点、不发合成按键、不占操作者键鼠</b>。
 *
 * <p><b>为什么是这个方案</b>:{@code renderdoccmd capture} 没有"抓第 N 帧"参数,抓帧只能靠
 * RenderDoc 的抓帧热键或应用内 API。用合成按键(SetForegroundWindow + keybd_event)会抢焦点、
 * 副作用不可归属 —— 本项目已明文弃用;上游 Cua Driver 也规定"拒绝后不得转前台重试"。
 * 所以唯一正确路径是 <b>{@code RENDERDOC_GetAPI} → {@code TriggerCapture}</b>。</p>
 *
 * <p><b>零编译依赖</b>:JNA 全反射调用({@code com.sun.jna.*} 只出现在字符串里),本类不引用
 * Minecraft、不引用任何 native。JNA 在 Minecraft 运行时 classpath 上自带(jna-5.12.1),
 * 离线契约环境没有它 ⇒ {@link #triggerCapture()} 安全返回 false。</p>
 *
 * <p><b>槽位来源(不是猜的)</b>:RenderDoc 1.46 便携版自带 {@code renderdoc_app.h};
 * 1.6.0 是 {@code RENDERDOC_API_1_7_0} 的 typedef,按"union 计一个槽"解析得
 * {@code TriggerCapture} = <b>第 15 槽</b>(x64 偏移 120);邻居吻合:
 * 13 GetNumCaptures / 14 GetCapture / 16 IsTargetControlConnected / 17 LaunchReplayUI /
 * 19 StartFrameCapture。解析清单见
 * {@code docs/evidence/2026-09-25-renderdoc/renderdoc-api-slots.txt}(工作区)。</p>
 *
 * <p><b>自检(关键)</b>:拿表后先调<b>槽 0</b>{@code GetAPIVersion}(偏移 0,确定无疑),
 * 只有报出 {@code major==1 && minor>=6} 才继续用槽 15;<b>版本不符就拒绝</b>,
 * 不用可疑偏移去赌 —— 赌错是 JVM 崩溃,不是异常。</p>
 */
public final class RenderDocApi {
    /** {@code eRENDERDOC_API_Version_1_6_0}(renderdoc_app.h L716)。 */
    public static final int VERSION_1_6_0 = 10600;
    /** 槽 0:{@code GetAPIVersion}(偏移 0)。 */
    public static final int SLOT_GET_API_VERSION = 0;
    /** 槽 15:{@code TriggerCapture}(x64 偏移 120)。 */
    public static final int SLOT_TRIGGER_CAPTURE = 15;
    /** 指针宽度(仅 Windows x64 装机;32 位下本绑定不适用,自检会因取不到版本而拒绝)。 */
    private static final int PTR = 8;

    private static final String LIB_NAME = "renderdoc";
    private static final String GET_API = "RENDERDOC_GetAPI";

    /** 自检成功后的 API 版本串(如 "1.6.0");未成功 ⇒ null。 */
    private static volatile String lastVersion;

    private RenderDocApi() {}

    /** JNA 是否在位(纯 Class.forName,不加载 native)。 */
    public static boolean jnaPresent() {
        try {
            Class.forName("com.sun.jna.NativeLibrary");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自检成功后的 API 版本串;未成功 ⇒ null。 */
    public static String lastVersion() {
        return lastVersion;
    }

    /**
     * 触发一次抓帧。任何失败(无 JNA / 无注入 / 版本不符 / 调用异常)⇒ {@code false}，<b>绝不抛</b>。
     * <p>调用方必须<b>先</b>确认 {@code renderdoc.dll} 已在进程内({@code RenderDocGate.isAvailable()});
     * 本方法只负责"已在进程内之后怎么调",不负责发现注入。</p>
     */
    public static boolean triggerCapture() {
        try {
            return triggerCaptureInner();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean triggerCaptureInner() throws Exception {
        Class<?> nativeLibrary = Class.forName("com.sun.jna.NativeLibrary");
        Class<?> pointerClass = Class.forName("com.sun.jna.Pointer");
        Class<?> functionClass = Class.forName("com.sun.jna.Function");
        Class<?> pointerByRef = Class.forName("com.sun.jna.ptr.PointerByReference");
        Class<?> intByRef = Class.forName("com.sun.jna.ptr.IntByReference");

        // 1) 取 RENDERDOC_GetAPI 并要 1.6.0 的表(返回值 1 = 成功)
        Object library = nativeLibrary.getMethod("getInstance", String.class).invoke(null, LIB_NAME);
        Object getApi = nativeLibrary.getMethod("getFunction", String.class).invoke(library, GET_API);
        Object out = pointerByRef.getConstructor().newInstance();
        int rc = (Integer) functionClass.getMethod("invokeInt", Object[].class)
                .invoke(getApi, (Object) new Object[] { VERSION_1_6_0, out });
        if (rc != 1) {
            return false;
        }
        Object apiTable = pointerByRef.getMethod("getValue").invoke(out);
        if (apiTable == null) {
            return false;
        }

        // 2) 自检:先调槽 0 GetAPIVersion(偏移 0)。版本不符 ⇒ 拒绝,不拿槽 15 去赌。
        long getVersionAddr = (Long) pointerClass.getMethod("getLong", long.class)
                .invoke(apiTable, (long) (SLOT_GET_API_VERSION * PTR));
        if (getVersionAddr == 0L) {
            return false;
        }
        Object major = intByRef.getConstructor(int.class).newInstance(0);
        Object minor = intByRef.getConstructor(int.class).newInstance(0);
        Object patch = intByRef.getConstructor(int.class).newInstance(0);
        Object getVersion = functionClass.getMethod("getFunction", pointerClass)
                .invoke(null, pointerClass.getConstructor(long.class).newInstance(getVersionAddr));
        functionClass.getMethod("invokeVoid", Object[].class)
                .invoke(getVersion, (Object) new Object[] { major, minor, patch });
        int majorV = (Integer) intByRef.getMethod("getValue").invoke(major);
        int minorV = (Integer) intByRef.getMethod("getValue").invoke(minor);
        int patchV = (Integer) intByRef.getMethod("getValue").invoke(patch);
        if (majorV != 1 || minorV < 6) {
            return false; // 槽位表按 1.6.0 解析;不是 1.6+ 就不碰 TriggerCapture
        }
        lastVersion = majorV + "." + minorV + "." + patchV;

        // 3) 槽 15 TriggerCapture
        long triggerAddr = (Long) pointerClass.getMethod("getLong", long.class)
                .invoke(apiTable, (long) (SLOT_TRIGGER_CAPTURE * PTR));
        if (triggerAddr == 0L) {
            return false;
        }
        Object trigger = functionClass.getMethod("getFunction", pointerClass)
                .invoke(null, pointerClass.getConstructor(long.class).newInstance(triggerAddr));
        functionClass.getMethod("invokeVoid", Object[].class)
                .invoke(trigger, (Object) new Object[0]);
        return true;
    }
}

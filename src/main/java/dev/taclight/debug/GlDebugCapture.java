package dev.taclight.debug;

import dev.taclight.TacLightMod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;

import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step2 前置**可用性实验**:GL 调试消息(KHR_debug / GL43)在这台机器上到底吐不吐?
 *
 * <p><b>为什么必须先做实验而不是直接写断言</b>:实测上下文 <b>不是 debug context</b>
 * ({@code context_flags=0x1},缺 {@code GL_CONTEXT_FLAG_DEBUG_BIT})。规范上非 debug context 下
 * 实现<b>不必须</b>产生调试消息——NVIDIA 实践上通常仍会投递,但这是"通常",不是判据。
 * 先承诺后验证会产出假绿。</p>
 *
 * <p><b>实验设计(两条独立通道,必须分开计数)</b>:
 * <ol>
 *   <li><b>通道 A(管线自证)</b>:{@code glDebugMessageInsert} 自己插一条消息 ⇒ 收到即证明
 *       "回调已装好、消息能到达 Java"。这一条与驱动行为无关。</li>
 *   <li><b>通道 B(驱动行为)</b>:故意制造 3 个真实 GL 错误(非法 enum / 非法 value) ⇒
 *       收到即证明"驱动在非 debug context 下确实为错误产生消息"。</li>
 *   <li><b>通道 C(编译失败路径,2026-09-25 补验)</b>:故意编译一个坏 shader ⇒
 *       分别报告"编译确实失败"(`GL_COMPILE_STATUS`)与"失败是否被驱动报告"(消息数),
 *       因为这两件事必须分开——B 只覆盖了 API 错误这一条产生路径。</li>
 * </ol>
 * 只有 A 绿 B 红,才说明"回调没问题但驱动不吐"——那才是必须换退路的判据。</p>
 *
 * <p><b>★ 必须显式重开消息控制</b>:Minecraft 自己也有 GL 调试设施({@code options.txt} 的
 * {@code glDebugVerbosity},本机为 1),它会用 {@code glDebugMessageControl} 按严重级过滤
 * (verbosity=1 通常只放 HIGH)。我们的 {@code glDebugMessageCallback} 会<b>替换</b>它的回调,
 * 但<b>它的过滤器仍然生效</b> ⇒ 不重开控制就会把低严重级的错误全滤掉,
 * 从而把"驱动不吐"和"被自己滤掉"混为一谈。<b>这是本实验最容易做错的一步。</b></p>
 *
 * <p><b>副作用(如实记录)</b>:安装后 MC 自己的 GL 调试回调被替换 ⇒ 本轮 MC 的 GL 调试日志会消失。
 * 仅在 relay-only 测试变体里可达({@code dev.taclight.debug} 与 {@code DebugCommandRelay} 均被发布包剔除)。</p>
 *
 * <p>输出:{@code <gameDir>/taclight-gl-messages.jsonl},一行一条
 * {@code {"source":..,"type":..,"id":..,"severity":..,"message":".."}}。</p>
 */
public final class GlDebugCapture {

    // 与 GL43 常量等值的字面量(避免类初始化顺序问题;值取自 GL 规范,稳定)
    private static final int DONT_CARE = 0x1100;              // GL_DONT_CARE
    private static final int DEBUG_OUTPUT = 0x92E0;           // GL_DEBUG_OUTPUT
    private static final int DEBUG_OUTPUT_SYNCHRONOUS = 0x8242;
    private static final int SEVERITY_HIGH = 0x9146;
    private static final int SEVERITY_MEDIUM = 0x9147;
    private static final int SEVERITY_LOW = 0x9148;
    private static final int SEVERITY_NOTIFICATION = 0x826B;
    private static final int TYPE_UNDEFINED_BEHAVIOR = 0x824C;
    private static final int SOURCE_APPLICATION = 0x824A;
    private static final int TYPE_OTHER = 0x8251;

    private static final Object LOCK = new Object();
    /** 必须强引用:否则 native stub 被 GC 回收 ⇒ 回调悬空(典型静默失败)。 */
    private static GLDebugMessageCallback callback;
    private static boolean installed;
    private static String installNote = "not-installed";
    private static Path jsonl;

    private static final AtomicInteger total = new AtomicInteger();
    private static final AtomicInteger high = new AtomicInteger();
    private static final AtomicInteger medium = new AtomicInteger();
    private static final AtomicInteger low = new AtomicInteger();
    private static final AtomicInteger notification = new AtomicInteger();
    private static final AtomicInteger undefinedBehavior = new AtomicInteger();
    private static final AtomicInteger fromApplication = new AtomicInteger();
    private static volatile String last = "";

    private GlDebugCapture() {
    }

    /** 幂等安装:回调 + 全开消息控制 + 输出文件。返回人读说明。 */
    public static String install() {
        synchronized (LOCK) {
            if (installed) {
                return "already-installed (" + installNote + ")";
            }
            try {
                jsonl = FMLPaths.GAMEDIR.get().resolve("taclight-gl-messages.jsonl");
                boolean gl43 = org.lwjgl.opengl.GL.getCapabilities().OpenGL43;
                boolean khr = org.lwjgl.opengl.GL.getCapabilities().GL_KHR_debug;
                if (!gl43 && !khr) {
                    installNote = "unsupported: OpenGL43=" + gl43 + " GL_KHR_debug=" + khr;
                    return installNote;
                }
                callback = GLDebugMessageCallback.create(GlDebugCapture::onMessage);
                GL43.glDebugMessageCallback(callback, 0L);
                // ★ 关键:重开全部消息。MC 自己的 glDebugVerbosity 过滤器会残留并滤掉低严重级消息。
                IntBuffer noIds = BufferUtils.createIntBuffer(0);
                GL43.glDebugMessageControl(DONT_CARE, DONT_CARE, DONT_CARE, noIds, true);
                GL11.glEnable(DEBUG_OUTPUT);
                GL11.glEnable(DEBUG_OUTPUT_SYNCHRONOUS);
                drainErrors(64);
                installed = true;
                installNote = "installed (OpenGL43=" + gl43 + " GL_KHR_debug=" + khr + ")";
            } catch (Throwable t) {
                installNote = "install failed: " + t;
                TacLightMod.LOGGER.warn("[TacLight] GLMSG install failed: {}", t.toString());
            }
            return installNote;
        }
    }

    /**
     * 双通道自检:先插一条自己的消息(通道 A),再制造真实 GL 错误(通道 B),分开计数。
     */
    public static String selfTest() {
        String note = install();
        if (!installed) {
            return "selfTest aborted: " + note;
        }
        // ---- 通道 A:我们自己插一条(与驱动是否产生消息无关) ----
        int aBefore = total.get();
        int aAppBefore = fromApplication.get();
        try {
            GL43.glDebugMessageInsert(SOURCE_APPLICATION, TYPE_OTHER, 9001, SEVERITY_HIGH,
                    "taclight-selftest-channel-A");
        } catch (Throwable t) {
            return "channel A insert failed: " + t;
        }
        int aGot = total.get() - aBefore;
        int aAppGot = fromApplication.get() - aAppBefore;

        // ---- 通道 B:制造 3 个真实 GL 错误 ----
        int bBefore = total.get();
        int errs = 0;
        GL11.glEnable(0xDEAD);                                              // 非法 enum
        errs += drainErrors(16);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 3);                    // 非法 value(只允许 1/2/4/8)
        errs += drainErrors(16);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, 0xDEAD); // 非法 enum
        errs += drainErrors(16);
        int bGot = total.get() - bBefore;

        // ---- 通道 C:着色器**编译失败**是否也走这条通道(2026-09-25 补验边界) ----
        // 通道 B 只证了"API 错误"这一条产生路径;编译/链接失败是另一条
        // (Iris 的编译点可能自行记录并吞掉)。用 GL_COMPILE_STATUS 与调试消息**分开**报告,
        // 这样"编译确实失败了"与"失败是否被驱动报告"不会混为一谈。
        int cBefore = total.get();
        boolean compileOk = true;
        String infoLog = "";
        int shader = 0;
        try {
            shader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(shader, "#version 410 core\nvoid main() { this is not glsl }\n");
            GL20.glCompileShader(shader);
            compileOk = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE;
            infoLog = GL20.glGetShaderInfoLog(shader);
        } catch (Throwable t) {
            infoLog = "exception: " + t;
        } finally {
            if (shader != 0) {
                try {
                    GL20.glDeleteShader(shader);
                } catch (Throwable ignored) {
                }
            }
        }
        int cErr = drainErrors(16);
        int cGot = total.get() - cBefore;
        String flat = infoLog == null ? "" : infoLog.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() > 220) {
            flat = flat.substring(0, 220) + "...";
        }

        String verdict = (aGot > 0 ? "A=WIRED" : "A=DEAD") + "/"
                + (bGot > 0 ? "B=DRIVER-EMITS" : "B=DRIVER-SILENT") + "/"
                + (cGot > 0 ? "C=COMPILE-EMITS" : "C=COMPILE-SILENT");
        return "selfTest " + verdict + " | channelA: inserted=1 received=" + aGot
                + " fromApplication=" + aAppGot
                + " | channelB: glErrors=" + errs + " messages=" + bGot
                + " | channelC: compileStatus=" + (compileOk ? "PASS(unexpected!)" : "FAIL(expected)")
                + " glErrors=" + cErr + " messages=" + cGot + " infoLog=\"" + flat + "\""
                + " | totals: " + counts();
    }

    public static String status() {
        return "installed=" + installed + " note=" + installNote + " " + counts()
                + " file=" + (jsonl == null ? "-" : jsonl.getFileName().toString())
                + " last=\"" + last + "\"";
    }

    /** 清空计数与输出文件(便于"安装前/安装后"对照)。 */
    public static String clear() {
        total.set(0);
        high.set(0);
        medium.set(0);
        low.set(0);
        notification.set(0);
        undefinedBehavior.set(0);
        fromApplication.set(0);
        last = "";
        try {
            if (jsonl != null) {
                Files.writeString(jsonl, "", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Throwable ignored) {
        }
        return "cleared";
    }

    private static String counts() {
        return "total=" + total.get() + " high=" + high.get() + " medium=" + medium.get()
                + " low=" + low.get() + " notification=" + notification.get()
                + " undefinedBehavior=" + undefinedBehavior.get()
                + " fromApplication=" + fromApplication.get();
    }

    private static void onMessage(int source, int type, int id, int severity, int length,
                                  long message, long userParam) {
        try {
            total.incrementAndGet();
            if (severity == SEVERITY_HIGH) {
                high.incrementAndGet();
            } else if (severity == SEVERITY_MEDIUM) {
                medium.incrementAndGet();
            } else if (severity == SEVERITY_LOW) {
                low.incrementAndGet();
            } else {
                notification.incrementAndGet();
            }
            if (type == TYPE_UNDEFINED_BEHAVIOR) {
                undefinedBehavior.incrementAndGet();
            }
            if (source == SOURCE_APPLICATION) {
                fromApplication.incrementAndGet();
            }
            String msg = length > 0 ? GLDebugMessageCallback.getMessage(length, message) : "";
            last = severityName(severity) + "/" + typeName(type) + " id=" + id + " " + msg.trim();
            Path p = jsonl;
            if (p != null) {
                Files.writeString(p,
                        "{\"source\":" + source + ",\"type\":" + type + ",\"id\":" + id
                                + ",\"severity\":" + severity + ",\"message\":" + quote(msg) + "}\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Throwable ignored) {
            // 回调里绝不抛:否则会在 GL 调用点炸开
        }
    }

    private static int drainErrors(int max) {
        int n = 0;
        for (int i = 0; i < max; i++) {
            if (GL11.glGetError() == GL11.GL_NO_ERROR) {
                break;
            }
            n++;
        }
        return n;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String severityName(int s) {
        return s == SEVERITY_HIGH ? "HIGH" : s == SEVERITY_MEDIUM ? "MEDIUM"
                : s == SEVERITY_LOW ? "LOW" : s == SEVERITY_NOTIFICATION ? "NOTIFICATION" : ("0x" + Integer.toHexString(s));
    }

    private static String typeName(int t) {
        return switch (t) {
            case 0x824C -> "UNDEFINED_BEHAVIOR";
            case 0x824D -> "PORTABILITY";
            case 0x824E -> "PERFORMANCE";
            case 0x824F -> "MARKER";
            case 0x8250 -> "PUSH_GROUP";
            case 0x8251 -> "POP_GROUP";
            case 0x824B -> "OTHER";
            case 0x8248 -> "ERROR";
            case 0x8249 -> "DEPRECATED";
            default -> "0x" + Integer.toHexString(t);
        };
    }
}

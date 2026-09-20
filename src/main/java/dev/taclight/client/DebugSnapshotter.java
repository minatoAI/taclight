package dev.taclight.client;

import dev.taclight.TacLightMod;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键调试快照(2026-09-19 最小闭环):{@code saveSnapshot(reason)} 在
 * <b>{@code <gameDir>/debug-snapshots/<时间戳>/}</b> 写
 * {@code snapshot.json}(pose/灯参读现值,能读到的先写,读不到写 null 并注明)+
 * {@code note.txt}(首行 renderdoc 状态),截图沿用
 * {@code net.minecraft.client.Screenshot.grab}(抄
 * {@link DebugCommandRelay} 的 {@code !shot} 分支 try/catch,落盘仍是原版
 * {@code screenshots/} 时间戳命名,此处只记录结果)。
 *
 * <p>三入口调同一函数:F9({@link TacSnapshotKeys})/{@code !snap}(文件命令中继)/
 * {@code /taclight snap}(服务端命令,专用服守卫先行)。本类方法绝不抛:
 * {@code saveSnapshot} 顶层吞 Throwable(失败回 null,调用方记日志)。</p>
 *
 * <p>可测性:目录命名/JSON 渲染/note 首行是纯 java.* 静态方法(包内可见),
 * 契约纯 JVM 直测;pose/截图等 MC 侧读值各自 try/catch,无世界即 null+注记。</p>
 */
public final class DebugSnapshotter {
    /** 快照根(与中继 taclight-cmds.txt 同口径:gameDir 根,不加 run/)。 */
    public static final String ROOT_DIR = "debug-snapshots";
    /** snapshot.json 的全部顶层键(单一真源;契约逐键断言,新增键只许追加尾部)。 */
    public static final List<String> JSON_KEYS = List.of(
            "reason", "takenAt", "epochMilli", "renderdoc", "pose", "light", "tune", "voxel", "notes");
    /** pose 段键(读不到写 null,note 注明原因)。 */
    static final List<String> POSE_KEYS = List.of("x", "y", "z", "yaw", "pitch", "note");
    /** light 段键(ClientLightState 现值)。 */
    static final List<String> LIGHT_KEYS = List.of("handheld", "gun", "gunEffective", "neon", "self", "note");
    /** tune 段键(各旋钮 status 现值;voxel 另有顶层体素状态行,不在此列)。 */
    static final List<String> TUNE_KEYS = List.of(
            "bright", "dist", "atten", "knee", "beam", "scat", "beamcap", "cone",
            "beamonly", "occl", "tm", "note");

    private DebugSnapshotter() {}

    /**
     * 纯数据快照(全 java.* 类型):生产由 {@link #collect} 装配,契约注入假值直测渲染。
     */
    public record SnapData(
            String reason,
            String takenAt,
            long epochMilli,
            String renderdoc,
            Map<String, Object> pose,
            Map<String, Object> light,
            Map<String, Object> tune,
            String voxel,
            List<String> notes) {}

    /**
     * 统一入口(三线共用):写盘 snapshot.json + note.txt,并抄 !shot 做一次截图。
     * 绝不抛;彻底失败(如连 gameDir 都拿不到)回 null,部分失败仍回目录。
     */
    public static Path saveSnapshot(String reason) {
        String safeReason = (reason == null || reason.isBlank()) ? "unspecified" : reason.trim();
        try {
            Path gameDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toAbsolutePath();
            Path root = gameDir.resolve(ROOT_DIR);
            Path dir = resolveUniqueDir(root, dirBaseFor(System.currentTimeMillis()));
            SnapData data = collect(safeReason);
            String shotOutcome = grabScreenshot();
            String captureOutcome = triggerRenderDocCapture();
            Files.writeString(dir.resolve("snapshot.json"), buildSnapshotJson(data), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("note.txt"), buildNoteText(data, shotOutcome, captureOutcome),
                    StandardCharsets.UTF_8);
            TacLightMod.LOGGER.info("[TacLight] SNAP {} -> {}", safeReason, dir);
            return dir;
        } catch (Throwable t) {
            try {
                TacLightMod.LOGGER.warn("[TacLight] SNAP failed({}): {}", safeReason, t.toString());
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** 读现值装配(每段独立 try/catch;读不到写 null 并在 note 注明)。 */
    public static SnapData collect(String reason) {
        long now = System.currentTimeMillis();
        String takenAt;
        try {
            takenAt = java.time.OffsetDateTime.now().toString();
        } catch (Throwable t) {
            takenAt = "unknown";
        }
        String renderdoc;
        try {
            renderdoc = RenderDocGate.getStatus();
        } catch (Throwable t) {
            renderdoc = RenderDocGate.ABSENT;
        }
        Map<String, Object> pose = collectPose();
        Map<String, Object> light = collectLight();
        Map<String, Object> tune = collectTune();
        String voxel;
        List<String> notes = new ArrayList<>();
        try {
            voxel = VoxelGrid.status();
        } catch (Throwable t) {
            voxel = null;
            notes.add("voxel: unreadable(" + t.getClass().getSimpleName() + ")");
        }
        return new SnapData(reason, takenAt, now, renderdoc, pose, light, tune, voxel, notes);
    }

    /** pose 现值:mc.player 坐标+朝向;无客户端/无世界 → 全 null + note。 */
    private static Map<String, Object> collectPose() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : POSE_KEYS) {
            m.put(k, null);
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                m.put("note", "no-client:Minecraft.getInstance()=null");
                return m;
            }
            if (mc.player == null) {
                m.put("note", "no-world:mc.player=null");
                return m;
            }
            m.put("x", mc.player.getX());
            m.put("y", mc.player.getY());
            m.put("z", mc.player.getZ());
            m.put("yaw", mc.player.getYRot());
            m.put("pitch", mc.player.getXRot());
            m.put("note", null);
        } catch (Throwable t) {
            m.put("note", "unreadable:" + t.getClass().getSimpleName());
        }
        return m;
    }

    /** 灯参现值:ClientLightState 各取值;读不到 → 全 null + note。 */
    private static Map<String, Object> collectLight() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : LIGHT_KEYS) {
            m.put(k, null);
        }
        try {
            m.put("handheld", ClientLightState.isOn());
            m.put("gun", ClientLightState.gunLightOn());
            m.put("gunEffective", ClientLightState.gunLightEffective());
            m.put("neon", ClientLightState.debugMode());
            m.put("self", ClientLightState.selfLightEnabled());
            m.put("note", null);
        } catch (Throwable t) {
            m.put("note", "unreadable:" + t.getClass().getSimpleName());
        }
        return m;
    }

    /** 灯参现值之二:十一旋钮 status(纯内存覆盖层,无 MC 依赖);读不到 → 全 null + note。 */
    private static Map<String, Object> collectTune() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : TUNE_KEYS) {
            m.put(k, null);
        }
        try {
            m.put("bright", dev.taclight.channel.LightTuneOverride.configureBright("status"));
            m.put("dist", dev.taclight.channel.LightTuneOverride.configureDist("status"));
            m.put("atten", dev.taclight.channel.LightTuneOverride.configureAtten("status"));
            m.put("knee", dev.taclight.channel.LightTuneOverride.configureKnee("status"));
            m.put("beam", dev.taclight.channel.LightTuneOverride.configureBeam("status"));
            m.put("scat", dev.taclight.channel.LightTuneOverride.configureScat("status"));
            m.put("beamcap", dev.taclight.channel.LightTuneOverride.configureBeamcap("status"));
            m.put("cone", dev.taclight.channel.LightTuneOverride.configureCone("status"));
            m.put("beamonly", dev.taclight.channel.LightTuneOverride.configureBeamonly("status"));
            m.put("occl", dev.taclight.channel.LightTuneOverride.configureOccl("status"));
            m.put("tm", dev.taclight.channel.LightTuneOverride.configureTemporal("status"));
            m.put("note", null);
        } catch (Throwable t) {
            m.put("note", "unreadable:" + t.getClass().getSimpleName());
        }
        return m;
    }

    /**
     * 截图:逐字仿 {@code DebugCommandRelay} 的 {@code !shot} 分支
     * (与 F2 同源直读主帧缓冲,中继在渲染 tick 内 GL 上下文在位;命令线程调入时若
     * 上下文不在位则抛,由本 try/catch 接住记 outcome,不影响 JSON/note 落盘)。
     */
    private static String grabScreenshot() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getMainRenderTarget() == null) {
                return "skipped(no-client/no-world)";
            }
            net.minecraft.client.Screenshot.grab(
                    net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toFile(),
                    mc.getMainRenderTarget(),
                    (msg) -> TacLightMod.LOGGER.info("[TacLight] SNAP shot: {}", msg.getString()));
            return "ok(screenshots/,原版时间戳命名,见日志)";
        } catch (Throwable t) {
            TacLightMod.LOGGER.warn("[TacLight] SNAP shot failed: {}", t.toString());
            return "failed:" + t.getClass().getSimpleName();
        }
    }

    /** RenderDoc 抓取(有注入才尝试;结果只记 note,不影响快照落盘)。 */
    private static String triggerRenderDocCapture() {
        try {
            if (!RenderDocGate.isAvailable()) {
                return "skipped(renderdoc absent)";
            }
            return RenderDocGate.triggerCapture() ? "requested" : "not-bound(最小闭环:仅门控,未接 RENDERDOC_GetAPI)";
        } catch (Throwable t) {
            return "failed:" + t.getClass().getSimpleName();
        }
    }

    // ---------------- 纯 JVM 部分(契约直测,无 MC 引用) ----------------

    /** 目录基名(系统时区 yyyyMMdd-HHmmss-SSS;同毫秒碰撞由 resolveUniqueDir 后缀化解)。 */
    static String dirBaseFor(long epochMilli) {
        java.time.LocalDateTime t = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMilli), java.time.ZoneId.systemDefault());
        return t.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
    }

    /**
     * 建快照目录(不存在即建;已存在追加 -2/-3…,<b>永不覆盖已有目录</b>)。
     * 返回已建好的空(或已存在但本次未动)目录。
     */
    static Path resolveUniqueDir(Path root, String base) throws java.io.IOException {
        Files.createDirectories(root);
        Path dir = root.resolve(base);
        int n = 2;
        while (Files.exists(dir)) {
            dir = root.resolve(base + "-" + (n++));
        }
        Files.createDirectories(dir);
        return dir;
    }

    /** snapshot.json 渲染(手写最小转义,零依赖,键序=JSON_KEYS)。 */
    static String buildSnapshotJson(SnapData d) {
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("reason", d.reason());
        top.put("takenAt", d.takenAt());
        top.put("epochMilli", d.epochMilli());
        top.put("renderdoc", d.renderdoc());
        top.put("pose", d.pose());
        top.put("light", d.light());
        top.put("tune", d.tune());
        top.put("voxel", d.voxel());
        top.put("notes", d.notes());
        return toJson(top);
    }

    /** note.txt 渲染(<b>首行 renderdoc 状态</b>,契约钉死)。 */
    static String buildNoteText(SnapData d, String shotOutcome, String captureOutcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("renderdoc=").append(d.renderdoc()).append("\n");
        sb.append("reason=").append(d.reason()).append("\n");
        sb.append("takenAt=").append(d.takenAt()).append("\n");
        sb.append("screenshot=").append(shotOutcome).append("\n");
        sb.append("capture=").append(captureOutcome).append("\n");
        sb.append("voxel=").append(d.voxel()).append("\n");
        Object poseNote = d.pose() == null ? "pose段缺失" : d.pose().get("note");
        sb.append("poseNote=").append(poseNote).append("\n");
        return sb.toString();
    }

    /** 最小 JSON 渲染:Map/List/String/Number/Boolean/null;其余兜底引号化 toString。 */
    private static String toJson(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        }
        if (o instanceof Number || o instanceof Boolean) {
            return o.toString();
        }
        if (o instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(escapeJson(String.valueOf(e.getKey()))).append("\":")
                        .append(toJson(e.getValue()));
            }
            return sb.append("}").toString();
        }
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object e : list) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append(toJson(e));
            }
            return sb.append("]").toString();
        }
        return "\"" + escapeJson(o.toString()) + "\"";
    }

    /** JSON 字符串最小转义(引号/反斜杠/控制字符;非 ASCII 原样过,文件按 UTF-8 写)。 */
    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
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
        return sb.toString();
    }
}

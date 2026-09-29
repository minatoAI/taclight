package dev.taclight.debug.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 机位注册表:run/config/taclight-cams.json(运行时可改、免重编译;调试自动化用)。
 * 同一 bug 的 A/B 对比必须同机位 —— 传送=确定性,不依赖手稳(doc/调试环境搭建计划 §3B)。
 * 默认机位与 scene 预设(P1)配套;文件缺失时以 DEFAULTS 播种,用户/AI 可随时加。
 * yaw 语义 = MC 原版:0=+Z(南) 90=-X(西) 180=-Z(北) 270=+X(东)。
 */
public final class CamStore {
    public static final class Cam {
        public double x;
        public double y;
        public double z;
        public double yaw;
        public double pitch;
        /** 维度(实机教训:QuickPlay 会从上次退出维度进世界;跨维度必须显式记录)。 */
        public String dim = "minecraft:overworld";

        public Cam() {}

        public Cam(double x, double y, double z, double yaw, double pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    static final class Raw {
        public Map<String, Cam> cams;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 内置机位:场景区 X=2000,Z=0,平台顶面 y=121(与 scene 预设坐标契约一致)。
     * 命名纪律:只用 [A-Za-z0-9_+-] —— brigadier 的 word()/string()(未加引号)都拒绝 '@'
     * (实机 2026-08-29 "Incorrect argument" 实锤),名字带 @ = 每次都要引号。
     */
    private static final Map<String, Cam> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("wall_front", new Cam(2000.0, 121.5, 8.0, 180.0, 5.0));
        // 2026-09-04 双端漏光验证:B 站墙后看墙背。墙 z=0 高 121..123;
        // B 在 z=-8 朝南(yaw=0)回看墙背,与 wall_front 成镜像对照。
        DEFAULTS.put("wall_back", new Cam(2000.0, 121.5, -8.0, 0.0, 5.0));
        DEFAULTS.put("corridor_end", new Cam(2001.5, 122.0, -0.5, 270.0, 0.0));
        DEFAULTS.put("bloom_inside", new Cam(2010.5, 122.0, 9.0, 180.0, 0.0));
        DEFAULTS.put("grass_low", new Cam(2000.0, 121.6, -8.0, 0.0, 10.0));
    }

    /** 内置机位只读视图(契约测试 ScenePlanContract 与 scene 几何做交叉校验用)。 */
    public static synchronized Map<String, Cam> defaults() {
        return new LinkedHashMap<>(DEFAULTS);
    }

    public static synchronized Map<String, Cam> load() {
        Map<String, Cam> out = new LinkedHashMap<>();
        Path p = path();
        if (Files.isRegularFile(p)) {
            try {
                Raw raw = GSON.fromJson(Files.readString(p), Raw.class);
                if (raw != null && raw.cams != null) out.putAll(raw.cams);
            } catch (Exception ignored) {
                // 损坏文件按默认重建
            }
        }
        for (Map.Entry<String, Cam> e : DEFAULTS.entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
        return out;
    }

    public static synchronized void upsert(String name, Cam cam) {
        Map<String, Cam> all = load();
        all.put(name, cam);
        persist(all);
    }

    private static void persist(Map<String, Cam> cams) {
        try {
            Raw raw = new Raw();
            raw.cams = cams;
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(raw));
        } catch (Exception ignored) {
        }
    }

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("taclight-cams.json");
    }

    private CamStore() {}
}

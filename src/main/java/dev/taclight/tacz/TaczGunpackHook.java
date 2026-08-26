package dev.taclight.tacz;

import com.tacz.guns.api.resource.ResourceManager;
import dev.taclight.TacLightMod;

/**
 * 通过 TaCZ 官方扩展 API(ResourceManager.EXTRA_ENTRIES)注册本模组 JAR 内的 gunpack 目录,
 * 使其被子系统扫描为"taclight_gunpack"(Main namespace = taclight)。
 * 本类引用 TaCZ 类,必须经反射调用。
 */
public final class TaczGunpackHook {
    public static void register() {
        ResourceManager.EXTRA_ENTRIES.add(
                new ResourceManager.ExtraEntry(TacLightMod.class, "/assets/taclight/gunpack", "taclight_gunpack"));
        TacLightMod.LOGGER.info("[TacLight] gunpack extra entry registered: taclight_gunpack");
    }

    private TaczGunpackHook() {}
}

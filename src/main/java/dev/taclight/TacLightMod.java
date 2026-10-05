package dev.taclight;

import com.mojang.logging.LogUtils;
import dev.taclight.config.TacLightConfig;
import dev.taclight.registry.ModItems;
import dev.taclight.tacz.TaczCompat;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(TacLightMod.MODID)
public class TacLightMod {
    public static final String MODID = "taclight";

    /**
     * 版本号 —— <b>单一真源</b> = {@code gradle.properties} 的 {@code mod_version}。
     *
     * <p>构建期由 {@code build.gradle} 的 {@code generateBuildInfo} 生成为
     * {@link TacLightBuildInfo#VERSION}(生成物是**唯一**带版本字面量的地方)。</p>
     *
     * <p><b>2026-09-28 R12 修 B2</b>:此前这里是手写字面量(旧值为 0.10.0),而打包元数据走
     * {@code mod_version} ⇒ 同一个 jar 自报两个版本(模组列表一个、日志横幅另一个),
     * 玩家报 bug 时两边对不上。现在两边同源,并由
     * {@code InteropPackagingContract.versionCoherence()} 钉成机器判据
     * (发布件里本类的常量池必须含 {@code mod_version},而本文件的**代码**里不得再出现手写版本字面量)。</p>
     */
    public static final String VERSION = TacLightBuildInfo.VERSION;
    public static final Logger LOGGER = LogUtils.getLogger();

    public TacLightMod() {
        LOGGER.info("[TacLight] v{} loading: spotlight device mod", VERSION);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, TacLightConfig.SPEC, "taclight-client.toml");
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.register(bus);
        bus.addListener(ModItems::onCreativeTabLoad);
        tryRegisterGunpack();
        bus.addListener(this::commonSetup);
        bus.addListener(this::clientSetup);
    }

    /** 软依赖注册:仅当 TaCZ 存在时,反射调用 TaczGunpackHook(其类引用 TaCZ)。 */
    private static void tryRegisterGunpack() {
        if (!TaczCompat.present()) {
            LOGGER.info("[TacLight] TaCZ not detected: gunpack & gun-light integration disabled");
            return;
        }
        try {
            Class.forName("dev.taclight.tacz.TaczGunpackHook").getMethod("register").invoke(null);
        } catch (Throwable t) {
            LOGGER.warn("[TacLight] gunpack hook failed: {}", t.toString());
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // M5 多人灯状态通道(SynchedEntityData 真源 + 开关上报/回发两个包)
        event.enqueueWork(dev.taclight.network.TacLightNetwork::register);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // BUILTIN 2026-10-05: install TacLight-Builtin into shaderpacks on first launch
        // (Iris discovers packs only from disk; the JAR cannot register one in memory).
        // Any failure degrades to skip: lighting falls back to injection/generic paths.
        event.enqueueWork(() -> {
            try {
                var settings = builtinSettings();
                var outcome = dev.taclight.builtin.BuiltinPackInstaller.ensureInstalled(
                        net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get(), settings,
                        () -> dev.taclight.builtin.BuiltinPackInstaller.class.getResourceAsStream(
                                dev.taclight.builtin.BuiltinPackInstaller.RESOURCE_ZIP));
                LOGGER.info("[TacLight] builtin pack: {}", outcome.detail());
            } catch (Throwable t) {
                LOGGER.warn("[TacLight] builtin pack install skipped: {}", t.toString());
            }
        });
    }

    private static dev.taclight.builtin.BuiltinPackInstaller.Settings builtinSettings() {
        try {
            return new dev.taclight.builtin.BuiltinPackInstaller.Settings(
                    TacLightConfig.EXPOSURE_ADAPTIVE.get(),
                    TacLightConfig.EXPOSURE_TARGET.get(),
                    TacLightConfig.EXPOSURE_MIN.get(),
                    TacLightConfig.EXPOSURE_MAX.get(),
                    TacLightConfig.EXPOSURE_ADAPT_RATE.get(),
                    VERSION);
        } catch (Throwable t) {
            return dev.taclight.builtin.BuiltinPackInstaller.defaults(VERSION);
        }
    }
}

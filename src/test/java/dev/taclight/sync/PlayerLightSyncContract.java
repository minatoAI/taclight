package dev.taclight.sync;

import dev.taclight.mixin.TacLightMixinPlugin;
import dev.taclight.network.TacLightNetwork;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * M5 多人灯状态契约(纯 JVM,离线):
 * 1) mixin 注入在公共列表且不被 TaCZ 门控(无 TaCZ 时同步必须仍生效);
 * 2) 两个 accessor id 互异(同实体双布尔,defineId 冲突 = 静默覆盖);
 * 3) C2S/S2C 包 encode→decode 回环一致(协议兼容性锚点,wire 2 字节)。
 */
public class PlayerLightSyncContract {
    public static void main(String[] args) throws Exception {
        // ---- 1) mixin 注册与门控旁路 ----
        String json = Files.readString(Path.of("src/main/resources/taclight.mixins.json"));
        check(json.contains("\"mixins\""), "mixins.json 有公共 mixins 列表");
        check(json.contains("PlayerSynchedDataMixin"), "PlayerSynchedDataMixin 在公共列表(非 client)");
        TacLightMixinPlugin plugin = new TacLightMixinPlugin();
        check(plugin.shouldApplyMixin("net.minecraft.world.entity.player.Player",
                "dev.taclight.mixin.PlayerSynchedDataMixin"),
                "Player 同步 mixin 旁路 TaCZ 门控(恒 true)");
        boolean tacZ = dev.taclight.tacz.TaczCompat.present();
        check(plugin.shouldApplyMixin("net.minecraft.client.renderer.item.ItemRenderer",
                "dev.taclight.mixin.BeamRendererMixin") == tacZ,
                "TaCZ 专属 mixin 门控值 == TaczCompat.present()(" + tacZ + ")");

        // ---- 2) accessor 互异 + 离线降级 ----
        // EntityDataSerializers 静态初始化走原版注册表:离线 JVM 必须先手动引导
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        check(PlayerLightAccess.FLASHLIGHT != null && PlayerLightAccess.GUNLIGHT != null,
                "两个 EntityDataAccessor 定义成功(Bootstrap 后静态初始化)");
        check(PlayerLightAccess.FLASHLIGHT.getId() != PlayerLightAccess.GUNLIGHT.getId(),
                "accessor id 互异");
        check(!PlayerLightAccess.syncReady, "离线 JVM 无 mixin 应用:syncReady=false(读侧优雅降级)");

        // ---- 3) 包回环 ----
        FriendlyByteBuf b1 = new FriendlyByteBuf(Unpooled.buffer());
        TacLightNetwork.SetLightC2S.encode(new TacLightNetwork.SetLightC2S(true, false), b1);
        check(b1.readableBytes() == 2, "C2S wire = 2 字节(双布尔)");
        TacLightNetwork.SetLightC2S c2s = TacLightNetwork.SetLightC2S.decode(b1);
        check(c2s.handheld() && !c2s.gun(), "C2S 回环 (true,false)");

        FriendlyByteBuf b2 = new FriendlyByteBuf(Unpooled.buffer());
        TacLightNetwork.SyncLightS2C.encode(new TacLightNetwork.SyncLightS2C(false, true), b2);
        check(b2.readableBytes() == 2, "S2C wire = 2 字节");
        TacLightNetwork.SyncLightS2C s2c = TacLightNetwork.SyncLightS2C.decode(b2);
        check(!s2c.handheld() && s2c.gun(), "S2C 回环 (false,true)");

        System.out.println("PlayerLightSyncContract: ALL PASS (10 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

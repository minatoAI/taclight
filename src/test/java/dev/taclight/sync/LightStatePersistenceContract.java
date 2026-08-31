package dev.taclight.sync;

import net.minecraft.nbt.CompoundTag;

/**
 * 灯态持久化契约(09-01,用户 bug:退出灯开、重进灯灭):NBT 读写纯函数是
 * "重进游戏灯态自动恢复"的地基,离线钉死存储位置与语义。
 */
public final class LightStatePersistenceContract {
    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("PASS: " + msg);
    }

    public static void main(String[] args) {
        // ---- 1) 空数据:present=false,不恢复(保持原版默认关) ----
        LightStatePersistence.LightFlags empty =
                LightStatePersistence.readFrom(new CompoundTag());
        check(!empty.present() && !empty.handheld() && !empty.gun(), "未写过时 present=false");

        // ---- 2) 写读回环(四种组合) ----
        boolean[][] combos = {{true, true}, {true, false}, {false, true}, {false, false}};
        for (boolean[] c : combos) {
            CompoundTag data = new CompoundTag();
            LightStatePersistence.saveTo(data, c[0], c[1]);
            LightStatePersistence.LightFlags f = LightStatePersistence.readFrom(data);
            check(f.present() && f.handheld() == c[0] && f.gun() == c[1],
                    "回环 handheld=" + c[0] + " gun=" + c[1]);
        }

        // ---- 3) 存储位置:Forge persisted 子树(Player.PERSISTED_NBT_TAG)——跨 relog 且跨死亡克隆 ----
        CompoundTag data = new CompoundTag();
        LightStatePersistence.saveTo(data, true, false);
        CompoundTag persisted = data.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
        check(persisted.getBoolean("taclight:handheld") && !persisted.contains("taclight:gun_true_dummy"),
                "旗标落在 persisted 子树");
        check(data.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
                .getBoolean("taclight:gun") == false, "枪灯旗标同子树");

        // ---- 4) 不污染同级数据;重复写覆盖 ----
        data.putUUID("other_key", new java.util.UUID(1L, 2L));
        CompoundTag persisted2 = data.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
        persisted2.putFloat("other_mod", 3.14f);
        data.put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, persisted2);
        LightStatePersistence.saveTo(data, false, true);
        check(data.hasUUID("other_key"), "forgeData 同级键不受影响");
        check(Math.abs(data.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
                .getFloat("other_mod") - 3.14f) < 1e-5f, "persisted 子树他模组键不受影响");
        LightStatePersistence.LightFlags overwritten = LightStatePersistence.readFrom(data);
        check(overwritten.present() && !overwritten.handheld() && overwritten.gun(), "重复写覆盖旧值");

        // ---- 5) 空串键名防冲突:读他模组同形数据安全 ----
        CompoundTag foreign = new CompoundTag();
        foreign.putBoolean("taclight:handheld", true); // 裸键(不在 persisted 子树)——应被忽略
        check(!LightStatePersistence.readFrom(foreign).present(), "裸键不入读(仅认 persisted 子树)");

        System.out.println("LightStatePersistenceContract: ALL PASS (" + passed + " checks)");
    }
}

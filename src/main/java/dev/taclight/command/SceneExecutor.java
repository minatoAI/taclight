package dev.taclight.command;

import dev.taclight.TacLightMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * ScenePresets 计划的服务端执行器:清场(空气盒+清实体)→ 环境锁(午夜/停昼夜/停刷怪/晴)
 * → 逐 fill setBlock → spawn → 一行结构化日志(自动化 grep 依赖)。
 * 幂等:计划为纯函数 + 清场在前,重复执行结果一致(计划文档 §3A)。
 */
final class SceneExecutor {

    /** 返回值 = 命令 exit code(0 = 未知预设失败)。 */
    static int apply(CommandSourceStack source, String preset) {
        ScenePresets.Plan plan = ScenePresets.plan(preset);
        if (plan == null) {
            source.sendFailure(Component.literal("[TacLight] scene '" + preset
                    + "' 不存在。可用: " + String.join(", ", ScenePresets.names())));
            TacLightMod.LOGGER.info("[TacLight] SCENE {} MISS", preset);
            return 0;
        }
        // 场景区契约绑定主世界(玩家可能在任意维度 —— QuickPlay 会从上次退出维度进世界)
        ServerLevel level = source.getServer().overworld();

        // ---- 1 布置:终态折叠(last-writer-wins)后只写差量。重叠 fill(平台↔小屋地板)
        //      不再互相翻转 —— 旧法每次重建 30 次真实翻转写,被误读为"30 格回滚"(坑位 16)----
        int changed = 0;
        List<String> unknownBlocks = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (ScenePresets.Expected e : ScenePresets.expectedList(plan)) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(e.block()));
            if (block == null) {
                unknownBlocks.add(e.block());
                continue;
            }
            var state = block.defaultBlockState();
            pos.set(e.x(), e.y(), e.z());
            if (level.getBlockState(pos) == state) continue;   // 同态跳过
            if (level.setBlock(pos, state, 3)) changed++;
        }

        // ---- 2 清实体(玩家除外,区域盒外扩 8 格)----
        AABB killBox = new AABB(ScenePresets.REGION_MIN_X - 8, ScenePresets.REGION_MIN_Y - 4,
                ScenePresets.REGION_MIN_Z - 8, ScenePresets.REGION_MAX_X + 8,
                ScenePresets.REGION_MAX_Y + 8, ScenePresets.REGION_MAX_Z + 8);
        List<Entity> doomed = level.getEntitiesOfClass(Entity.class, killBox, e -> !(e instanceof Player));
        for (Entity e : doomed) e.discard();

        // ---- 3 环境锁:午夜 + 停昼夜 + 停刷怪 + 晴 ----
        // 2026-09-04 双端漏光环境防干扰三件套(实机双端被蜘蛛咬死):
        // 和平(已刷怪清仇恨/停新仇恨) + 预设怪 NoAI(原地不动) + 双玩家创造(免伤)。
        // 注意:和平会清掉本次新刷的猪以外的旧怪 —— 清实体在前,spawn 在后,顺序已有保障。
        level.setDayTime(18000);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.setWeatherParameters(6000, 6000, false, false);
        level.getServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
        for (net.minecraft.server.level.ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        }

        // ---- 4 实体 ----
        int spawned = 0;
        List<String> spawnErrors = new ArrayList<>();
        for (ScenePresets.Spawn s : plan.spawns()) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(s.entity()));
            if (type == null) {
                spawnErrors.add(s.entity());
                continue;
            }
            Entity e = type.create(level);
            if (e == null) {
                spawnErrors.add(s.entity());
                continue;
            }
            e.moveTo(s.x(), s.y(), s.z(), 0.0f, 0.0f);
            if (e instanceof Mob mob) {
                mob.setPersistenceRequired(); // 防消失,保机位确定性
                mob.setNoAi(true);            // 2026-09-04 防干扰:原地不动,不咬玩家
            }
            level.addFreshEntity(e);
            spawned++;
        }

        String env = "midnight daylight=off mobspawning=off clear";
        final int fChanged = changed;
        final int fSpawned = spawned;
        String notes = (spawnErrors.isEmpty() ? "" : " spawnFailed=" + spawnErrors)
                + (unknownBlocks.isEmpty() ? "" : " unknownBlock=" + unknownBlocks);
        source.sendSuccess(() -> Component.literal("[TacLight] scene '" + plan.name() + "' built: fills="
                + plan.fills().size() + " blocks=" + fChanged + " spawns=" + fSpawned + notes), false);
        TacLightMod.LOGGER.info("[TacLight] SCENE {} fills={} blocks={} spawns={}{} | {}",
                plan.name(), plan.fills().size(), changed, spawned, notes, env);
        return 1;
    }

    /** 在线验收:计划期望块 vs 世界实际块,逐格对照(坑位 16 回滚取证)。 */
    static int verify(CommandSourceStack source, String preset) {
        ScenePresets.Plan plan = ScenePresets.plan(preset);
        if (plan == null) {
            source.sendFailure(Component.literal("[TacLight] scene '" + preset
                    + "' 不存在。可用: " + String.join(", ", ScenePresets.names())));
            TacLightMod.LOGGER.info("[TacLight] SCENEVERIFY {} MISS", preset);
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        ScenePresets.VerifyResult r = ScenePresets.verify(plan, (x, y, z) -> {
            Block b = level.getBlockState(new BlockPos(x, y, z)).getBlock();
            ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(b);
            return rl != null ? rl.toString() : b.toString();
        });
        String msg = "[TacLight] SCENEVERIFY '" + plan.name() + "' total=" + r.total()
                + " mismatch=" + r.mismatch() + (r.mismatch() == 0 ? " OK"
                : " first=" + r.first());
        source.sendSuccess(() -> Component.literal(msg), false);
        TacLightMod.LOGGER.info("{} | sampled@overworld", msg);
        return 1;
    }

    private SceneExecutor() {}
}

package dev.taclight;

import dev.taclight.channel.LookTraceContract;
import dev.taclight.channel.LightLevelOverrideContract;
import dev.taclight.channel.LightTuneContract;
import dev.taclight.tune.TuneContract;
import dev.taclight.channel.MultiLightCollectorContract;
import dev.taclight.channel.OcclTableContract;
import dev.taclight.channel.TemporalReuseContract;
import dev.taclight.channel.RemoteBaseSnapContract;
import dev.taclight.channel.RemotePosSnapContract;
import dev.taclight.channel.RemoteLookPredictorContract;
import dev.taclight.channel.SlabHitDistContract;
import dev.taclight.channel.SpotlightBufferLayoutContract;
import dev.taclight.channel.UploaderSemanticContract;
import dev.taclight.channel.BoundedIdentityCacheContract;
import dev.taclight.channel.VoxelClassifyContract;
import dev.taclight.channel.VoxelDdaContract;
import dev.taclight.channel.VoxelFieldContract;
import dev.taclight.channel.VoxelProbeContract;
import dev.taclight.client.BobViewControlContract;
import dev.taclight.client.VoxelGridWiringContract;
import dev.taclight.client.VoxelRealRegistryContract;
import dev.taclight.client.CameraSweepContract;
import dev.taclight.client.TpFallbackControlContract;
import dev.taclight.client.TpOffscreenRenderGateContract;
import dev.taclight.client.GunControlContract;
import dev.taclight.client.SelfLightGateContract;
import dev.taclight.pose.MuzzlePoseMathContract;
import dev.taclight.pose.MuzzlePoseModelContract;
import dev.taclight.pose.MuzzlePoseStoreContract;
import dev.taclight.pose.TpLightResolverContract;
import dev.taclight.scene.ScenePlanContract;
import dev.taclight.sync.LightStatePersistenceContract;
import dev.taclight.sync.PlayerLightSyncContract;
import dev.taclight.shader.ShaderCoreContract;
import dev.taclight.interop.InlineCoreContract;
import dev.taclight.interop.PackFingerprintContract;
import dev.taclight.interop.PatchExecutorContract;
import dev.taclight.interop.TemplateLibraryContract;
import dev.taclight.builtin.BuiltinPackContract;
import dev.taclight.tacz.GunLaserReaderContract;
import dev.taclight.tacz.GunLightAllowContract;

/** 汇总契约运行器(离线、纯 JVM)。 */
public class AllContracts {
    public static void main(String[] args) throws Exception {
        // ⚠️ 2026-09-26 task-24(审核 R3 建议):本套件是 **fail-fast** —— 任一契约抛异常,其后的契约
        // **根本不会跑**。读到 FAIL 时不要把它读成"后面的契约没抓到问题"。例如 KeyInjectContract 在
        // 本轮排 8/46,它一红 ⇒ 后面 38 个契约全部未执行(这也是我当时"部分红"归因踩过的坑)。
        System.out.println("[AllContracts] fail-fast 套件:任一契约 FAIL ⇒ 其后契约未执行(勿把'部分红'读成'没抓到')");
        // ⚠️ 注册表全序(task-64,2026-09-26):新条目必须插入正确位置,只增删整行/整块,
        //    **不得改动任何既有行、不得整体重排**。全序口径(与 ContractOrderContract 一致):
        //   键1 = 契约【简单类名】按【序数(UTF-16 code unit)升序】(String.compareTo;禁文化敏感排序);
        //   键2 = 并列时按【全限定类名】序数升序。交换两行 ⇒ ContractOrderContract 必红。

        System.out.println("== BobViewControlContract ==");
        BobViewControlContract.main(args);

        System.out.println("== BoundedIdentityCacheContract ==");
        BoundedIdentityCacheContract.main(args);

        System.out.println("== BuiltinPackContract ==");
        BuiltinPackContract.main(args);

        System.out.println("== CameraSweepContract ==");
        CameraSweepContract.main(args);

        // 契约注册表顺序(task-64):一契约一行 + 序数全序(简单类名 asc,并列按全限定名 asc)
        // + 每个注册项都有对应表头;交换两行 ⇒ 本契约必红。
        System.out.println("== ContractOrderContract ==");
        dev.taclight.ContractOrderContract.main(args);



        System.out.println("== GunControlContract ==");
        GunControlContract.main(args);

        System.out.println("== GunLaserReaderContract ==");
        GunLaserReaderContract.main(args);

        System.out.println("== GunLightAllowContract ==");
        GunLightAllowContract.main(args);

        // 手持灯持物门(2026-09-25 用户报的 bug):手里拿枪时两盏灯同时亮、离手不灭。
        System.out.println("== HandheldGateContract ==");
        dev.taclight.client.HandheldGateContract.main(args);

        // !hud off|on|status(2026-09-26 task-51 第 2 步):三分支真值表 / mutates /
        // 赋值落在 !hud 分支片段内 / STATUS 块只读 / describe 逐字 / dev-only 命令串。
        System.out.println("== HudCommandContract ==");
        dev.taclight.client.HudCommandContract.main(args);

        System.out.println("== InlineCoreContract ==");
        InlineCoreContract.main(args);

        // 锚点层离线判据(2026-09-19 闸门换位):读真实包 fixture(本机为用户 r5.9.3 zip),
        // 断言"锚点逐字全中 ⇒ 可注入";包不可得时退化为合成 fixture 并大声标注 FIXTURE=。
        System.out.println("== InteropAnchorFixtureContract ==");
        dev.taclight.interop.InteropAnchorFixtureContract.main(args);

        // 发布包边界反回归(2026-09-19):发布 jar 不得含调试通道类;dev 构建树必须含(剔除≠没编译);
        // build.gradle 剔除规则在;监听路径 = <gameDir>/taclight-cmds.txt;文案不得再指"游戏内 !interop"。
        System.out.println("== InteropPackagingContract ==");
        dev.taclight.interop.InteropPackagingContract.main(args);

        // !interop 诊断工具契约(2026-09-19):内容层五要素(纯逻辑)+ 接线层(源码文本级)。
        System.out.println("== InteropStatusContract ==");
        dev.taclight.interop.InteropStatusContract.main(args);

        // 合成按键注入纯核心(2026-09-26 待办 A5,中继 !key):名字表 / 两类消费路径 /
        // 毫秒自动抬起状态机 / Tracker(离线真跑) + dev 中继接线(进程内 KeyboardHandler.keyPress)。
        System.out.println("== KeyInjectContract ==");
        dev.taclight.channel.KeyInjectContract.main(args);

        // 按键存档值持久化(2026-09-26 task-32):纯解析口径 + "先注册后应用"的顺序断言
        // + 可观测行(供真机判定"注册 vs Options.load"先后)⇒ 修"改键重启失效"的玩家级 bug。
        System.out.println("== KeyPersistContract ==");
        dev.taclight.client.KeyPersistContract.main(args);

        System.out.println("== LightLevelOverrideContract ==");
        LightLevelOverrideContract.main(args);

        System.out.println("== LightStatePersistenceContract ==");
        LightStatePersistenceContract.main(args);

        System.out.println("== LightTuneContract ==");
        LightTuneContract.main(args);

        System.out.println("== LookTraceContract ==");
        LookTraceContract.main(args);


        System.out.println("== MultiLightCollectorContract ==");
        MultiLightCollectorContract.main(args);

        System.out.println("== MuzzlePoseMathContract ==");
        MuzzlePoseMathContract.main(args);

        System.out.println("== MuzzlePoseModelContract ==");
        MuzzlePoseModelContract.main(args);

        System.out.println("== MuzzlePoseStoreContract ==");
        MuzzlePoseStoreContract.main(args);

        System.out.println("== OcclTableContract ==");
        OcclTableContract.main(args);

        System.out.println("== PackFingerprintContract ==");
        PackFingerprintContract.main(args);

        System.out.println("== PatchExecutorContract ==");
        PatchExecutorContract.main(args);


        System.out.println("== PlayerLightSyncContract ==");
        PlayerLightSyncContract.main(args);

        System.out.println("== RemoteBaseSnapContract ==");
        RemoteBaseSnapContract.main(args);

        System.out.println("== RemoteLookPredictorContract ==");
        RemoteLookPredictorContract.main(args);

        System.out.println("== RemotePosSnapContract ==");
        RemotePosSnapContract.main(args);

        System.out.println("== ScenePlanContract ==");
        ScenePlanContract.main(args);

        System.out.println("== SelfLightGateContract ==");
        SelfLightGateContract.main(args);

        System.out.println("== ShaderCoreContract ==");
        ShaderCoreContract.main(args);

        // 光影包自检(2026-09-25):interop 运行时注入成功却被报"无注入"的误报修复。
        System.out.println("== ShaderPackDiagContract ==");
        dev.taclight.client.ShaderPackDiagContract.main(args);

        // 薄板命中距离(2026-09-30 R13):建表路径 taclight_vox_hit_dist 对薄板返回"入格距离"
        // 而非"板面穿越距离" ⇒ 薄雪层阴影被拉长(中位 0.88/最差 1.36 格)。契约 BACKLOG §2.123/§2.124。

        // 形状调色板(2026-10-03 R21):精确性口径/去重/容量与降级/布局与 GLSL 逐值对齐。
        // 它是 4bit→8bit + 调色板段的唯一机核闸门 —— 错一个偏移 = 读到别的浮点 = 随机假遮挡。
        System.out.println("== ShapePaletteContract ==");
        dev.taclight.channel.ShapePaletteContract.main(args);

        System.out.println("== SlabHitDistContract ==");
        SlabHitDistContract.main(args);

        System.out.println("== SpotlightBufferLayoutContract ==");
        SpotlightBufferLayoutContract.main(args);

        // 事件订阅作用域 + 可观测性(2026-09-26 task-54):禁"嵌套类携带 @Mod.EventBusSubscriber";
        // 有副作用的站点必须有"确实跑过"的标记;类内无日志者查两条边界标记(调用方字面量 + 打印侧日志行)。
        System.out.println("== SubscriberScopeContract ==");
        dev.taclight.SubscriberScopeContract.main(args);

        System.out.println("== TemplateLibraryContract ==");
        TemplateLibraryContract.main(args);

        System.out.println("== TemporalReuseContract ==");
        TemporalReuseContract.main(args);

        System.out.println("== TpFallbackControlContract ==");
        TpFallbackControlContract.main(args);

        System.out.println("== TpLightResolverContract ==");
        TpLightResolverContract.main(args);

        System.out.println("== TpOffscreenRenderGateContract ==");
        TpOffscreenRenderGateContract.main(args);

        System.out.println("== TuneContract ==");
        TuneContract.main(args);

        System.out.println("== UploaderSemanticContract ==");
        UploaderSemanticContract.main(args);

        System.out.println("== VoxelClassifyContract ==");
        VoxelClassifyContract.main(args);

        System.out.println("== VoxelDdaContract ==");
        VoxelDdaContract.main(args);

        System.out.println("== VoxelFieldContract ==");
        VoxelFieldContract.main(args);

        System.out.println("== VoxelGridWiringContract ==");
        VoxelGridWiringContract.main(args);

        // 体素单元诊断探针(2026-09-25 细雪层穿光轮):live(现场判定)/grid(已上传)并排,
        // 复用生产同一条 DDA;接线 = !voxprobe / !voxray。
        System.out.println("== VoxelProbeContract ==");
        VoxelProbeContract.main(args);

        // 真 registry 契约放最后:bootstrap 若在本机 JavaExec 环境不可用,它会大声失败,
        // 但前面的结果已全部打印(不会掩盖其它契约的结论)。
        System.out.println("== VoxelRealRegistryContract ==");
        VoxelRealRegistryContract.main(args);

        // 2026-10-03 R19:形状调色板的设计尺寸闸门(不同形状数 / 单形状最大盒数),
        // 也放最后 —— 它同样需要 bootstrap。
        System.out.println("== VoxelShapePaletteContract ==");
        dev.taclight.client.VoxelShapePaletteContract.main(args);

        System.out.println("AllContracts: ALL PASS");
    }
}

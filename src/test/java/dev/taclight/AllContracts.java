package dev.taclight;

import dev.taclight.channel.LookTraceContract;
import dev.taclight.channel.LightLevelOverrideContract;
import dev.taclight.channel.LightTuneContract;
import dev.taclight.tune.TuneContract;
import dev.taclight.channel.MotionCaptureContract;
import dev.taclight.channel.FrameRecorderContract;
import dev.taclight.channel.MultiLightCollectorContract;
import dev.taclight.channel.OcclTableContract;
import dev.taclight.channel.TemporalReuseContract;
import dev.taclight.channel.RemoteBaseSnapContract;
import dev.taclight.channel.RemotePosSnapContract;
import dev.taclight.channel.PerfStatsContract;
import dev.taclight.channel.RemoteLookPredictorContract;
import dev.taclight.channel.SpotlightBufferLayoutContract;
import dev.taclight.channel.UploaderSemanticContract;
import dev.taclight.channel.BoundedIdentityCacheContract;
import dev.taclight.channel.VoxelClassifyContract;
import dev.taclight.channel.VoxelDdaContract;
import dev.taclight.channel.VoxelFieldContract;
import dev.taclight.channel.VoxelProbeContract;
import dev.taclight.client.BobViewControlContract;
import dev.taclight.client.DebugSnapshotContract;
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
import dev.taclight.tacz.GunLaserReaderContract;
import dev.taclight.tacz.GunLightAllowContract;

/** 汇总契约运行器(离线、纯 JVM)。 */
public class AllContracts {
    public static void main(String[] args) throws Exception {
        // ⚠️ 2026-09-26 task-24(审核 R3 建议):本套件是 **fail-fast** —— 任一契约抛异常,其后的契约
        // **根本不会跑**。读到 FAIL 时不要把它读成"后面的契约没抓到问题"。例如 KeyInjectContract 在
        // 本轮排 8/46,它一红 ⇒ 后面 38 个契约全部未执行(这也是我当时"部分红"归因踩过的坑)。
        System.out.println("[AllContracts] fail-fast 套件:任一契约 FAIL ⇒ 其后契约未执行(勿把'部分红'读成'没抓到')");
        System.out.println("== GunLaserReaderContract ==");
        GunLaserReaderContract.main(args);
        System.out.println("== GunLightAllowContract ==");
        GunLightAllowContract.main(args);
        System.out.println("== SpotlightBufferLayoutContract ==");
        SpotlightBufferLayoutContract.main(args);
        System.out.println("== VoxelFieldContract ==");
        VoxelFieldContract.main(args);
        System.out.println("== VoxelDdaContract ==");
        VoxelDdaContract.main(args);
        System.out.println("== VoxelClassifyContract ==");
        VoxelClassifyContract.main(args);
        // 体素单元诊断探针(2026-09-25 细雪层穿光轮):live(现场判定)/grid(已上传)并排,
        // 复用生产同一条 DDA;接线 = !voxprobe / !voxray。
        System.out.println("== VoxelProbeContract ==");
        VoxelProbeContract.main(args);
        // 合成按键注入纯核心(2026-09-26 待办 A5,中继 !key):名字表 / 两类消费路径 /
        // 毫秒自动抬起状态机 / Tracker(离线真跑) + dev 中继接线(进程内 KeyboardHandler.keyPress)。
        System.out.println("== KeyInjectContract ==");
        dev.taclight.channel.KeyInjectContract.main(args);
        // 按键存档值持久化(2026-09-26 task-32):纯解析口径 + "先注册后应用"的顺序断言
        // + 可观测行(供真机判定"注册 vs Options.load"先后)⇒ 修"改键重启失效"的玩家级 bug。
        System.out.println("== KeyPersistContract ==");
        dev.taclight.client.KeyPersistContract.main(args);
        // !hud off|on|status(2026-09-26 task-51 第 2 步):三分支真值表 / mutates /
        // 赋值落在 !hud 分支片段内 / STATUS 块只读 / describe 逐字 / dev-only 命令串。
        System.out.println("== HudCommandContract ==");
        dev.taclight.client.HudCommandContract.main(args);
        // 事件订阅作用域 + 可观测性(2026-09-26 task-54):禁"嵌套类携带 @Mod.EventBusSubscriber";
        // 有副作用的站点必须有"确实跑过"的标记;类内无日志者查两条边界标记(调用方字面量 + 打印侧日志行)。
        System.out.println("== SubscriberScopeContract ==");
        dev.taclight.SubscriberScopeContract.main(args);
        System.out.println("== BoundedIdentityCacheContract ==");
        BoundedIdentityCacheContract.main(args);
        System.out.println("== VoxelGridWiringContract ==");
        VoxelGridWiringContract.main(args);
        System.out.println("== ShaderCoreContract ==");
        ShaderCoreContract.main(args);
        System.out.println("== PatchExecutorContract ==");
        PatchExecutorContract.main(args);
        System.out.println("== TemplateLibraryContract ==");
        TemplateLibraryContract.main(args);
        System.out.println("== PackFingerprintContract ==");
        PackFingerprintContract.main(args);
        // 锚点层离线判据(2026-09-19 闸门换位):读真实包 fixture(本机为用户 r5.9.3 zip),
        // 断言"锚点逐字全中 ⇒ 可注入";包不可得时退化为合成 fixture 并大声标注 FIXTURE=。
        System.out.println("== InteropAnchorFixtureContract ==");
        dev.taclight.interop.InteropAnchorFixtureContract.main(args);
        // !interop 诊断工具契约(2026-09-19):内容层五要素(纯逻辑)+ 接线层(源码文本级)。
        System.out.println("== InteropStatusContract ==");
        dev.taclight.interop.InteropStatusContract.main(args);
        // 发布包边界反回归(2026-09-19):发布 jar 不得含调试通道类;dev 构建树必须含(剔除≠没编译);
        // build.gradle 剔除规则在;监听路径 = <gameDir>/taclight-cmds.txt;文案不得再指"游戏内 !interop"。
        System.out.println("== InteropPackagingContract ==");
        dev.taclight.interop.InteropPackagingContract.main(args);
        System.out.println("== InlineCoreContract ==");
        InlineCoreContract.main(args);
        System.out.println("== UploaderSemanticContract ==");
        UploaderSemanticContract.main(args);
        System.out.println("== LightLevelOverrideContract ==");
        LightLevelOverrideContract.main(args);
        System.out.println("== LightTuneContract ==");
        LightTuneContract.main(args);
        System.out.println("== TuneContract ==");
        TuneContract.main(args);
        System.out.println("== OcclTableContract ==");
        OcclTableContract.main(args);
        System.out.println("== TemporalReuseContract ==");
        TemporalReuseContract.main(args);
        System.out.println("== MuzzlePoseMathContract ==");
        MuzzlePoseMathContract.main(args);
        System.out.println("== MuzzlePoseStoreContract ==");
        MuzzlePoseStoreContract.main(args);
        System.out.println("== MuzzlePoseModelContract ==");
        MuzzlePoseModelContract.main(args);
        System.out.println("== TpLightResolverContract ==");
        TpLightResolverContract.main(args);
        System.out.println("== CameraSweepContract ==");
        CameraSweepContract.main(args);
        System.out.println("== TpFallbackControlContract ==");
        TpFallbackControlContract.main(args);
        System.out.println("== TpOffscreenRenderGateContract ==");
        TpOffscreenRenderGateContract.main(args);
        System.out.println("== GunControlContract ==");
        GunControlContract.main(args);
        System.out.println("== SelfLightGateContract ==");
        SelfLightGateContract.main(args);
        // 光影包自检(2026-09-25):interop 运行时注入成功却被报"无注入"的误报修复。
        System.out.println("== ShaderPackDiagContract ==");
        dev.taclight.client.ShaderPackDiagContract.main(args);
        // 手持灯持物门(2026-09-25 用户报的 bug):手里拿枪时两盏灯同时亮、离手不灭。
        System.out.println("== HandheldGateContract ==");
        dev.taclight.client.HandheldGateContract.main(args);
        System.out.println("== ScenePlanContract ==");
        ScenePlanContract.main(args);
        System.out.println("== MultiLightCollectorContract ==");
        MultiLightCollectorContract.main(args);
        System.out.println("== RemoteLookPredictorContract ==");
        RemoteLookPredictorContract.main(args);
        System.out.println("== PerfStatsContract ==");
        PerfStatsContract.main(args);
        System.out.println("== RemoteBaseSnapContract ==");
        RemoteBaseSnapContract.main(args);
        System.out.println("== RemotePosSnapContract ==");
        RemotePosSnapContract.main(args);
        System.out.println("== LookTraceContract ==");
        LookTraceContract.main(args);
        System.out.println("== MotionCaptureContract ==");
        MotionCaptureContract.main(args);
        System.out.println("== FrameRecorderContract ==");
        FrameRecorderContract.main(args);
        System.out.println("== BobViewControlContract ==");
        BobViewControlContract.main(args);
        System.out.println("== DebugSnapshotContract ==");
        DebugSnapshotContract.main(args);
        System.out.println("== PlayerLightSyncContract ==");
        PlayerLightSyncContract.main(args);
        System.out.println("== LightStatePersistenceContract ==");
        LightStatePersistenceContract.main(args);
        // 真 registry 契约放最后:bootstrap 若在本机 JavaExec 环境不可用,它会大声失败,
        // 但前面的结果已全部打印(不会掩盖其它契约的结论)。
        System.out.println("== VoxelRealRegistryContract ==");
        VoxelRealRegistryContract.main(args);
        System.out.println("AllContracts: ALL PASS");
    }
}

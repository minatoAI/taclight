package dev.taclight;

import dev.taclight.channel.LookTraceContract;
import dev.taclight.channel.MotionCaptureContract;
import dev.taclight.channel.MultiLightCollectorContract;
import dev.taclight.channel.RemoteBaseSnapContract;
import dev.taclight.channel.RemotePosSnapContract;
import dev.taclight.channel.RemoteLookPredictorContract;
import dev.taclight.channel.SpotlightBufferLayoutContract;
import dev.taclight.channel.UploaderSemanticContract;
import dev.taclight.channel.VoxelFieldContract;
import dev.taclight.pose.MuzzlePoseMathContract;
import dev.taclight.scene.ScenePlanContract;
import dev.taclight.sync.LightStatePersistenceContract;
import dev.taclight.sync.PlayerLightSyncContract;
import dev.taclight.tacz.GunLaserReaderContract;

/** 汇总契约运行器(离线、纯 JVM)。 */
public class AllContracts {
    public static void main(String[] args) throws Exception {
        System.out.println("== GunLaserReaderContract ==");
        GunLaserReaderContract.main(args);
        System.out.println("== SpotlightBufferLayoutContract ==");
        SpotlightBufferLayoutContract.main(args);
        System.out.println("== VoxelFieldContract ==");
        VoxelFieldContract.main(args);
        System.out.println("== UploaderSemanticContract ==");
        UploaderSemanticContract.main(args);
        System.out.println("== MuzzlePoseMathContract ==");
        MuzzlePoseMathContract.main(args);
        System.out.println("== ScenePlanContract ==");
        ScenePlanContract.main(args);
        System.out.println("== MultiLightCollectorContract ==");
        MultiLightCollectorContract.main(args);
        System.out.println("== RemoteLookPredictorContract ==");
        RemoteLookPredictorContract.main(args);
        System.out.println("== RemoteBaseSnapContract ==");
        RemoteBaseSnapContract.main(args);
        System.out.println("== RemotePosSnapContract ==");
        RemotePosSnapContract.main(args);
        System.out.println("== LookTraceContract ==");
        LookTraceContract.main(args);
        System.out.println("== MotionCaptureContract ==");
        MotionCaptureContract.main(args);
        System.out.println("== PlayerLightSyncContract ==");
        PlayerLightSyncContract.main(args);
        System.out.println("== LightStatePersistenceContract ==");
        LightStatePersistenceContract.main(args);
        System.out.println("AllContracts: ALL PASS");
    }
}

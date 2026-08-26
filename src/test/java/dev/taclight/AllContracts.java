package dev.taclight;

import dev.taclight.channel.SpotlightBufferLayoutContract;
import dev.taclight.pose.MuzzlePoseMathContract;
import dev.taclight.tacz.GunLaserReaderContract;

/** 汇总契约运行器(离线、纯 JVM)。 */
public class AllContracts {
    public static void main(String[] args) throws Exception {
        System.out.println("== GunLaserReaderContract ==");
        GunLaserReaderContract.main(args);
        System.out.println("== SpotlightBufferLayoutContract ==");
        SpotlightBufferLayoutContract.main(args);
        System.out.println("== MuzzlePoseMathContract ==");
        MuzzlePoseMathContract.main(args);
        System.out.println("AllContracts: ALL PASS");
    }
}

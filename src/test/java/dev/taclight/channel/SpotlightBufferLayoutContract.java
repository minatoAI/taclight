package dev.taclight.channel;

import java.nio.ByteBuffer;

/** SSBO std430 布局契约测试:16B 头 + 每灯 96B,写入/读回逐字节一致。 */
public class SpotlightBufferLayoutContract {
    public static void main(String[] args) {
        check(SpotlightBufferLayout.bufferSize(0) == 16, "0灯缓冲=16B");
        check(SpotlightBufferLayout.bufferSize(1) == 112, "1灯缓冲=112B");
        check(SpotlightBufferLayout.bufferSize(3) == 304, "3灯缓冲=304B");
        check(SpotlightBufferLayout.lightOffset(1) == 112, "第2灯偏移=112");

        SpotlightData light = SpotlightData.spot(1.5f, -2.5f, 3.0f, 24f,
                1f, 0.96f, 0.88f, 6f,
                0.1f, 0.2f, 0.97f, 0.848f, 0.951f);
        ByteBuffer buf = SpotlightBufferLayout.newBuffer(1);
        SpotlightBufferLayout.writeHeader(buf, 1, 1.0f, SpotlightBufferLayout.FLAG_HAS_DATA);
        SpotlightBufferLayout.writeLight(buf, 0, light);
        check(buf.getInt(0) == 1, "lightCount=1");
        check(buf.getInt(8) == SpotlightBufferLayout.FLAG_HAS_DATA, "flags=HAS_DATA");
        check(SpotlightBufferLayout.FLAG_HAS_DATA == 1, "FLAG_HAS_DATA=1");
        check(SpotlightBufferLayout.FLAG_DEBUG == 2, "FLAG_DEBUG=2");
        check(SpotlightBufferLayout.FLAG_TIMING_PROBE == 8, "FLAG_TIMING_PROBE=8");
        check(SpotlightBufferLayout.FLAG_DEBUG != SpotlightBufferLayout.FLAG_HAS_DATA
                && SpotlightBufferLayout.FLAG_DEBUG != SpotlightBufferLayout.FLAG_TIMING_PROBE, "flag bits distinct");

        SpotlightData read = SpotlightBufferLayout.readLight(buf, 0);
        check(Float.compare(read.posX(), 1.5f) == 0, "posX roundtrip");
        check(Float.compare(read.posY(), -2.5f) == 0, "posY roundtrip");
        float len = (float) Math.sqrt(0.1f * 0.1f + 0.2f * 0.2f + 0.97f * 0.97f);
        float expectedZ = 0.97f / len;
        check(Float.compare(read.dirZ(), expectedZ) == 0, "dirZ roundtrip (normalized)");
        check(Float.compare(read.cosOuter(), 0.848f) == 0, "cosOuter roundtrip");
        check(Float.compare(read.intensity(), 6f) == 0, "intensity roundtrip");
        check(read.type() == 1.0f, "type=1 spot");
        System.out.println("SpotlightBufferLayoutContract: ALL PASS (16 checks)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }
}

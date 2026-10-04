package dev.taclight.channel;

import java.nio.ByteBuffer;

/** SSBO std430 布局契约测试:16B 头 + 每灯 96B,写入/读回逐字节一致。 */
public class SpotlightBufferLayoutContract {
    public static void main(String[] args) {
        // v0.12:lights 改定长 [8],尾段并入体素遮挡栅格(SSBO 总长固定)
        check(SpotlightBufferLayout.lightOffset(1) == 112, "第2灯偏移=112");
        check(SpotlightBufferLayout.lightOffset(7) == 16 + 7 * 96, "第8灯偏移(定长数组内)");
        check(SpotlightBufferLayout.OFF_VOX_ORIGIN == 16 + 8 * 96, "voxOrigin 紧跟 8 灯");
        check(SpotlightBufferLayout.OFF_VOX_ORIGIN % 16 == 0, "voxOrigin 16B 对齐(std430 vec4)");
        check(SpotlightBufferLayout.OFF_VOX_META == SpotlightBufferLayout.OFF_VOX_ORIGIN + 16, "voxMeta 偏移");
        // 2026-10-03 R21 形状调色板:码 16+slot 指向的盒区插在 voxMeta 与 voxData 之间
        check(SpotlightBufferLayout.OFF_VOX_PAL_META == SpotlightBufferLayout.OFF_VOX_META + 16, "voxPalMeta 偏移");
        check(SpotlightBufferLayout.OFF_VOX_PAL_META % 16 == 0, "voxPalMeta 16B 对齐(std430 ivec4)");
        check(SpotlightBufferLayout.OFF_VOX_PAL_BOX == SpotlightBufferLayout.OFF_VOX_PAL_META + 16, "voxPalBox 偏移");
        check(SpotlightBufferLayout.OFF_VOX_DATA
                == SpotlightBufferLayout.OFF_VOX_PAL_BOX + ShapePalette.TOTAL_FLOATS * 4, "voxData 偏移(盒区之后)");
        check(SpotlightBufferLayout.OFF_VOX_DATA % 4 == 0, "voxData 4B 对齐(std430 uint[])");
        check(SpotlightBufferLayout.VOX_MAX_UINTS == 128 * 128 * 128 / 4,
                "VOX_MAX_UINTS=8bit 打包 128^3(2026-10-03 形状调色板:4bit→8bit)");
        check(SpotlightBufferLayout.bufferSize()
                == SpotlightBufferLayout.OFF_VOX_DATA + SpotlightBufferLayout.VOX_MAX_UINTS * 4, "缓冲=定长布局");
        // 阳性对照:总长必须**大于**"没有调色板段"的旧布局,否则说明盒区被算丢了
        check(SpotlightBufferLayout.bufferSize()
                > SpotlightBufferLayout.OFF_VOX_META + 16 + SpotlightBufferLayout.VOX_MAX_UINTS * 4,
                "总长含调色板段(旧布局 " + (SpotlightBufferLayout.OFF_VOX_META + 16
                        + SpotlightBufferLayout.VOX_MAX_UINTS * 4) + " B,现 "
                        + SpotlightBufferLayout.bufferSize() + " B)");
        check(SpotlightBufferLayout.HEAD_STAGE_BYTES == SpotlightBufferLayout.OFF_VOX_PAL_BOX,
                "每帧头段止于 voxPalMeta(盒区/体素数据单独上传)");

        // voxPalMeta 写入语义 + 无效位必须把槽数清零(否则 GLSL 会去读没上传的盒区)
        ByteBuffer pv = SpotlightBufferLayout.newBuffer(1);
        SpotlightBufferLayout.writeVoxPalMeta(pv, 7);
        check(pv.getInt(SpotlightBufferLayout.OFF_VOX_PAL_META) == 7
                && pv.getInt(SpotlightBufferLayout.OFF_VOX_PAL_META + 4) == ShapePalette.SLOT_STRIDE,
                "voxPalMeta 写入:槽数 + 每槽 float 数");
        SpotlightBufferLayout.writeVoxInvalid(pv);
        check(pv.getInt(SpotlightBufferLayout.OFF_VOX_PAL_META) == 0,
                "栅格无效位同时清零调色板槽数(GLSL 不会读未上传的盒区)");

        // 体素头/无效位写入语义
        ByteBuffer vox = SpotlightBufferLayout.newBuffer(1);
        SpotlightBufferLayout.writeVoxInvalid(vox);
        check(vox.getFloat(SpotlightBufferLayout.OFF_VOX_ORIGIN + 12) < 0.0f, "无效位=w<0(GLSL 回退 SSO)");
        SpotlightBufferLayout.writeVoxHeader(vox, 1.0f, 2.0f, 3.0f, 4, 5, 6);
        check(vox.getFloat(SpotlightBufferLayout.OFF_VOX_ORIGIN) == 1.0f
                && vox.getFloat(SpotlightBufferLayout.OFF_VOX_ORIGIN + 8) == 3.0f
                && vox.getFloat(SpotlightBufferLayout.OFF_VOX_ORIGIN + 12) > 0.0f, "voxOrigin 写入+有效位");
        check(vox.getInt(SpotlightBufferLayout.OFF_VOX_META) == 4
                && vox.getInt(SpotlightBufferLayout.OFF_VOX_META + 8) == 6, "voxMeta 写入");

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
        System.out.println("SpotlightBufferLayoutContract: ALL PASS (" + checks + " checks)");
    }

    private static int checks;

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

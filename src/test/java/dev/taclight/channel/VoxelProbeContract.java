package dev.taclight.channel;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link VoxelProbe} 契约(2026-09-25 细雪层穿光轮):把"游戏内单元诊断"的判据钉死。
 *
 * <p>要点:① live/grid 两面必须并排且分叉可见;② 探针<b>复用生产同一条 DDA</b>
 * ({@link VoxelDda#traceVisited}/{@link VoxelDda#transmit}),不得自写遍历——
 * 否则探针报的不是渲染看到的东西;③ 接线层(中继两条动词)存在。</p>
 */
public class VoxelProbeContract {
    private static int checks = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError("FAIL " + what);
        System.out.println("  PASS " + what);
    }

    private static String read(String rel) throws Exception {
        Path p = Path.of(rel);
        if (!Files.exists(p)) throw new AssertionError("FAIL 缺失文件: " + rel);
        return Files.readString(p);
    }

    public static void main(String[] args) throws Exception {
        // ---- 码名 ----
        check(VoxelProbe.codeName(VoxelField.CODE_EMPTY).equals("EMPTY"), "码 0 = EMPTY");
        check(VoxelProbe.codeName(VoxelField.CODE_VEG).equals("VEG"), "码 1 = VEG");
        check(VoxelProbe.codeName(VoxelField.CODE_LEAF).equals("LEAF"), "码 2 = LEAF");
        check(VoxelProbe.codeName(VoxelField.CODE_SOLID).equals("SOLID"), "码 3 = SOLID");
        check(VoxelProbe.codeName(VoxelProbe.OUT).equals("OUT"), "码 -1 = OUT(盒外)");
        check(VoxelProbe.codeName(VoxelField.slabBottomCode(1)).equals("SLAB[0.000..0.125]"),
                "薄板码可读名(雪 1 层)=" + VoxelProbe.codeName(VoxelField.slabBottomCode(1)));
        check(VoxelProbe.codeName(VoxelField.slabTopCode(4)).equals("SLAB[0.500..1.000]"),
                "顶薄板码可读名=" + VoxelProbe.codeName(VoxelField.slabTopCode(4)));
        // 2026-10-03 R21 形状调色板:码 ≥16 必须有自己的可读名(不能落 CODE16 那种无信息的兜底)
        check(VoxelProbe.codeName(VoxelField.CODE_PALETTE_BASE).equals("PAL[0]"),
                "调色板码可读名=" + VoxelProbe.codeName(VoxelField.CODE_PALETTE_BASE));
        check(VoxelProbe.codeName(VoxelField.CODE_PALETTE_BASE + 7).equals("PAL[7]"),
                "调色板槽号进可读名=" + VoxelProbe.codeName(VoxelField.CODE_PALETTE_BASE + 7));

        // ---- 单格:一致不标注 / 分叉显式标注(这正是区分"判定错"与"上传错"的那一面) ----
        String same = VoxelProbe.cellReport(10, 64, -3, VoxelField.CODE_EMPTY, VoxelField.CODE_EMPTY);
        check(same.equals("VOXPROBE (10,64,-3) live=EMPTY grid=EMPTY"), "一致:live/grid 并排且无标注,实际=" + same);
        String diff = VoxelProbe.cellReport(10, 64, -3, VoxelField.CODE_SOLID, VoxelField.CODE_EMPTY);
        check(diff.contains("MISMATCH"), "分叉:MISMATCH 显式标注,实际=" + diff);

        // ---- 射线:合成格 (1,0,0)=VEG、(2,0,0)=EMPTY;live 口径全 SOLID ----
        VoxelDda.Classifier grid = c ->
                (c.x() == 1 && c.y() == 0 && c.z() == 0) ? VoxelField.CODE_VEG : VoxelField.CODE_EMPTY;
        VoxelDda.Classifier live = c -> VoxelField.CODE_SOLID;
        String r = VoxelProbe.rayReport(0.5, 0.5, 0.5, 3.5, 0.5, 0.5, 24, live, grid, null);
        check(r.contains("cells=2"), "两端豁免:中间格 = 2,实际=" + firstLine(r));
        check(r.contains("gridT=0.750"), "grid 口径:穿一格 VEG ⇒ T=0.75,实际=" + firstLine(r));
        check(r.contains("liveT=0.000"), "live 口径:穿满实心格(pen=1.0≥FUZZ) ⇒ T=0,实际=" + firstLine(r));
        check(r.contains("live=SOLID") && r.contains("grid=VEG"), "逐格同时列出 live 与 grid 码");
        check(r.contains("pen=1.000"), "逐格给出穿透长度(实心软化判据的输入)");

        // ---- 调色板码必须被 Java oracle 真正消费(2026-10-03 R21) ----
        // 反例:不给 ShapeLookup ⇒ 调色板码在两侧都不落分支会被当"全透射",
        // 诊断报出的 gridT 就不是着色器算出来的那个数(探针失效比没有探针更坏)。
        int palCode = VoxelField.CODE_PALETTE_BASE + 3;
        VoxelDda.Classifier palGrid = c ->
                (c.x() == 1 && c.y() == 0 && c.z() == 0) ? palCode : VoxelField.CODE_EMPTY;
        VoxelDda.ShapeLookup shapes = (c, code) -> new float[]{0.25f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f};
        String pr = VoxelProbe.rayReport(0.5, 0.5, 0.5, 3.5, 0.5, 0.5, 24, live, palGrid, shapes);
        check(pr.contains("gridT=0.000"),
                "调色板盒被消费:射线穿 1/2 宽的小盒(路径 0.5 ≥ band 0.35)⇒ T=0,实际=" + firstLine(pr));
        check(pr.contains("boxes=[(0.250,0.250,0.250)-(0.750,0.750,0.750)]"),
                "调色板格的形状被逐盒打印(人眼可核对'这格记成什么形状')");
        String prNoLookup = VoxelProbe.rayReport(0.5, 0.5, 0.5, 3.5, 0.5, 0.5, 24, live, palGrid, null);
        check(prNoLookup.contains("gridT=0.000"),
                "无 ShapeLookup 时保守按整格 ⇒ 仍 T=0(宁可误挡不可漏光),实际=" + firstLine(prNoLookup));

        // ---- 截断与零长(长射线不刷屏、同格不误报) ----
        String t = VoxelProbe.rayReport(0.5, 0.5, 0.5, 3.5, 0.5, 0.5, 1, live, grid, null);
        check(t.contains("TRUNCATED(1"), "超 maxCells 显式标注截断,实际=" + t.substring(t.indexOf("TRUNCATED")));
        String z = VoxelProbe.rayReport(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 24, live, grid, null);
        check(z.contains("cells=0") && z.contains("无中间格"), "零长射线:cells=0 且显式说明");

        // ---- 盒扫描:签名是"非空气却被判透光"的计数 + 按方块归类,且漏光格必须优先打印 ----
        // 2026-09-25 高度感知后雪层已是薄板码(不再漏光),真正的 EMPTY 例改用蛛网(coll 空/occ 满格)。
        java.util.List<VoxelProbe.Row> leaky = java.util.List.of(
                new VoxelProbe.Row(1, 2, 3, "minecraft:cobweb", VoxelField.CODE_EMPTY, VoxelField.CODE_EMPTY),
                new VoxelProbe.Row(1, 2, 4, "minecraft:cobweb", VoxelField.CODE_EMPTY, VoxelField.CODE_EMPTY));
        java.util.List<VoxelProbe.Row> rest = java.util.List.of(
                new VoxelProbe.Row(1, 3, 3, "minecraft:snow{layers=1}",
                        VoxelField.slabBottomCode(1), VoxelField.slabBottomCode(1)),
                new VoxelProbe.Row(1, 3, 4, "minecraft:stone", VoxelField.CODE_SOLID, VoxelField.CODE_SOLID));
        String s = VoxelProbe.scanReport(leaky, rest, 5, 2, 0, 0, 1, 1, 1);
        check(s.contains("nonAir=5") && s.contains("非空气却被判透光(EMPTY,完全不遮挡)=2"),
                "盒扫描摘要给出'非空气却判透光'计数,实际=" + firstLine(s));
        check(s.contains("SLAB=1") && s.contains("SOLID=1"),
                "盒扫描把薄板码单列(SLAB),不混进 SOLID(2026-09-25 真机首轮实测的计数缺陷)");
        check(s.contains("PAL=1"),
                "盒扫描单列调色板格数(2026-10-03 R21:一眼看出'这格真吃了调色板槽'还是退回了基础码)");
        check(s.contains("漏光格按方块归类: minecraft:cobweb×2"),
                "盒扫描把漏光格按方块归类(一眼看出是哪种方块)");
        check(s.indexOf("minecraft:cobweb") < s.indexOf("minecraft:snow{layers=1}"),
                "漏光格优先打印(2026-09-25 真机教训:按坐标序会被地下石头挤掉)");
        check(s.contains("block=minecraft:cobweb") && s.contains("live=EMPTY grid=EMPTY"),
                "盒扫描逐格给出方块标识(含属性)+判码+已上传值");
        check(s.contains("live=SLAB[0.000..0.125]"), "盒扫描对薄板码给出高度区间(雪 1 层可读)");
        check(s.contains("TRUNCATED(1"), "未列出格数显式标注(5-4=1)");

        // ---- 探针必须复用生产 DDA(不得自写遍历,否则探针≠渲染看到的) ----
        String probe = read("src/main/java/dev/taclight/channel/VoxelProbe.java");
        check(probe.contains("VoxelDda.traceVisited") && probe.contains("VoxelDda.transmit"),
                "VoxelProbe 复用生产 DDA(traceVisited + transmit)");
        check(!probe.contains("for (int guard"), "VoxelProbe 未自写 DDA 步进循环");
        String dda = read("src/main/java/dev/taclight/channel/VoxelDda.java");
        check(dda.contains("static List<Visited> traceVisited"),
                "traceVisited 为包内可见(供探针复用同一遍历)");

        // ---- 接线层:中继两条动词 + 网格探针入口 ----
        String relay = read("src/main/java/dev/taclight/client/DebugCommandRelay.java");
        check(relay.contains("!voxprobe") && relay.contains("VoxelGrid.probe("), "中继 !voxprobe → VoxelGrid.probe");
        check(relay.contains("VoxelGrid.scan("), "中继 !voxprobe 六参 → 盒扫描 VoxelGrid.scan");
        check(relay.contains("!voxray") && relay.contains("VoxelGrid.ray("), "中继 !voxray → VoxelGrid.ray");
        check(relay.contains("getEyePosition") && relay.contains("getViewVector"),
                "!voxray 无参 = 眼位沿视线(免算坐标)");
        String gridSrc = read("src/main/java/dev/taclight/client/VoxelGrid.java");
        check(gridSrc.contains("liveCode(mc") && gridSrc.contains("gridCode(") ,
                "VoxelGrid 提供 live(现场判定)与 grid(已上传)两面");
        check(gridSrc.contains("VoxelField.unpack"), "grid 面直接解包已上传缓冲(不是重新分类)");

        System.out.println("VoxelProbeContract: ALL PASS (" + checks + " checks)");
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}

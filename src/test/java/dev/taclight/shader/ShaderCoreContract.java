package dev.taclight.shader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * interop 核心剥离分层契约(v1.0,方案 C 前置里程碑)。
 *
 * <p>目标:把"跨光影包可移植的照明核心"与"taclight 包私有编码"在源码层面
 * 切开,为运行时注入(方案 C:模组把核心 GLSL 注入玩家所选光影包)建立
 * 零依赖地基。三层结构:
 * <pre>
 *   math     公开数学(IGN/HG),core 与 style 共用,零依赖
 *   core     SSBO + 坐标换算 + 照明数学 + SSO + 体素 DDA + 灯循环
 *            —— 只依赖 Iris 标准 uniform 与模组 SSBO,禁止 colortex 字面量
 *   adapter  本包 G-Buffer 私有编码(colortex3.a 遮挡系数)+ 量纲标定(GAIN)
 * </pre>
 * 守护四条边界:core 零依赖、注入点宏默认保守、common 聚合顺序、无残留双份定义。
 */
public class ShaderCoreContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String core = read("pack/shaders/lib/taclight_core.glsl");
        String math = read("pack/shaders/lib/taclight_math.glsl");
        String adapter = read("pack/shaders/lib/taclight_adapter.glsl");
        String common = read("pack/shaders/lib/taclight_common.glsl");
        String style = read("pack/shaders/lib/taclight_style.glsl");
        String composite = read("pack/shaders/composite.fsh");

        // ---- 边界 1:core 只 include 公开数学层(零依赖核心) ----
        List<String> coreIncludes = includeLines(core);
        check(coreIncludes.size() == 1 && coreIncludes.get(0).contains("taclight_math.glsl"),
                "core 仅可 include taclight_math.glsl(实际: " + coreIncludes + ")");
        String coreCode = core.lines()
                .map(l -> l.replaceAll("//.*$", ""))   // 剥行注释:注释可提及,代码不可出现
                .collect(Collectors.joining("\n"));
        check(!coreCode.contains("colortex"),
                "core 禁止出现 colortex 字面量(G-Buffer 私有编码只在 adapter/包侧)");

        // ---- 边界 2:遮挡系数注入点 + 保守默认 ----
        check(core.contains("#ifndef TACLIGHT_OCCLUSION_AT"),
                "core 的 SSO 遮挡系数必须经 TACLIGHT_OCCLUSION_AT 宏注入");
        check(core.contains("#define TACLIGHT_OCCLUSION_AT(uv) (1.0)"),
                "未适配的包回退保守默认 1.0(宁可误挡不可漏光)");
        check(adapter.contains("#define TACLIGHT_OCCLUSION_AT(uv) (texture(colortex3, uv).a)"),
                "adapter 提供本包遮挡系数实现(colortex3.a 分类)");
        check(adapter.contains("uniform sampler2D colortex3;"),
                "colortex3 uniform 声明归属 adapter(core 零 colortex)");

        // ---- 边界 3:量纲标定归属适配层 ----
        check(adapter.contains("#define TACLIGHT_LIGHT_GAIN"),
                "亮度量纲标定(LIGHT_GAIN)在 adapter,移植到其他包必须重标定");
        check(!coreCode.contains("TACLIGHT_LIGHT_GAIN"),
                "core 不含本包量纲常数(radiance 未标定,标定在消费侧)");

        // ---- 边界 4:核心函数清单(跨包移植面) ----
        for (String fn : new String[]{
                "vec3 taclight_world_to_scene(vec3 worldPos)",
                "vec3 taclight_scene_to_view(vec3 scenePos)",
                "vec2 taclight_view_to_uv(vec3 viewPos)",
                "vec3 taclight_depth_to_view(vec2 uv, float depth)",
                "vec3 taclight_view_to_world(vec3 viewPos)",
                "float taclight_attenuation(float dist, float radius, float kOverride)",
                "float taclight_soft_knee(float x)",
                "float taclight_soft_knee(float x, float gOverride)",
                "vec3 taclight_soft_knee3(vec3 x, float gOverride)",
                "vec3 taclight_shoulder3(vec3 x, float t, float head)",
                "vec3 taclight_ggx(vec3 n, vec3 v, vec3 l, float roughness, vec3 f0)",
                "float taclight_sso(vec3 fragView, vec3 lightView, TacLightSpot L)",
                "float taclight_vox_transmit(vec3 worldA, vec3 worldB)",
                "float taclight_debug_green_cone(TacLightSpot L, vec3 fragView)",
                "vec3 taclight_surface_lighting(vec3 fragView, vec3 albedo, vec3 n,"}) {
            check(core.contains(fn), "core 暴露跨包接口: " + fn);
        }

        // ---- 边界 5:common 是纯聚合头,顺序 adapter→core(gbuffer/style 随后) ----
        check(common.indexOf("taclight_adapter.glsl") >= 0
                        && common.indexOf("taclight_adapter.glsl") < common.indexOf("taclight_core.glsl"),
                "common 聚合顺序 adapter 先于 core(OCCLUSION_AT 宏先于 SSO 展开,红线:#include 行尾无注释)");
        for (String def : new String[]{
                "vec3 taclight_world_to_scene(vec3", "float taclight_sso(vec3",
                "float taclight_vox_transmit(vec3", "vec3 taclight_surface_lighting(vec3",
                "layout(std430, binding = 7)"}) {
            check(!common.contains(def), "common 聚合头不残留本体定义(防双份): " + def);
        }

        // ---- 边界 6:math/style 无双份 ----
        check(math.contains("float taclight_ign(vec2 p)") && !math.contains("taclight_hg"),
                "math 层定义 IGN 公开数学(HG 相位已移除:侧面相位 sin²θ 内联在 composite1,2026-09-05 定案)");
        check(style.contains("#include \"/lib/taclight_math.glsl\"") && !style.contains("float taclight_ign(vec2 p)"),
                "style 经 include 复用 math(传递可见给 final),不再重复定义");

        // ---- 边界 7:消费 pass 不残留灯循环本体 ----
        check(core.contains("taclight_soft_knee3(") && core.contains("L.cone.w"),
                "surface 主循环消费 cone.w(!knee 经 SSBO 透传,0=恒等回退)");
        check(composite.contains("taclight_surface_lighting(fragView, albedo, n, roughness, metal, f0)"),
                "composite 表面照明经 core 接口调用(本 pass 只做 G-Buffer 解码)");
        check(!composite.contains("if (dist > radius || radius < 1e-3) continue;")
                        && !composite.contains("if (vis <= 0.003) continue;"),
                "composite 不残留照明门/遮挡分流本体(半径门/vis 只在 core;DBG 调试视图复用核心函数不算)");

        System.out.println("ShaderCoreContract: ALL PASS (" + checks + " checks)");
    }

    private static List<String> includeLines(String source) {
        return source.lines()
                .map(String::trim)
                .filter(l -> l.startsWith("#include"))
                .collect(Collectors.toList());
    }

    private static String read(String rel) throws IOException {
        Path p = Path.of(rel);
        if (!Files.exists(p)) throw new AssertionError("FAIL 缺失文件: " + rel);
        return Files.readString(p);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL " + what);
        checks++;
        System.out.println("  PASS " + what);
    }
}

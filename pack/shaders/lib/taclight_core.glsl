// ============================================================================
// TacLight Shaders · 零依赖照明核心(lib/taclight_core.glsl)
// ============================================================================
// 【分层契约 —— interop 核心剥离(v1.0)】
//   本文件 = TacLight 照明核心,是跨光影包移植的"唯一需要搬运的代码"。
//   依赖边界(由 ShaderCoreContract 契约钉死):
//     · 仅依赖 Iris/Oculus 标准 uniform(gbufferModelView 系列/cameraPosition/
//       depthtex1)与模组绑定的 SSBO binding=7;
//     · 仅 #include lib/taclight_math.glsl(公开数学,零依赖);
//     · **禁止出现 colortex 字面量** —— G-Buffer 私有编码(法线/albedo/材质/
//       遮挡系数布局)一律在 taclight_adapter.glsl(包侧)或消费 pass 中;
//     · 唯一的注入点:遮挡系数采样宏 TACLIGHT_OCCLUSION_AT(uv),未适配的包
//       回退默认值 1.0(保守全挡:宁可误挡不可漏光)。
//   亮度量纲:TACLIGHT_LIGHT_GAIN 等对本包 tonemap 链的标定常数在 adapter,
//   不在本文件 —— 移植到其他包后需随对方管线重新标定。
//
// 【SSBO 契约镜像】唯一真源(Single Source):
//   taclight/src/main/java/dev/taclight/channel/SpotlightBufferLayout.java
//   守护:SpotlightBufferLayoutContract + UploaderSemanticContract(纯 JVM 契约)
//
// 【坐标语义 —— doc06 §2.5 铁律 3,v0.9.0 起生效】
//   posRadius.xyz / dirType.xyz 均为 **world** 坐标系;
//   scene-relative 转换只允许发生在消费侧,且必须经过下方
//   taclight_world_to_scene -> taclight_scene_to_view 两级封装。
//   历史教训:直接把 world 乘进 composite 的 gbufferModelView(R-only,
//   无平移)= 灯被摆到几百格外(v0.8.4 事故)。本文件其余代码禁止再出现
//   内联的坐标换算。
//
// 仅可被 #version 430 及以上的程序 include(composite* 等);gbuffers 用不到它。
// ============================================================================

#ifndef TACLIGHT_CORE_INCLUDED
#define TACLIGHT_CORE_INCLUDED

#include "/lib/taclight_math.glsl"

// ---- 头部 flags 位(与 SpotlightBufferLayout.java 逐位一致)----
#define TACLIGHT_FLAG_HAS_DATA     1u   // bit0
#define TACLIGHT_FLAG_DEBUG        2u   // bit1 K 键绿锥调试(doc06 §2.10)
#define TACLIGHT_FLAG_BEAM_ONLY    4u   // bit2 !beamonly 只看光束(跳过表面照明,2026-09-05)
#define TACLIGHT_FLAG_TIMING_PROBE 8u   // bit3 reserved 回读探针(DEBUG 构建才置位)

// ---- 每灯 96B · 6×vec4(std430,与 Java writeLight 写序一致)----
struct TacLightSpot {
    vec4 posRadius;       // xyz = **world** 坐标;w = 半径(格)
    vec4 colorIntensity;  // rgb = 线性色;a = 强度
    vec4 dirType;         // xyz = 归一化 world 方向(灯→目标);w = 类型(1=spot)
    vec4 cone;            // x = cos 外锥半角;y = cos 内锥半角;z/w 保留
    vec4 vlParams;        // x 轴向底亮 f(!scat,侧面相位 sin²θ),y 密度(!beam),z 软上限倍率 m(!beamcap,默认1)
    vec4 cookie;          // GLSL→Java 回写诊断槽位(探针阶段启用)
};

// 注意(铁律 2):不要在 shaders.properties 里声明 bufferObject.7 ——
// 那会让 Iris 自建同名缓冲覆盖模组绑定。此缓冲由模组创建并每帧更新,包只读。
// v0.12(09-01 深夜④):lights 改定长 [8],尾段并入体素遮挡栅格
// (VoxelField/VoxelGrid 每 tick 填充;taclight_vox_transmit DDA 消费)。
layout(std430, binding = 7) buffer TacLightSSBO {
    uint  lightCount;     // 头偏移 0
    float vlIntensity;    // 头偏移 4
    uint  flags;          // 头偏移 8
    uint  reserved;       // 头偏移 12(时序探针回写字)
    TacLightSpot lights[8];   // 16..783(定长;Java 侧 clamp 8 同源)
    vec4  voxOrigin;      // 784: xyz=栅格角点 world(方块格对齐) w>0=有效/w<=0=无效
    ivec4 voxMeta;        // 800: xyz=各轴格数;w 保留
    uint  voxData[];      // 816..: 2bit/体素,idx=x+y*dx+z*dx*dy,word=idx>>4,bit=(idx&15)*2
};

// ---- 各阶段矩阵约定(doc06 §2.2 表)----
// 2026-09-02 更正:gbufferModelView = R·T **含 bob 平移**(bobView 写进渲染
// PoseStack,Iris 原样捕获;旧注释"R-only"是错误前提,曾致 mat3-only 换算把
// ±bob 位移注入世界/视图坐标=影子随步频跳位,见坑57)。方向向量用 mat3 仍正确
// (平移对方向无意义,且 bob 微转两侧一致)。
uniform mat4 gbufferModelView;         // composite/final:R·T(含 bob 平移!)
uniform mat4 gbufferModelViewInverse;  // 全矩阵逆(view→world 必须用它抵消 bob 平移)
uniform mat4 gbufferProjection;        // view→clip(composite 中有效)
uniform mat4 gbufferProjectionInverse; // clip→view(深度重建用)
uniform vec3 cameraPosition;
uniform sampler2D depthtex1;           // 实心几何深度(不含半透明;Iris 标准附件)

// ----------------------------------------------------------------------------
// 遮挡系数注入点(interop 适配接口):
//   语义:返回该屏幕位置的"遮挡者材质消光系数"∈(0,1],1.0=全挡实心。
//   本包实现(colortex3.a 分类)在 taclight_adapter.glsl;未适配的包回退
//   保守默认 1.0 —— 植被/树叶按实心处理,宁可误挡不可漏光。
// ----------------------------------------------------------------------------
#ifndef TACLIGHT_OCCLUSION_AT
#define TACLIGHT_OCCLUSION_AT(uv) (1.0)
#endif

// ----------------------------------------------------------------------------
// 项目仅有的一对纵深入口(doc06 §2.2 规则 6):
// 全部光照代码只允许调用这两个函数做坐标换算,禁止内联重复公式。
// ----------------------------------------------------------------------------

/** world → 场景相对(world − cameraPosition)。v0.9.0 起上传侧为 world,先走这一步。 */
vec3 taclight_world_to_scene(vec3 worldPos) {
    return worldPos - cameraPosition;
}

/** 场景相对 → 视图空间。**必须用全矩阵**:gbufferModelView = R·T 含 bob 平移
 *  (bobView 写进渲染 PoseStack,Iris 原样捕获),光栅化几何的 fragView 带同一平移;
 *  两侧都含平移,相减才抵消。旧 mat3-only 形式丢平移 → 距离/锥角以步频抖动。 */
vec3 taclight_scene_to_view(vec3 scenePos) {
    return (gbufferModelView * vec4(scenePos, 1.0)).xyz;
}

/** 视图空间 → 屏幕 uv(供遮挡步进等屏幕空间运算取参考 uv)。 */
vec2 taclight_view_to_uv(vec3 viewPos) {
    vec4 clip = gbufferProjection * vec4(viewPos, 1.0);
    return (clip.xy / clip.w) * 0.5 + 0.5;
}

/** 深度缓冲反投影:uv + 设备深度 → 视图空间表面位置(composite 光照的地基)。 */
vec3 taclight_depth_to_view(vec2 uv, float depth) {
    vec4 ndc  = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = gbufferProjectionInverse * ndc;
    return view.xyz / view.w;
}

/** 视图空间 → world。**必须用全矩阵逆**:旧 transpose(mat3) 形式丢掉 gbufferModelView
 *  里的 bob 平移 → 反算世界坐标带 ±bob 位移假偏移,体素 DDA 阴影随步频相对画面跳位
 *  (实机差分 7.2-7.4px@bob开 vs 2.3-2.7px@bob关,evidence/2026-09-02-bob-sway-verdict/)。
 *  体素栅格/SSBO 均为 world 域,消费端经本函数出入,不内联换算(铁律 3)。 */
vec3 taclight_view_to_world(vec3 viewPos) {
    return cameraPosition + (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz;
}

// ----------------------------------------------------------------------------
// 距离衰减(doc06 §2.3 定义 + §5 D6 已批:平滑反平方,k 由半径标定)
//   atten(0)=1,atten(radius)=0,中段长尾自然;分母 1+k·d² 无奇点。
// v0.9.0 M0 热修:绿锥预览即用本函数(替换旧 (1-d/r) 线性淡出——线性在
// 半半径处只剩 50% 亮度,视觉半径"提前死亡",即实测"照明距离不足"主因)。
// M1 表面照明直接复用本函数,不再另写。
// 2026-09-03 真实感调参(用户体感"光晕太亮照不清"):K 2.0→5.0。
// 数学:分母 1+K(d/r)²,K 只改变"中段肩部"(0.5r 处 50%→20% 亮度),
// 端点 atten(0)=1/atten(r)=0 不动,远场尾部保持。移植到其他包按宿主量纲重标(同 GAIN)。
// ----------------------------------------------------------------------------
#define TACLIGHT_ATTEN_K 5.0   // 标定常数:越小尾越长;5.0 = 0.5r 处约 20% 亮度
// 2026-09-04 逐灯 K(用户体感 !atten 经 SSBO cone.z 透传):kOverride>0 取逐灯值,
// ≤0 回退编译期默认 —— 半径 r 仍走原通道,端点 atten(0)=1/atten(r)=0 语义不动。
float taclight_attenuation(float dist, float radius, float kOverride) {
    float kk = kOverride > 0.0 ? kOverride : TACLIGHT_ATTEN_K;
    float k = kk / max(radius * radius, 1e-4);
    float tail = 1.0 / (1.0 + k * radius * radius);   // = 1/(1+K)
    float e = 1.0 / (1.0 + k * dist * dist) - tail;
    return max(e, 0.0) / (1.0 - tail);
}

// ----------------------------------------------------------------------------
// 近场软肩压缩(v0.9.0 M0 热修,实机反馈:近场过曝糊死、看不清被照物体)
//   f(x) = x / (1 + G·x) —— 斜率设计:
//   · x 小(远场尾部)时 f ≈ x,几乎不衰减(远处保持可见);
//   · x 大(近场)被压向上限 1/G(近场显著压暗,不再削顶);
//   · G = TACLIGHT_KNEE_GAIN 是用户可调的"亮度曲线"旋钮:越大近场压得越狠、
//     近远对比越大;调小则整体趋平(退化为旧行为 G→0)。
// 效果标定(K=2, r=56):0.25r 处 0.83→0.31,0.5r 处 0.50→0.25,
// 0.8r 处 0.158→0.120(×合成增益 1.8 = 0.216 仍清晰可见);近远比 5.3:1 → 2.6:1。
// 参考本项目 v0.8.2 软膝(taclight_knee = e/(1+e))的行为语义,公式重写;
// M1 表面高光压缩直接复用本函数,完整 ACES 仍在 M2。
// ----------------------------------------------------------------------------
#define TACLIGHT_KNEE_GAIN 2.0
float taclight_soft_knee(float x) {
    return x / (1.0 + TACLIGHT_KNEE_GAIN * x);
}

/** !knee 覆盖版(gOverride 经 SSBO cone.w 逐灯透传,0=回退编译期默认 G)。
 *  语义:g>0 用之,否则 TACLIGHT_KNEE_GAIN —— 调试绿锥侧语义。 */
float taclight_soft_knee(float x, float gOverride) {
    float g = gOverride > 0.0 ? gOverride : TACLIGHT_KNEE_GAIN;
    return x / (1.0 + g * x);
}

/** knee 的逐通道版本(表面照明 radiance 是 vec3,逐灯贡献非线性后累加)。 */
vec3 taclight_soft_knee3(vec3 x) {
    return x / (1.0 + TACLIGHT_KNEE_GAIN * x);
}

/** !knee 覆盖版(逐通道)。语义:g<=0 恒等(不施加)——
 *  与标量版不同:surface 默认 cone.w=0 即"无膝"(旧行为),仅在 !knee 开启时压缩。 */
vec3 taclight_soft_knee3(vec3 x, float gOverride) {
    if (gOverride <= 0.0) return x;
    return x / (1.0 + gOverride * x);
}

// ----------------------------------------------------------------------------
// 多源感知肩部(v0.10.1,08-30 深夜 interfere 判定:双灯同点线性叠加过亮,用户复核)
//   理论定位:渲染方程 L_o = Σ_i f_r·L_i·(n·ω_i) 对多光源是线性叠加(光的超叠加原理),
//   radiance 的求和保持不动;但人眼亮度感知是压缩的(Stevens 幂律,指数 ~0.33),
//   "N 灯同点应只比单灯略亮"属于感知/显示域 —— 在 filmic 管线里由肩部曲线承担
//   (与色调映射 shoulder 同构;此处放在 M1 输出级,锚点 = 单灯名义核心辐射 T,
//   即曝光理论"把名义曝光锚在肩部起点"的做法)。
//   f(x) = x                    (x ≤ T:逐像素恒等 —— 单灯外观零改变)
//        = T + H·tanh((x-T)/H)  (x > T:C1 平滑收敛,H = q·T)
//   性质:N 灯同点 ≤ T+H = (1+q)·T 恒有界(对未知超和簇也鲁棒);等强度双灯
//   (2T)→ ≈(1+q)·T;q=0.15 → ≤1.22×;非重叠双灯互不影响(逐像素压缩,
//   非按灯数归一,远场/第二灯独立光斑不受压)。
// ----------------------------------------------------------------------------
#define TACLIGHT_SHOULDER_T 0.55
#define TACLIGHT_SHOULDER_Q 0.15
vec3 taclight_shoulder3(vec3 x, float t, float head) {
    vec3 e = max(x - vec3(t), vec3(0.0));
    // GLSL 版本兼容(2026-09-03 interop:Complementary gbuffers 经 #version 130 编译,
    // tanh 需 GLSL 1.30+;双曲正切恒等式 tanh(e)=1-2/(exp(2e)+1),exp 全版本可用):
    vec3 te = vec3(1.0) - vec3(2.0) / (exp(vec3(2.0) * e / max(vec3(head), vec3(1e-4))) + vec3(1.0));
    return min(x, vec3(t)) + head * te;
}

// ----------------------------------------------------------------------------
// M1 · 表面照明数学(全部公开标准公式,自写实现)
// GGX 分布 × Smith 遮蔽 × Schlick 菲涅尔。枪身金属反光用。
// 阶段二:F0 由适配层解码 —— 金属的 F0 = albedo(彩色),介电为常量灰。
// ----------------------------------------------------------------------------
vec3 taclight_ggx(vec3 n, vec3 v, vec3 l, float roughness, vec3 f0) {
    vec3 h = normalize(v + l);
    float ndh = max(dot(n, h), 0.0);
    float ndv = max(dot(n, v), 1e-3);
    float ndl = max(dot(n, l), 0.0);
    float a = roughness * roughness;
    float a2 = a * a;
    float d = a2 / (3.14159265 * pow(ndh * ndh * (a2 - 1.0) + 1.0, 2.0));
    float k = a * 0.5;
    float g = (ndv / (ndv * (1.0 - k) + k)) * (ndl / (ndl * (1.0 - k) + k));
    vec3 f = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    return d * g * f;
}

// ----------------------------------------------------------------------------
// M1 · 屏幕空间遮挡(doc06 §2.7:普通 Iris 包的可行上限,写死接受)
// 沿片元→灯的视图空间射线步进 24 步(F5,2026-08-30:16→24,台阶更细),
// 投影回屏幕与 depthtex1(实心几何深度,不含半透明)比较;被挡则按遮挡者
// 材质系数消光。已知局限:视锥外的遮挡者不投影(墙后物体不挡光)。
// IGN 抖动把台阶软化成噪点(风格层颗粒进一步融合)。
// 遮挡系数经 TACLIGHT_OCCLUSION_AT(uv) 注入 —— 本包 = colortex3.a 分类
// (1.0 实心 / 0.6 树叶 / 0.25 软植被,见 taclight_adapter.glsl):镂空植被
// 按全挡处理会把满草场景的地面消成死黑、只剩草叶亮(实机实锤),半透折中。
// ----------------------------------------------------------------------------
#define TACLIGHT_SSO_SELF_FREE 0.6   // 贴灯豁免半径:自身体/枪身贴着灯,其阴影半影
                                     // 物理上全弥散,按"可见即照亮"豁免(消脚下暗环)

// F2(2026-08-30):自体胶囊豁免。灯锚在玩家身体上(眼位+固定偏移),身体
// 对"从身体内发出的光"不是硬遮挡物——半影覆盖全身,不存在锐利阴影边。
// 只豁免贴灯球不够:第三人称下腿部距灯 >0.6m,身体仍把锥的下半切出硬边
// (0830 截图"左右不对称"根因 R2)。Java 侧每灯在 cookie 槽写
// (灯→胶囊中心偏移.xyz, 胶囊半径);胶囊竖直半高为常量。w<=0 = 未启用。
#define TACLIGHT_SELF_CAP_HALF 1.05

float taclight_sso(vec3 fragView, vec3 lightView, TacLightSpot L) {
    const int STEPS = 24;   // F5:16→24
    vec3 rayVec = lightView - fragView;
    float dither = taclight_ign(gl_FragCoord.xy);
    float blocked = 0.0;
    // 自体胶囊(视图空间):中心 = 灯位 + mat3 旋转后的偏移;轴 = 世界竖直
    // 经 mat3 旋转。每灯一次预计算,步进内只做点积/距离。
    bool capOn = L.cookie.w > 0.0;
    vec3 capC = lightView + mat3(gbufferModelView) * L.cookie.xyz;
    vec3 capAxis = mat3(gbufferModelView) * vec3(0.0, 1.0, 0.0);
    for (int i = 1; i <= STEPS; i++) {
        float t = (float(i) - 0.5 + dither * 0.9) / float(STEPS);
        vec3 sp = fragView + rayVec * t;
        if (distance(sp, lightView) < TACLIGHT_SSO_SELF_FREE) continue;   // 贴灯豁免
        if (capOn) {
            vec3 rel = sp - capC;
            float h = clamp(dot(rel, capAxis), -TACLIGHT_SELF_CAP_HALF, TACLIGHT_SELF_CAP_HALF);
            if (distance(sp, capC + capAxis * h) < L.cookie.w) continue;  // 自体胶囊豁免
        }
        vec2 suv = taclight_view_to_uv(sp);
        if (any(lessThan(suv, vec2(0.0))) || any(greaterThan(suv, vec2(1.0)))) continue;
        // 热修 13:比较必须在视图空间线性距离上做。设备深度 ≈ near/距离,
        // 同样世界距离差的设备深度值随距离二次缩小——设备深度域比较的 bias
        // 在 5m 外比半格厚真遮挡的信号还大,必漏检,与热修 11 删除轮廓
        // 检测是同款陷阱。
        vec3 occView = taclight_depth_to_view(suv, texture(depthtex1, suv).r);
        float sceneDist = -occView.z;
        float rayDist = -sp.z;
        float biasW = 0.06 + 0.04 * t;   // 世界尺度防自表面 acne;远小于任何真遮挡物
        if (rayDist > sceneDist + biasW) {
            // F2 扩展(2026-08-30):遮挡者本体落在自体胶囊内 → 不计。
            // 场景:第三人称相机下,"灯照亮的远墙"与"玩家身体"在屏幕上重叠,
            // 世界空间射线并未穿体,但深度测试拿身体的深度当遮挡者 → 整面
            // 墙的光斑被自己的身体假消光。按遮挡者的世界位置(非采样点的
            // 屏幕投影)做胶囊判定;occView 与 sceneDist 同源,零额外反投影。
            bool selfOcc = false;
            if (capOn) {
                vec3 orel = occView - capC;
                float oh = clamp(dot(orel, capAxis), -TACLIGHT_SELF_CAP_HALF, TACLIGHT_SELF_CAP_HALF);
                selfOcc = distance(occView, capC + capAxis * oh) < L.cookie.w;
            }
            if (!selfOcc) blocked += TACLIGHT_OCCLUSION_AT(suv);
        }
    }
    // F5 软化(2026-08-30:斜率 1.2 保留,让细遮挡物(栏杆)从"消到不可见"退化为
    // "半消光闪烁带";1.2 保持"全挡趋灭、边缘缓降",把 IGN 噪闪幅度压一半,代价
    // 是极细遮挡物透光略增(与树叶半透折中同一方向)。
    float occ = blocked / float(STEPS);
    return pow(max(1.0 - occ * 1.2, 0.0), 2.0);
}

/** M1 · 体素 DDA 实心格穿透软化带宽(方块,2026-09-02 根因轮):≥带宽 T=0,
 *  掠边按比例放行;取值依据见 taclight_vox_transmit 头注释。 */
#define TACLIGHT_VOX_FUZZ 0.35

// ----------------------------------------------------------------------------
// M1 · 体素 DDA 遮挡(v0.12,2026-09-01 深夜④;立项 = 用户实测墙后地面漏光,
// 满足 AGENTS §4 条件项"实机真见漏光才立项")
// 根治 SSO 已知局限(上方注释:视锥外的遮挡者不投影 → 墙后地面漏光):
// 世界空间 Amanatides-Woo 体素步进,1 格 = 1 体素(对齐方块网格,零重采样误差)。
// 分类码与适配层遮挡系数同源(0 空/1 软植被 0.25/2 树叶 0.60/3 实心 1.0),
// 数据由模组每 tick 采样填充上传(SSBO 尾段 voxOrigin/voxMeta/voxData)。
// 返回透射率 T ∈ [0,1]:实心体素一票否决(T=0,硬阴影——DDA 是精确几何,无需
// SSO 的 1.2 斜率软化);树叶/植被按穿越格数透射衰减。栅格无效或光线任一端点
// 在栅格外 → 返回 -1(调用方回退 SSO;覆盖半径不足的远灯退化为旧行为,不假遮挡)。
// 端点格双向豁免:起点格(灯所在空气格)先步进后判定,天然跳过;终点格(被照
// 表面所属方块,沿射线回退 1e-3 定位)步进至即停,不自遮——端点各让一格后,
// 中间任何实心格都是真遮挡。
// 2026-09-02 根因轮两修(实机四臂消融 evidence/2026-09-02-dda-bob-stripe/):
// ① tie 语义:Amanatides-Woo 同一 crossing time 的全部 tied axes 一次推进——
//   旧单轴分轮会访问射线仅擦边、并未穿入的侧邻格(假阴影边界,随 bob 成片翻转);
// ② 穿透软化带 TACLIGHT_VOX_FUZZ:实心格按射线在其内穿透长度放行(≥带宽仍
//   严格 T=0,墙后遮挡基线不变;掠边按比例部分透射)——影子轮廓上硬 0/1 在 bob
//   亚像素采样移动下成片翻转,即"条纹随视角晃动节奏放大"的机制。帧证据:
//   条纹 = 墙柱硬影(SSO 漏光时被糊掉不可见);实机标定 0.08 不够(边缘 |bob|
//   相关仍 0.13),0.20 ≈ bob 视差(1-2.5cm@3-5m)的 4-8×、≈ 20% 条纹周期。
// ③ 0.20→0.35(2026-09-02 体感轮):用户实测"步行条纹放大仍在,跳跃前进(原版
//   bob 振幅离地衰减)即不明显"= 步频 bob 摇晃 × 硬影缘残留闪烁;加宽半影带
//   压掉边缘时间对比度。墙后遮挡不变(穿墙射线穿透 >> 0.35 仍 T=0)。
// ----------------------------------------------------------------------------
float taclight_vox_transmit(vec3 worldA, vec3 worldB) {
    if (voxOrigin.w <= 0.0) return -1.0;
    vec3 a = worldA - voxOrigin.xyz;      // 方块格空间
    vec3 b = worldB - voxOrigin.xyz;
    vec3 dim = vec3(voxMeta.xyz);
    if (any(lessThan(a, vec3(0.0))) || any(greaterThanEqual(a, dim)) ||
        any(lessThan(b, vec3(0.0))) || any(greaterThanEqual(b, dim))) return -1.0;
    vec3 dv = b - a;
    float len = length(dv);
    if (len < 1e-4) return 1.0;
    vec3 dir = dv / len;
    ivec3 cell = ivec3(floor(a));
    ivec3 last = ivec3(floor(b - dir * 1e-3));
    ivec3 istep = ivec3(dir.x > 0.0 ? 1 : (dir.x < 0.0 ? -1 : 0),
                        dir.y > 0.0 ? 1 : (dir.y < 0.0 ? -1 : 0),
                        dir.z > 0.0 ? 1 : (dir.z < 0.0 ? -1 : 0));
    vec3 tDelta = vec3(abs(dir.x) > 1e-9 ? 1.0 / abs(dir.x) : 1e9,
                       abs(dir.y) > 1e-9 ? 1.0 / abs(dir.y) : 1e9,
                       abs(dir.z) > 1e-9 ? 1.0 / abs(dir.z) : 1e9);
    vec3 tMax = vec3(abs(dir.x) > 1e-9 ? (dir.x > 0.0 ? (float(cell.x) + 1.0 - a.x) : (a.x - float(cell.x))) * tDelta.x : 1e9,
                     abs(dir.y) > 1e-9 ? (dir.y > 0.0 ? (float(cell.y) + 1.0 - a.y) : (a.y - float(cell.y))) * tDelta.y : 1e9,
                     abs(dir.z) > 1e-9 ? (dir.z > 0.0 ? (float(cell.z) + 1.0 - a.z) : (a.z - float(cell.z))) * tDelta.z : 1e9);
    float T = 1.0;
    for (int guard = 0; guard < 384; guard++) {
        float tNext = min(tMax.x, min(tMax.y, tMax.z));
        float tieEps = max(1e-6, abs(tNext) * 1e-5);
        bvec3 tied = lessThanEqual(abs(tMax - vec3(tNext)), vec3(tieEps));
        cell += istep * ivec3(tied);
        tMax += tDelta * vec3(tied);
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(dim)))) return T;
        if (all(equal(cell, last))) return T;
        int idx = cell.x + cell.y * int(dim.x) + cell.z * int(dim.x) * int(dim.y);
        uint code = (voxData[idx >> 4] >> uint((idx & 15) * 2)) & 3u;
        if (code == 3u) {
            // 穿透长度软化:tMax 以归一化方向计,单位=沿射线方块数(终点在 t=len);
            // 出格时间-入格时间(钳到 len)即该格内穿透长度;≥带宽仍 T=0。
            float tExit = min(tMax.x, min(tMax.y, tMax.z));
            float penLen = max(0.0, min(tExit, len) - tNext);
            float f = clamp(penLen / TACLIGHT_VOX_FUZZ, 0.0, 1.0);
            if (f >= 1.0) return 0.0;
            T *= 1.0 - f;
        }
        else if (code == 2u) T *= 0.40;      // 树叶:0.6 遮挡/格 → 透射 0.4/格
        else if (code == 1u) T *= 0.75; // 软植被:0.25 遮挡/格
    }
    return T;
}

/** F3(2026-08-30):spec 项能量钳制。GGX 分布项(d)在低 roughness 下峰值可到
 *  10+,× intensity 6 → 镜面尖峰独占 ~2.0 辐射,与 diffuse/bloom/体积多链叠加
 *  推出饱和平台(R1 高频推手)。diffuse 有 albedo 纹理作视觉载体,spec 是无
 *  载体的窄峰——压幅不压形:0.35 保留高光形状,削去能量尖峰。 */
#define TACLIGHT_SPEC_DAMP 0.35

// ----------------------------------------------------------------------------
// M0 · K 键调试绿锥(doc06 §2.4 锥判定公式 × §2.10 诊断方式):
// 忽略材质,锥内输出绿色,用于肉眼验证"数据通道 + 锥几何 + 半径"三件事。
// 返回 [0,1] 的锥内系数(内锥全亮、外锥归零、半径外为零)。
// ----------------------------------------------------------------------------
float taclight_debug_green_cone(TacLightSpot L, vec3 fragView) {
    if ((flags & TACLIGHT_FLAG_DEBUG) == 0u) return 0.0;
    if ((flags & TACLIGHT_FLAG_HAS_DATA) == 0u) return 0.0;

    vec3 lightView = taclight_scene_to_view(taclight_world_to_scene(L.posRadius.xyz));
    vec3 toFrag = fragView - lightView;
    float dist = length(toFrag);
    float radius = L.posRadius.w;
    if (dist > radius || radius < 1e-3) return 0.0;

    // 方向只用旋转部分(gbufferModelView 的平移在 composite 阶段无效)
    vec3 dirView = normalize(mat3(gbufferModelView) * normalize(L.dirType.xyz));
    float cosAng = dot(toFrag / max(dist, 1e-4), dirView);
    // 内锥(cosY)全亮,外锥(cosX)全灭:spot = smoothstep(cosOut, cosIn, cosAng)
    float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
    // 软肩压缩:近场压暗、远场几乎不动(实机调优 2026-08-27,详见上方注释)
    // !knee 覆盖经 cone.w 透传(0=编译期默认 G,调试锥旧行为不变)。
    return taclight_soft_knee(spot * taclight_attenuation(dist, radius, L.cone.z), L.cone.w);
}

// ----------------------------------------------------------------------------
// M1 · 表面照明主循环(interop 版接口):
//   输入 = 适配层解码后的物理量(视图空间片元、线性 albedo、视图法线、
//   LabPBR 粗糙度、金属标志、F0);返回该像素的灯辐射 radiance(未标定,
//   量纲标定/肩部由消费侧完成)。锥判定/衰减/遮挡分流为跨包通用逻辑。
//   遮挡分流(2026-09-02 修订,坑58):体素 DDA **无条件先执行**——它是
//   世界空间射线,灯≈相机时依然有效(起点格先步进后判定、终点格回退
//   1e-3,双端豁免,视线即光路),跳过它 = 自灯影子整体丢失。
//   同轴豁免("可见即无遮挡",热修 12)只救屏幕空间 SSO 的退化
//   (灯在相机处 SSO 假消光黑边),且判定必须在**场景域**
//   (world−camera,无 bob):坑 57 修复后 lightView 是真实视图距离,
//   含 bob 平移 ±0.1,自灯锚点(手持 0.44/枪灯 ~0.6)恰在 0.5 格阈值
//   两侧,随步频翻转 = 影子"消失+移动闪烁"(实机回归)。
// ----------------------------------------------------------------------------
vec3 taclight_surface_lighting(vec3 fragView, vec3 albedo, vec3 n,
                               float roughness, float metal, vec3 f0) {
    vec3 radiance = vec3(0.0);
    for (uint i = 0u; i < lightCount && i < 8u; i++) {
        TacLightSpot L = lights[i];
        if (L.dirType.w < 0.5) continue;          // 预留:类型过滤
        vec3 lightScene = taclight_world_to_scene(L.posRadius.xyz);
        vec3 lightView = taclight_scene_to_view(lightScene);
        vec3 toFrag = fragView - lightView;
        float dist = length(toFrag);
        float radius = L.posRadius.w;
        if (dist > radius || radius < 1e-3) continue;   // 廉价门:半径窗口
        vec3 lf = toFrag / max(dist, 1e-4);       // 灯→片元(锥判定轴,与绿锥同式)
        float cosAng = dot(lf, normalize(mat3(gbufferModelView) * normalize(L.dirType.xyz)));
        float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
        if (spot <= 0.001) continue;              // 廉价门:锥外
        // M1 根因热修(2026-08-27):l 此前直接沿用灯→片元方向,同轴光下
        // dot(n,l) 恒负 → ndl 门拒绝全部像素(五轮"无白光"的真正根因);
        // 光照约定必须是 表面→灯。
        vec3 l = -lf;
        float ndl = max(dot(n, l), 0.0);
        if (ndl <= 0.0) continue;                 // 廉价门:背面
        float vis;
        float vt = taclight_vox_transmit(L.posRadius.xyz, taclight_view_to_world(fragView));
        if (vt >= 0.0) {
            vis = vt;
        } else if (dot(lightScene, lightScene) < 0.25) {
            vis = 1.0;    // 同轴+栅格无效:可见即无遮挡(热修 12 原意)
        } else {
            vis = taclight_sso(fragView, lightView, L);
        }
        if (vis <= 0.003) continue;

        vec3 lc = L.colorIntensity.rgb * L.colorIntensity.a;
        float atten = taclight_attenuation(dist, radius, L.cone.z);
        vec3 diffuse = albedo * (ndl * (1.0 - metal));
        vec3 spec = taclight_ggx(n, -normalize(fragView), l, roughness, f0) * (ndl * TACLIGHT_SPEC_DAMP);
        // 近场软膝(2026-09-05,用户第四旋钮 !knee):默认 cone.w=0 恒等(旧行为);
        // 开启后逐灯贡献先膝压再累加(非线性必须在求和前施加,否则近缘混合失真)。
        radiance += taclight_soft_knee3((diffuse + spec) * lc * (spot * atten * vis), L.cone.w);
    }
    return radiance;
}

#endif // TACLIGHT_CORE_INCLUDED

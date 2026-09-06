## 09-06 下午 · 遮挡查表双线性化(条纹修复)+ 体积光时间复用:灯光开销减半、同机位 +58% 帧(未 push)

- 用户批准两项优化一并落地(讨论定案:先修查表条纹,再上时间复用;半分辨率路线
  被本包史否决——v0.10.0 热修 Oculus 1.8.0 buffer 全屏假设不成立,故为全分辨率减步)。
- **① 条纹修复**:用户实测查表版有"径向条纹/过渡断层"→ 根因 = 512×256 表每 texel
  0.7° 扇形在 30m 处横跨 ≈37cm,超过 FUZZ 0.35 软化带,NEAREST 相邻扇区 vis 整带
  跳变。修复 = `taclight_occl_table_row` 改**双线性 4tap**(texelFetch 定点取数,
  经度 mod 环绕/纬度钳制;凸组合性质由契约钉死)。实机同姿态 occl on vs off
  (柱影机位)imgdiff **meanDiff 0.0144 / changed 0.0025%**(NEAREST 时代同口径
  0.894 / 0.16%,降 62×/64×)= 查表与逐格精确 DDA 噪声级一致。OcclTableContract
  5263 → **17061** checks(新增 texel 中心恒等/凸性/边界 Lipschitz/跨界插值四钉)。
- **② 时间复用 `!tm`(默认开,第十旋钮)**:composite1 步数 64→**32** + IGN 抖动
  逐帧旋转(`fract(ign+0.618·frame)`,帧间去相关)+ 上一帧历史 **colortex9**
  (clear=false,rgb=混合后光束 a=终点距离/256)**重投影混合**(权重=0.75×逐灯
  置信度)。重投影 = 终点经 gbufferModelViewInverse **全逆**(铁律 3)→ world →
  previousCameraPosition → 上一帧全矩阵回投;有效性门 = 历史 a>0 非 NaN + uv 出界
  拒用 + 终点距离差 ≥2m 拒用(disocclusion)。**防拖影**:Java 新增
  `LightMotionConf` 按灯身份键(自身手持/枪、远程按实体 id,槽位换位免疫)差分
  上帧位姿,`conf=exp(-6·Δpos)·exp(-4·Δangle)`,经 SSBO **vlParams.w**(原恒 0
  保留槽,布局 96B 不变)透传;首帧/灯开关/瞬移=0=全新鲜;无贡献像素 conf=0
  (灯关即灭,不留衰减尾巴)。`!tm off` = 64 步全新鲜(逐位旧行为)。
  flags **bit5(FLAG_TEMPORAL=32)**,重启清零回默认 on。
- **实机(4K 窗口,锥 20,单灯,机位=远看被照墙长射线穿束体,bench×3 中位)**:
  灯关 422.6;tm off+occl on 116.2(灯开销 6.24ms)→ **tm on+occl on 183.5
  (3.08ms,开销 −50.6%,fps +58%)**;DDA 路径同得 +57%(106.2→167.0);新默认 vs
  旧 DDA 路径 +72.8%;1%low 59→146-150。**画质**:tm on 收敛 vs tm off 同机位
  imgdiff **meanDiff 0.0057 / maxDiff=1 / changed=0 = 逐位一致**(静态有效步数
  32/(1−0.75)=128,时间累积把 32 步质量补回 64 步以上)。
- 契约:新增 TemporalReuseContract **60** 项(置信度 oracle/有效步数/GLSL+Java 源码钉/
  !tm 三态),连同全部既有 AllContracts ALL PASS + BUILD SUCCESSFUL。坑105 复查:
  实机 reload 后 grep 无编译错,tm on/off 帧数差异证明新 composite1 真在跑。
- 证据 `docs/evidence/2026-09-06-temporal-bilinear/`(README+6 截图+manifest)。
  收尾按降温纪律:柱子移除、灯关、pack=NO_PACK。**待用户体感:①移动甩灯/步行
  拖影(嫌拖影 !tm off 即回旧路径)②条纹观感确认 ③连同锥角/knee/scat 等旋钮
  一起拍板 config 默认交正式版本**。B 端本轮未启动,双人验收跑 mp-session。

## 09-06 午 · 体积光遮挡查表化(方案二)落地:最坏场景 38.4→65.4 帧(+70%)(未 push)

- 用户批"按方案二做,把性能表现优化一下"→ **逐灯全向遮挡距离表**(shadow-map 思想
  的逐灯变体)落地并实机闭环。VL_STEPS **回 64**(查表消掉 DDA 大头后,64 步采样
  质量重新可负担,上午 64→32 实验项就此关闭)。
- **设计**:composite 最早段(MRT `/* DRAWBUFFERS:08 */` 写 **colortex8**)每帧为
  每灯预计算 512×256 equirect 全向"该方向首个遮挡距离 D"表(4 灯 RGBA 打包,
  D/128 归一,1.0=128m=全通;构建=新 `taclight_vox_hit_dist`,与 vox_transmit 完全
  同语义镜像:起始格豁免/tie 全轴/FUZZ 0.35 穿透软化/树叶软地板 0.45);composite1
  每采样 NEAREST 查一次表代替 384 格 DDA 行走,vis=`clamp((D−dist)/FUZZ+0.5,0,1)`
  与旧软化带一致。栅格无效 → Java 不置 **flags bit4(FLAG_OCCL_TABLE=16)** →
  GLSL 自动回退旧逐采样 DDA,fail-safe 语义保留。
- **新旋钮 `!occl on/off/status`(默认 on,重启清零,九旋钮)**:off=回旧逐采样
  DDA(慢但逐格精确,保底+对照);knob.ps1 已入白名单。
- **实机(4K,锥 20°,双灯,`!bench`×3 中位)**:用户还原的最坏场景(近景亮墙+
  地面光池+门洞光锥同屏,"主视角被体积光包围")**occl on 65.4 帧(1%low 56.8)vs
  occl off 38.4 帧(1%low 30.2)= +27 帧 +70%,−10.7ms/帧;off 与用户早上体感
  "掉到 40 帧"吻合**。开阔机位四点:俯地 95.9/89.1、沿轴 92.5/83.2、墙面 92.2/84.5、
  仰空 105.9/100.0——表模式处处不慢(构建成本 <2ms@4K)。**画面等价 imgdiff:
  meanDiff 0.894/255,差异像素 0.16% 全部在光束亮区内部(NEAREST 角向量化正常抖动),
  光束外逐位一致=零新增漏光**。
- 契约:**OcclTableContract 5263 项 ALL PASS**(JVM oracle 与 VoxelDda 互证 800 迭代
  ×3 场景、深墙 200 点零漏、方向↔texel 往返恒等 5000 检、假挡率 ≤2%、近场零失配、
  旋钮三态、GLSL/Java 源码钉),连同全部既有契约 `AllContracts: ALL PASS`。
  interop:`TemplateLibrary.directiveFree` 已登记 `TACLIGHT_OCCL_TABLE_AT` 钩子块
  (宿主包注入=哨兵全可见),iterationT 冒烟模板加载检查当场抓住过漏登记 → 坑 113。
- 证据 docs/evidence/2026-09-06-occl-table/(包围视角基准表 + off/on 对照帧 +
  imgdiff + manifest);坑 113 入册。
- **收尾(12:07,应"系统温度高"用户要求)**:双端灯已关、A 端光影包已禁用
  (`pack=NO_PACK`,SSBO count=0),实例保持运行,下次实测前再拉包。
- **待用户:下次实测拉包后体感①最坏场景帧数是否达标(65 帧级)②光束遮挡观感与
  之前有无可感差异(契约与 imgdiff 均说无,以体感为准);连同锥角/knee/scat 等
  各旋钮一起拍板 config 默认后交正式版本。**

## 09-06 上午 · 光池内掉帧定位 + VL_STEPS 64→32 实验(未 push,GLSL 待用户拍板)

- 用户报"开灯前 240 帧 / 开灯后掉到 40"→ **同机位同视角受控 A/B**(4K,双灯 on,
  cone 20° 用户手调,站光池内近全屏覆盖,`!bench`×3 取中位):灯关 **354.5fps
  (2.82ms)** → 灯开 **28.4fps(35.2ms)= 开灯 +32.4ms**,比 09-06 凌晨远景机位的
  +3.49ms 大一个数量级——差别 = 覆盖率(近全屏像素进 composite1 逐采样路径)。
- **三臂拆解:主体是灯侧体素 DDA ≈20.6ms**——beamonly on(跳表面照明)29.4fps
  → 表面照明仅 1.2ms;voxel off **69.2fps** → DDA 占 2/3。体积 raymarch 数学本身
  (无 DDA)≈11.6ms。凌晨远景测的"DDA +0.13ms"不可外推到 in-pool(坑 112)。
- **VL_STEPS 64→32(composite1.fsh 一行)= 同机位 55.3fps(省 17.1ms)**,DDA 与
  raymarch 随步数线性减半;重载零编译错,宽锥(15-20°)下实拍无可见劣化
  (64 步的原始理由"细锥 8° 远背景跨采样"在宽锥下不成立)。**GLSL 已改、留工作区
  未提交:LightTuneContract:195 钉 64,待用户体感拍板(保 32=改契约+提交;嫌糙
  回 64)**。若仍不够,候选工程手段="每 K 步一跑 DDA、步间复用透射率"(未立项)。
- 其余数字:锥 20°→8° 省 9.6ms;`!beamonly` 不省帧;基线 fps 是视角强函数
  (灯关同机 354 / 看场景 128 / 看天 ~2100),对比帧数必须同机位同视角。
- 证据 docs/evidence/2026-09-06-inpool-perf/(三臂实拍 + 六臂表 + README);
  坑 112 入册。

## 09-05 晚 4 · 体积光穿墙修复 + `!cone` 第八旋钮(8°/4°)+ knee 默认开 + 技术报告(未 push)

- 用户四点:①体积光穿遮挡(B 在墙后,雾糊到 Dev 窗口)必须修;②性能开销过大就
  不做体积光;③Dev 站进 B 光束近场反光刺眼;④锥角太宽远距离显得范围巨大 → 改近
  平行光;最后要一份从原理讲清实现与参数的技术报告。
- **漏光修复(composite1)**:原只有视线深度门,缺"灯侧门"——灯→采样点光路穿墙的
  空气采样照样过锥判定。修复=逐采样 `taclight_vox_transmit(L.posRadius.xyz, spWorld)`
  (与表面照明同栅格同 DDA),vis≤0.003 剔除,−1(栅格无效)回退可见 fail-safe;
  前置便宜门 `spot·atten·ph<1e-4` 把绝大多数采样挡在 DDA 之前。
- **锥角收窄**:`!cone <2..45>`(第八旋钮,内角自动=半角,Java 重写 cos,SSBO/GLSL
  零改动);config 默认 32°/18°→**8°/4°**(30m 光池半径 18.7m→4.2m)。
- **knee 默认开**:DEFAULT_KNEE_G 0(恒等)→**2.0**,近场软膝出厂即生效
  (off=回 2.0)——近场白爆→bloom 眩光的第一治理点。
- 契约 LightTune 88→**113** 项 ALL PASS + 全套 BUILD SUCCESSFUL;双端 reload 零编译错。
- 实机(41×26 石台+石英墙舞台):①漏光同姿态热交换 A/B:漏光带 ROI **83.4→56.2**
  (=环境基线),侧视光束仍在(遮挡不杀丁达尔);②cone 8 细长楔形 vs 32 宽水洗,
  diag cos=(0.990,0.998) 与 8/4 吻合;③knee G2→G8 光池核 ROI(避开名牌行)
  **189.1/238 → 106.0/132**,对照远墙 ratio 1.00(近场选择性);④**!bench 四臂**:
  灯关 0.592ms / 灯开全链 0.782-0.785ms → **TacLight 全部逐像素成本 ≈0.19ms/帧,
  灯侧 DDA 新增≈0,锥宽无影响**——体积光保留,60fps 预算占比 ~1.2%。
  证据 docs/evidence/2026-09-05-vox-shadow-cone-knee/。
- **技术报告 docs/光源实现技术报告.md**(用户要):数据主线/五参数公式与实算表/
  体积光相位-密度-上限-灯侧遮挡/色调链白爆归因/8 旋钮总表(坑107 作用域铁律)/
  bench 数字/现象→根因→旋钮速查/代码地图。
- 坑 109(`!beam 0` 不关 raymarch 循环,量成本必须灯关基线;1700fps 档 bench 要
  3 遍取中位)/110(小结构 ROI 判定先画块级亮度图确认物理载体,名牌文字≠判定
  载体)入册 §6。
- **09-06 凌晨 4K 性能复测(用户问"高分辨率影响",实测回答)**:A 端全屏重启
  (options.txt fullscreen:true),`!shot` 实证帧缓冲 **3840×2160**(显示器原生 4K,
  系统 2560×1440 系 150% DPI 缩放虚拟值——WMI/Forms 屏幕查询被 DPI 缩放骗,坑 111);
  重摆场景+配方后四臂×3:灯关 7.80ms(128fps)/灯开全链 11.29ms(**88fps**,1%low 73)/
  宽锥 32 14.10ms(71fps)→ **TacLight 总成本 ≈3.49ms(480p 0.19ms × 18.4/20.2 像素
  倍 ≈ 线性);灯侧 DDA +0.13ms(≈4%);旧宽锥 32° 在 4K 暴露 +2.81ms(480p 上是
  噪声级)= 收窄锥角在高分辨率下的真实收益**。用户 4K 默认配方 ≈88fps,60fps 余量足。
- 双端现场已复原成交接配方:两端 bright 12/atten 2/beam 0.5/scat 0.15/knee off
  (=2.0)/cone off(=8/4)/beamcap off/beamonly off/voxel on,双灯 on;
  A 端现为全屏 4K(用户真实模式),B 端 854×480 窗口。
- **待用户体感拍板**:锥角 8/4 与 knee 2.0 的默认手感(嫌眩 `!knee 4~8`,嫌宽
  `!cone 2~6`),连同前几轮旋钮一起固化 config 默认后交正式版本。

## 09-05 晚 3 · 体积光"侧面服务型"相位(丁达尔定案,未 push)

- 用户定案:"体积光只需要给侧面视角服务,主要是实现丁达尔效应,强化旁观者视觉感受;
  正面(体积+表面叠加)太亮要调低"。
- **HG 相位整体替换为侧面剖面**:composite1 `phase = 0.4·(f + (1−f)·sin²θ)`,
  θ=视线×光传播——正侧 90° 最亮、正对/沿轴只剩底亮 f;`TACLIGHT_BEAM_NORM 0.4`、
  f=vlParams.x 改义"轴向底亮份额"(Java `BEAM_ANISOTROPY 0.55`→`BEAM_SIDE_FLOOR 0.04`,
  record 字段 anisotropy→sideFloor);taclight_hg 从 math 库移除;`!scat` 旋钮改义 f
  (区间不变,0=纯侧面)。
- 对账:正侧 0.4 vs 旧 0.0373;持灯者沿轴 0.016 vs 旧前向瓣 **0.609**;迎面 0.016 vs 旧后向 0.0149。
- 契约 LightTune **93** 项(计数串修正:上轮实为 90)+ShaderCore 34 钉"hg 已移除",ALL PASS。
- 实机(新大型舞台:41×26 平滑石台+石英墙,日常配方,新旧窗口分别 !scat 0.04/0.55):
  侧视空中锥带 **57.2→91.1(+59%)锥缘清晰成型**;持灯者沿轴视带 **94.5→67.9(−28%)**;
  正对中心轴 45.7→44.6(持平略降)——侧面提亮+正面/沿轴压暗双双落袋。
  证据 docs/evidence/2026-09-05-beam-side-phase/(README 三张配对+带亮度实测)。
- 坑 106(/tp facing 脚部锚点=相机抬头)/107(远端灯观感取观察者本地旋钮)/
  108(黑建材夜景判读假象)入册 §6。
- **待用户体感**:侧视锥亮度(NORM 0.4 定标,嫌暗升/嫌亮降说一声)、`!scat 0..0.9`
  正对残光手感和固化默认。

## 09-05 晚 2 · !beamcap 重叠软上限 + BEAM_GAIN 翻倍(未 push)

- 用户反馈:①双灯同照体积光无限叠加白爆刺眼 → 要上限;②侧视轮廓仍偏暗 → 提亮。
- **`!beamcap <0.25..8>`**(第七旋钮):vlParams.z 透传倍率 m,GLSL 分量级指数肩部
  `cap=TACLIGHT_BEAM_CAP(2.0)×m`——**≤半帽点恒等(单灯零变化),>半帽点渐近封顶**
  (非硬截断)。**BEAM_GAIN 0.5→1.0**(全角度提亮,眩光由上限兜底)。
- 契约 LightTune 68→**88** 全绿;实机(双灯汇聚同片墙,diag count=2):
  cap 0.25=**墙纹清晰眩光消失**,off(2.0)/8=白爆成片;**单灯恒等对 meanDiff 0.033
  ≈噪声底(零误伤)**;cap off vs 0.25 meanDiff **17.57**/changed 21.1%。
  证据 docs/evidence/2026-09-05-beam-cap-gain/。
- **坑105 入册**:首版 `vl<=sh`(vec3 与 float 比较三元)→ C1020 → Iris **静默禁包**
  (全场原版黑夜,极易误判逻辑错);修复=min+exp 分量级;**GLSL 改动必须实机 reload
  后 grep 日志编译错,契约绿≠包能编译**。
- 使用:双灯刺眼 `beamcap 0.5`(或 0.25 最狠);侧视再提亮 `beam 0.8~1.0`+`scat 0~0.1`
  (背景需近景)。

## 09-05 晚 · !scat 第六旋钮 + raymarch 64 步:侧视丁达尔改进落地(未 push)

- 用户确认 beamonly 下体积光存在但"侧面太弱",批准改进(散射方向+采样)。
- **`!scat <0..0.9>`**:SSBO vlParams.x 逐灯直接换值,GLSL 零改动(表面照明不消费该槽,
  只影响光束形态);**0=完全各向同性(侧视最亮),off=回编译期默认 0.55**(与 !beam 同族,
  0 是合法消费值非哨兵)。LightTune 51→**68** 项,`AllContracts: ALL PASS`;同能单变量
  A/B(g=0.55 vs 0,beam1.0+亮30+atten0.5)meanDiff **5.46**/changed **12.6%**(噪声底 0.48)。
- **TACLIGHT_VL_STEPS 32→64**(composite1):远背景视线步长 3m→1.5m,近场细锥不再被
  步进跨过;成本 ~0.25→~0.5ms/帧量级(32 步 0.25ms 基准外推,待正式验收 !bench 实测)。
- **实机定案(稳定 B 机位:Dev 灯横穿 B 视野、墙做背景)**:beam 1.0+亮30+atten 0.5+
  scat 0 = **清晰离散光锥**;beam 0.6+亮12+atten 2+scat 0.15 = 仍可见(淡)——推荐日常配方。
  此前 A 端 yaw63 机位全黑=病态工况(光束背景=无限远虚空+仅近端 2.5m 细锥入画),非缺陷。
  证据 docs/evidence/2026-09-05-beam-scat-knob/。
- **观察要领**:从侧面 3~5m 看光束、让墙/地面当背景;A 端已留推荐配方(beamonly on +
  beam 0.6 + bright 12 + atten 2 + scat 0.15)。更细"一根柱"→ 窄锥角另立项。

## 09-05 · 体积光可见性诊断:!beamonly 后"看不到丁达尔"定案(未 push)

- **用户报**:Dev(A 端)视角观察 ObserverB 的灯,`!beamonly on` 后无体积光,问是开关
  问题还是实现问题。**定案:开关没问题、实现没问题,不可见=四机制叠加**——
  ①HG g=0.55 相位,侧/背视(63°~117°)比顺轴暗 16~27×;②水平光束与视线夹 ~63°,
  70° FOV 内只剩近端 2.5m 细锥段;③composite1 固定 32 步全屏 raymarch,远背景视线
  (天空 96m/掠地)步长 1~3m 会把细锥跨过;④密度默认 0.05 + K=5 远段衰减。
- 实证六图 `docs/evidence/2026-09-05-beam-visibility-diagnosis/`:用户态(beam 0.2)侧视
  黑场 / 同参数照地出**明显光扇**(渲染+响应正常)/ 调平侧视 max 参数全黑 / 近段视图仅
  墙端辉光 / **轴向实拍巨大光球**(实现铁证)/ diag flags=0xd、dir 同步正确。
- **待拍板改进**:`!scat` g 旋钮(侧视 ~2×↑/顺轴 −4× 权衡)、TACLIGHT_VL_STEPS 32→64
  (composite1 ~0.25→~0.5ms 待实测)、窄锥角(细光柱形态前提);现有配方即可看:
  照地看光扇、顺轴看光球(beam 0.5~1.0 + bright 12~20 + atten 2)。

## 09-05 · !beamonly 只看光束开关(未 push)

- **用户需求**:单独观察体积光形态。SSBO 头部 flags bit2(`FLAG_BEAM_ONLY=4`,契约守卫与
  HAS_DATA/DEBUG/TIMING 无冲突)→ composite.fsh 的 M1 表面照明分支整支跳过(1 行门),
  composite1 体积束照常;`!beamonly on|off|status`,重启清零,knob.ps1/knob.bat 已同步。
- 契约 LightTune 42→**51** 项,`AllContracts: ALL PASS`;实机(6m+亮30+beam0.5)on vs off
  meanDiff **11.7**/changed **12.2%**——on 态墙砖平黑(照明关断),白色光球=体积束顺视
  积分+bloom;off 态光池内可见被照墙砖。证据 docs/evidence/2026-09-05-beam-density-knob/
  (追加 A-beamonly-off/on.png + README)。
- 观感配方:`beamonly on` + `beam 0.5` + `bright 10~15`,退 6m;侧视角见柱形、顺视角为
  光雾球(g=0.55 前向散射特性)。

## 09-05 · !beam 第五旋钮:体积光束密度(丁达尔效果强度,未 push)

- **背景**:用户体感"没有丁达尔效应"。判定:功能早已存在(composite1 32 步 raymarch + HG
  前向散射 g=0.55),不可见 = 三因叠加——密度默认 0.05 太淡 + 贴墙光路仅 ~1.1m +
  前向散射侧视弱 5×;放大实验(亮 30/衰减 2/6m 光路,双端拍片)在 0.05 密度下仍不可见
  → 密度是约束,亮度/照距旋钮救不回。
- **性能定案**(用户机,scene wall,!bench):composite1 开 395.5fps vs 临时关
  (`program.composite1.enabled=false`)439.2fps = **0.25ms/帧固定成本**;复原后 447.5fps。
  密度调大不增加成本(32 步恒定)。
- **新旋钮** `!beam <0..1>`:SSBO vlParams.y 逐灯直接换值,GLSL 零改动;**0=完全关光束**
  (开关对比),**off=回 config 默认 0.05**,无参=status;重启清零。knob.ps1/knob.bat 已同步。
- 契约 LightTune 31→**42** 项,`AllContracts: ALL PASS`;实机三档 A/B(6m+亮30):
  beam0 vs 0.35 meanDiff 17.0/changed 29.6%,beam0 vs 0.7 meanDiff 18.9/30.4%——光池外围
  可见光晕,0.7 更弥散;顺视角读作光雾、侧视角读作光柱(与 g=0.55 一致)。
  证据 docs/evidence/2026-09-05-beam-density-knob/。
- 待用户:beam 扫参(建议退 6m+侧视角,0→0.2→0.5→1.0 对照),与四旋钮一起固化 config 默认。

## 09-05 · 贴墙穿墙漏光修复(坑103,未 push)

- **用户原报**:持枪贴墙时枪灯穿过墙照到对面,破坏沉浸感。
- **根因(实测钉死)**:枪口灯位被推进墙体素格(诊断 `L0 pos z=0.20` 落墙格 z=0..1 内),
  体素 DDA 起点格豁免 → 1 格墙对该灯透明;症状=墙正面全黑+墙后地面被照亮。
- **修复**(GLSL 零改动):Java 上传 SSBO 前灯头出实心钳制(`VoxelField.clampOutOfSolid`+
  上传器接入):灯落实心格 → 沿 −dir 步进 0.05m 退到首个非实心格(上限 2m,无出路原样
  fail-safe);只换灯位三坐标,其余 21 字段保留,自体胶囊 cookie 按位移平移;
  表面/体积/前向注入三路径共用 SSBO 灯位,一处修全修好。
- **实机**(iterationT 注入 +22224,scene wall):同站位灯头 z=0.20→−0.05;墙正面光池
  mean 72.7/p90 148.2/ge128 28018;墙后地面 mean 60.8/ge200 208 与对照(灯移走
  mean 60.7/ge200 208)逐位一致(208=月光本底)。**穿墙漏光消除。**
- 契约 `AllContracts: ALL PASS`(VoxelField 21/ UploaderSemantic 38,钳制 13 项新)。
  证据 docs/evidence/2026-09-05-wall-desolidify/。坑103 入坑位册。
- 注意:本轮含未提交的 !knee 四旋钮改动(8 文件),待与本修复同批提交;待用户体感验收。

## 09-05 · 主线合回 interop/core-extract(无快进合并,冲突已解,未 push)

- **合并后端到端验收(用户要求"合并后能不能正常用",evidence/2026-09-05-merge-e2e/)**:
  jar 重建(build/libs/taclight-0.10.0.jar,269KB,refmap/mixins/模板/类全在,内网需
  -Dcheck.certs=false)+ 真实客户端(run/ 主机,ComplementaryReimagined)零模组级错误:
  loading/NET/CMDS/SSBO/mixin gate 全绿,**injected family=complementary (+8010 chars)×5**;
  灯 off/on 服务端真源+ACK+像素 meanDiff 11.66/changed 17.7%(锥池目检确认);
  三旋钮实机 `!bright 10`/`!dist 24`/`!atten 2` RELAY 回显+像素生效,off 全部回默认。
  ("无 TacLight 注入"提示=已知过期误报,注入日志已证命中。)
- **合并**:`master(4d6f09e)` ← `interop/core-extract`(含 935548a 三旋钮+坑99-102 批量),基 `c3bb689`。
  冲突 3 处,全部"双留"零语义丢失:`build.gradle`(generateRefmap+copyInlineCore 两个 Task 并存);
  `taclight.mixins.json`(client 侧 7 项并集,补 TransformPatcherMixin+GunModelRenderProbeMixin 等);
  坑位册(主线段原文不动+互操作段 25 条仅条目前序号+17 顺排 64-88,坑号原文不动——
  坑82-85 主线/互操作各一套,合并注写明段落归属,新坑从 103 起编;双方证据包零改动,manifest 有效)。
- **自动合并高危区已验**:uploader(!lv+SELF_LIGHT 总闸+三旋钮三路取值共存);
  relay(!gun 手动旗/!selflight+!bright/!dist/!atten/!back 入口共存);
  AllContracts 注册(TemplateLibrary/InlineCore/LightTune/SelfLightGate 全在)。
- 契约(合并树实跑):TemplateLibrary 37 / InlineCore 41 / LightTune 22 / SelfLightGate 11 /
  ScenePlan 207,`AllContracts: ALL PASS`+BUILD SUCCESSFUL。
- 待用户验收:三旋钮实机扫参(新构建重启双端后 `!bright/!dist/!atten`)+ 本合并无行为变更(纯汇合)。

## 09-04 02:0x · 生产 jar 离线验收通过(新 jar 含 refmap,forge 实机 Done)

- **验收**(C:/Users/20506/AppData/Local/Temp/prodtest,Forge47.1.3+online-mode=false,无 Mojang):Done(1.411s)!0 mixin 报错;[TacLight]loading/NET channel/CMDS 全绿,只缺 TaCZ/Oculus 降级提示正常。证明 jar 可用。
- **根因**:jar 缺 refmap → 生产 SRG 环境找不到 defineSynchedData 崩;AP 路径已证伪(FG6 下 -AinMapFileName/-AreobfSrgFile 不生效,MappingProviderSrg 只读 SRG 前缀格式,不读 tsrg2)。修复=build.gradle generateRefmap(Task,build/createSrgToMcp/output.srg 转 TaCZ 格式)+taclight.mixins.json 增 refmap 键;源码保持 mojmap 写法。契约 AllContracts ALL PASS。
- **注意**:本轮验的是 jar 加载可用性;双账号远端灯同步仍需双正版(run* dev 环境仍可用于同步验证)。新坑 86 入坑位册。

## 09-04 00:3x · 生产 jar 双账号验收:产物就绪,待用户双正版账号执行(需用户拍板)

- **产物**(刚构建,未提交):build/libs/taclight-0.10.0.jar(225KB,含 v3+坑85,混淆后 TpOffscreenRenderMixin 在列)+ build/distributions/taclight-shaders-0.10.0.zip(85KB,刚重打,源码无漂移)。契约 AllContracts ALL PASS+BUILD SUCCESSFUL。
- **生产安全审计**:DevLanAuthHook 有属性门控(生产无 -D 属性=原版验证行为);tacz/oculus/embeddium 全 optional+acceptMissingOr,缺包可降级;红线:iterationT fork 在仓库外,勿随包分发。
- **为什么必须双账号**:本机 dev 双端靠 disableLanAuth 绕过 Mojang 会话验证;生产集成服 online-mode=true 硬编码,离线/单账号第二端会被 Invalid session 踢,远程灯同步测不到。
- **待用户执行**(约 20 分钟,两个正版账号):两台启动器装同版本 Forge47.1.3+TaCZ1.1.8+Oculus1.8.0+Embeddium0.3.31+本 jar+本 shader zip;A 开 LAN,B 加入;验收清单 docs/03-实机验收清单.md A-D+双向对看(本轮 v3/坑85/handheld 三项)。
- **回答用户问**:不等于完工。最初目标(v0.10 自研包+M5 同步)已达成并超额(v3 屏外不剔除等);但开放项仍有 Complementary 模板、延迟补偿三档体感、interop 正式合并三项待拍板。

## 09-03 21:1x · 身体灯(手持)远程链判真伪:链路通,无 bug(用户批准开工)

- **判定**(构建 8019aca,LAN 63057):A 端 `/taclight light on Dev` →
  服务端 LIGHT-SYNC handheld=true;B 端 DIAG-REMOTE Dev flash=false→true,
  SSBO count=1→2(L0 远程手持灯正常展开)。写侧→读侧→SSBO 三段全通,不修代码。
- 历史疑似(04:1x 轮 4449 帧无 handheld 灯)未复现,关闭;判后已 light off 恢复。
- 证据 docs/evidence/2026-09-03-handheld-remote-check/(README+manifest)。

## 09-03 21:0x · v3 收尾:用户体感通过 + 双向开关修复验证通过(构建 8019aca,LAN 63057)

- **v3 体感通过**:Dev 屏外奔跑,B 视角无出入跳变;屏外灯照侧面(跟真枪不跟头)。
  程序化自检(410 帧全 fresh,方向步进 max 0.27°)+ 体感双闭环 → v3 主路径正式关闭。
- **坑85 修复验证通过**:Dev 与 ObserverB 各自视角灯亮、开关有效。
- **交叉诊断**(!diag 双端):A 端 SSBO count=1(Dev flash=false/gun=true);
  B 端 SSBO count=1(Dev flash=false/gun=true)——跨端所见一致,灯态同步正常。
- 证据包 docs/evidence/2026-09-03-v3-offscreen-nocull/(README 补体感结论+manifest 重建)。


## 09-03 19:5x · 小问题修复:枪灯手动开关"无效"(tick 探针+S2C 双覆盖)+ !selflight 显式设定(用户实机反馈)

- **用户反馈**:Dev 视角看不到灯,开关切换无变化,无法验证 Dev 看 B 持枪奔跑。
- **根因**(双覆盖):①每 tick TaCZ 探针无枪即 setGunLight(false),!gun 手动 on 当
  tick 被覆盖回 off;② !gun 未同步服务端真源 + S2C 回显再覆盖 —— 本端 SSBO 无光
  (叠加 selflight=false 更全黑),对端同步读也恒 false,B 永远看不见 Dev 灯。
- **修复**:手动旗 gunManual —— !gun 走 setGunLightManual(置旗+上报新值到服务端,
  对端立即可见)+ 探针 setGunLight / S2C 回显手动后不再覆盖枪灯位;!selflight 补
  on/off 显式设定与无参回显(用法 RELAY 回显)。自证:SelfLightGateContract 6→11
  checks,AllContracts ALL PASS,BUILD SUCCESSFUL。
- **待用户**(需重启新代码才生效,现实例仍旧代码):两端 !selflight on 恢复自身灯;
  Dev 端 !gun 开枪灯 → Dev 自己先看到锥池 → B 端应看到 Dev 枪灯;证据待新一轮
  实机截图/日志。
- 坑85 入册(调试环境搭建计划.md §6)。

## 09-03 18:3x · v3 屏外不剔除渲染 + 自身灯总闸 + mp-session STALE-KILL(用户批准 v3 开工)

- **v3(屏外不剔除渲染)**:EntityRenderDispatcher.shouldRender mixin,对"距离内+
  开枪灯的远程玩家"强制 true —— 真实渲染链(TaCZ 枪模动画+捕获钩子)在屏外照跑,
  屏外捕获不断,入场无交接差。门禁纯逻辑 TpOffscreenRenderGate(契约 10 项):
  本人/枪灯关/超距/NaN/总开关 off 一律放行零变化。实机自检(证据
  evidence/2026-09-03-v3-offscreen-nocull/):B 扫掠 120° 段 410 帧全 fresh,
  世界方向步进 max 0.267°/p99 0.228°;传送入场段 55 帧全 fresh。待用户体感:
  屏外疾跑光晕是否跟真枪、入视野有无转动。
- **自身灯总闸(用户需求:枪灯测试单变量)**:配置 SELF_LIGHT_ENABLED(默认 true
  零变化)+ 上传侧门禁(关时自身两盏不进 SSBO,远程灯照常)+ 中继 `!selflight`
  运行时翻转;契约 SelfLightGateContract 6 项。注意:本轮双端 18:10 启动时该
  功能尚未合入,用户看到的双灯叠加属实;`!selflight` 双端已关(false 回显齐)。
- **mp-session STALE-KILL(用户报 LAN 连接超时)**:残留旧实例占过期端口致 B
  "无法连接至服务器 连接超时";只杀超 10 分钟的本项目 runClient,排除
  wt-interop 并行任务(坑82)。另坑83(MSYS2 路径转换吞 `/` 开头中继)/坑84
  (ps1 首行 `//` 注释害死 param 绑定)入册。
- **契约**:AllContracts ALL PASS(新增 Gate 10 + 自身闸 6)+ TOOLS-SELFTEST PASS。

## 09-03 02:3x · 方案B姿态模型上线后两症状根治:模型俯仰约定反相 + 捕获束向反180°穿零扫动(用户实机报告,G 行实证)

- **用户报告**:①屏外光晕转向与视角相反("往上看,光晕在下面照地面");②
  入视野仍有突变,"跟改之前差不多"。G 行打桩实证**三个根因**,前两个独立于
  上轮的位置链修复(位置链本身工作正常):
  1. **模型俯仰约定反相**(坑79):MuzzlePoseModel 首版按"俯仰正=抬头"+y=+sin(p)
     合成,而输入是 MC xRot(正=低头)——实机低头 8.4° 时束向朝上 6.4°。平视
     自检数值巧合相同,约定错误不可见。修复=常量改 xRot 正增量(+2/+6=枪口
     低于视线)+ y=−sin(p);历史拟合口径更正:移动族"−6.0°"物理语义=MC 正增量。
  2. **捕获束向反 180°**(坑80):束骨 ±Z 拉伸符号未定,上传端 alignBeamAway
     兜底保画面,resolver 内 capDir 反平行毒化 fresh/hold(hold 帧 dYaw=179.98°
     实测)。修复=resolver 内以"枪口−capturedRef 眼位"外向基准对齐。
  3. **lerp 穿零扫动**(症状②真凶):dir=lerp(fallback,capDir) 两端反平行时
     插值穿零向量,归一化后**单帧扫动 104.07°**=入场 200ms 光池扫半圈,两代
     构建同在;方向维度此前从无判定/契约覆盖(坑80 教训③),修 2 后自然消除。
- **修复后程序化自检**(!sweep pitch + tp 入出场,坑76 程序化):束向-视线俯仰
  相关 **+1.0000**(旧 −1)、族偏移 +2.00° sd=0.00°、hold dYaw **+0.5°**(旧
  179.98°)、fresh 爬升单帧方向步进 **max 3.82°**(旧 104.07°)、fresh→hold
  过渡 0.24°。契约 `AllContracts: ALL PASS`(模型 22 项+resolver 50 项)。
- **环境**:坑81(`!rec` 无参=仅回显,布防必须 `!rec on`+grep armed 回显确认
  ——上轮用户首轮白测根因);mp-session 重启后 B 曾静默掉线一次(无崩溃栈,
  疑系统强杀),A 保留 LAN 单独重拉 B 成功;drive.ps1 Find-McWindow 排除
  wt-interop 窗口(并行任务隔离,显式 -ProcId 仍必须)。
- 证据 `docs/evidence/2026-09-03-pose-model-pitch-fix/`(before 用户实测反相
  会话 + after 程序化自检会话,判定数字可复算);坑79/80/81 入册。
- **待用户体感**:①Dev 从 B 视野外步行入场(光晕应平滑跟随、方向随枪口低 6°、
  无扫动);②屏外奔跑+抬头低头(光晕俯仰应同向跟随:抬头灯朝上、低头灯朝下)。
  残差:静止站立族先验 +2° vs 捕获真值 ≈0°(屏外静止差 ~2°,8m 墙 ~0.3m,
  可单常数下调);偏航摆 ±1.76° 不建模维持。

## 09-02 22:1x · 步行入场光晕跳变根治:局部偏移重构跟随 + 坑78投毒防线 + drive.ps1 抢焦点修复(用户三项实机反馈)

- **用户报告**:B 静止,Dev 开灯站在其视野外;Dev 步行入视野瞬间,B 看到光晕
  位置卡顿跳变。另两项:drive.ps1 postkey 抢鼠标/抢焦点;并行任务实例隔离澄清。
- **打桩定位**(旧构建 s0012,G 行):入场瞬间 fallback→fresh 单帧锚点步进
  **0.1483 格**;根源两层——fallback 锚=眼位+0.45·视线与真实枪口差 **~0.75 格**
  (200ms 分帧兑现=追赶滑落);旧稳态门禁含位置项,步行即破 hold。
- **修复 = 局部偏移重构跟随**(TpLightResolver 重写):捕获给出"枪口−眼位"局部
  偏移(前/上/右),绕实时偏航旋转加到实时 psnap 眼位——平移(步行/坠落/传送)
  由重构吸收,只有姿态(偏航2°/俯仰2°/瞄准/手持)变化才降级;fresh 锚=捕获
  精确世界位,200ms 爬升只桥接摆动级残差;blend/fallback 恒在重构上(降级不弃
  跟)。出厂默认偏移=双会话 307 帧实测标定 **(1.064,−0.326,0.142)**。
- **坑78(实机抓获)**:自校准偏移被陈旧姿态投毒——A 重启后 Dev 出生在平台边缘
  恰在 B 面前,B 进服捕获边缘姿态偏移 (1.5,−0.5,−1.0),/tp 挪走后位置不破门禁,
  坏偏移污染整段接近,入场单帧 **1.2118 格**。防线=**证据随龄衰减**:捕获偏移
  随龄线性衰减到默认先验,10s 归零(契约三档钉死:0s 全额/5s 各半/>10s 全先验)。
- **分析器新增 TP-FOLLOW 判定**(灯随人不变式):局部系"枪口−眼位"偏移应恒定
  (按 aim/item 分组,行程≥2 格才判);对旧缺陷会话追溯 FAIL(drift 0.7912),
  最终回归 PASS(0.1459/13.04m)。自测 92 项。
- **回归(最终构建 s0001)**:`REC-ANALYZE PASS`,TP-CONTINUITY PASS
  (transStep **0.1247**,=不可预测摆动相位,~2px@5m),TP-FOLLOW PASS;接近全程
  灯随人无滑落。契约 `AllContracts: ALL PASS`(resolver 契约 46 项)。
- **附带修复(用户明令,坑77)**:drive.ps1 postkey 路径真实点击标题栏+合成
  WM_ACTIVATE 抢鼠标/抢焦点 → postkey/holdkey/postf3r 改纯 PostMessage+scancode
  (坑46 通道同构,坑23 keyup 位保留),真前台动作族(present/press/chat/f3r/
  rawkey/click/quit)文件头明令自动化禁用;实机验证 postkey 前后前台窗口/光标
  零变化、F2 照常落盘。另记:!gun 中继只改本地不同步 c2s(自动化用 kit+数字键
  持枪绕过,待补)。
- **环境隔离**:并行任务(wt-interop,interop/core-extract)自带测试实例,其
  窗口/进程不属本任务;服务端日志证实其从未连入本 LAN 服(仅 Dev+ObserverB 两
  席);自动化必须显式 -ProcId 选窗。
- 证据 `docs/evidence/2026-09-02-tp-walkin-jump/`(四会话 CSV+判定串+入场帧+manifest)。
- **待用户体感**:Dev 从 B 视野外步行入场,光晕应平滑跟随、无卡顿跳变
  (!tpfb hard 可切旧语义对照)。refPos 基准改为 psnap 眼位(旧 CSV 对照需 +1.62)。

## 09-02 15:5x · TP 打桩判定工具链 + 屏外枪灯连续性(hold+blend)——跳变 0.73 格→0/0.09,变异注入当场抓获

- **背景(用户三问之②③)**:①要求调试从"截图目检"转向"代码/数据直接验证"
  (打桩测试思想:连续读取相机输入与光晕各计算中间值,看是否同步变化,逐级缩小);
  ②要求调研屏外光源的主流方案并落地。本轮一并交付。
- **打桩链路**:`!rec` 会话新增 **G 行(34 列)**:TP 枪灯每帧全链中间值(状态/权重 →
  mixin 束轴原样读数 → 捕获时刻相机 → 映射后世界锚/向 → 活体 referent),与 C 行
  同帧;`rec-analyze.js` 新增两个硬判定——**TP-INVARIANCE**(冻结目标+相机扫掠:
  束向世界漂移 ≤3° 且与相机相关 ≤0.5)与 **TP-CONTINUITY**(状态过渡锚点步进
  ≤0.15 格)。新旋钮 `!sweep yaw|pitch 度 秒`(程序化扫掠,取代不可靠键鼠注入)、
  `!tpfb blend|hard`(回退 A/B)、`!tproe col|row`(坑68 读数复现/变异)。
  契约 6 份新增/扩容;rec-analyze.test.js 79 项(含红绿合成用例)。
- **屏外方案选型**(主流工具箱:dead reckoning / critically damped / one-Euro(2012,
  VR 输入标准)/ 离屏捕获 pass / 有效性保持+置信度混合):选**后者**——信号非噪声
  而是"间歇可用",滤波加滞后会让灯追枪慢半拍且只遮掩阶跃;离屏 pass 成本高留备选。
  实现=TpLightResolver 三级状态机:**fresh**(在渲染,精确)→ **hold**(屏外但持灯者
  referent 未动[位置 5cm/角 2°/瞄准/手持],沿用捕获世界位,零跳变零滞后)→
  **blend**(referent 变,权重出 400ms/入 200ms slew 限速滑向眼位近似)。
  捕获链同步改**绑定捕获时刻相机**映射(fresh 窗口内跨帧用当前相机会漏进相机旋转,
  坑68 家族);MuzzlePoseStore 过期不再销毁条目(get=新鲜门,+peek;坑70)。
- **实机判定**(B 端 60fps,150°/6s 扫掠,证据 2026-09-02-tp-probe-and-offscreen-continuity):
  hard 旧基线过渡步进 **0.7297 格**(=用户看到的跳变);blend 冻结场景 fresh→**hold 250 帧**
  过渡步进 **0**,方向全程不随相机(maxDirDev 0.56~0.83°,|corr|≤0.22);/tp 挪 Dev
  (referent 变)→ blend 23 帧(恰=400ms)滑落,步进 0.0924 格;**变异测试**:!tproe row
  复现坑68 读数 → TP-INVARIANCE 当场 **FAIL maxDirDev=52.3°**(同手工双机位 56.1°
  signature),恢复 col → PASS——打桩工具抓错能力实机证实。
- **新坑**:坑69(!rec 布防态跨会话存续,会话按时间戳选不按序号)、坑70(诊断路径
  不得有消费副作用)、坑71(运动刺激必须数据验证,被墙吞的 W 与正确 hold 同形)。
- **待用户体感验收**:观察者随便转视角/快速甩视角,光池应钉在激光点串处不跳;
  快速扫过(枪模出视锥)也不再有整体跳变。默认 `!tpfb blend`(新行为),
  `!tpfb hard` 可随时切回旧对照。勿按 K(坑59)。

## 09-02 14:0x · 坑68 修复:TP 束轴误读 joml 矩阵"第2行"——旁观者转视角灯晕跟着转(用户实机报告闭环)

- **用户实机报告两个症状**:①"激光指示器指墙、照明光晕却在地面,灯没跟枪口";
  ②"旁观者转动视角,Dev 的灯光光晕跟着移动、大小角度也变"。实机复现+冻结对照
  定位单一根因:**BeamRendererMixin TP 捕获读 (m02,m12,m22) 把 joml 矩阵第 2 行
  当成 +Z 轴的像**。joml mXY() = 第X列第Y行(transformPosition 布局),+Z 轴像
  = (m20,m21,m22),第 2 行 = 转置像(逆旋)→ 相机旋转被"再施加"而非消掉。
  **铁证(Dev 姿态冻结,仅动机位):mdir 双机位差 56.1°(位置链 mpos 逐位稳定)**
  ——位置一直对、方向随相机转,是行/列读数错误的唯一指纹。
- **修复(TDD)**:`MuzzlePoseMath.extractTpBeamAxis` 读 m20/21/22(真值=
  transformDirection(0,0,1));`MuzzlePoseMathContract` 新增 4 项含**相机不变性**
  (同实体枪姿 × 两相机 → 世界方向恒等;单一位置向量定不死旋转,空间标定必须配
  方向不变性契约)。红:临时旧读数 FAIL;绿:`AllContracts: ALL PASS`(35 项)。
- **实机验证(双端重启后)**:mdir-look 夹角 0.2°~0.5°(四样本)、双机位 mdir 差
  0.3°;主判定图 `after-money-shot-convergence.png` 激光点串一路汇聚进光池,
  换机位光池仍钉在点串上。证据 `docs/evidence/2026-09-02-tp-beam-axis-row-col/`。
- **诚实更正**:此前"26-32° 下垂=真实腰射枪姿"、"步行束向反平行翻转"、
  ③"mdir 与 look 前向一致"均为本读数误差的假象(alignBeamAway 一直在给症状
  打补丁,保留作符号保险);③证据包 README 结论 3 与其自带 diag 样本矛盾,以
  本包为准。坑68 已入册(附 joml mXY 速查与"别裸写 mXY 访问器"守则)。
- 附注:快速转视角时枪模出视锥→捕获 300ms 过期→回退眼位+look·0.45,光晕仍有
  一次跳变(设计内降级);修复后稳态方向与回退方向接近,跳变幅度大幅缩小。
- 快捷:`/tp <player> <pos> facing <pos>` 的俯仰以**脚底**为原点(实测),瞄
  下俯视角要按脚底算目标 y。

## 09-02 11:5x · 里程碑③闭环:playerAnimator 引入,远程疾跑姿差异实锤,灯枪一致保持

- **playerAnimation-lib-forge 1.0.2-rc1+1.20 接入 dev 运行时**(build.gradle
  runtimeOnly fg.deobf "blank:player-animation-lib-forge",libs/*.jar 已 gitignore
  =红线1 零分发;本模组零代码引用,TaCZ 消费)。B 端首次连接登录阶段挂起 32s 超时
  (与 09-02 早轮偶发同症,杀 B 单独重启 4s 入服;机制未查,复发再究)。
- **远程疾跑姿差异实锤(B 观察 A)**:疾跑中枪姿前指沿跑向、束线平伸、光池落在
  跑向正前方;与②怠速下垂基线及 07:0x 轮"疾跑枪横持抬起、灯池甩向左上"明显不同
  = TaCZ 默认包 rifle_default.player_animation.json 的 run 姿态接管第三人称渲染。
  **捕获链零改动即跟随新动画姿**(束/池全程随姿,停止后回落)= "捕获所见"设计在
  动画系统下依然成立,③的灯侧结论=零适配成本。证据 docs/evidence/2026-09-02-playeranimator-sprint/。
- **坑67(预登记)**:本场景平台实域约 x∈[1995,2010],疾跑 8.4 格两次冲出东缘掉虚空
  (靠 /tp 回收);疾跑测试先探边缘留 3 格余量(W1500→W700)。
- 契约与主链零变化(③无代码路径改动);待用户体感:疾跑观感对比是否满意。

## 09-02 11:4x · 里程碑②闭环:TP 枪口捕获空间标定(Q·Ry180)+ 束向离体校正;步行跟随实机验证通过

- **TP 空间映射定案:与坑60 FP 同构 `世界 = 相机位 + Q_cam·Ry(180°)·v`**。上轮
  "TP 无 180° 翻转"假设(Q·v)被 DIAG-TP 实测推翻:落点偏枪口线 16.87 格,唯
  Q·Ry180 落 1.00 格(tools/tp-space-solve.js 五候选枚举,DIAG-REMOTE 实测位为真值;
  翻转源自 level 渲染栈 YP180,非手部渲染私有)。**捕获语义换代**:成对差值方向法
  退役(激光模块侧轨安装,根→束起点不沿枪管,73° vs +Z 列 28.8°),改直采
  origin=束起点平移(枪口)+dir=束骨局部 +Z 列归一(字节码 stringVertex z=0..length)。
- **修复后三重自洽**:mdir-look 夹角 26.5°(腰射下垂合理)、mpos 距眼 1.08 格
  (沿视线前 0.84/下 0.4)、|offRaw|=8.35≈|相机→眼| 8.32。端到端:光池亮白椭圆落在
  标定预测位(luma 3.15 倍),门探针示 composite 全门通过,A 本体 FP 对照正常。
  **主图**:束线自枪口发出、光池正落在束线×地面交点(透视投影逐点核算吻合);
  观察者换机位 mpos 逐位不变(世界锚定)。证据 docs/evidence/2026-09-02-tp-muzzle-calibration/。
- **新坑+修复(束向离体校正 alignBeamAway)**:A 步行后捕获 +Z 列偶发反平行翻转
  (mdir 103°/155°,取反后 76°/25°),位置链不受影响,灯照持枪者本人(实机截图)。
  物理不变式 dot(束向,枪口−眼睛)≥0 恒成立,uploader/DIAG 自校正,契约 5 项;
  修复后步行 mdir 保持 28.3° 前向、往返复现。根因上游(疑远程步行动画翻转束骨系,
  视觉束线疑同步翻)记开放项。
- **gun_light_display.json 补 third_person_length=18**(LaserConfig 字节码实锤键名),
  TP 束长与 FP 一致。契约:MuzzlePoseMath 31 项,AllContracts ALL PASS;新增
  RenderedEntityTracker+LivingEntityRenderEntityMixin(基方法注入,坑63)+GunModelRenderProbeMixin。
- 里程碑①(全枪械 allow 白名单+ak47 原始 NBT 兜底读,坑62)本轮一并实机验证收口:
  ak47 灯亮归属证据+hk416d 回归零偏差(见 09-02 前轮记录)。剩余开放项:束向翻转
  根因(B 端远程动画状态?)、TP 束线/光池体感验收、③playerAnimator 引入对比。

## 09-02 08:0x · 远程枪姿机制字节码核实:TaCZ 有完整同步+第三人称渲染链;配件提案②路线修正

- **用户质询"远程枪姿 TaCZ 不同步"→ 逐类读 tacz-1.1.8-hotfix.jar 字节码,结论:
  上轮提案中该说法有误**。三层证据链全部实锤:
  ①同步:`ClientMessagePlayerAim(boolean)` C2S → 服务端 `IGunOperator.fromLivingEntity
  (sender).aim(isAim)`(lambda$handle$0 字节码)→ TaCZ 自有 `entity.sync.core.
  SyncedEntityData` → `ServerMessageUpdateEntityData(entityId, entries)` 广播观察端
  (握手 `ServerMessageSyncedEntityDataMapping` 注册键);②数据:`LivingEntityMixin`
  把 IGunOperator 混入所有 LivingEntity,观察端可查 `getSynIsAiming()/
  getSynAimingProgress()`(0~1 连续)/`getSynSprintTime()`/`getSynReloadState()`;
  ③渲染:`HumanoidModelMixin`→`ThirdPersonManager` 对每个被渲染人形应用
  `IThirdPersonAnimation.animateGunHold/animateGunAim`(持枪/瞄准两套姿态)。
  **远程玩家并非单一固定持枪姿。**
- **提案②路线升级(零新增包,M5 兼容)**:`renderLaserBeam` 头部门禁字节码 =
  `attachment==null→return; !firstPerson && context!=THIRD_PERSON_RIGHT_HAND→return`
  ——TaCZ 刻意在第三人称渲染激光束(专属 lengthThird/widthThird),且
  BedrockGunModel/BedrockAttachmentModel 均在第三人称路径调用它 → 现有
  BeamRendererMixin 注入点在观察端渲染远程玩家时本来就触发,仅被我方
  `context.firstPerson()` 门禁挡掉。实施=去门禁按 context 分流+ThreadLocal 识别
  当前渲染实体(TaCZ 不传实体给 BETWR)+重标定第三人称捕获矩阵空间语义(坑60
  同款三重自洽,矩阵来自 level poseStack 而非手部渲染)。原"三档姿态近似"降级
  fallback。附带:gun_light_display.json 缺 lengthThird/widthThird(第三人称光束
  走默认长度),实施时补。

- **追加(用户追问疾跑枪姿):默认第三人称动画无疾跑姿态**。ThirdPersonManager$1
  (DEFAULT)字节码 = animateGunHold 纯常数角叠加(−0.3/0.8/−1.4 rad)+animateGunAim
  按 aimingProgress lerp,**零 sprint 输入**;getSynSprintTime() 读
  ModSyncedEntityData.SPRINT_TIME_KEY(已网络同步)但默认实现不消费。分流顺序:
  PlayerAnimatorCompat.hasPlayerAnimator3rd(可选模组,**本环境未装**→恒走原版回退);
  默认包自带 player_animator/rifle_default.player_animation.json 含 run_upper/lower
  +walk+crouch_walk 全套(**装 playerAnimator 即得第三人称疾跑姿态,TaCZ 官方数据**)。
  hk416d_display.json third_person_animation="default"。影响:提案②"捕获所见"路线下
  观察端灯枪恒一致;远程疾跑灯效幅度 = 固定持枪姿+原版摆臂(<FP 疾跑),差异属 TaCZ
  第三人称视觉行为,非灯链缺陷。补齐路线:装 playerAnimator(推荐,双端,零改动)/
  自研 TP 疾跑姿 mixin(改 TaCZ 视觉,超灯范畴,不建议)/灯单独加疾跑摆(制造灯枪
  不一致,违背"灯跟枪"原则,排除)。

## 09-04 · 手电三旋钮:亮度/距离/衰减手动调参(用户体感自助,已落码待提交)

- **用户需求**:当前效果"不是很正常,不符合直觉";不要 AI 代调,要三个零重启命令亲手扫出合适效果。
  有衰减系数(此前为 GLSL 编译期 `#define`,config 热改进不了着色器)——现经 SSBO cone.z 逐灯透传,零重启可调。
- **新命令**(文件中继,纯内存覆盖,重启清零;无参=status,off=回默认):
  `!bright 0.5..30`(绝对亮度,默认 6.0;与 `!lv` 档位互斥,后写者胜,建议只用一路);
  `!dist 4..96`(绝对照距/格,默认 36,bypass √亮度耦合,钳制 ≤radiusMax);
  `!atten 0.2..20`(衰减系数 K,默认 GLSL K=5.0;越小尾越长,0.5r 处约 44%..2% 亮度)。
- **实现**:新 `LightTuneOverride`(三路覆盖)+`buildSpotBeam` 接入(bright 在 lv 后取值/绝对半径/cone.z 透传,
  SSBO 96B 布局不动,保留槽此前恒 0)+GLSL `taclight_attenuation` 升三参(主包 composite/composite1/绿锥/surface
  与前向 surface 全消费 `L.cone.z`,≤0 回退编译期默认)+`DebugCommandRelay` 三入口。TDD:`LightTuneContract` 22 项
  (默认直通/越界拒绝不污染/SSBO cone.z 回环/收尾零残留)。
- 契约:TemplateLibrary 37 / InlineCore 41 / ScenePlan 207,`AllContracts: ALL PASS`。
- 本提交批量含坑99/100/101/102+证据包(forward-vox-uint/raw-albedo/shadow-fuzz/dual-leak)+SESSION 记录;
  第三方 `.ref-packs` 与 tmp 中继脚本不进提交(红线/清理)。
- 待用户:新构建重启双端后亲手扫参(`!bright/!dist/!atten`);下一步=合回主线(待执行)。

## 09-04 · 前向体素取数精度修复：方形假阴影消除（已修待用户目验，未提交未 push）

- **方形杂影定案=前向取数 float 精度丢失（坑102）**：用户体感报墙面/地面出现“不存在方块的
  阴影”、杂乱方形阴影出现在不该有的位置。与坑100横贯硬带不同机制：旧
  `taclight_vox_fetch` 用 `float(voxData[word])` 整字转 float 再除法剥槽——float 尾数仅 24 位，
  字值超 2^24 即舍入（Node 实算：50331651→50331652、4294967295→4294967296、
  50331649→50331648、16777217→16777216），空↔实心翻转=凭空多出/少掉整块方形阴影。
  修法=uint 域内逐槽剥除（`w/4u` + `w%4u`，小值转 float 精确，全程整数域）；
  slot=idx-word*16（与 Java VoxelField pack 同语义）。TDD：先加红断言
  （/4u+%4u 必备、禁 float 整字除法路径）再实现。
- **验证**：双端重拉（RESOLVED 进程缓存，!reload 换不上来），A/B 均注入 +7859；
  B（gunless）wall_back 开关对照全图 meanDiff **29.58**/maxDiff 175/changed 26.3%；
  热点 on mean **136.6**/p50 160.7/p90 182.4/**ge128=23255/ge200=0/ge250=0**——
  锥池居中柔和、无死白；on 帧白砖墙面干净，无方形假阴影。
  证据 `docs/evidence/2026-09-04-forward-vox-uint/`（README+off/on+log-excerpt+manifest，可复算）。
- 契约：TemplateLibrary 37 / InlineCore **39**（+2 坑102 断言）/ ScenePlan 207，
  `AllContracts: ALL PASS`。
- 收尾状态：双端运行中（LAN 25560，B 灯开，包启用；收尾/交用户前按 §7 关灯+禁包）；
  未提交未 push（等批准）；待用户目验（用户截图场景的方形杂影是否已消，本包为 wall_back 墙面验证）。

## 09-04 · 调用点反照率污染修复+失焦弹菜单修复（已修待用户目验，未提交未 push）

- **横贯硬边定案=调用点反照率污染（坑100）**：用户实机报灯锥区一条与灯无关的横向亮暗硬带
  （Complementary 前向注入改动后出现）。机制=宿主 DoLighting 把太阳阴影/月光/火把乘进
  color.rgb，调用点复用它作锥光 albedo = 宿主阴影二次放大。修法=DoLighting 调用前一行快照
  `vec3 taclightRawAlbedo = color.rgb;`，调用点改用快照（entities/hand 保留 `* color.a`）；
  注入 `+7859→+7903`（+44，快照行，日志实证）。
- **验证**：B（gunless）wall_back 开关对照 meanDiff **21.73**/changed 22.5%/maxDiff 161；
  on 帧锥池柔和居中、无横向硬切；热点 on mean **90.1**/p90 179.6/**ge250=0** 无死白。
  证据 `docs/evidence/2026-09-04-raw-albedo/`（README+3帧+log-excerpt+manifest，可复算）。
  TDD：先加红断言（调用点含快照名、禁 post-lighting color.rgb、禁 /2048.0）再实现。
- **失焦弹菜单修复（坑101）**：B 失焦弹 GameMenu 而 A 不弹=run-observer/options.txt
  pauseOnLostFocus:true（mp-setup 只管缺席新建不管已存在；MC 会回写默认 true）。
  修法=当场改 false + build.gradle taclightMpSetup 加 else 分支幂等修理（UTF-8 无 BOM）；
  实机 B 失焦 8 秒仍在游戏内（准星血条锥池俱在）。
- 契约：TemplateLibrary 37 / InlineCore 37 / ScenePlan 207，`AllContracts: ALL PASS`。
- 收尾状态：双端运行中（LAN 25560，B 灯开，包启用；收尾/交用户前按 §7 关灯+禁包）；
  未提交未 push（等批准）；待用户目验（用户截图场景的横贯硬带是否已消，本包为 wall_back 墙面验证）。

## 09-04 · 前向 DDA 掠边假阴影修复+back 关界面命令（已修待用户目验，未提交未 push）

- **阴影破碎定案=前向 DDA 掠边假阴影（坑99）**：A 视角墙面左半块黑色锯齿咬痕+阶梯齿；
  排除猪影/月影/灯位偏移后，机制=float 累积排序翻转（掠射末步进错邻格、墙体素即判 0）+ 零 FUZZ 硬归零，
  前向 tie eps 1e-5 过松。修法=照搬主线配方（tie eps 1e-6 + 穿透软化带 0.35 字面量内联，前向零预处理指令红线），
  墙后深穿遮挡基线不变（漏光 CLOSED 不受影响）。
- **验证**：灯开修前→修后 meanDiff **1.62**/changed 3.0%；枪灯严格对照（handheld=false gun=true）
  meanDiff **1.49**/changed **3.1%**/maxDiff 227 集中原咬痕区；post_on 黑齿消失只剩柔和左渐变；
  热点 mean **84.4**/p90 169.2/**ge250=0** 无回归无死白。证据 `docs/evidence/2026-09-04-shadow-fuzz/`
  （README+4帧+log-excerpt+manifest，可复算）。TDD：先加红断言（penLen+/0.35/T*=1.0-f/eps 1e-6）再实现。
- **back 关界面命令**：`DebugCommandRelay` 新增 `!back`（=setScreen(null)，与“回到游戏”同入口），
  补程序化缺口（坑96：此前菜单挡帧只能手点）；B 真实菜单事故中 RELAY back 日志确认自愈。
- **教训入库**：坑97（按窗口标题杀进程误杀宿主→只看命令行，宿主只许优雅停）/ 坑98（中继一次一调用逐条验 log；
  B 传送后必须 shot 目检机位；同机位重叠操作禁用）/ 小谜团记一笔（墙顶东沿 3 像素亮斑+猪排掉落物，场景卫生非渲染 bug，待拍板）。
- 契约：TemplateLibrary 35 / InlineCore 37 / ScenePlan 207，`AllContracts: ALL PASS`。
- 收尾状态：双端已由用户手动关闭（优雅退出，世界全存盘）；未提交未 push（等批准）；实例已关，目验需重开摆回约 8 分钟。

## 09-04 · 发射10活体感+实体不透明+漏光复核（三项全绿，待用户体感/拍板）

- **发射10锥池（活A=InteropA3，新角色）**：A侧on→off meanDiff **15.07**/changed **41.5%**；
  hotspot mean 85.1/p90 180.5/**ge200仅2/ge250为0**（亮但无死白）；与03:3x轮（89.8/182.8）基本一致→
  锥池亮度由SSBO锥主导，发射5→10几乎不动锥池、只补暖氛围。证据
  `docs/evidence/2026-09-04-emission10-entity/`（README+4帧+log-excerpt+manifest，可复算）。
- **玩家实体不透明CLOSED**：B贴脸看受照A（后脑/发片/躯干暖照实心、无透层）+猪/手部一致，`* color.a`修复成立。
- **漏光复核**：活A照墙时B看墙背仍黑，verdict维持CLOSED；bench开/关灯双双avgFPS 59.8（vsync上限，零可感开销），无阴影伪影。
- **新角色rollout通过**：InteropA3离线UUID文件`e4aea662…`逐字节验算一致，登录点世界出生点（无墙前机位现象，佐证v2§3纠错）；
  B后加入者生存→重跑scene转创造（坑95已按v2§3重写：entity-id推理收回+删档=重置+真新玩家标准）。
- 契约：Java核心全绿；FrameRecorder跨语言项红系本环境沙箱EPERM拦Node-spawn-Node（已定位，与代码无关，详见交接）。
- 收尾状态：A灯开+双端包启用（留给用户体感，未执行§7关灯/禁包）；LAN 25560开；未提交未push（等批准）。

## 09-04 · 双端墙体漏光验证：锥光不穿墙（反向验证闭环，待用户体感/拍板）

- **判定=正结果**：活人B持灯照墙背，B侧on→off meanDiff **18.73**/changed **24.5%**（锥工作）；
  A侧看对面on→off meanDiff **0.29**/changed **0.8%**=噪声。标量DDA遮挡成立，穿墙无锥池。
  证据 `docs/evidence/2026-09-04-dual-leak/`（README+6帧+热图+log-excerpt+manifest，可复算）。
- **穿墙亮区真凶**：前序“灯关还亮”=死客户端不吃S2C、A物品发射等级卡10（宿主heldLighting无遮挡直照），非SSBO锥漏。
- **新坑94（cam机位≠灯方向，待立项）**：灯跟玩家头不跟相机；A相机yaw180头留出生朝向→锥照身后，A端墙面帧全降级为参考。B头/机一致→B侧结论有效。
- **A端5连死**（06:41蜘蛛旧+09:53/10:09/10:16/10:21 fresh重生数分钟内死；和平已落盘Difficulty=0仍死→非怪；登录点恒为墙前机位可疑）。死亡调查按用户要求冻结，不阻塞本结论。
- 契约：改动相关三组绿（TemplateLibrary/InlineCore/ScenePlan）；AllContracts尾部FrameRecorder+rec-analyze红系沙箱EPERM拦Node管道（环境限制，与代码无关）。未提交未push（等批准）。

## 待办 tickets(用户 09-04 立)

- **T1 枪灯手动开关**(用户要求先备忘不实现,键位未定):现状枪灯=每 tick TaCZ 探针
  读主手枪 LASER 槽(装 taclight:gun_light=开,无=关),`!gun` 调试翻转会被探针覆盖。
  需求=玩家可手动开关(无附件也能强制亮?与探针的优先级?键位待定)。涉及
  ClientLightState.gunManual 旗+探针/S2C 覆盖规则(主线 09-03 8019aca 同款模式)。

## 09-04 · Complementary r5.9 前向注入命中+锥池可见(用户问 iterationT 自适应曝光影响)

- **状态=正结果**:`interop injected family=complementary (+4543 chars)` ×2(两份片元
  半体:terrain 271068 + translucent 285528 变体),零崩溃,灯开后草地+土墙见柔和锥池
  (近亮远暗),灯关即消失。证据 `docs/evidence/2026-09-04-complementary-inject/`
  (README+comp_v2_off/on+log-excerpt-v2+manifest.sha256;旧 comp_off/on 留作零注入 AB 对照)。
- **判定数字**(grass_low,hotspot bbox 280,190,560,340):off mean 21.2/p90 33.9/ge200 68;
  on mean 89.8(×4.2)/p90 182.8/ge128 68→14185;全图 meanDiff 30.63/changed 228224/409920。
- **本轮根因=单规则双锚点跨半体注定 miss**:patchSodium 6 入参按顶点/片元分半到达
  (顶点半体 175k/246k 有顶点锚无 DoLighting;片元半体 271k/285k 有定义+调用点无顶点锚),
  旧模板双锚点绑一条规则=恒一锚缺席=all-or-nothing 全 miss。修=片元单规则
  (extension 开 SSBO + DoLighting 定义前文件域内联 + 调用后加性锥光)。
- **版本定案**:SSBO 用 `#extension GL_ARB_shader_storage_buffer_object`(宿主同式),
  不抬升 130→430(430 杀宿主 texture2D/varying 兼容路径);旧"注释锚被剥离"结论有误
  (落盘两半体皆含 //Program//),坑91 已修正。
- 契约 `AllContracts: ALL PASS`;新坑 **93**(版本抬升杀兼容路径)入册;坑91 结论修正。
- 待用户体感:Complementary 下锥池是否自然、有无 AE 回压感;下一步候选=冻结回主线或
  继续 entities/hand 钩子(地形锥池已闭环,按需再挂,保持混入面最小)。

## 09-04 · Complementary r5.9 前向注入首轮(负结果,已超驰:见上条正结果)

- **状态=负结果**:零崩溃、零注入——`interop injected` 日志从缺,off/on 截图逐位一致
  (hotspot mean 21.9→32.7 系手持模型位移噪声,ge200 恒 68),SSBO handheld=true 有数但
  画面无锥池。证据 `docs/evidence/2026-09-04-complementary-inject/`(README+off/on+
  log-excerpt+manifest.sha256)。
- **AE 对照(源码级,待实机确认)**:iterationT composite 注入在 AE 采样环内
  (MotionBlur GetExposureTiles→colortex2.a→Final GetExposureValue 全局 exposure),
  AE 回压手电;Complementary=手动 Lottes tonemap(TM_EXPOSURE=1.00,无自适应),
  前向注入成功后预期不吃反馈。
- **根因链**:①钩子 miss(patchComposite 不覆 gbuffers,已加 patchSodium 6 钩子);
  ②gbuffers AST `missing ';' at '{'` 三连(减重 21k→4.4k 行号仍随动,函数体内声明与
  AST 冲突;前向精简去 SSO/GGX/绿锥/体素 DDA,恒可见桩,遮挡交宿主);
  ③当前卡点=`//Program//` 注释锚在 patchSodium 输入侧不存在(jcpp 剥离注释)→静默零
  注入,已改顶点 main 体真分支锚(GetLightMapCoordinates,selectorCount=1),契约绿但
  **实机未验证**(实例已停)。
- 契约 `AllContracts: ALL PASS`(TemplateLibrary 29+InlineCore 34 含前向 18);
  新坑 **90**(gbuffers AST 体内声明)/**91**(注释锚剥离)/**92**(双实例存档锁僵尸)入册。
- 待用户拍板:是否继续投实机验证(重启单实例+off/on,预期有锥池+injected 日志);
  或先冻结 Complementary,回主线。

- **根因**:调用点直接加物理 radiance,宿主 composite 尾部 `/=MAIN_OUTPUT_FACTOR
  (=2048)` + LinearToCurve——宿主内光照是"输出前量纲",高千倍饱和糊死。
  修复=调用点 `(radiance×GAIN/2048)→shoulder3(T=0.55/Q=0.15,本家包同参)`。
- **衰减压近场**:TACLIGHT_ATTEN_K 2.0→5.0(0.5r 处 50%→20%;端点/远场尾部不动,
  只改中段肩部)。hotspot ge200:12473→334(−97%),饱和归零,草叶/砖墙/远景全可读。
- **!lv 档位覆盖层**(LightLevelOverride+relay,10 项契约):CLIENT config 热改 toml
  不回读——覆盖层供零重启体感扫参(lv d→intensity=6·2^-d,radius √自耦合);
  实证 lv2/3/4 画面逐位一致(亮度由 shoulder 参数决定,SSBO 已在线性段外),
  反推旧 radiance 在肩部之上 ≈2.6×。重启=覆盖清零。
- 契约 `AllContracts: ALL PASS`;证据 `docs/evidence/2026-09-03-interop-true-flashlight/`。
- 新坑:**坑88**(CLIENT config 游戏内热改 toml 不回读,重启才生效;亮度对照走 !lv
  覆盖层)、**坑89**(`/` 行原版命令在 dev 客户端被本地预解析拒,scene 预设的
  setWeatherParameters 才是可靠晴天路径)。
- 待用户体感:最终柔和版是否自然(近亮远暗+远景可见);下一步候选=Complementary 族模板。

## 09-03 03:1x · 里程碑2(方案C 运行时注入引擎)实机全链闭环,验收 1-7 全 PASS

- **引擎上线实机**:mixin 挂 Oculus(oculus-1.8.0)`TransformPatcher.patchComposite`
  4 个 String 入参 → `RuntimePackInjector.patchSource`(指纹=包名+关键文件哈希 →
  模板 JSON(路线 P 格式:replaceFirst/insertBeforeLine/insertAfterLine+`<INLINE_CORE>`)
  → iterationT 3.2.0 注入成功 `+20852 chars`,管线编译零报错。冻结午夜 smoke:
  off=全黑夜景 / on=锥形光池,imgdiff meanDiff=36.43 maxDiff=242 changed=45.0%
  (首轮 wj 对 37.30/40.8% 同量级互证)。验收 1-7 全 PASS:补丁包零静默禁用 /
  injected 日志 / 灯开关像素差 / 视觉正确 / 本家包回归(原生链照常+零注入)/
  未知包安全(零注入+一次性提示不刷屏)/ 幂等(连续两次 !reload 每次管线重建
  注入恰一次,字节数恒定)。证据 `docs/evidence/2026-09-03-interop-runtime-inject/`
  (README 判定表+timeline+composite-dump 运行时文本取证+manifest.sha256)。
- **契约**:新增 65 项(TemplateLibrary/InlineCore 16/PatchExecutor 20/RuntimePackInjector/
  PackFingerprint 等),探针清理后复跑 `AllContracts: ALL PASS`。
- **新坑 4 条(坑位册 53-56)**:坑82 mixin 门控禁 Class.forName 目标类(prepare 期
  抢先加载→零 mixin 缓存静默失效;字符串 targets 自守卫);坑83 patchComposite 输入=
  jcpp 预处理文本(锚按运行时实测文本写/注入文本指令全解析+零 uniform/注入点在宿主
  uniform 声明后);坑84 一次性亮帧异常归因存档(不可复现不阻塞,再遇先查首建灯态);
  坑85 CHM 禁 null value+宿主管线回调必须 fail-safe(异常=原文返回零注入)。
- **工具**:interop 三脚本(interop-session/stop/interop-smoke)落盘可复跑;
  零重启模板迭代法(per-packName RESOLVED 缓存+手写 build/resources 模板+sed
  oculus.properties+!reload)本轮 5 次 A/B 消融零重启完成。
- **待用户**:体感验收 iterationT 注入观感(灯色/强度/tonemap 二次调色与离线派生包
  时代的差异);下一步候选 = Complementary 族模板(需用户拍板优先级)。

## 09-02 23:x · 里程碑2 批准开工;计划文档落定;系统维护暂停待就绪信号

- 用户批准开工,并指明"多参考 Iris 官方 DH 兼容的已验证实现"。实施蓝图 =
  `docs/方案C-运行时注入引擎计划.md`(架构/模板格式 v2/契约清单/实机验收 7 项/
  风险 8 项/包升级跟版 SOP)。写文档阶段追加钉死的事实:patchComposite 调用方
  字节码实锤 = CompositeRenderer(composite/deferred)+ FinalPassRenderer(final),
  签名 (String×4, TextureStage, map);taclight_surface_lighting 签名
  (fragView, albedo, n, roughness, metal, f0);外包 prelude 必须定义
  TACLIGHT_LIGHT_GAIN(core 无兜底),TACLIGHT_OCCLUSION_AT 不定义=core 默认 1.0
  保守(遮挡主路径=体素 DDA,包无关);内联文本 = math+core 拼接去 include 行。
- **暂停**:电脑维护,不起实例/不跑构建;就绪信号后按计划 §9 顺序开工(契约红→引擎→
  mixin→iterationT 冒烟→三重回归→证据包)。

## 09-02 22:3x · 里程碑2(运行时注入引擎)调研收网,提案待批

- **调研结论(网络案例+本地 jar 取证)**:①Iris 官方 DH 兼容就是"per-family GLSL 补丁"先例
  (补丁文本按 dhTerrainVsh/Fsh 命名进 ShaderProperties,DH_SHADER 指令守门=未声明的包零改动;
  坑:旧 OptiFine 式 option 指令曾让补丁判定反转失效,Iris PR #2493);②Euphoria Patches
  (Complementary 补丁层)证明 per-family 维护可行但每包升级要跟,版本必须配对;③本项目
  路线 P 的 PackPatcherTool(git 0a98400)已有成熟补丁格式:JSON+锚点唯一+marker 幂等+
  addFiles/insertBefore/insertAfter/replace,当年实机在 Oculus 下加载成功。
- **关键本地事实**:实例依赖实际是 **oculus-1.8.0**(Iris 1.7.x 移植,非传言的 1.7.0);
  jar 里 `TransformPatcher.patchComposite` 为 public static=deferred/composite/final 统一
  转换入口,即理想注入钩子(gbuffers 走 patchVanilla/patchSodium,不碰);DH 补丁机制
  (iris/compat/dh/DHCompat)同在;glsl-transformer 在 classpath。自家包= `#version 430 core`
  + `layout(std430, binding=7)` 源内直声明,实机已验证可行。
- **提案(待用户批准,未动工)**:mixin patchComposite(Inject RETURN 改写返回 map)+
  指纹(shaderPack 名+关键文件哈希)→ 模板库(路线 P JSON 格式复活,taclight_core 文本
  内联免 #include);未知包=零注入+一次性提示(设计即安全,坏指纹自动降级)。
  首目标=Complementary 族(冒烟可先复活 iterationT 旧模板)。代价:引擎~1天,
  每族模板 0.5-1 天且包升级需跟。风险:patchComposite 调用路径需冒烟实证、#version
  升级个别包不兼容(该族标不支持)、注入后 Iris 静默禁包(注入后预校验)、tonemap
  位置错=二次调色(像素判定防)、Oculus 升级需重验(混入面仅 1 类 1 方法)。

## 09-02 21:2x · interop 实机回归闭环(像素恒等)+ 独立实例与程序化测试通道

- **实机回归(上一条目的待办,已闭环)**:独立实例 run-interop 上,基线包
  (c3bb689)vs 重构包(5302eab)同场景同机位冻结午夜对照,**干净四对
  imgdiff:草地开/关灯逐位零差异(changed=0/409920),走廊开/关灯各仅
  4px(0.001%)且为 3×2 固定点、开/关灯同位同幅=与光影无关的外来元素**。
  热重载 ×3 全部 `RELAY Iris.reload() ok` 零编译错误。证据
  `docs/evidence/2026-09-02-interop-live-regression/`(8 图+imgdiff 输出+
  manifest.sha256)。**方案 C 第一里程碑(核心剥离)实机零回归,可合并。**
- **独立实例(run-interop)**:build.gradle 新增 `clientInterop` run 配置
  (parents=client;quickPlay 靠继承勿复写,坑73)+ `syncShaderPackInterop`;
  世界 = PROBE 存档克隆(停更存档零写入风险);`ops.json`(InteropA level 4,
  UUID=playerdata 文件名,坑74);启动 = `gradlew-interop.cmd runClientInterop
  -PtaclightQuickPlay=interop -PtaclightUser=InteropA`(gradle 缓存复用主仓,
  坑61)。里程碑 2 运行时注入的换包测试就在此实例做。
- **测试驱动程序化(用户明令,坑76)**:drive.ps1 postkey 会前台激活目标窗口
  → MC 抓鼠标+抢用户焦点(用户实测被锁鼠标)。改为:中继新增 `!shot`
  (Screenshot.grab 直读主帧缓冲,与 F2 像素等价);`/` 行改走
  `sendUnsignedCommand`(绕过 dev 客户端不完整命令树本地预解析,原版命令
  /time /gamerule 全通,坑74);中继批发同 tick 多命令会被服务端反刷屏踢出
  (坑72,一条一写 ≥0.9s);单机 GUI 不吃后台键盘(坑75,恢复=杀进程
  quickPlay 重启)。AllContracts ALL PASS 复验。
- 分支:`interop/core-extract` @ 5302eab(git worktree wt-interop)。

## 09-02 08:3x · interop 核心剥离:照明核心与包私有编码分层(方案 C 第一里程碑)

- **背景与决策**:为让玩家用其他光影包时保留锥形照明,方案 C 定型为**运行时注入**
  (模组内 patch 引擎把核心 GLSL 注入玩家所选光影包;未知包系提示不支持),
  替代"事前工具生成修改包"。第一里程碑 = 把跨包可移植的照明核心从本包私有
  编码中切出。HandheldMoon 调研结论支撑路线决策:动态光照路线(CPU chunk
  重建 churn + 0-15 级灰度量化 + 泛洪漏光)视觉上限是硬的,只配做兜底不做主线。
- **新分层**(pack/shaders/lib/):
  `taclight_math.glsl`(IGN/HG 公开数学,core 与 style 共用)→
  `taclight_core.glsl`(**零依赖照明核心**:SSBO 契约+坐标换算五函数+衰减/软膝/
  肩部/GGX+SSO+体素 DDA+绿锥+表面照明主循环 `taclight_surface_lighting()`;
  仅依赖 Iris 标准 uniform+depthtex1+SSBO,**禁止 colortex 字面量**)→
  `taclight_adapter.glsl`(本包私有:colortex3.a 遮挡系数经
  `TACLIGHT_OCCLUSION_AT` 宏注入 core,默认回退保守 1.0;量纲标定
  `TACLIGHT_LIGHT_GAIN` 移入——移植到其他包时**整文件替换+重标定**)。
  `taclight_common.glsl` 改纯聚合头(adapter→core→gbuffer→style),
  对 composite 家族接口零变化;gbuffers/final 零改动。
- **契约**:新增 `ShaderCoreContract`(31 项)钉死分层边界(core 零依赖/
  宏注入点/聚合顺序 adapter 先于 core/无残留双份定义/消费 pass 不残留照明门);
  `VoxelDdaContract` 的 GLSL 文本断言跟代码迁至 core(断言内容不变)。
  先红(旧结构上 FAIL 实证)后绿:**AllContracts ALL PASS(495 checks)**。
- **实机回归(待做)**:双实例当前被并行任务占用(08:15 场景布防中),
  待空闲后 sync+!reload+截图回归;行为预期零变化(纯重构,逐行搬运)。
- 分支:`interop/core-extract`(git worktree wt-interop,与其他任务隔离)。

## 09-02 07:0x · 遮挡静态回归关闭 + 枪灯坑60修复(姿态跟随实机验证);配件适配提案就绪

- **遮挡静态数字回归(悬置项)关闭**:在 FUZZ 0.35+坑57+坑58 三重变更下复测 09-01
  同协议,四项判定全吻合——北漏光 off/on 82.2/44.3(历史 82.3/44.3,DDA 后=无灯
  基线 43.7)、南直射池 64.5 逐分位恒等(历史 64.5)、imgdiff N 14.996/40.0%
  (历史 14.94/40%)、S 2.623/3.19%(历史 2.58/3.2%)。**零回归**,
  证据 evidence/2026-09-02-occlusion-static-regression/。
- **坑60 修复(TDD,姿态契约 11→18 项,AllContracts ALL PASS)**:枪灯姿态跟随链
  (BeamRenderer 捕获→MuzzlePoseCapture→上传器)自接入以来首次实机验证方向,即发现
  上传方向恒为 −look、灯位在眼后上方 1.5m。根因=捕获矩阵是枪渲染空间(GL 视图,
  −Z 为前)而 `Camera.rotation()` 是 MC 约定(+Z 为前),正确换算 = **Q_cam·Ry(180°)·v**,
  旧实现用 conjugate。字节码反编译锁定机制(injection 点在骨变换之后、stringVertex
  光束沿骨局部 −Z),修复后 DIAG dir 逐分量等于视线。**枪渲染空间换算一律走
  MuzzlePoseMath.gunViewDirToWorld,禁止裸 conjugate。**
- **kit 预装附件**:`/taclight kit` 经官方 API `IGun.installAttachment` 把
  taclight:gun_light 直接装上 HK416D(原先发散件需改装 UI 手动装,后台鼠标注入
  无效=自动化死路,坑41)。
- **实机验证**:腰射灯池=枪口指向的前下方;疾跑(双击 W)枪横持抬起、灯池甩向
  左上方——姿态跟随确认。证据 evidence/2026-09-02-gunlight-pose-following/。
  ADS 需按住鼠标右键,后台注入无效(坑41),留用户体感(机制与疾跑同链)。
- **坑59 入册(§6 条 45)**:K 键(本模组霓虹调试)与 Iris/Oculus"切换光影包"
  默认键冲突,按 K=禁包;游戏内禁包后 `!reload` 不恢复,且 Oculus 落盘坏文件名
  `config/\oculus.properties`(正确文件未被改)。恢复=删坏文件+再按 K 翻回+`!reload`。
  待办:DEBUG_TOGGLE 改绑非冲突键(涉及用户肌肉记忆,先登记不动)。
- **收尾**:双端关灯+光影包禁用(oculus.properties enableShaders=false+reload,
  双端日志 "Shaders are disabled" 确认)。
- **下一步 = 枪械配件适配里程碑(用户 09-02 提出,方案待批)**:核心链已实装并
  验证(附件+枪姿捕获+坑60 修复);缺口=①全枪械 allow_attachments 白名单(现仅
  hk416d)②第三人称/远程枪灯近似改进(现眼位+look×0.45,无远程枪姿数据)
  ③可选:头盔/独立物品形态。提案详见会话汇报。

## 09-02 05:5x · bob 跳位链闭环:用户终验通过(坑57+坑58);复盘文档+临时数据清理

- **用户终验:"实测没啥摇晃的视觉瑕疵现象了"** —— 坑57(远灯跳位)+坑58
  (自灯影子消失/闪烁)双修复闭环,出厂配置下无已知视觉瑕疵。坑58 附带的
  手持灯真影行为一并验收通过。
- **收尾(按 §7 升级规则)**:双端聚光灯已关;**双端光影包已禁用**
  (`oculus.properties enableShaders=false`+reload,日志 "Shaders are
  disabled");下次实测前改回 true+`!reload` 拉起。
- **复盘学习文档**:`docs/复盘学习-bob影子跳位战役-2026-09-02.md`(用户指定
  学习用途:项目推进/工具价值/工程思想映射/AI 协作复盘)。
- **临时数据清理**:删除 mcap 自动录制帧 58438 张(保留 s0001 坏协议样本与
  recorder-canary 引用的 run-20260902-012910-105-p18948/s0002 原始图)、
  run*/screenshots 旧 F2 截图 259 张、tools/.session 分析中间图 70 张;
  证据包 docs/evidence/(124M)全部保留。

## 09-02 05:4x · 自灯影子回归修复:同轴快速通道视图域阈值被 bob 平移调制(坑58)

- **用户实测坑57 修复"生效一半"**:远灯(别人静止灯)影子跳位已消 ✔;自灯影子
  "不显示+移动闪烁" ✘。机制:composite.fsh 同轴快速通道
  `dot(lightView,lightView)<0.25`(灯距相机 <0.5 格 → vis=1 跳过遮挡,热修 12)
  ——坑57 修复后 `lightView` 含 bob 平移 ±0.1,自灯锚点(手持 0.4365/枪灯
  ~0.6)恰在 0.5 阈值两侧,灯距随步频翻转 = 影子时有时无;远灯 »0.5 永远走
  DDA 不受影响,故呈"修一半"。
- **修复(TDD,25→29 契约全绿)**:①体素 DDA **无条件先执行**(世界空间射线,
  灯≈相机依然有效:起点格先步进后判定、终点格回退 1e-3 双端豁免);②同轴
  判定改**场景域** `dot(lightScene,lightScene)<0.25`(world−camera 无 bob 恒定),
  且只作 DDA 无效(-1)时的回退豁免。附带:手持灯(旧版从不投影)现在也投
  真实影子。坑58 入册(两条规则:几何阈值判定用场景域;快速通道不得跳过主算法)。
- **实机**:AllContracts ALL PASS;sync+双端 reload 0 编译错(用户已自行禁用
  光影,本轮重新拉起);真机截图自灯灯池中方块投影清晰
  (evidence/2026-09-02-selflight-shadow-regression/,截图后双端灯已关)。
  **待用户体感终验:开摇晃步行,自灯影子稳定存在不闪烁,远灯依旧不跳位。**
  静态数字回归(漏光 ROI/直射池)仍未补跑,下轮布防优先。
- **收尾规则升级(用户明令)**:空闲实例**禁用光影包**(只关灯不够,包本身有
  负载)→ AGENTS §7。

## 09-02 05:1x · bob 跳位真因修复:view→world/scene→view 换算丢 bob 平移(坑57)

- **用户观察"影子相对画面跳位(非整体晃)"指对**。差分判决(影界−石柱,消刚体
  公共摆动):bob 开 7.2-7.4px vs bob 关 2.3-2.7px(噪声底)——影子相对世界真实
  跳位,幅度=bob 平移 ±0.05 格。上一轮"透视呼吸物理正确"结论已撤回
  (evidence/2026-09-02-bob-sway-verdict/README 顶部更正)。
- **真因**:`taclight_view_to_world` 用 `transpose(mat3(gbufferModelView))`、
  `taclight_scene_to_view` 用 `mat3(gbufferModelView)·scene`——gbufferModelView
  实为 R·T 含 bob 平移(旧注释"R-only"错误前提),mat3-only 换算丢平移 →
  DDA 阴影/锥判定/距离随步频抖动。体积束(composite1)同函数,一并修正。
- **修复(TDD)**:契约先红(4 条坐标换算契约,VoxelDdaContract 20→24 项)→
  全矩阵形式 `(gbufferModelViewInverse*vec4(v,1)).xyz` /
  `(gbufferModelView*vec4(s,1)).xyz` → `AllContracts: ALL PASS`。次坑:漏声明
  `uniform mat4 gbufferModelViewInverse` = 编译错 C1503 + Iris 静默禁包(实机
  踩中,已补声明并入契约)。坑57 入册(§6 坑位册)。
- **实机**:双端 oculus.properties `enableShaders=true` + `Iris.reload() ok`,
  pack=TACLIGHT_PACK、0 编译错。**待用户开视角摇晃实测:看别人静止灯,影子
  不应再相对画面跳位(只剩整体刚体摆=正常)**。静态数字回归(漏光/直射池)
  在 FUZZ 0.35+本修复下仍未补跑,下轮布防优先。

## 09-02 05:0x · bob 晃动判决定案(用户判决实验+同帧摆动测量+数据流排除)

- **定案**:出厂功能下"看别人静止灯,步行时地面影子随步频晃动放大"的唯一触发
  条件=**视角摇晃(view bob)**。用户双实验:关摇晃→消失;跳跃(bob 离地衰减)
  →减轻。四臂 ABBA(bob×voxel)+本次判决互证。
- **用户假说"bob 误作用到他人光源(延迟补偿过冲)"已排除**:①双端补偿全关消融,
  条纹依旧;②延迟补偿输入=网络实体数据,bob 只进投影矩阵,数据流无交点;③光位
  L0 全程 238 采样 range=0+DIAG 19 分钟间隔逐位同——光源世界系静止,影子图案
  世界系静止。
- **同帧摆动测量**(s0005,bob on+voxel on,81 帧,`evidence/2026-09-02-bob-sway-verdict/`):
  影界横向摆动 rms 6.7-7.0px,世界特征(石柱/砖缝)3.5-6.7px,同量级;与 bob
  公式预测的相机重投影幅度(±5-11px@3-5m)吻合。影界/石柱比 1.34=掠射角放大
  (投射几何,非 bug)。跟踪噪声底 ±3px 已声明;定案不依赖精细比值(通道已被封死)。
- **机制**:bob=相机步频平移±5-10cm+微转 → 画面透视呼吸;影子=对比度最高+视角
  最掠射的图案 → 同样位移在它上面最显眼。影子"晃得比世界多"=显著度差异,非位移差。
- **改进空间(待用户拍板)**:①自己举灯→`!lanchor bob` 消融旋钮(灯锚/灯向跟随
  bob,头灯物理,需新代码 TDD);②看别人的灯→物理正确无正当消除手段,选项=关
  摇晃(正当)/FUZZ 降对比(已 0.35)/接受;③bloom 脉动放大嫌疑可用 DBG5 隔离验证。
- **流程**:测试收尾关聚光灯已入 AGENTS §7+记忆(用户明令);本轮已执行
  (`/taclight light off ObserverB`,ssbo count=0 回显)。


## 09-02 条纹根因定位 + DDA tie/软化带修复(四臂消融实证;待用户体感验收)

- **根因(帧证据定案)**:用户报告的"地面条纹随视角晃动节奏放大" =
  **体素 DDA 算出的墙柱硬影**(正确遮挡输出;SSO 时代同区域漏光把柱影糊掉所以
  不可见,算法更新后显形——用户"与算法更新有关"的判断正确)+"硬 0/1 影缘在
  行走 bob 亚像素视差下的采样翻转/闪烁"。排查链:四臂消融只有 on/on 异常 →
  tie 红灯修复不彻底(34.1→27.5) → fuzz0.08 无效(30.8) → 帧证据三连
  (胶片颗粒 ±10 让原始差分不可用;相位平均差分 A1≈B1 排除时间性脉动;
  p90 波形 u≈2.9 空间悬崖 214→63 vs SSO 212→160) → 帧目检确认条纹=柱影、
  |bob| 相关是位置混河假象。
- **修复两处,契约钉死(`VoxelDdaContract` 20 项,含 GLSL 源码字符串契约)**:
  ① `taclight_vox_transmit` tie 语义改为统一 crossing time 一次推进全部 tied
  axes(旧单轴分轮会访问射线仅擦边未穿入的侧邻格=假阴影边界);新增纯 JVM
  几何 oracle `VoxelDda` 与 GLSL 逐语义镜像。② 实心格穿透软化带
  `TACLIGHT_VOX_FUZZ 0.20`:按射线在实心格内穿透长度放行,≥0.20 格仍严格
  T=0(墙后遮挡内部不变),掠边部分透射=影缘 0.2 格半影,把 bob 视差采样跳动
  从全幅翻转压成半影内滑动;树叶/植被整格语义、端点豁免、负方向、边界不变。
- **实机四臂 ABBA(同场景同步行,`!rec` 自动开窗,16 会话全 `REC-ANALYZE PASS`)**:
  on/on 臂 p90 去趋势残差 RMS **34.07→20.17(−41%)**、|bob| 相关 **0.407→0.173**
  (tie→+0.20 两步累计);三对照臂全程 8.4-13.8 不变;L/R 不变量 4 臂全程 0 违例。
- **DDA 回归全绿(09-01 同协议同机位)**:北视墙后漏光 ROI mean 82.1(off)→
  **44.3(on)=无灯基线 43.7,漏光修复保持**;南视直射池 off/on 逐位恒等
  (64.5/p90 207.7);`!bench` 59.8fps/1%low 55.9 与 09-01 持平。
  `AllContracts: ALL PASS` + `TOOLS-SELFTEST PASS`。
- **配套调试设施**:`!bob on|off|status`(`BobViewControlContract` 7 项,写
  options.bobView,仅调试用);证据 `docs/evidence/2026-09-02-dda-bob-stripe/`
  (README 判定数字 + 4 构建×4 臂 CSV + 帧证据截图 + manifest.sha256)。
- **软化带 0.20→0.35(09-02 04:0x 体感轮,预授权 0.30-0.40 内)**:用户两条关键
  实测——①步行条纹放大仍在;②**跳跃前进时不明显**(原版 bob 振幅离地自动衰减,
  落地恢复)= 步频 bob 摇晃 × 硬影缘残留闪烁的直接证据,与四臂消融 bob on/off
  结论互证。加宽半影带压边缘时间对比度;深穿透(≥0.35 格)仍 T=0,墙后遮挡、
  直射池、叶/植语义均不变(VoxelDdaContract 20 项全绿,掠边契约改为穿透长度
  守恒式,不再硬编码透射率区间)。双端 `Iris.reload() ok` 03:57:03。
  **待用户体感复测(步行 vs 跳跃对比)**;静态数字回归(漏光/直射池)待场景
  空闲后补跑。本轮同时确认:用户在 A 端举灯走时,延迟补偿(psnap/bsnap/extrap)
  不在其视角链路内(本地灯直连),该组旋钮只影响观察端;B 端仍保持全关。
- **待用户体感验收**:行走看 Dev 静止灯池。半影嫌软 → `TACLIGHT_VOX_FUZZ` 降
  0.12;仍有节律 → 升 0.30-0.40(sync+`!reload` 即生效)。p90 残差 20 不会降到
  基线 9:剩余=空间悬崖(柱影本身,正确遮挡),非时间缺陷。
- **用户新观察(09-02 03:33,验收中)**:放大规律与**移动方向相关**——方向相关性
  是远程姿态重建链(extrap/psnap/bsnap)的典型指纹,非纯 projection 视差特征。
  已应要求把 B 端延迟补偿全关(`!psnap off`+`!bsnap off`+`!extrap off`,日志回显
  确认)供用户复测;方向相关性在补偿全关后仍存在 → 排除延迟链,坐视差/DDA;
  若消失或形态变化 → 延迟链涉案,重启 `!rec` 单因素隔离 psnap/bsnap/extrap。
- **新坑入册(§6 坑55/56)**:坑55=整段亮度极差/去趋势残差会被"空间悬崖+胶片
  颗粒"伪装成时间脉动,判定须先做相位平均差分与逐帧波形;坑56=`drive -ProcId`
  目标窗口不存在时回抓前台窗(mp-session 对 B 误截图),截图前必须核对窗口标题。
- 旧因果更正落定:9530b09=锚点行为事实;"vanilla bob 写 Camera.position"已推翻
  (bob 只进 projection),本轮帧证据与该前提一致。

## 09-02 凌晨(!rec 可信录制器实机通过;已作为独立里程碑提交 d9a0017)

- **schema/集成门已对齐**:`S/F=type,t,frame,seq,filename...`;footer `dropped=ΣD.count`。
  Node fixture 改为生产布局并加入 D 两行/count 总和 5 的反漂移用例；Java
  `FrameRecorderContract` 直接产出 CSV+PNG 后调用真实 `rec-analyze.js`，防止两边各自绿。
- **生产协议**:唯一 run/session 目录、ShotToken、显式 `shot-%06d.png`、C/L/R/P/S/F/D、
  pending 清零后 footer、纳秒 deadline accumulator、每渲染帧唯一采样、world-unload 幂等
  封口；header 固化 `targetFps`，分析器严格核对 footer/token/PNG 精确集合和渲染帧单调性。
- **双端实机 canary(ObserverB 走动,Dev 远程灯静止)**:目标 60fps，最终会话
  `run-20260902-012910-105-p18948/s0002` 为 **138 C/P/S + 138 PNG、F=0、D=0、
  2.292s、实际 59.773fps**；无 ≤5ms 双采样；L 灯锚/方向与 R 远程显示位置 range=0。
  严格分析器 `REC-ANALYZE PASS`。证据 `docs/evidence/2026-09-02-recorder-canary/`。
- **权威门**:`REC-ANALYZE-TEST PASS (56)`、`FrameRecorderContract ALL PASS (22)`、
  `MotionCaptureContract ALL PASS (20)`、`TOOLS-SELFTEST PASS`、`AllContracts: ALL PASS`。
- **启动器顺手修复(同一调试基础设施范围)**:`session/mp-session` 改走规定的
  `gradlew-java17.cmd` 并显式 `-p taclight`;A 子会话失败向父级传播；B READY 改判
  客户端真实 `LIGHT-SYNC-ACK`，不再等待只出现在服务端的 `logged in with entity id`。
- **因果注释纠错**:保留“手持/fallback 锚玩家眼位”的行为事实，明确 vanilla bob 位于
  projection、不写 Java Camera.position；录制器完成不代表条纹根因已确认。下一步才加
  `!bob` 并执行 bob×voxel 四臂 ABBA，证据前仍禁止改 DDA。

## 暂停点 · 09-01 深夜⑥(!rec 可信录制器已实现,待 schema 对齐+实机 canary;条纹根因未改)

- **用户目标**:先把调试环境提效成 `!rec`——布防后本地/远程一运动即自动开窗,
  按目标 60fps 截最终画面并逐渲染帧记录 C/L/R,双通道静止后自动封口;再用它定位
  “观察者移动时地面条纹随视角晃动节奏放大”,确认根因后才修渲染算法。
- **关键纠错(本地 1.20.1 Forge/Oculus 1.8.0 字节码已核实)**:`GameRenderer.bobView`
  只修改 projection 用的局部 PoseStack,不写 `Camera.position`/Java camera rotation;
  Oculus `cameraPosition` 仍读 main Camera。故 9530b09 的“手持/fallback 枪灯改锚玩家眼位”
  是真实改动,但“vanilla bob 注入 Camera.position”因果解释已推翻。现有截图观测保留,
  机制须重做正交 A/B;旧 CHANGELOG/证据/契约注释尚待本轮收尾更正。
- **当前最强候选但尚未实证**:09-01 新增 1 格体素 DDA 的硬离散边界/tie 语义可能把
  画面 bob 的屏幕采样位移放大为周期条纹;也可能是纯 projection 视差/bloom/体积链。
  **未改 GLSL、未宣布根因**。下一步必须先做 bob on/off × `!voxel on/off` 四臂 ABBA。
- **`!rec` Java 侧已完成(TDD)**:
  - 唯一目录 `mcap/run-<wallclock>-p<pid>[-n]/sNNNN`,不再跨重启复用 s0001;
  - 不可变 ShotToken 固定 session/seq/frame/显式 `shot-%06d.png`,CSV 为 C/L/R/P/S/F/D;
  - closing 与 current 会话并存,异步回调写回原会话,pending 清零后才写 footer;
  - 60fps 纳秒 deadline accumulator,跨 deadline 写 D;max 边界 token 归旧会话后翻窗;
  - C/L/R 只从 RenderLevelStage 每渲染帧采一次(ClientTick 重复 onFrame 已删除),
    C 位置 1e-5 精度并记录 walkDist/walkDistO/bob/oBob/bobEnabled;
  - off/re-arm/world-unload 清参考并幂等封口。FrameRecorderContract 19 +
    MotionCaptureContract 20 全绿。
- **分析器候选已实现但暂停在集成审查**:`tools/rec-analyze.js` 严格 footer/token/PNG
  完整性、50 张分批 lumastats、真实时间 lag、L/R 不变量;`rec-analyze.test.js` 55 项绿,
  已接 `tools/selftest.ps1`。刚修了真实 header 可识别 `TacLight rec` 的一行,但发现两处
  **尚未对齐,重启后先修**:
  1. Java `S/F = type,t,frame,seq,filename...`,分析器当前把第 3 列误读为 seq;
  2. Java footer `dropped` 是所有跳过 deadline 的总数,单条 D 可含 count>1;分析器当前
     错拿 `dropped == D 行数`。应统一 S/F 列索引,并用 `ΣD.count` 校验 footer。
  测试 fixture 也要改成真实 Java schema,防止“两边各自绿、集成仍红”。
- **暂停时权威结果**:`gradlew-java17.cmd taclightContracts` 通过:
  `REC-ANALYZE-TEST PASS (55 checks)`、`TOOLS-SELFTEST PASS`、
  `MotionCaptureContract ALL PASS (20)`、`FrameRecorderContract ALL PASS (19)`、
  `AllContracts: ALL PASS`、`BUILD SUCCESSFUL in 18s`。该门运行在 schema 交叉审查前,
  因 fixture 漂移不能替代待做的真实 canary。
- **工作树/环境暂停事实**:HEAD 仍为 `9530b09`,无 commit/push;10 个目标源码/工具文件
  保持未提交(另有 `tools/.selftest-rec-analyze/` 临时目录待确认后清理);系统重启前未发现
  java/javaw 游戏进程,无需关服。没有创建本轮 evidence 包。
- **重启后的第一段工作**:①查 `git status`;②修上述 schema+fixture,跑 node 测试与完整
  contracts;③启动双端一次做 `!rec` canary(OPEN/CLOSE/footer、P=S+F、ΣD、PNG 精确集合、
  render C 无 1–5ms 双采样、实际 FPS);④ canary 绿后才加 `!bob` 并跑四臂 ABBA;
  ⑤按证据决定是否给 DDA tie 写红灯/修复。禁止直接按当前假设改 GLSL。

## 未提交 · 09-01 深夜⑤(第一人称灯锚 view-bob 根治:"地面条纹随观察者视角晃动同频放大")

- **定位(用户报:观察另一个玩家的灯光光晕时,观察者移动则地面条纹随视角晃动
  频率有节奏放大;推测新算法)**。先排后修:
  | 怀疑项 | 判定 |
  |---|---|
  | 远程灯数据摆动 | 排除:LOOKTRACE 逐帧 hO=hC/pO=pC/posO=posC 全等(远程重建=常数) |
  | 体素 DDA(新算法) | 排除:世界空间 Amanatides-Woo 相机无关;`!voxel on/off` 同视角 rim 脉动无差 |
  | 自适应曝光 / bloom EMA | 排除:曝光锁定 1.0;α=0.6 记忆亚帧;池静止 rim ±0.5% |
  | **FP 灯锚=相机眼球** | **病灶**:1.20.1 view-bob 注入相机位置(横摆 ±~0.2m + 姿态旋转;实测相机 y 恒 122.62 不动)→ 灯源随 bob 摆动 → 灯源-地面几何以 bob 频率微调(近距池缘 ~3-6%)→ bloom 软阈带(smoothstep 0.6-1.4)放大 ~3x → "条纹有节奏放大" |
- **修法**:`ClientSpotlightUploader` 新增纯函数 `spotAnchor(cameraEye, playerEye)`,
  灯锚**一律取玩家眼位**(`getEyePosition`,bob-free,与自体胶囊/SSO 豁免同源);
  freecam/第三人称语义不变;视线仍取相机(所见即所照)。枪灯枪口姿态路径不动(意图
  行为)。Java 变更需客户端重启(本轮仅重启 ObserverB 验证)。
- **契约**:`UploaderSemanticContract` §7 钉死"FP 灯锚=玩家眼位"(先红:spotAnchor
  未定义 compile 失败;后绿:AllContracts **440** 项全绿,437→440)。
- **实机(B=ObserverB 重启新构建,Dev 灯+B 灯开)**:走中 `!diag` 锚值恒等
  (静止 cam=(2004.00,122.62,3.50)/L0=(2004.39,122.42,3.39);走中 cam=(2006.02,
  122.62,1.30)/L0=(2006.41,122.42,1.19)——锚 y 与静止逐位恒等,无 bob 分量);
  远程灯同步一致(Dev 本地 dir=(0.557,−0.242,0.795) vs B 端 (0.562,−0.219,0.798),
  预测外插残余 <0.03)。证据 evidence/2026-09-01-viewbob-anchor-fix/。
- **坑49**:F2 截图 toast 污染量测(三值节拍 0.521/0.614/0.706 假脉动)。
- **坑50**:`/tp <x> <y> <z> <yaw> <pitch>` 方向约定(yaw: `dir=(−sin yaw, +cos yaw)`;
  pitch 负=朝上)——本轮 3 次把被观察者灯对天照,远程池假阴性。
- **坑51**:capture-burst 窗口不在前台 → CopyFromScreen 全静态帧;walk 与 capture
  各自 Add-Type 编译 ~1.2-1.5s,走中连拍须错峰 ~2.2s。
- **边界/遗留**:相机姿态 bob(视角晃动)按设计保留(手持真实感);若观察远处
  对方池、自身灯关时体感仍有极轻微联动 = vanilla 视角视差,另行立项。
- **待用户体感验收**:走路观察对方(或自身)灯池,条纹不再随视角晃动节奏放大。

## 未提交 · 09-01 深夜④(体素 DDA 遮挡:墙后漏光根治,条件项触发+用户要求尝试新算法)

- **立项**:用户实测"光照穿透墙壁,墙后地面有淡淡光晕"= AGENTS §4 条件项
  ("DDA 体素遮挡:实机真见漏光才立项")触发,并明确要求尝试新算法、关注性能开销。
- **实现**:`VoxelField`(纯逻辑:盒计算/2bit 打包,VoxelFieldContract 14 项)+
  `VoxelGrid`(世界侧:每 tick 方块采样,section hasOnlyAir 整段跳过 + BlockState
  分类身份缓存,实测 0.08ms/tick)+ SSBO 尾段(lights[8] 定长 + voxOrigin/voxMeta/
  voxData,总长 525,104B,数据区按 usedUints 增量上传)+ GLSL `taclight_vox_transmit`
  (Amanatides-Woo DDA:实心 T=0 一票否决,树叶 0.4/格、软植被 0.75/格;栅格无效/
  端点出界回退 SSO;起终点格双向豁免不自遮)。分类与 block.properties 同源
  (空气/流体透光)。旋钮 `!voxel <on|off|status>`(默认 on,off 回退 SSO)。
- **实机 A/B(wall 场景,B 站墙顶俯视)**:北面地台漏光 ROI mean **82.3 → 44.3**
  (−46%,= 无灯基线 43.7,漏光消除);南直射池 **64.5 → 64.5 全分位恒等(零回归)**;
  `!bench` 60 锁帧 59.9 vs 59.8 fps、1% low 56.2 vs 55.0(噪声内)。证据
  evidence/2026-09-01-voxel-dda-occlusion/(真机 F2 截图 ×4 + 判定数字)。
- **坑47**:String.format `%d` 接 float 字段在事件监听器抛
  IllegalFormatConversionException → 客户端 FATAL 崩溃(非仅日志)。
- **坑48**:原版远程玩家 HEAD 角同步缺口——RotateHead 包仅在量化字节变化时发送,
  relog 后被观察者静止 → 观察端 yHeadRot 卡旧值(实测 +4.63° vs 真值 −180°)
  → 远程灯方向错 180°。规避:验收前让被观察者动一下头。
- **边界**:体积束(composite1)灯侧遮挡未动(束段穿墙待实机可见再立项);
  树叶/植被透射常数未单独标定;双灯跨度/照距超栅格覆盖时逐光线回退 SSO。
- AllContracts 437 项全绿(新增 VoxelField 14 + 布局契约重写 23)。

## 未提交 · 09-01 深夜③(位置链移动闪烁:定位 + 死推滤波修复,用户批准"定位后修")

- **定位(用户报:平移/缩放时光晕边缘仍有闪烁;要求移动循环+消融+定位后修)**:
  ①tp 步进循环三轮消融(基线/`!extrap off`/`!bsnap off`)数字完全一致(0.347 格)
  → 旋转链零贡献,且 0.347=步进粒度伪影 → 换刺激;②**真实 WASD 步行**(PostMessage
  键盘按住驱动 Dev,同步链输入=用户走路)12 会话:平移光斑残差 0.22-0.55 格(≈53-130px,
  去趋势窗标定修正),位置链贡献 100%、方向链 0 → **病灶=位置链 o→C lerp 的逐 tick
  速度调制(±20%)**。证据 evidence/2026-09-01-position-flicker/。
- **v1(lerpX/Y/Z 延迟段快照插值)实机否决**:psnap on 残差反升 27-41% —— 实测 lerpX
  为突发台阶(单步最大 0.428≈2 tick 位移),非匀速序列;忠实回放=更噪。
- **v2 RemotePosSnap(死推+速度导引)**:显示按平滑 v̂ 积分,每 tick 误差小比例
  校正(位置拉回 0.35 + 速度导引,25ms 校正节拍,急停时也强制校正),传送(|err|>0.6)
  直落 + 单向超前钳制 0.08 格。**同输入 A/B:步行中段稳态光斑晃动 disp 4px(纵深)/
  30px(平移)vs 旧管线 6-7px/45-50px,且无 20Hz 前后跳变**;起停瞬态有界(≤0.35 格)
  平滑收敛。`!psnap <on|off>`(默认 on)。LivingEntityLerpAccess mixin(lerpX/Y/Z 只读,
  探针 tgt 列)+LOOKTRACE 增 tgt/disp 列。RemotePosSnapContract 21 项,AllContracts 全绿。
- **坑45**:强杀客户端后 Forge 早显窗口 glfwGetPrimaryMonitor 失败("Failed to locate
  a primary monitor"),启动必崩 → `run*/config/fml.toml` `earlyWindowControl=false` 绕行。
- **坑46**:GLFW 按键识别依赖 scancode(keybd_event scan=0 → KEY_UNKNOWN,白烧一轮);
  后台驱动走 PostMessage WM_KEYDOWN/UP(postkey 路线)不依赖前台焦点。
- **待用户体感验收**:平移稳态 30px 接近可感下限,起步追赶 ≤0.35 格是否可感;
  `!psnap off` 一键回退。

## 未提交 · 09-01 深夜②(snap+pred 实施 + 同输入 A/B 实机对比,用户批准)

- **RemoteBaseSnap(snap+pred,用户批准"尝试一下")**:基角 = 延迟一段快照插值
  (C_k 到达后 [t_k, t_k+50ms] 播 C_{k-1}→C_k,只依赖自用 C 历史、不碰 O/C 对 →
  构造上位置连续,消源1;预测臂两级 EMA τv=0.15s→τout=0.08s 保留方案A 超前,消源2)
  + 传送级双钳制 + 2s 陈旧重置。旋钮 `!bsnap <on|off>`(默认 on,off 退回旧管线)、
  `!extrap` 仍控超前。RemoteBaseSnapContract 25 项;**AllContracts 395 项 ALL PASS**。
- **同输入 A/B 实机对比**(双端重启,同序列 /tp 步进 ×15° 各一轮):jumpMax 平均
  **5.58°→1.82°(-67%)**、最差 5.80°→1.88°;静止尾段误差最差 **4.44°→0.75°(-83%)**;
  errP2P 6.15°→5.40°(滞后未回退)。活体行为:到达不跳/50ms ease/静止 base≡hC 恒等
  全按契约。round2 首会话 64° 峰 = 测试序列自身跨 ±180 回绕伪影(传送级钳制正确处置,
  0.5s 收敛,统计剔除)。证据 evidence/2026-09-01-snap-pred-fix/(README+1822 行原始
  信号+manifest)。**待用户真鼠标体感验收;`!bsnap off` 一键回退。**

## 未提交 · 09-01 深夜(非对称确认:用户双向实测 + !mcap 真鼠标采集分析)

- **用户双向实机对照**:B 看 Dev(开灯转动+移动)= 光晕移动时边缘明显闪烁;
  Dev 看 B = 无类似闪烁。同 shader 同渲染链仅交换被看者结果相反 → 闪烁定位在
  观察端"远程玩家姿态重建"同步链(B 走真实 socket;A=LAN 主机同进程),渲染/光影无罪。
- **!mcap 无人值守采集实战**:用户测试全程自动抓取 s0005-s0012 共 7 个有效信号会话、
  6291 帧 LOOKTRACE、4683 张截图,静止自停 100%,全程方案A 默认 1.25。
- **真鼠标消融数字**(tools/lookreplay.js,墙距 6.5m):现行管线纹波 **3.2-11.2°**
  (墙面晃动 36-127cm)、单帧尖峰至 15.8°;候选对比:chaser/chaser0 纹波最低但滞后
  -6~-16° 淘汰;snap 无超前回退滞后问题;**snap+pred(基角跟 C 快照 + 方案A 超前保留)
  稳定砍纹波 52-69%、尖峰约减半** → 提案实施对象,参数以本批真实向量为 TDD 契约向量。
  O-滞后全 0%;#11 慢扫纹波 0 但仍单帧 6° 跳变(尖峰与纹波为两种可感症状)。
  证据 evidence/2026-09-01-asymmetry-confirm/。**修复实施待用户批准。**

## 未提交 · 09-01 晚(运动门控采集开关 !mcap + 灯态跨 relog 持久化)

- **!mcap(用户指令:开关布防→动则采集、静则自停)**:MotionCapture 纯 JVM 状态机
  (35 项契约)——被观察角色朝向/位置超阈(yaw/pitch/pos=0.3°/0.3°/0.02m,参考采样
  100ms)开会话,静止 800ms 自动收窗,单会话 1200 张上限翻转,目标切换重参考,
  ±180° 环绕安全;`!mcap [on|off|yaw=|pitch=|pos=|ref=|still=|fps=|max=]`。
  LookTrace 增 on 门控模式 + posO/posC 字段(尾置,lookreplay 向后兼容)。
  截图 = RenderTick END 进程内 Screenshot.grab(帧末主缓冲,免前台窗口,坑34 旁路),
  60fps 节流 + ioPool 积压跳帧;落盘 <gameDir>/mcap/s%04d/screenshots/。
  实机:8 次 /tp 步进 → 4 会话/473 行信号/346+103 张 PNG/静止自停 100%,
  imgdiff 光斑摆动 bbox 可见;证据 evidence/2026-09-01-motioncap/。
  用途:用户真实鼠标轮次(方案A on/off 三档对照)自动采集,不再盯秒表。
- **灯态持久化(用户 bug:退出灯开、重进灯灭)**:根因 = 灯开关真源在 SynchedEntityData
  (实体实例字段),重进服务端重建玩家实体回落默认关,无持久化层。修复 = serverApply
  (唯一服务端写口)落玩家 Forge persisted NBT 子树(PERSISTED_NBT_TAG,跨 relog 且跨
  死亡克隆)+ PlayerLoggedInEvent 重放(实体数据 + SyncLightS2C 回包一并恢复,
  本地状态零新增包跟随)。LightStatePersistenceContract 11 项;实机:B 重登
  零命令 → login-restore handheld=true + 本地 ACK(03:27:10/11)。

## 未提交 · 09-01(闪烁消融工具链 + 受控实验:探针/分析器/机制判定/方案分析)

- **工具链(用户指令:消融实验定位根源)**:①`!looktrace` 逐帧全量角度链路探针
  (LookTrace + LookTraceContract,AllContracts 全绿);②`tools/lookreplay.js` 消融分析器
  (raw/lerp/lerp+pred/snap/snap+pred/chaser 七配置回放对比;纹波=去趋势残差峰峰、
  边界跳变、20Hz Goertzel、O-滞后分布;自测 8 项 PASS)。证据
  evidence/2026-09-01-looktrace-ablation/。
- **受控实验**(僵尸追村民连续转动 4.2s/-109°):O-滞后 61/61 边界全零 → 连续转动下
  O/C 对良性,源 1(每边界锯齿)未在 zombie 复现,触发条件收敛到玩家实体特有因素
  (双通道/±180 邻近,用户锯齿恰在 yaw≈±180)——待对远程玩家采集钉死。AI look-snap
  单 tick 达 16.51°;**snap 快照插值 jumpMax 16.51→0.43°(33×)**;临界阻尼追随器因
  恒速滞后 2v/ωn 被自测淘汰。
- **方案分析**:推荐 **snap+pred**(自己的 C 历史分段线性播放 + 速度 EMA×超前)——
  同时免疫源 1(O/C 异常与样本台阶)与源 2(纹波),保留超前;B(只慢化 τ)不解决
  源 1。**实现待用户批准**;下一步=mp-session 对远程玩家真鼠标采集钉死源 1 + 实测矩阵。
- **坑43 入册**:中继按批消费(整个文件同 tick 执行,# 零延迟)→ 序列必须逐行写入
  ≥500ms 间隔;多会话拼接会伪造假边界(分析器已分会话);maxFps=260=原版"无上限",
  实测裸跑 1788fps。

## 未提交 · 08-31 深夜(边缘闪烁再诊断 — 修正"拓扑不对称"结论;实测两抖动源)

- **用户反驳成立**:拓扑不对称只能解释**滞后差**(B 端慢/dev 端快),解释不了**光晕边缘
  闪**;且坑36 修复后闪烁曾确认"基本消失",方案A 之后又出现。复挖用户体感会话日志
  (run*/latest.log,`!extrap log on` 已留痕 21:06-21:39,全部真鼠标数据),实测**两个
  独立抖动源**;分析脚本+原始样本存 evidence/2026-08-31-flicker-rediag/。
- **源1 基础角 20Hz tick 边界锯齿(先于方案A 存在)**:坑36 同源插值后的 base 角在 tick
  边界前跳 +2~3° 又回退 ~2°,周期精确 50ms(rotLerp(pt,O,C) 理论连续,实测违背 → 疑原版
  O/C 更新时序或 RotateHead+Move 双通道叠加,机制待探针钉死)。**与原版头部渲染同公式 →
  头部模型同步微振 = 用户看到"头/晕同步"的原因;头部纹理掩蔽、光斑软边缘放大。**
- **源2 方案A ext 纹波(08-31 新增)**:ext=EMA(20Hz 量化 ω̂×ticks),τ=80ms 每 tick 收敛
  46% → 20Hz 台阶近直通。实测 dev 端默认 1.25 稳态扫视 ext 峰峰 **3.52°**(21:38:47 段,
  翻转 1.9Hz=真摆动);B 端因 21:07:37 误设 `!extrap 2.0` 放大到 **8.71°**。既往验证漏检
  原因:静止(ext=0)/直走(ω=0)/tp 阶跃(风暴淹没)/慢转(锯齿∝转速)全踩不到连续快扫。
- **换算**:6.5m 墙面 1°≈11cm → dev 端 3.52° ≈ 40cm 边缘晃动/半秒,可见闪烁实锤。
- **修正**:08-31 晚"拓扑不对称非缺陷"仅对滞后成立,对闪烁不成立。
- **待办**:①2 分钟决定性对照(dev 中继 `!extrap off` → 真鼠标快扫,预期闪烁减但不消);
  ②修复提案(光斑方向解耦平滑,吸收锯齿+纹波、保留超前补偿;契约用本次实测 ω 序列做
  测试向量)——**等用户批准后动工**。

## 已提交 8c3105c · 2026-08-31 晚(体感首轮反馈诊断补录 — 无代码变更,知识回写项目文档)

- **用户体感反馈**:B(observer)看 dev 灯转动/移动仍闪,dev 看 B 正常;提问"是否只对
  dev 做了优化"。**此诊断此前只进了 AI 会话私有记忆、未进项目文档,新会话查不到只能
  从零重推,交接低效 —— 教训入 AGENTS.md(收尾知识回写纪律),结论必须落 CHANGELOG。**
- **诊断结论(非新缺陷、非单端优化)**:双端同源码/同光影包/同配置,不存在单端优化;
  差异全部来自 **LAN 拓扑不对称** —— dev=LAN 主机,集成服在同进程,B 的实体数据零
  网络延迟直达,dev 看 B 天然"正常";B 走完整同步链(20Hz 打包→tick 转发→插值),
  **B 端才是方案A 的真正考验场**。快甩急停过冲 ~4.5°/250ms = 方案A 已知代价
  (EMA 起停平滑),非回归。
- **干扰因素**:排障发现 B 端曾被中继执行 `!extrap 2.0`(放大过冲);旋钮不持久化,
  重启回默认 1.25。
- **待用户判定**:三档对照(B 端中继 `!extrap 1.0` / `!extrap 1.25` / `!extrap 1.75`)
  + 定性"位置晃 vs 亮度闪";都不满意 → 立项"急停过冲收敛"改进(先提案)。

## 已提交 4451861 · 2026-08-31(用户复测:闪烁已消;新发现转动滞后 → 方案A 预测外推)

- **远程灯转动滞后诊断**:B 眼里光斑转动滞后 A 本地视角 ~100-250ms = 原版实体同步链固有
  (A 20Hz 打包 + 集成服 20Hz tick 转发 + B 端 lerpSteps=3 渐近收敛 + 渲染 1-tick 插值窗);
  修闪烁前同延迟被 20Hz 台阶抖动掩盖,平滑后暴露为滞后 —— 同一数据两种症状,非修复引入。
- **方案A(用户批准)→ RemoteLookPredictor(新,纯 JVM 可测)**:同源角速度外推
  extrapTicks×ω̂(ω̂=O→current/tick,与渲染同源零新包);双层钳制(ω̂≤20°/tick、|ext|≤12°)
  + 外推量 EMA τ=80ms(稳态无损、起停平滑防回弹/防 20Hz 台阶);状态按实体 id,2s 未见即清。
  默认 1.25 tick;**运行时调参 `!extrap <0-3|off>` + 校准日志 `!extrap log on|off`**
  (bang 命令;/taclight 是服务端路由,管不到 B 的客户端状态 —— 坑40)。
  接线:collectRemoteLights(远程手持+枪灯共用预测方向);静止 ext=0 行为不变。
- **实机验证**(证据 docs/evidence/2026-08-31-extrap/):契约 15 项全绿;静止
  extrap off/on 像素恒等(changed 41/409920=0.01%);/tp 阶跃扫掠 ext 峰值 10.17°<12°、
  急停 ~250ms 平滑单调回落、符号正确;直走 2.5s 光斑稳定无噪声(走直线⇒ext=0,与
  用户已验收行为逐位一致)。待用户体感验收:A 转视角 B 看光斑跟随。
- **排障三坑入册(39/40/41)**:坑39 场景预置自带蜘蛛推玩家下平台 + **死亡玩家实体不被
  跟踪 → 远程灯假消失**(预防:创造/清怪/关生成);坑40 relay `!light` 只切本地不上报
  服务端(已修补 sendSetLight;服务端真源一律 /taclight light);坑41 后台 PostMessage
  合成鼠标点击对 GLFW 屏界面(死亡界面)无效,键盘消息可以 → 自动化救援不可行靠预防。
- `!diag` 增 DIAG-REMOTE 探针(syncReady/accessor id/各玩家 flash 标志)。

## 未提交 · 2026-08-30 深夜②(用户实机复核两反馈:双灯仍过亮 + 远程移动闪烁)

- **P1 双灯过亮 → 多源感知肩部(composite.fsh + taclight_common.glsl)**:渲染方程保持
  线性叠加(超叠加原理),感知压缩放显示域 —— `taclight_shoulder3`:x≤T 逐像素恒等
  (单灯不变),x>T tanh 收敛,上界 (1+q)·T。T=0.55(锚定单灯名义核心,DBG8 实测标定),
  q=0.15。实测:双开/单开显示峰值 248.1/247.4(旧 255 且白斑 4.3×),均值比 1.23×,
  饱和像素双双归零(8329→387 / 35605→481),砖缝纹理全程可读。
- **P2 远程移动闪烁 → 坑36 + 坑37**:
  坑36 ClientSpotlightUploader:远程灯方向 getLookAngle() 是 20Hz tick 瞬时值,与模型
  渲染插值脱 sync → 采集端改 rotLerp/lerp 与渲染同源;
  坑37 composite3:移动光斑扫过 bloom 软阈值带被 smoothstep 放大(F6 同族,成员=表面
  光斑)→ 大半径辉光对上一帧 EMA(α=0.6),历史存 colortex6(唯一未占用槽)。
- 回归:带修复直走连拍残差 0.03(静止基线同级)、零翻转;静态双朝向 dir 校验正确。
  附注:一次重启后移动窗口出现"方向瞬时指反"未复现,留观。
- 工具:capture-burst.ps1 加 -ProcId 选窗 + 自动最小化其他 MC 窗(坑34 连拍版);
  坑38 入册(连拍前 /weather clear + 目检首帧,雨丝残差虚高 5×/空视野=空采样伪结果)。
- 证据:docs/evidence/2026-08-30-m5-interfere/(n 三态 + burst 连拍代表帧)。

## 未提交 · 2026-08-30 深夜(M5 附加:双光源同点叠加 interfere 判定)

> 用户问题:两个玩家的光同照一点,会不会亮度过高?结论:**不会失控过曝** ——
> 叠加近似物理相加(线性域中位 0.957),核心饱和面积 5.5%→23.5%(局部白斑扩大,
> 中低亮区纹理可辨),knee+ACES+曝光锁约束下有界,无全局泛白。证据
> docs/evidence/2026-08-30-m5-interfere/(终帧+DBG8×1 三态、lumastats 量化、README)。

- **摆位**:wall 场景双玩家显式 yaw/pitch 同瞄 (2000,122.4,1);A 中继一点控制双灯
  (`/taclight light on|off [player]`);状态序列经服务器 LIGHT-SYNC 日志逐条核对。
- **取证**:双客户端截图改走 F2 postkey(坑34:双窗重叠+DPI 使 CopyFromScreen 抓错窗);
  DBG8 增益临时 ×6→×1 暴露重叠核心(测后还原,W3 标定不动)。
- **坑33/34/35** 入册:/tp facing 脚底锚点偏航;F2 自截帧通道;多态对比常量像素陷阱
  (必须"两灯贡献均>阈值"真重叠过滤 + 空间分布,禁全图聚合)。
- **新工具** tools/lumastats.js(亮度/饱和统计,--bbox/--exclude 扣常量 UI)。
- 开放点(不阻塞):线性域局部簇超和 +10~30%、核心 1.86×强侧单开(>knee 模型 1.40×),
  模型缺项待单客户端 duo 场景隔离;立项条件 = 实际画面出现不可接受的过亮。

## 未提交 · 2026-08-30 晚(M5 双客户端 LAN 联测通过)

> 首次真实双端验证。结论:**通过** —— 观察者实时渲染主机手电(表面光斑+光束),
> 开关灯实时跟随。证据 docs/evidence/2026-08-30-m5-lan/(视觉对照 + SSBO 计数 + imgdiff)。

- **坑32 根因(字节码级确认)**:原版 1.20.1 `IntegratedServer.initServer()` 硬编码
  `setUsesAuthentication(true)` —— 局域网加入者须过 Mojang 会话验证,dev 第二客户端
  无会话必被 "Invalid session" 踢(约 2 秒,两侧日志无声)。修复 = `DevLanAuthHook`
  (仅 `-Dtaclight.dev.disableLanAuth=true` 时关闭集成服验证;dev run 配置携带,
  生产不带属性、行为与原版一致)。
- **坑31 附带修复**:`--quickPlayMultiplayer` 直连无 status ping,客户端把 Forge 服
  误判 vanilla → 通道谓词改 `NetworkRegistry.acceptMissingOr`(同时支持单侧装 mod 降级)。
- **工具链六连修(坑29/30)**:mp-session 中继无 BOM 写入+端口正则对齐;
  drive.ps1 `-ProcId` 按进程选窗 + present ALT 解锁前台锁;FG6 clientObserver
  `parents` 继承 client(未知 run 名不配主类);observer oculus.properties 须在
  `run-observer/config/`。
- 量化:双端 /list=2;B 端 ssbo count 随 A 开关灯 1→0→1 实时跟随;
  b_on/b_off 帧差 changed=10823px(光束区),maxDiff=220。

## 未提交 · 2026-08-30 傍晚(W2/W3 标尺换基准:几何判定改 DBG4/DBG8,终帧质心降级)

> 用户批准坑26 提案:"把 W2/W3 的几何判定基准改成 DBG4 光束"。当日落地并实机取证。

- **新增 DBG strip 8**(表面光直读):`final.fsh` 直读 colortex0(M1 照明,无光束/bloom),
  ×6+γ0.45 显示增益与 DBG4 同款(只影响诊断显示);与 strip 4 构成 W3 的风格无关双腿。
- **acceptance.ps1**:align 新增 `-LumaThA/-LumaThB` 每腿阈值(W3 配方:A=DBG8 饱和核心
  253 / B=DBG4 光束 110;253→254 实测 Δcx=0.0006 阈值不敏感);frac 空判定护栏改用
  各腿阈值(堵住"A 腿空核心仍绿灯"的洞)。TOOLS-SELFTEST 全绿。
- **§7 规格变更**:W2=`side` on DBG4(✅ cx=0.5268,带 [0.49,0.58]);W3=align DBG8↔DBG4
  (✅ dx=0.0095/dy=−0.0180,容差 0.05/0.08 未放宽);终帧亮区质心降级为观测量
  (实测再证摆动:同姿态跨场次 0.4541↔0.4852)。W4 加坑26 备注(本次未动)。
- **取证 SOP 补坑**:ESC 菜单污染截图(两张图质心相同才暴露)——截图先目检再判定;
  gradle 离线时 syncShaderPack 手工等价(include 检查 + cp)。坑26 关闭。
- 证据:docs/evidence/2026-08-30-w2w3-recalib/(manifest.sha256)。

## 未提交 · 2026-08-30 午后(色调管线 v2:参考对比 + 消融实验 + AgX/线性域)

> 用户实机验收阶段二(铁块高光 ✅)后反馈"画面色调有点别扭",要求对比借鉴
> iterationT,以拆解+消融找其观感本质。全程证据 docs/evidence/2026-08-30-tone-ablation/。

### 许可查证(借鉴的边界)
- iterationT 3.2.0:**包内无许可文件**,镜像站标注互相矛盾(GPLv3 vs 保留所有权利),
  MineBBS 标"转载",作者经 Bilibili/MGC 分发 → 默认保留所有权利;只读学习思想/公式,
  不搬代码不搬资源。SEUS 血统一说(SEUS EULA:仅限个人修改、禁止再分发)更加固此结论。
- E-LITE 5.1.1:Modrinth 标 LGPL-3.0-or-later(已核实)→ 可合法读码;用户已对比,
  观感不如 iterationT,仅辅助参考。
- 本项目纪律不变:只使用公开数学,0 行第三方 shader 代码。

### 根因(消融实验量化,stylemetrics 四指标)
1. **加法域错误**:M1 在 gamma 域把光照加进原版画面,final 再整体 pow(2.2)
   —— 光斑贡献非线性、色相偏移。
2. **ACES(Hill) 中间调反差硬**:夜景局部对比 0.298 vs iterationT 系 0.092;
   关掉 ACES 直通掉到 0.179 → 色调算子是反差主源。
3. **bloom 阈值在 gamma 域**(0.55):白天整片天空进 bloom → 泛白雾、玻璃死白。
4. **split-tone 全幅 1.0**:量化显示抬饱和 +0.06、蓝移 +0.08。

### 修复(全部实机标定)
- composite 入口统一线性化(colortex0 契约改线性,taclight_gbuffer.glsl 头注同步);
  final 不再 pow(2.2);albedo 线性域照明。
- **AgX 色调映射**(公开 minimals 数学自实现:前向矩阵列和=1、逆矩阵数值求逆、
  S 型对比多项式;EV 窗口 ±6 适配显示参照输入);TACLIGHT_TONEMAP=1 可切回 ACES。
- bloom 阈值 → 线性域 1.0 软阈 ±0.4;增益 0.35/0.55→0.18/0.30。
- split-tone 1.0→0.35;后置饱和 +0.05;暗角 0.78→0.85;颗粒 0.035→0.025;
  LIGHT_GAIN 1.0→2.2、BEAM_GAIN 1.4→0.5(线性域换算后同观感重校,fix1→fix2)。
- DBG4 诊断视图加 ×6+γ0.45 显示增益(线性域小值直读近乎全黑,量测会被 TACZ
  HUD 抢走最亮区)。

### 验证(实机)
- 昼夜反差对齐参考带:昼 CONTRAST 0.269→0.189(参考 0.176);夜 0.298→0.254
  (含光斑本体对比,参考夜景无光斑 0.092);夜亮度保持(SHADOW 0.785→0.788)。
- 光束几何(外观无关基准):DBG4 质心 cx=0.5264 ∈ [0.49,0.58] **PASS**,cy=0.502。
- 静帧对 meanDiff **2.46**(无闪烁);开关灯 A/B meanDiff 23.08 / changed 23%
  (与阶段二 24.4 同量级)。
- **W2(final 质心 0.4541)/W3 dx(-0.072) 数值 FAIL,已根因定位**:AgX 下玻璃按
  真实透射率变暗 + 软肩展宽,终帧质心测量随外观旋钮摆动 ±0.05+(消融 B1/B4=0.52
  实证)。标尺换基准(W2/W3 改以 DBG4 光束为几何基准)属验收口径变更,**留用户审批**,
  未擅自改。

### 附带发现
- **零重启换包**:sed oculus.properties 的 shaderPack= 行 + `!reload` 即可
  (Iris.reload() 重读 oculus.properties,日志实证)——本次实验全程零重启。
- 8/26 旧作 iterationT(taclight) fork 复活验证:SSBO 通路活着(霓虹绿锥正常),
  真实光路不可见(疑其 gbuffer.normalL 约定 → ndl 恒 0),支线待查;仅本地不入库。

## 未提交 · 2026-08-30 晨(阶段二:M1 GGX specular + LabPBR 解析;git 历史重建)

> 用户实机验收 F1-F6 通过后批准:①按里程碑重建 git 历史(8 笔,快照重建——共享
> 文件按主体里程碑归档);②开工阶段二(TACZ PBR 前置)。

### 阶段二实施(全部实机验证,证据 docs/evidence/2026-08-30-stage2-specular/)

- **查证先行**:LabPBR 1.3 标准(shaderlabs wiki)= R perceptual smoothness
  (roughness=(1-s)²)/ G:0-229 线性 F0(≤0.898)、230-255 金属、255=albedo 作 F0,
  标准明文允许"230-255 全按 255 简化";"只读 R+G 即 LabPBR-ready"。
  Oculus 1.8.0 jar 内确认 CustomTextureSamplerInterceptor(`specular` 采样器)。
  **TACZ 默认枪包自带 LabPBR _s/_n 贴图**(gun/uv/*.png 319 张)。
- **G-Buffer 契约升级(lib/taclight_gbuffer.glsl)**:colortex2.a = 0.3 占位 →
  LabPBR smoothness;新增 colortex5(RGBA8,r=F0 介电值 g=金属标志 b=smoothness
  副本供 final DBG7——final 读时 colortex2 已被 bloom 复用)。无 _s 数据回落旧默认
  (roughness 0.7 经 1-sqrt(0.7) 逆变换 / F0 0.04),原版材质观感与阶段一一致。
- **gbuffers_terrain/entities/hand**:DRAWBUFFERS 0123→01235 + `specular` 采样器
  解码(taclight_decode_specular);textured/water 等不写材质(粒子半透不参与,
  water 不写 5 → 水下地形材质保留,属正确行为)。
- **composite(M1)**:roughness = clamp((1-s)², 0.20, 1.0)——0.20 下限是能量护栏
  ((1-s)² 下 D 峰 ∝ 1/a⁴ 发散,0.20×SPEC_DAMP 0.35 把同轴镜心压在 knee 平台内);
  taclight_ggx f0 参数 float→vec3(金属彩色菲涅尔);金属 diffuse 清零(albedo 转 F0);
  调用点 vec3 化(首测 C7623 隐式收窄炸整包,坑14 同款)。
- **DBG7 材质审计视图**:首版写在 composite 被 final 的 beam/bloom 二次叠加污染
  (中心径向亮斑),移到 final 早退(colortex5 在 gbuffers 后无人写,干净)。
- **测试台 pack-dev/labpbr-rig/**:只含 _s 贴图不改原版 albedo(gen.ps1 确定性生成);
  stone_bricks 砖面 R200/G30 + 砖缝 R40/G12、smooth_stone R170/G20、iron_block
  R205/**G230(金属)**;A 通道写 255(ignored,防预乘)。

### 实机验证(雨天+停雨两轮)

- DBG7:砖面/砖缝逐 texel 对比清晰;iron 补丁白色(金属位);TACZ HK416D 整枪白色
  = 金属+高 smoothness(自带 _s 经 hand 路径正确解码),手臂正确回退
- final:iron 补丁 diffuse 抑制 + 镜面光泽,与砖墙形成物理正确材质对比;acceptance
  side PASS cx=0.5044;闪烁指数 0.22%(雨中静止);雨本底帧差 4.9 vs 开灯 6.6(雨主导)
- !bench avgFPS=375.1(见坑24,数值体系已变)

### 坑24(重大):options.txt 从未生效过

- 症状:labpbr-rig 资源包进不了 Reload 列表;日志 "Failed to load options"
  (NumberFormatException on key 值,OptionsKeyLwjgl3Fix)
- **根因链**:session.ps1 用 PS5.1 `Set-Content -Encoding UTF8` 重写 options.txt =
  写入 BOM → 首行 `version:3465` 版本标记被吃 → MC 把 options 当史前格式跑全量
  datafix → OptionsKeyLwjgl3Fix 对现代键名抛异常 → **整个 options 丢弃全默认**
- 影响面:**08-29 起所有 session 会话的 maxFps/vsync/pauseOnLostFocus/resourcePacks
  从未生效**;旧 bench 118.9 ≈ 默认 maxFps 120 上限(非真实性能上限);坑17 的
  pauseOnLostFocus 修复属无效药方(症状消失另有原因)
- 修复:session.ps1 改 `[IO.File]::WriteAllText(..., UTF8Encoding($false))` 无 BOM
  + version 标记守护;drive.ps1 postkey 键位表补数字键 1-9(热栏切换通道)

### git 历史重建(用户批准)

- 8 笔里程碑提交:v0.10.0(M1-M4)→ 调试环境 P0-P2 → 边界规格 §7 两大 bug 关断 →
  M5+缺陷分析 → 第一性原理 → F1-F5 → F6 → docs 收尾。工作树快照重建,共享文件按
  主体里程碑归档(各提交信息内有归属说明);gitignore 增补 logs/*.gz、build-log.txt、
  tools/.session/

## 未提交 · 2026-08-30 深夜(F6 移动闪烁:定位+修复,用户报告驱动)

> 用户指出移动闪烁是"光源移动引起的光晕忽亮忽暗",与雨丝无关。建移动光源调试
> 工具链 → 对照实验定位 → 修复 → 复测归零。证据 move-burst/(b1 修复前/b2 关 beam
> 对照/dbg4b 体积单独/b4 修复后,各 24 帧 @3.4fps)。

### 新工具

- `tools/drive.ps1` 新动作 `holdkey`(WM_KEYDOWN 持住 $Dur ms 再 KEYUP)+ `-Dur` 参数
  —— 玩家自动移动通道;`tools/capture-burst.ps1`(进程内循环 CopyFromScreen,
  ~3.4fps,BMP 落盘);`tools/move-flicker.js`(帧序列量化:光斑亮度时序/相邻帧
  ΔL/符号翻转/去趋势残差/逐像素时域σ,支持 BMP+PNG)

### 定位过程(三连对照,唯一嫌疑收敛到 bloom 提取)

- 静止段 ΔL=0.00 恒定 → IGN 抖动图案不随时间变,静止无噪声(排除"抖动本身")
- B1(完整管线)移动段 ΔL=+0.8/+2.4/**−4.5**/+3.1/**−2.0**:锯齿,3 次符号翻转
  —— **问题确认,±1~2% 光斑亮度忽亮忽暗**
- B2(关 beam)移动段单调 → DBG4b(体积单独成像,握手流程)移动段**单调平滑**
  → beam 积分与 scene 都平滑,**锯齿诞生在合成环节**
- **根因 F6**:composite2 的 bloom 提取 `sceneAt = scene + beam`,同轴视角中央
  luma≈0.63 恰落在软阈值(TACLIGHT_BLOOM_TH±0.15 = 0.40~0.70)中段,beam 的
  平滑变化被 smoothstep 非线性放大成提取权重跳变 → 忽亮忽暗
- 修复:`sceneAt` 只取 colortex0(beam 不进 bloom;beam 本身即辉光观感,
  final 直加,无需二次 bloom)

### 复测(B4)

- 移动段 ΔL=+2.1/+2.9/+1.1/+7.4/+2.9 **单调,零翻转**(靠近墙变亮=物理正确);
  静止段恒定
- 副产品:中央 sat220 9.9%→0.3%(bloom 不再给光斑叠白),**墙面砖缝/衰减层次
  完全清晰,fp_final_bloomfix.png 为当前最佳观感**;acceptance side PASS(0.5064)
- 抖动幅度实验(jitter 0.9→0.35,B3)无改善 → 佐证非采样噪声,已回退保持 0.9

### 实机坑位 23

- **WM_KEYUP 的 lParam=0 被 GLFW 当"重复按下"→ 移动键永久粘滞**(玩家顶墙走,
  传送回起点 1s 内又走回;连拍全程静止画面,曾误判"goto 失效")。KEYUP 必须
  lParam=0xC0000000(bit30|31)。F5 等切换键不受 repeat 影响,故 postkey 一直
  "看似正常"。另:goto 与 diag 同帧消费时 diag 抓到传送前一瞬位置,机位判定
  需轮询握手(连续两次一致才可信)

## 未提交 · 2026-08-30(F1–F5 修复实施 + 实机验收,用户批准后执行)

### 修复内容(全部实机验证,证据 docs/evidence/2026-08-30-f1-f5-fix/ + manifest)

- **F1 雨不再破坏照明**:`gbuffers_weather` → `DRAWBUFFERS:0` + alpha<0.1 discard;
  **M1 表面查找与 M3 march 终点统一切 depthtex1**(实心表面语义,半透不再当被照面/
  march 终点)。实机:雨天墙面光斑稳定,雨丝无亮纹;雨/晴静止帧差 2.0 vs 0.01-1.6
  (残差=雨丝自身运动)
- **F2 自体胶囊豁免(SSO 假遮挡根治)**:cookie 槽语义扩展(SSBO 布局 96B 不变):
  Java 侧 `selfCapped()` 每灯写 (灯→胶囊中心偏移.xyz, 半径 0.45),胶囊=玩家眼位
  -0.55y、竖直半高 1.05(GLSL 常量 `TACLIGHT_SELF_CAP_HALF`);GLSL 侧两级豁免:
  ①采样点在胶囊内不计(身体切锥),②**遮挡者本体在胶囊内不计**(TP 下身体与
  远墙屏幕重叠的假消光——DBG3 全屏成像定位,occView 与 sceneDist 同源零额外成本)。
  本地手持/枪灯/远程灯全部携带。实机:TP 身后视角墙面光斑连续,身体不切光
- **F3 能量重标定**:radius 默认 56→18(室内档;atten(8m) 0.94→0.58,√亮度耦合
  不变;**run/config/taclight-client.toml 已同步——Forge 默认值改动不覆盖已有 toml**);
  spec 项 ×`TACLIGHT_SPEC_DAMP` 0.35(压 GGX 峰值×intensity 的能量尖峰)。
  实机:sat220 面积 24.2%→(见 F4 延伸)
- **F4 M3 近场正则 + 同轴爆炸治理**:`taclight_attenuation` 输入 `max(d, 0.75)`;
  TP 枪灯 fallback 改锚玩家眼+玩家视线(原锚相机)。**实机新发现(R6):FP 同轴视角
  下 HG 相位前向峰值 0.609(g=0.55)×32 步全程贴轴 → inscattering 积分 ≈1.06,
  体积束单独就把中央洗白(DBG4 colortex4 直读证实),这才是过曝主因;
  `TACLIGHT_BEAM_GAIN` 8.0→1.4**。实机:sat220 24.2%(旧 GLSL)→ 17.2%(gain2)
  → **12.8%(gain1.4),砖缝纹理/衰减层次/锥形边界全可见**
- **F5 噪声软化**:SSO 16→24 步、消光 2.4→1.2;体积 24→32 步。实机:静止相邻帧差
  ~2.0/255(≈雨丝自然运动),无噪闪

### 实机排障沉淀(工具链坑位 19-22)

- **坑 19:GLSL 改动必须 `gradlew syncShaderPack` 后再 `!reload`**——游戏读
  `run/shaderpacks/taclight-shaders-dev` 副本,只改 pack/ 等于白改(本轮 F3 首测
  24.2% 不变即此因)
- **坑 20:brigadier `string()` 无引号模式字符集同 `word()`,`@` 截断参数**——
  `cam goto "wall@front"` 机位名必须带引号(注释声称 string() 支持 @ 是错的)
- **坑 21:mixin `@Shadow` 不能 shadow 继承成员**——`PlayerSynchedDataMixin` 的
  `getEntityData()` 声明在 Entity,Player 字节码无此方法 → "was not located"启动崩;
  改 `((Entity)(Object)this).getEntityData()` cast 调用(M5 实机验收欠债补上)
- **坑 22:本机 F5 循环顺序实测反常**(FP→正面→身后),TP 机位判定以 diag
  `cam=(…,z)` 为准:身后视角 z≈玩家+4

### 契约与文档

- UploaderSemanticContract 24→29 项(withSelfCapsule/selfCapped/cookie 语义)
- AllContracts 287 项 ALL PASS;证据包 24 图 + SHA-256 manifest

## 未提交 · 2026-08-30(第一性原理分析轮)

- **新报告 docs/聚光灯手电第一性原理与技术路线-0830.md**:渲染方程把手电拆成四子问题
  (锥形 diffuse / specular / 可移动阴影 / 体积散射),"1/2/4 是算术,3 是架构";
  AAA 正解 = per-frame spot shadow map,Iris shadow pass 被锁死太阳方向 = shaderpack 天花板;
  SSO 是降级方案,应按降级方案做干净而非当正解交付
- **Radiance / Caustica 查证(读 README)**:两者均为"重写渲染器 + 硬件 RT"路线
  (Caustica=Vulkan 路径追踪接管世界渲染,MC 26.2 Fabric;Radiance=C++/Vulkan 替换 OpenGL,alpha),
  README 均无动态点光源描述 → 对 1.20.1 Forge + TACZ 不可移植,佐证天花板判断;
  结论:**不换路线**,留在 Iris/Oculus composite,按四子问题补齐(下一步:specular + LabPBR 解析服务 TACZ)

## 未提交 · 2026-08-30(缺陷分析轮 + M5 多人同步)

### M5 多人同步(提案 → 实施,契约全绿)

- **状态真源上服务端**:Player `SynchedEntityData` 两个 boolean(手持/枪灯),原版自动同步;
  mixin `PlayerSynchedDataMixin` 入公共 mixins 列表且旁路 TaCZ 门控(`TacLightMixinPlugin`)
- **网络通道** `taclight:main`:`SetLightC2S`(L 键/枪灯探针状态变化上报)+ `SyncLightS2C`
  (命令改灯后本人客户端跟随真源)
- **命令**:`/taclight light <on|off|toggle> [player]` + `light status`(RCON/控制台可驱动)
- **收集端**:`ClientSpotlightUploader.collectRemoteLights` 遍历 `level.players()`,距离剔除
  (config `remoteLightMaxDist`=48)+ 就近上限(config `remoteLightMaxCount`=8,SSBO 硬顶 8);
  远程手持灯=玩家眼位+`handheldOffset`(与本地第三人称同一条数学,契约钉死);远程枪灯眼位近似
- **顺带修复 Freecam 锚定陷阱**:`fp = isFirstPerson && cam.getEntity() == mc.player`(旁观方案 §2)
- **多人测试环境**:LAN 双实例拓扑(`tools/mp-session.ps1`:A 复用 session.ps1 → /publish →
  B `runClientObserver` 自动入服);`taclightMpSetup`(观察者低配 options/oculus 预置)+
  `syncShaderPackObserver` 双目录同步;`tools/rcon.ps1`(Source RCON 最小客户端,备用)。
  **实测:dev 专用服不可行(oculus/embeddium 纯客户端 mod 在 runtimeOnly → 服务端 dist 崩;
  FG classpath 剔除方案引入全量 jar 重复也失败),勿再尝试**
- 新契约 ×2:`MultiLightCollectorContract`(11)、`PlayerLightSyncContract`(10,含
  `Bootstrap.bootStrap()` 离线引导——`EntityDataSerializers` 静态初始化依赖注册表,离线 JVM 必须手动引导)

### 0830 显示缺陷根因分析(详见 docs/聚光灯显示缺陷分析与实现方案-0830.md)

- **左右不对称根因 = 第三人称 SSO 自体阴影**(灯锚头侧偏右 0.22m,身体把自己锥的左下切掉,
  硬阴影边贴身体轮廓;第一人称有 vis=1 豁免,第三人称没有——设计缺口)
- **过曝平台根因 = 半径 56 的反平方在室内尺度无衰减**(atten(8m)≈0.94)×intensity 6×spec+
  体积+bloom 推平顶(截图 10% 面积饱和 224,纹理全毁)
- **移动闪烁主因 = 雨丝逐帧覆写 G-Buffer**(`gbuffers_weather` DRAWBUFFERS:0123,辅助附件
  无混合整块覆写;原版 1.20.1 雨写深度,与该文件头注释前提相反)
- **M3 体积束缺陷**:表面衰减公式直接进体积积分(近场 1/d² 未正则)、TP 枪灯 fallback 锚相机
- 修复路线图 F1-F5 待批准实施(F1 雨裁剪 → F2 自体豁免 → F3 能量重标定 → F4 近场正则 → F5 抖动)

### 参考实现拆解(docs/聚光灯显示缺陷分析与实现方案-0830.md §3)

- DynamicLightsReforged(已切 `1.20` 分支)= LambDynamicLights 家族:动态光改写 lightmap 坐标
  (线性衰减/全向/默认无遮挡/逐 tick 区块重建)
- HandheldMoon = 其上的体素级角度遮罩 + `level.clip` 逐块遮挡 + **2D 屏幕中心提亮 post pass
  (即"光锥"本体)**;多人靠物品 NBT 同步天生可用。两项目均未做真 3D 聚光

## v0.10.0(路线 S 完整版 —— M1 闭环 + M3 体积光 + M2 风格层 + M4 发布物料)

> 本版本把里程碑推进到"可发布成品":体积光束 + 风格层一次性上线(用户裁决:
> 直接完成到最终成果阶段验收,不再逐里程碑中途验收)。

### M1 闭环 · 实机热修 10–13(2026-08-29 用户实测驱动)

- **热修 10·ndl 符号反转(五轮"无白光"唯一根因)**:主循环把"灯→片元"向量(锥判定轴,正确)直接当光照方向 `l` 用,而光照约定 `l` 必须"表面→灯"——同轴灯下 `dot(n,l)≡-1` → 背面门拒绝全部像素 → 辐射恒零;修复 `vec3 l = -lf`(K 绿锥一直正常,因锥判定不需要 ndl)
- **热修 11·门探针判读污染事故(方法论教训)**:曾据门探针得出"Embeddium 地形属性法线不可信"并转导数法线——该结论出自 ndl 符号 bug 时代(惩罚正确法线、奖励背面朝向),判读被污染;作废旧结论,法线回归属性路径(`taclight_decode_normal`)+ 构造性朝向校正;实机验收:法线审计盒逐面变色 ✓
- **热修 12·导数法线 = 黑边根因 + 遮挡按灯-相机几何分流**:①dFdx/dFdy 按 2×2 像素四边形差分,混合四边形(近草+远地)输出两表面切向混合的垃圾 → ndl=0 黑边;且设备深度差随距离二次缩小,固定阈值必漏检 → 轮廓检测方案整体退役。②第一人称灯锚眼睛=同轴光,"可见即无遮挡"是几何事实 → vis=1;第三人称灯与相机分离存在真遮挡 → 走 SSO。教训:第一人称的几何结论不可外推到第三人称
- **热修 12b·430 core 严格隐式转换**:vec4 表达式赋 vec3 直接编译失败(C7011)→ Iris 静默禁用整包("Failed to create shader rendering pipeline"),排查入口 `run/logs/latest.log` 搜 `GlShader`
- **热修 13·SSO 深度域缺陷(实机:透光仍在)**:遮挡判定此前在非线性设备深度域做,bias(1.5e-3)在 5m 外比半格厚真遮挡的设备深度信号(~0.001)还大,必漏检——与热修 11 删除轮廓检测同款陷阱("设备深度差随距离二次缩小");修复 = 比较搬回视图空间线性米数(世界尺度 bias 6–10cm);配套消光曲线改陡(挡 1/4 步数剩 ~16%)、步进 12→16
- **M1 收尾·植被半透挡光**:草/花/作物/藤蔓(软植被 0.25)与树叶(0.6)经 `block.properties` 分类 + `mc_Entity` 写入 colortex3.a,SSO 按遮挡者材质系数消光——修复满草场景"草亮地黑"(镂空植被被当实心全挡,地面像素射线全部误判遮挡);G-Buffer 契约同步:colortex3.a = 遮挡系数(1.0 实心)
- **M1 收尾·贴灯豁免**(`TACLIGHT_SSO_SELF_FREE=0.6`):距灯 <0.6 格的遮挡者(自身体/枪身)不参与 SSO——其阴影半影物理上全弥散;消第三人称脚下暗环
- **亮度总增益减半**(用户实测过曝):`TACLIGHT_LIGHT_GAIN` 2.0→1.0
- 调试条带 `TACLIGHT_DBG_STRIP` 关闭(法线审计盒验收通过)

### M3 · 体积光束(提前于 M2 实施,与 M2 同版本交付)

- `composite1`(全屏 24 步 + IGN 抖动):沿像素视线 raymarch,锥内采样点按 HG 相位 × D6 衰减 × 密度(`vlParams.y` = 配置 beamDensity)累加散射;深度遮挡走 depthtex1 视图空间线性比较(热修 13 同款);贴灯豁免与 SSO 共用半径——自身体不切光束
- **HG 各向异性接线**:`SpotlightData.BEAM_ANISOTROPY=0.55`(vlParams.x,此前硬编码 0=各向同性雾球无方向感)——前向散射强,光束沿照射方向最亮(手电束感,参照 Handheld Moon 观感)
- 灯数据预取(view 空间)后步进内层只做数学;性能旋钮 `TACLIGHT_VL_STEPS`/`TACLIGHT_BEAM_GAIN`

### M2 · 风格层(doc06 §2.9,bloom 经 §8.3 审批)

- **两级 bloom**(doc06 §8.3 最简实现):composite2 从 scene+beam 提取亮部(软阈值 0.55)按 2×2 块降采样 → colortex5(内容半分辨率);composite3 按 1/4 密度 5tap 十字模糊 → colortex6;final 中两级 LINEAR 上采样叠加
- **自适应曝光**:composite2 稀疏 8×3 网格全屏平均亮度 → 目标亮度(0.12,夜景基调)反比 → 帧率无关眼适应(~0.3s)→ colortex7 跨帧 history(`clear=false`);暗场景自动提亮、光斑聚焦不过曝
- **ACES 色调映射**(Hill 拟合,ACES 官方色度常量,公开数学):gamma→线性→曝光→ACES→显示 gamma;高光滚降防平白
- **split-tone**(阴影冷/高光暖)+ **暗角**(四角 0.78)+ **胶片颗粒**(IGN,暗部偏重);全部旋钮集中在 final.fsh/composite2.fsh #define 区
- HDR 链就绪:colortex0/4/5/6 升 RGBA16(Iris 注释常量声明,colortex0 从 8bit 升级以承载 >1 动态范围)

### M4 · 发布物料

- **打包任务** `gradlew packShaderZip`:shaders/ + pack.png 图标 → `build/distributions/taclight-shaders-0.10.0.zip`(版本号单一来源 gradle.properties,与模组 jar 一致)
- **pack.png** 图标(256×256,夜色手电光锥)
- **ShaderPackDiag 修复**:自检此前查路线 P 时代的注入文件 `shaders/Lib/taclight_lights.glsl`,对自研包必然误报"无 TacLight 注入";改查自研包 `shaders/shaders.properties` 的 `TACLIGHT_PATCH_BEGIN` 标记;文案"派生包"→"配套包"(M1 遗留项结清)
- pack/README.md 重写(安装/配置联动/风格旋钮表/已知边界);版本 0.9.0→0.10.0
- 验收与证据:M2/M3 量化验收(风格目标板、性能表)与 3×5 场景证据矩阵转入用户成品验收轮收集

## v0.9.0(路线 S 开工 —— 自研光影包启动)

- **审批落地**:docs/06 草案 v0.1 → 已批准 v1.0;D1–D8 全部按建议批准;三项未决问题同日裁决(6.1 坐标语义→执行 doc06 版 world 上传;6.3 bloom→纳入 M2;包位置→内包 `pack/`,M4 抽独立仓库),完整记录见 docs/06 §8
- **6.1 坐标语义整改(行为变更,三处对齐)**:`ClientSpotlightUploader.toSpot` 不再减眼位 —— posRadius.xyz 恢复传 **world 坐标**;scene-relative 转换移交配套包 GLSL 侧(`pack/shaders/lib/taclight_common.glsl`,`taclight_world_to_scene → taclight_scene_to_view` 两级唯一入口);`SpotlightBufferLayout` javadoc 同步标注。⚠️ 旧派生包 `iterationT 3.2.0 (taclight)` 冻结于 ≤0.8.4 scene-relative 契约,与 0.9.0+ 模组组合会"灯随镜头漂移",属预期废弃路径
- **新增契约**:`UploaderSemanticContract`(9 断言:world 直传逐位校验/无眼位减法/纯函数可复现),注册进 AllContracts;上传器参数提取为可注入 `LightParams`(测试零 MC 依赖)
- **M0 骨架包**(`taclight/pack/shaders/`,全自写 0 行照搬):gbuffers ×11 程序对(basic/textured/textured_lit/terrain/entities/hand/skybasic/skytextured/water/weather/beaconbeam,#version 120 最小直通+光图);composite(#version 430,binding=7 SSBO 只读消费 + K 键调试绿锥);final 直通(M2 风格层接入点);shaders.properties(铁律2:不声明 bufferObject)
- **新增任务**:`gradlew syncShaderPack`(pack/shaders → run/shaderpacks/taclight-shaders-dev/shaders)
- **提前清理**:LightBuffer.upload() 移除 SLOT PROBE(binding 0/1/8 冗余绑定);FLAG_TIMING_PROBE 头位保留至 M1 门控探针落地再收编(docs/06 §8.2)
- 版本统一:**TacLightMod.VERSION 与 mod_version 均 = 0.9.0**(D8)
- **M0 热修(首次实机加载,2026-08-27)**:composite.fsh 在 `#version 430 core` 下使用 varying/gl_FragData 触发 NVIDIA C5514/C7616 编译失败 → Iris 禁用整包;改为 fsh=`430 core`+`in`/`layout(location=0) out`、vsh=`330 compatibility` 的混搭 —— 与路线 P 派生包在本机验证过的组合一致。另在 shaders.properties 放入 `TACLIGHT_PATCH_BEGIN` 标记使 ShaderPackDiag 能识别自研包(文案"派生包"措辞系 v0.8.3 遗留,M1 再改"配套包")。证据:run/logs/latest.log 原报错行消除(待复验绿锥)
- **M0 热修 2·渲染帧同步(实机:转视角灯光拖拽)**:根因 = SSBO 上传挂在 ClientTick(20Hz),灯位滞后相机最多 50ms;迁移到 RenderLevelStageEvent.AFTER_LEVEL(渲染帧级,相机本帧终值,先于 Iris composite);退出世界时主动清空 SSBO。社区同型案例交叉验证(手持光滞后 = 数据未按帧更新):shaderLABS wiki / r/OptiFine / Chocapic13 论坛帖
- **M0 热修 3·D6 衰减提前落地(实机:照明距离不足)**:绿锥预览接入平滑反平方 `taclight_attenuation = 1/(1+k·d²)`(k=2/r²,atten(r)=0 长尾,分母无奇点),替换旧 `(1-d/r)` 线性淡出(半半径仅剩 50% 亮度 = 视觉半径提前死亡);radius 默认 24→40、上限 64→96(TacLightConfig + run/config 同步);M1 表面照明复用同一函数
- **M0 热修 4·亮度-距离 √ 耦合(实机:亮度高但照不远)**:反平方律推论 d ∝ √I —— `有效半径 = radius × √(intensity/6.0)` 钳制 ≤96;radius 语义改为"基准半径@亮度6",默认 40→56;未来挡位设计 = 只改亮度,照距自动 √ 缩放(doc06 §8.7);契约升级 12 断言(参考/×2/钳制/×0.05 四个标定点)
- **M0 热修 5·第三人称世界空间锚定**:手持灯非第一人称下锚玩家眼睛+玩家视线(原锚相机会"灯浮在相机上");第一人称行为不变。参考实现 Handheld Moon(ARR,只参考行为)分析入 doc06 §8.7:地面亮斑=M1、可见光锥=M3(已批, vlParams 已预留)、他人手持灯= v2 可纯客户端实现
- **M0 热修 6·近场软肩压缩(实机:近场过曝糊死)**:诊断 = 反平方归一化近场平台(0.25r 处仍 83%)+ 无高光压缩 → 削顶纯绿,属亮度曲线缺陷(非调试色问题);修复 = 软肩 `x/(1+G·x)`(G=2.0,远场≈线性不动、近场压向 1/G,近远比 5.3:1→2.6:1)+ 合成增益 1.5→1.8;可调旋钮 `TACLIGHT_KNEE_GAIN`;v0.8.2 同型软膝行为语义、公式重写,M1 复用
- **M1 开发热修 7·include 行尾注释炸整包(实机:整包禁用、K 键无光)**:`#include "/lib/…" // 注释` 被 Iris 按整行解析为路径 → `InvalidPathException: Illegal char <">` → 包加载失败回退原版(区别于编译错误:这次连 pipeline 都建不起来);规则 = **include 指令必须独占一行**;已加 `checkShaderIncludes` gradle 契约守护(syncShaderPack 强制前置)
- **M1 开发热修 8·"无照明"结案 + 双面法线**:门探针四象限判读证明照明系统自热修 7 后已正确工作(门控/几何全对,骷髅白斑=全门通过)——"没有照明"是场景误判:平射时地面入射角极浅(ndl≈0.1,物理正确)+ savanna 草丛交叉面片背面法线背光;真正缺陷 = 植被单面法线,修复 = `gl_FrontFacing` 背面翻转(7 个 gbuffers),草叶正反两面正确受光;门探针/四象限作为 M1 常备诊断工具保留(开关在 composite.fsh)
- **M1 开发热修 9·地形法线数据源不可信 → 导数法线**:双面法线后草丛面片亮了、地形顶面仍暗(俯射近距复现),门探针+实体对照组实锤 = **Embeddium 区块路径的 gl_Normal 方向错误**(实体路径同代码正确);修复 = composite 改用深度重建位置的 `cross(dFdx,dFdy)` 求面法线——方向构造性朝向相机,方块平直面更精确,实体低多边形棱面感 M1 接受;colortex1 顶点法线继续写出备用
- 证据:`gradlew-java17.cmd compileJava taclightContracts --offline` → BUILD SUCCESSFUL,契约 39/39 通过(Layout 16 + UploaderSemantic 12 + MuzzlePoseMath 11)

## v0.8.4(坐标约定修复——光终于画出来了!)
- **根因**:Iris/Oculus 的 composite 后处理 pass 里 `gbufferModelView` 是**纯旋转矩阵(无平移)**——坐标体系是"场景相对坐标"(world − cameraPosition);我们按"完整视图矩阵"换算,导致灯被算到 ~275 格外 → `dist>radius` 全部拒绝 → **光从未渲染过**(用户看到的"白团"= iterationT 内置传统手持光,HELDLIGHT_MODE=0)
- **修复**:`lightView = mat3(gbufferModelView) * posRadius`(纯旋转,场景相对→视图);surface/specular/beam 三处同步(与旧项目 tarkovline 的全程 scene-relative 约定一致)
- **端到端验证**:探针 `reserved=0x22fb` 全位通过;实机截图确认绿色锥形光 + 距离衰减可见
- 新增分阶段探针位与 GPU 回读(纠正 cookie 读回偏移 80→96 的 bug)

## v0.8.3(选包自检 + K 键反馈)
- **症状**:用户按 K 无反应、光仍为"无衰减白团" → 调查:代码无误,最可能是**选中的是原包迭代T而非派生包**,SSBO 通道根本没运行
- **新增开机自检**(ShaderPackDiag):读 config/oculus.properties + 检查活动包里是否有 TACLIGHT_PATCH_BEGIN 标记 → 每 5 秒检测,状态变化时聊天栏+日志提示(未激活/原包/派生包/无法判定)
- **K 键聊天反馈**:切换时聊天栏提示 ON/OFF;若手电筒关闭自动开启(便于观察绿锥)
- 主类版本日志 v0.8.3(确认运行构建)

## v0.8.2(真实感 + 通道可辨识)
- **修复削顶**:注入光加 filmic soft-knee(`taclight_knee = e/(1+e)`,增益 2.5)——此前强度 6 直接叠加导致锥形区域内近处远处全部钳到最亮,肉眼看不到距离衰减(用户反馈"无论多远亮度一样"的根因,也是"假"的主要来源)
- **K 键霓虹调试模式**:GLSL 输出纯绿锥形光(无 albedo/AO),与光影包内置手电一眼区分;契约新增 FLAG_DEBUG=2 断言(15 项)
- 光束/高光同样套用 soft-knee,消除白团
- 教学手册新增"怎么分辨你看到的是哪条通道"

## v0.8.1(热修)
- **修复**:taclight_specular 的 f0 参数 vec4→float(与 iterationT Material.f0/SpecularGGX 一致);此前导致 composite5 编译失败→整个光影管线关闭(也是 SSBO 探针全线归零的根因)
- **SSBO 通道端到端验证通过**:E2E 探针回读 reserved=0x1(表面 pass 触发+闭环))
- docs/04 结论修正:外部 SSBO 绑定可用的(推翻先前"Oculus 转换层阻断"的假设)

## v0.8.0
- **B 计划**:GunItemLightProviderMixin 给 ModernKineticGunItem 注入 IrisItemLightProvider 接口(官方 API 判定战术枪灯 → 光强 15),枪灯经 G 通道点亮 iterationT 内置 FLASHLIGHT
- 补丁工具修复:patchIterationT inputs 声明(消除 up-to-date 误判);PackPatcher 幂等改按内容判断
- docs/04-SSBO绑定调查记录.md(完整证据链与重启路径)
- 契约 29/29

## v0.7.0
- /taclight kit 命令(一键发放验收套件:手电筒+HK416D+战术枪灯)
- 正式发布构建(clean build 终验)

## v0.7.0-dev
- 新增 Forge 客户端配置 taclight-client.toml(半径/强度/内外锥角/光束密度/枪灯倍率)
- 发布文档:本文件、RELEASE.md、docs/03-实机验收清单.md

## v0.6.0-dev — V4 枪口精确姿态
- BeamRendererMixin(双路径)捕获 TaCZ 激光渲染矩阵 → 视图空间姿态
- MixinConfigPlugin 软依赖门控;MuzzlePoseMath(纯数学,契约 11 项)
- gunpack 模型骨名改为 laser_beam(光束渲染+捕获两用)
- 契约总数 29/29

## v0.5.0-dev — V3-p2 体积光束 + 高光
- taclight_beam(16 步 raymarch + 光束遮挡,composite.fsh)
- taclight_specular(GGX,composite5.fsh);SpotlightData.spotBeam

## v0.4.0-dev — V3-p1 SSBO 通道
- SSBO binding 7(std430:16B 头 + 96B/灯,兼容 irlite ABI)
- 表面锥光(smoothstep 软边/距离衰减/screen-space 遮挡/Burley)
- PackPatcherTool:锚点唯一性 + SHA-512 + marker 幂等;iterationT 3.2.0 派生包

## v0.3.0-dev — V2 TaCZ 枪挂灯
- gunpack taclight:gun_light(laser 类;官方 ResourceManager.EXTRA_ENTRIES 注册)
- 探针识别(显式优先/内置兜底)+ GunLaserReader 契约 6 项

## v0.2.0-dev — V1 手电筒物品
- FlashlightItem + FlashlightItemIris(IrisItemLightProvider)
- L 键开关(本地状态);中英语言;16x16 贴图

## v0.1.0 — V0 工程骨架
- Forge 47.1.3 + MC 1.20.1 + Java 17;离线构建(复用 1.67GB Gradle 缓存)

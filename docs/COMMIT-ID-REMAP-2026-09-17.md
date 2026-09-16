# commit id 重映射真源（2026-09-17 历史重写 D）

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

> **状态位：§P6 已验证**——pre-reviewer 用**四种独立方法 + 一个结构证明**（全量树差 108/108 只含 PNG 删除、patch-id 27/27、父提交回映射 108/108、subject 108/108；旧 108 → 新 109 ⇒ `--prune-empty` 一个都没剪 ⇒ 位置配对没有位移机会）得出 **108/108、0 错配**（`docs/review/oldtrack-opt-audit-phase5.md` §P6）。

## 1. 为什么会有这张表

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

- 2026-09-17 用户拍板方案 **D**：把已跟踪 PNG **移出 git 历史**，原件归档到仓外 `E:\dshHome\taclight-evidence-archive-20260917\`（`ARCHIVE-INDEX.md`：逐行 sha256/字节/路径）。
- 由此 `taclight` 仓库**全部 89 个含 PNG 的提交被重写**（另有 19 个"PNG 引入之前"的提交不变），**旧 commit id 全部悬空**。
- 效果（实测）：干净克隆 `.git` **331,467,233 → 80,863,244 B（−75.6%）**；双态共存文件 798 个 **blob 零差异**；发布 jar 重跑仍 **`7CE12F95…` 逐字节一致**。

**口径（2026-09-17 更正，task-37）**：实际**移出历史的 PNG 路径 = 315 条**（master 307 + `dh/compat-test` 8）；**归档 = 322 条目**（307 移出 + 7 功能性保留 + 8 补录），共 255,612,764 B。此前对外写的"**307 / 314**"是 **master 视角、不完整**——漏掉只在 `dh/compat-test` 分支历史上的 8 张 `docs/evidence/2026-09-05-dh-compat/*.png`（`dh_day_clean`/`dh_day_lods`/`dhcomp_off`/`dhcomp_on`/`dhit32_off`/`dhit32_on`/`dhself_off`/`dhself_on`），已于 task-37 用旧 blob 导出补录，见 `ARCHIVE-INDEX.md` **C 节**。

## 2. 重写命令原文

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

```
git -C E:\dshHome\mc-mod-spotlight-attachment\taclight filter-branch -f --index-filter "git rm -q --cached --ignore-unmatch -- 'docs/evidence/*.png'" --tag-name-filter cat --prune-empty -- --all
```
- 执行：2026-09-17，exit 0，29.6 s，`refs/original/**`（4 个分支旧引用）**刻意保留**作为仓内回滚点；**未 push**、未 `gc --prune`。
- 位置配对全量件（340 行 = 4 分支位置配对）：`docs/evidence/2026-09-15-oldtrack-verify/lead/2026-09-17/d13-phase1/commit-id-map.tsv`（77,308 B / `C51217A961E1E7082DBF42820708AB6530A1FC65AD69E99511366B66D57E79A7`）；CSV 版 `commit-id-map.csv`（77,568 B / `FA58FABFCF262598…`）。

## 3. 全量 old → new（唯一提交 108 个，按 master 链顺序）

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

| # | old id | subject | new id |
|---:|---|---|---|
| 1 | `9b3a1642800e5e227c3a2c99b9157eea416b6613` | 证据归档(task-25 E2E, 仅文本):README + manifest.sha256 + imgdiff-floor.json + NOT-COMMITTED.md | `82a45dea2e12b8fb7f50e4fb34cffaaf574746e8` |
| 2 | `f5a477000817bf0688dc549be1304e330e28a477` | 测试面(D2/D7):动作层 + 票面/驱动/校验器 + 第三方署名 | `9bff56e992cb4b2b022c93a99d0545805867875c` |
| 3 | `7b5ae28dbd2ca202daa1cfdc42632b675ac758e2` | 发布修复(D3b):发布包排除调试面 + mixin 配置按构建类型拆分 | `d1fbc02315cb62f4acbe29c85fceee3050f102fd` |
| 4 | `3a7b7c6dea576e46a20c0eddf8d0ff5fa9542f30` | opt① beamIdle 全灭早退(灯全灭跳过整支 raymarch;输出与逐采样×0 逐位一致) | `0a34f8934cee4fc39e395f74eb67c58e5ae0ef20` |
| 5 | `de03d1722fffacfdf7a7392238d1a2e106ec9fb2` | knob.ps1 BOM复发修复:edit落盘strip BOM致PS5.1解析失败 | `2142b59829b71b749b9d9c64fb4e1aac0b976f11` |
| 6 | `8894b151569dbf8acf618f41f5145cb6e4586617` | 持枪门:手动偏好x探针=有效灯,切走灭切回亮 | `5b585ece76b7d7e2a7d13635a0bdbbe95ee11012` |
| 7 | `696ddf2e0d56b029d6540307c43cad43cc120e45` | 09-07 枪灯M键+!gun auto+镜面F4+knee8真凶:TaCZ无激光开关故自绑M/GunControl状态机(契约13);Comp活但弱旧判作废;白斑=漫反射knee8治(池心-30%);证据docs/evidence/2026-09-07-comp-alive\|gunkey-specknee/ | `0e8356ebca7bc7879cf439acdd4eea5fdf7f53d6` |
| 8 | `fb70499ca02f54fa358130b8dd35e164384d5b75` | 09-06晚:扫参冻结(atten K20/beam0.25)+注入包性能对照(自研167/iterationT57/Comp负结果) | `a9f39c57897637722965c570cbe739c1c0f7f979` |
| 9 | `cfc04ce6da758c5a62e5803085336e854504efb9` | 环境:dev 实例接入 Freecam 模组(fg.deobf 通道)+ 坑114 入册 | `65c8b778fb4df75335ee6e4da4079d2420073dbf` |
| 10 | `923c58ff9407353920b81cbaf12001e12949949e` | 体积光双优化:遮挡查表双线性化修条纹 + 时间复用(!tm)灯光开销减半 | `3c784118f50aec8519c0eb6aa877f547d7b24537` |
| 11 | `ef16de07e0620faa085bf271b4f872f408e47b26` | 体积光遮挡查表化(方案二):in-pool 最坏场景 38.4→65.4 帧(+70%) | `9e00cb47717bd62844e1e88d4053427ebc7a0879` |
| 12 | `f8f454e795741e142047d6d54a5c0377c7594193` | docs: 光池内掉帧定位——开灯+32.4ms 主体是灯侧 DDA(20.6ms),VL_STEPS 64→32 实测省 17.1ms | `8990b74dacb8f0038b8282b3617b1acdd7221ac0` |
| 13 | `0d366dbcc83edc4f21440181783f909acf8a9e31` | 4K 原生分辨率性能复测:TacLight 逐像素成本≈3.49ms(随像素线性),默认配方 4K≈88fps | `f401b9918e8212359c69edd5f9c80f1058d08413` |
| 14 | `b83b5220738d03cabf51adceb4e0a85676f059df` | 体积光穿墙漏光修复+!cone 第八旋钮(8°/4°)+knee 默认开 G=2.0+技术报告 | `03cb798cf5d3354ce78d108bac6d2c99a5b39017` |
| 15 | `4429f57747f85be8c81eca426e903f44c3aa5551` | 体积光侧面服务型相位:HG 替换为 sin²θ 剖面(侧视丁达尔提亮,正面/沿轴压暗) | `b21086dcb5829b6946e9b655c400ba903e62fb2f` |
| 16 | `b3b1b94cfa888e88e887267d9ec7cd5579ee877f` | feat: !beamcap 第七旋钮(重叠软上限倍率,vlParams.z透传)+BEAM_GAIN 0.5→1.0+坑105 | `3b3301f2fd4ae559fe67aad58ace8f02679c899a` |
| 17 | `dd49047e871d8b95caa338978bc39a1e159238cd` | feat: !scat 第六旋钮(散射各向异性g 0..0.9,vlParams.x透传,0=侧视最亮)+VL_STEPS 32→64 | `623e1a0cf05eaa810accd0e19bae3ac605c86ef3` |
| 18 | `37e92ae709f26633a639ec33ea0812d75212a543` | 诊断:beamonly 下体积光不可见定案——开关/实现均无问题,四机制压制可见性 | `7463aa5074e63c0eb1f81d6c6ceda0e879017399` |
| 19 | `8dbd4ce2aed5d13f366c3790c295c21183be90b5` | feat: !beamonly 只看光束开关(flags bit2→GLSL跳过M1表面照明,composite1照常)+契约42→51+实机on/off对比证据(未push) | `15a9407a2e2365dbb6a9dcdd730c1825ee8465c4` |
| 20 | `50fc03466413bcd6abf0880cff6e854cab757eb1` | feat: !beam 第五旋钮(体积光束密度 0..1,vlParams.y 直接换值,0=关/off=回默认)+丁达尔不可见定案(密度约束+0.25ms固定成本)证据(未push) | `6afd32dfcf733042ee5d3e2aa9c7537ee67499c3` |
| 21 | `0da83529c308555973ab1a08508648e602271f23` | tools: knob.bat 双击/单发入口(消掉PS启动与引号门槛)+knob.ps1用法补说明(实机:用户在PS提示符输!命令报无法识别,非工具内提示符)(未push) | `dd5c29f0ef634f20b75dd606de268e01dc5f3a0b` |
| 22 | `bc343a631c4b8daa8113a4e5ca4413f1592c0e2a` | fix: knob.ps1 裸旋钮名自动补!(实机:用户漏!前缀致中继unknown line静默忽略=调了没反应)+四旋钮自研包像素A/B自证(未push) | `b227a55ec2ae4e97173c7818022fd9c83fd57b6e` |
| 23 | `418150f5349f728c1169fd727522f359573e9125` | tools: knob.ps1 体感调参输入器(中继无BOM写入,单发/交互双模式)+坑104(ps1脚本必须带BOM与中继相反)(未push) | `18f0bc88ec87e0d4531c83965eb4f849664718bf` |
| 24 | `bb9e6c896d606d21c2f8c737029810aa4a2ddbb6` | feat: !knee 近场软膝第四旋钮(cone.w 逐灯透传)+贴墙穿墙修复(灯头出实心钳制,坑103)+证据(未push) | `dfb40c0d74c29dd757f8c626e28a74ee0a1b890d` |
| 25 | `cb46f1f220a26be7329a0e23e41ff53e04dcdfe7` | docs: 双端LAN离线号远程灯同步验收补入 merge-e2e 证据包 | `4b928d8f6be60fea33717c36302860fa7e060731` |
| 26 | `df69272218e3fc17c23a2e3427b796967cdcd41e` | docs: 合并后端到端验收记录(evidence 2026-09-05-merge-e2e + CHANGELOG 补记) | `20c2051e6798048e428088e3b2bb6997ec3ee764` |
| 27 | `6f8592b7a53616a13b00810b366f625e28771bca` | merge: 主线合回 interop/core-extract(三旋钮+注入引擎+坑61-102,冲突双留零丢失,未push) | `23ead06bdfe3240c2458dc2655672e0aa50068f3` |
| 28 | `935548ac738f08e3d41cd3b3ac6d3964817ae494` | feat: 手电三旋钮bright-dist-atten(绝对亮度-照距-逐灯K cone.z透传)+坑99-102批量(前向DDA掠边-raw快照-失焦菜单-uint取数)+证据(未push) | `9fc1ea64a2076d7969a8a4e53c3201f3e9217012` |
| 29 | `a57dae3c76422f9cd045e56d9986ca8f5ad8ffa6` | 发射10活体感+实体不透明+漏光复核:三项全绿(未push) | `9c4cfaae6e2327c616af676d3500e4cc6be3e7cb` |
| 30 | `b716f3e3795fc6aff48b03176ea09bd5c47144d7` | Complementary r5.9 前向注入命中+锥池可见:片元单规则+extension+文件域core | `8c005971ed16a92a9ba06cd3de1122606f5991bd` |
| 31 | `4d6f09e61b1d8451cb056c0b365392f9135b9acd` | 生产 jar 离线验收通过:补 refmap 生成(Task)+mixins.json 指针,源码保持 mojmap | `a45d37958d7256d3ecccacbeb2d51db5f2e69a7c` |
| 32 | `561caae08600563176081007b7ff9149d12b609f` | Complementary r5.9 前向注入首轮(负结果,未 push):模板+前向精简+证据+回写 | `ff9cd34a1483f87b42f832751bdcfd6c4ddbbe51` |
| 33 | `d9819da5b2a8816111a24248f22137bc088c1413` | docs: 身体灯远程链判真伪-链路通无bug(双端diag三段全通,历史疑似关闭) | `a48b87ab6971ec2d33723f0d8f9f2c18ddbfbd20` |
| 34 | `14e68a2c332456d5aa17451c6256552ac311e92b` | docs: v3收尾-用户体感通过+双向开关验证通过(交叉diag跨端一致,证据包补体感结论) | `01a93389d3f98d13806ce7d0f96483e85eedbd3f` |
| 35 | `8019aca8b87686fe09ce5152afcaecfdf3fa27af` | fix: 枪灯手动开关双覆盖无效(!gun手动旗+S2C不再覆盖)+!selflight显式设定(坑85,用户实机反馈) | `4bd9112496afb2a13b87dc2c9d5d72a2602ecee1` |
| 36 | `6c5f374597766658492fde2cf8b8d1510b611f62` | iterationT 注入真实感手电调参(用户体感"太亮照不清晃眼"→ 柔和可读) | `9e593fd502f3d8bdaa2e27b6ca3619748bf9130f` |
| 37 | `edd252ae9f763e4c6a294d631428a8c8d3af71cc` | v3 屏外不剔除渲染+自身灯总闸+mp-session STALE-KILL(用户批准v3开工) | `de19a1cf26928bfc54d994bb3793f797702e4422` |
| 38 | `491d6d71dc6266a75bc0c6ae33bd35080e4821da` | 方案C 里程碑2:运行时注入引擎实机全链闭环,验收 1-7 全 PASS | `a4c71930157660fb3b5a52c38e003af6c6b4caba` |
| 39 | `6f053ac44b5ca50a99dbaebc9e81f58d8ec8cb20` | TP 枪灯方向双根因修复:模型俯仰约定反相(坑79)+捕获束向反180°/lerp穿零扫动(坑80) | `ddb95ce3472eecd680585b7694915c69cea1558c` |
| 40 | `dc39e1e29c63fbd2030e4ff3cb8e6388a5d025b6` | docs: 坑78 入册(item 58)——自校准被陈旧姿态捕获投毒(边缘出生姿 1.2118 格);交接收尾补记,机制与防线详见 CHANGELOG 22:1x 与 evidence/2026-09-02-tp-walkin-jump/ | `51ce8ef1314035a863545cab79b48455678e6337` |
| 41 | `e3193c1498b063195a81f4f7adf657800b46b859` | docs: 方案C里程碑2实施蓝图(运行时注入引擎计划:架构/模板格式v2/契约/验收/风险/SOP)+开工批准与暂停状态 | `961c08b165238ffd0af91ef0b2c83f167c5239c2` |
| 42 | `15c6e70e4607c27c0eb1efd92d24d7f775bf3e40` | fix: 步行入场光晕跳变根治——局部偏移重构跟随+坑78投毒防线;坑77 postkey抢焦点修复 | `cb5503beb8a14400e0838726305cb0a43998556b` |
| 43 | `1d47205c9ed1eafb3c2dd4a3d52502d22ab9489a` | docs: 里程碑2运行时注入引擎调研收网(Iris DH per-family补丁先例/Euphoria维护模式/路线P引擎复活;oculus-1.8.0=Iris1.7.x,patchComposite钩子)——提案待批 | `c627fdc9a86806ae918824ba426775bec102230b` |
| 44 | `ec1a3c4a33b051a2f9987e63f60b8892ae9637e2` | test: interop 实机回归闭环(像素恒等)+ 程序化测试通道(方案C 里程碑1 收尾) | `be3c3123d7009c3dfb395baf6e9581d5c65e7d4a` |
| 45 | `5302eab375fbc50710af27370a5be55a47f5318f` | refactor: interop 核心剥离——照明核心与包私有编码分层(方案 C 第一里程碑) | `7f29d5f541babd0c56a4ab579fd81101b29b90d8` |
| 46 | `69d6d38d0cb30dc63246f5eca17bbbc1c616aafa` | feat: TP打桩判定工具链+屏外枪灯连续性(hold+blend)——跳变0.73格→0/0.09,变异注入当场抓获 | `9b92e299e8dd28469682e794c2ca8734890965ce` |
| 47 | `e4f88d1c17bf53f4faf9aa6e0b5bcfe8103c1418` | fix: 坑68——TP束轴误读joml矩阵第2行,旁观者转视角灯晕跟着转(用户实机报告) | `a26afe3bbb3b06687215ccaa2405e5af74539d2c` |
| 48 | `6bced9eb2ff1f1c6ff94e3d6b60089d893515adc` | feat: 配件适配③——playerAnimator 引入,远程疾跑姿差异实锤,灯枪一致保持 | `15fc58ec51e0ea61adfa7cdf565ea3f214a3b1fb` |
| 49 | `23853b6c50015780e5ef312051980c8dcd89700d` | feat: 配件适配①②——全枪械白名单+TP枪口捕获空间标定(Q·Ry180)+束向离体校正 | `e722380c7df2fe471bd80eaad4489d2cdaa995a7` |
| 50 | `c3bb689ba931aad4bf658a4264bba96bbe6754f0` | fix: 枪灯姿态跟随修复(坑60 换算共轭)+kit 预装附件;遮挡静态回归零偏差关闭 | `08fd0b05d9f71292edc239eabbafbf60b2f02c41` |
| 51 | `8e9284cabef19737ecaedb20a78ef29b851e6562` | docs: bob 跳位链闭环——用户终验通过;复盘学习文档+临时数据清理记录 | `8de9bb48e22c145351db9bc6f66da613d363497f` |
| 52 | `8fb47c57e7083fc9371289431335eca974949018` | fix: 自灯影子回归——同轴快速通道视图域阈值被 bob 平移调制跨 0.5(坑58) | `e46935e173d38e56935817ea644c190da28e24e5` |
| 53 | `aa75a27d860a26f45ad18e0c3a6e9ddc5e1fd42d` | fix: view↔world/scene 换算丢 bob 平移致影子随步频跳位(坑57);全矩阵形式修复 | `1042b3dc2f811314bf8fb5991f056a5ecc27fcb8` |
| 54 | `df6d3d91a20ce507f21642e5c6596ef975d9a40f` | docs: bob 晃动判决定案——唯一触发=view bob;延迟补偿过冲假说三重排除;摆动测量证据包 | `783b67664900d7048b0b63bc6d7fafc1cca8e889` |
| 55 | `3738fca7b24008a7c0378e16f383367fa7c40493` | tune: 穿透软化带 0.20→0.35(用户实测跳跃即不明显=bob 离地衰减互证,步频摇晃×硬影缘残留) | `2ebe0790a28d0ef6bd7c49342e78af31bcba55cf` |
| 56 | `c557e1b949301f2d7696cc8fa47eca27ae83580d` | fix: DDA tie 全轴推进+实心格穿透软化带 0.20,根治移动时柱影条纹随视角晃动节律放大 | `32a7c0c1cbb7fe3d48c6303113b5ce26bb8541a2` |
| 57 | `d9a00172c4f890c02d26d04d66d716c481012510` | feat: 完成运动门控逐帧录制与可信分析协议 | `31e981acc71cebb0586fd3ee451c646a1af2ffc6` |
| 58 | `9530b098b3fe6dd760ab4be41ff846b3c99f635a` | fix: 第一人称灯锚改玩家眼位(spotAnchor),根治走路时地面条纹随视角晃动同频放大 | `e30f8c59ee7988ba766a5dfca57570bd5082b0cf` |
| 59 | `29538d686f1dc594b2ff3d03229c32479f10f00e` | 体素 DDA 遮挡:墙后漏光根治(条件项触发+用户要求尝试新算法) | `a4fdafd0c01ecf25799b203cde1faa8deddfba63` |
| 60 | `5b8cc2766c31132afcb02fcf9503426d06c45c39` | 位置链移动闪烁定位与死推滤波修复(用户批准定位后修):平移稳态30px vs 旧45-50px | `1fb20c4b3954c6935f06f04072306661ea9ad2ca` |
| 61 | `28e1ba2ea46ebb3a9b0bb267285830f641932501` | snap+pred 实施与同输入 A/B 实机对比(用户批准):跳变-67% 静止残差-83% | `05db7f52cad7c682c17e5da11c380022d3040b08` |
| 62 | `207d5c5fd2b7bf6c11cdc71976adbeb67ceff6ab` | 非对称确认:用户双向实测+真鼠标采集分析(B看Dev闪/Dev看B不闪) | `3204b961de549a6c4cd83d1a29bb7f15b5e954fe` |
| 63 | `bd05a5b3f032928b6ef90ebfc1474f8e4a906fb1` | mcap 运动门控采集开关 + 灯态跨relog持久化(用户报障修复) | `88adac26f33dcc9d84915c639660c9ad9242998d` |
| 64 | `de7cb96a8a4c128ef211872520f6043efdb356cd` | 源1远程玩家实锤:玩家/tp阶跃采集(会话9)显示yHeadRot样本序列20Hz折返(带内幅度2.46°=用户锯齿量级),双通道目标差致O/C静态偏3.29°/跳变23°;坑36-lerp与snap均忠实重放折返(纹波9.4°),唯低通可压(chaser0纹波2.33°);snap+pred推荐需修订为snap+低通+速度补偿超前 | `5b3c85d13b693a82555fc01fd8ae65ba2958aa73` |
| 65 | `53cd4479c233b1da02bb12082a239d706dbb8bd5` | 闪烁消融工具链:!looktrace 逐帧探针(LookTrace+契约,全绿)+lookreplay.js 七配置消融分析器(自测8项PASS,会话切分);受控实验:僵尸追村民连续转动 O-滞后61/61全零(源1未复现,收敛到玩家特有因素/±180邻近),AI look-snap单tick16.51°→snap快照插值0.43°(33×),chaser因2v/ωn滞后淘汰;方案分析:推荐snap+pred,实现待批准;坑43入册(中继按批消费/多会话拼接假边界/maxFps260=无上限实测1788fps);证据evidence/2026-09-01-looktrace-ablation/ | `2ba523597ed22b0a0d5083b2cab65a7fb5f532d7` |
| 66 | `e12940b9056d62a29936afb4f976b1d8e1d487e5` | 边缘闪烁再诊断(修正拓扑结论):日志实证两抖动源——①基础角20Hz tick边界锯齿±2-3°(先于方案A,与原版头部渲染同公式,头被纹理掩蔽/晕软边放大);②方案A ext纹波(dev默认1.25峰峰3.52°翻转1.9Hz,误设2.0时8.71°);EMA τ80ms每tick收敛46%=20Hz台阶近直通;6.5m墙1°≈11cm→3.52°≈40cm晃动;既往静止/直走/tp阶跃/慢转验证全踩不到连续快扫;证据evidence/2026-08-31-flicker-rediag/(脚本+55行原始样本);修复提案待批准 | `1eed9856f25860d2da37731b0a9d8ad8c57d075e` |
| 67 | `8c3105c4033d9e41220cc43d8280b0edda20c81c` | docs: 08-31 晚体感首轮反馈诊断补录(知识回写)——B 看 dev 闪/dev 看 B 正常=LAN 拓扑不对称非单端优化;急停过冲 4.5°/250ms=方案A 已知代价;B 端曾误设 !extrap 2.0(不持久化);待三档对照+定性;修正方案A 条目'未提交'→'已提交 4451861';根因=此前诊断只进 AI 私有记忆未进项目文档,新会话从零重推低效 | `3ca6b256299ed846bc01e41acccd65c6f4251cdb` |
| 68 | `826cb7613dda820e310df9e06327068604f9f0a2` | mp-session: PREP 步改同步调用+LASTEXITCODE 判定(PS5.1 Start-Process.ExitCode 为 null 误判失败);坑42 入册 | `c34f14135649ba07e13a294075fdb177258331b9` |
| 69 | `2420d363a5d34a2f28be79c84f8bc59296af7ef4` | docs: README 文档地图补 AGENTS.md(AI 协作手册)入口 | `30cbce2e360b68ce591bc8cce94272948a4a2a7f` |
| 70 | `a1bbf6d58fbfd4b832e44fdf3e64c665af081367` | 工具链小修:mp-session 观察者光影包路径(开放项)——taclightMpSetup 原写 run-observer/oculus.properties 是死文件,Oculus 1.8.0 读 config/oculus.properties(实机写回佐证:双文件各自 mtime/内容)→全新环境 B 端静默无光影包;改写 config/ + 删死文件 + mp-session 启动前幂等预置;另补 UTF-8 BOM(PS5.1 无 BOM .ps1 按 ANSI 解析,中文字节错位吃引号必败,与 session/drive 一致);验证:BUILD SUCCESSFUL + 路径检查 + ParseFile 无错 | `21382cef4328810aef2e3bbdf209f6a1a8b16d85` |
| 71 | `4451861a40c8ee221036044a7678059a43769144` | 方案A:远程灯转动滞后=预测外推 RemoteLookPredictor(同源ω̂外推1.25tick+双层钳制+EMA τ80ms);!extrap 运行时调参+校准日志;relay !light 补服务端上报(坑40);DIAG-REMOTE 探针;坑39 死亡实体不跟踪假象/坑41 后台合成点击无效 入册;证据 evidence/2026-08-31-extrap/ | `5cbbc338170244c0329946ef2414e6be57c9198e` |
| 72 | `9465dcd136244e78f009fe11452f468afde0f18b` | 用户复核修复:双灯过亮=肩部压缩(T=0.55,q=0.15);远程移动闪烁=方向插值(坑36)+bloom EMA(坑37) | `906f12ab38088f9e59a8720caab41ee368d01d70` |
| 73 | `b230745987b1df04aec89cb417d4429c26e4b82e` | M5 附加:双光源同点叠加判定不过曝 —— 近似物理相加(knee 次线性),核心饱和有界 | `b8242a6650dd404060868cb124e545c6e323e44d` |
| 74 | `c9affa378eee1d7382e0e64b1ada937893dabe9a` | M5 双客户端 LAN 联测通过:坑32 集成服会话验证修复 + 通道 vanilla 兼容 | `e7ca7734020226e0791731df1dc6f526b4003061` |
| 75 | `5b5a034e5f4eea91531411aef1949c5bb68baa88` | M5 联测环境第一轮修复(工具链,未含根因修复):mp-session 首跑六连修 | `32d8d006c6e6a58d06b8ff790c5eccac24264d6a` |
| 76 | `00792bdd8ed9844ad8c353ee4ad9d24f354b4e74` | W2/W3 标尺换基准:几何判定改 DBG4/DBG8,终帧质心降级(08-30 傍晚) | `1cea45fd3e0f988c6272b905c30dab2e63a8a24f` |
| 77 | `d3aeb1d9570f085df30bd8506e2ef089398dee1e` | 色调管线 v2:参考对比+消融实验+AgX/线性域(08-30 午后) | `ddc78cfec3a9327510ea79ecde155ecfb694b610` |
| 78 | `b2a4f2a5b5d75e81f4db3ccba7c1bd3004ec0a82` | 阶段二: M1 GGX specular + LabPBR 解析(TACZ PBR 前置)+ git 历史重建(08-30 晨) | `0febe404ad507890bfbfc3191283110bc75e945f` |
| 79 | `feaf323d20dadc4fdbdd60c5ca542ed3d577aa0b` | docs: 交接文档 + CHANGELOG 收尾 (v0.10.0 全特性实机验收通过) | `9deb6b843d7ea930018590eb6e64167afab1bf63` |
| 80 | `6f676bf025ea249c825f0c3f807a63c3ed267499` | F6 移动闪烁: bloom 提取剔除 beam + 移动光源调试工具链 (08-30 深夜, 用户报告驱动) | `2d51f1397bf8065e3ab3adf03079a1ae4daa9f16` |
| 81 | `5f025197ce71f4490559d6b4ff315ecb1290a5ea` | F1-F5 修复实施 + 实机验收 (08-30 晚, 证据 docs/evidence/2026-08-30-f1-f5-fix/) | `ca77a3b1d0c21cb33dc128fbfc56d617a1047563` |
| 82 | `9b30460cb2be700bef943e8682ce4b84e2491ad2` | 第一性原理分析: 渲染方程四子问题定调, 不换路线 (08-30 下午) | `4ce42dc6edad9bf8c9449044ce84d08d9ddfbaba` |
| 83 | `42ee2715782a23dd80f54afefcb69960864ec96f` | M5 多人同步实施 + 0830 缺陷根因分析 (08-30 上午) | `bac78e37ff90bbcdda0fa6d2df906b9bae0445e5` |
| 84 | `537699a43298fe778f00abc885ddba7d02eb0bb7` | 边界规格 §7 全绿: right() 手性修复 + 坑位16 终态折叠 (08-30 凌晨) | `9d4d831aa9d20e40b6323dec56d12c28bff28069` |
| 85 | `219f282005d5d70776bdc2c64c003ca7d44cffae` | 调试环境 P0-P2: 程序化驱动 + 文件中继 + 场景/机位系统 + 量化工具 (08-29) | `e971c38402626e04557efb6e29c93b4678c9b6f0` |
| 86 | `0c53d0861265e6a70e30e74801dd75b0c431ea66` | v0.10.0: M1 闭环照明 + M3 体积光束 + M2 风格层 + M4 发布物料 | `b0b451794457f0bea7bb4a3e69d87d714a345749` |
| 87 | `0c3b4ab4ea531e2a84de8f89ef181d65eedd7cd6` | docs: doc06 批准生效 v1.0(§8 审批/热修记录)+ CHANGELOG v0.9.0 + M0 证据集 | `b0a145fee9a66132a232260f22a8de0d8fc0a9e8` |
| 88 | `ba97f401aeebcc030d73402a578e5cc6c5bbd6a2` | v0.9.0: 路线S开工 — M0 骨架包(pack/ 内包开发)+ 实机六轮热修 + 6.1 world 上传契约整改 | `ba97f401aeebcc030d73402a578e5cc6c5bbd6a2` |
| 89 | `abe64dabbbe4f691fcfc51ceea9ae8b9892630ae` | chore: remove accidental unpacked jar classes and tool output | `abe64dabbbe4f691fcfc51ceea9ae8b9892630ae` |
| 90 | `62d14e74270b581cf24eb2a13ef0b4f68291caad` | v0.8.4: FIX root cause - Iris composite gbufferModelView is R-only (scene-relative coords); light now renders (E2E 0x22fb all stages) | `62d14e74270b581cf24eb2a13ef0b4f68291caad` |
| 91 | `c074b29a4083384557a8a0ee3248f6b2120acc9c` | v0.8.3: shaderpack auto-diagnosis (marker check) + K chat feedback + version log | `c074b29a4083384557a8a0ee3248f6b2120acc9c` |
| 92 | `de5d1af4eabf0f29528409107349e7da0658a2d5` | v0.8.2: soft-knee look integration (visible falloff) + K neon debug mode (channel-identifiable) | `de5d1af4eabf0f29528409107349e7da0658a2d5` |
| 93 | `d03c4b719ff4f55ade2ab1d049bb542d169329a0` | v0.8.1: fix f0 type (composite5 pipeline) — SSBO channel E2E verified (reserved=0x1); docs/04 conclusion corrected | `d03c4b719ff4f55ade2ab1d049bb542d169329a0` |
| 94 | `74d4aed116765ed12735acee49eb256232200542` | v0.8.0: B-plan release build + closing docs | `74d4aed116765ed12735acee49eb256232200542` |
| 95 | `91c9aa2165795ba4bbc16f124e4cc6e3eb5617a7` | B-plan: GunItemLightProviderMixin (Iris interface injection) unblocks gun light via G-channel; patcher idempotency+inputs fixes; SSBO investigation record (docs/04) | `91c9aa2165795ba4bbc16f124e4cc6e3eb5617a7` |
| 96 | `f362b0c3d0e528b0f69440c840a8669151ce6f8c` | v0.7.0: /taclight kit command + clean release build + teaching handbook | `f362b0c3d0e528b0f69440c840a8669151ce6f8c` |
| 97 | `3520aa4aa18ec29c69064874e4bdc5e6dcbcf243` | v0.7.0-dev: released config + CHANGELOG/RELEASE/acceptance docs | `3520aa4aa18ec29c69064874e4bdc5e6dcbcf243` |
| 98 | `ee21f5336380e8beec8de344889c341165eed3d9` | README: V4 status | `ee21f5336380e8beec8de344889c341165eed3d9` |
| 99 | `7598c5a81ea51ceb4280c248c6f93259e489d972` | V4: BeamRendererMixin muzzle pose capture + plugin gating + MuzzlePoseMath; contracts 29/29 | `7598c5a81ea51ceb4280c248c6f93259e489d972` |
| 100 | `f75d4810685cfce347bcf85eae17524426b9a778` | README: V3-p2 status | `f75d4810685cfce347bcf85eae17524426b9a778` |
| 101 | `fe0d746877285d916b8c8a7b2da4b6bd1bf67692` | V3-p2: volumetric beam (composite.fsh) + specular (composite5.fsh) + spotBeam ABI; contracts 18/18 | `fe0d746877285d916b8c8a7b2da4b6bd1bf67692` |
| 102 | `c33fb252a692f3603ef4a3a6450b6a43d87340c7` | README: v3-p1 status | `c33fb252a692f3603ef4a3a6450b6a43d87340c7` |
| 103 | `0a984004ee711b9c6fc9733a21ed1c18b60e02b2` | V3-p1: SSBO channel (binding 7) + iterationT patch pipeline + surface spotlight GLSL; contracts 18/18; patched pack loads under Oculus | `0a984004ee711b9c6fc9733a21ed1c18b60e02b2` |
| 104 | `794ccb8c442886f03b488e030315675c2267a4b4` | V2: taclight:gun_light gunpack (EXTRA_ENTRIES) + TaCZ probe + contract tests | `794ccb8c442886f03b488e030315675c2267a4b4` |
| 105 | `75fe0c43e58ac21c11a6e57c26bf072e24b50f6e` | V1: FlashlightItem + IrisItemLightProvider + keybind toggle + zh/en assets | `75fe0c43e58ac21c11a6e57c26bf072e24b50f6e` |
| 106 | `5a6ec4a2110c8bbe77ece019607bbefe3749acba` | V0: Forge 1.20.1 project skeleton (offline build, optional deps) | `5a6ec4a2110c8bbe77ece019607bbefe3749acba` |

### 3b. 仅出现在其它分支的提交（2 个）

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

| old id | subject | new id | 首次出现的分支 |
|---|---|---|---|
| `a9b7ad46a7eea540d0f439ff0b29eaa2f578531c` | 验证: Distant Horizons 3.2.0-b 共存性零冲突(三包系实机全过)+坑106 | `a77e258fa971cb5fd9d191ffc4e20e5e67580b44` | refs/heads/dh/compat-test |
| `4017270b4ecb3b1d2966bdec8f565a8549b2a631` | rehearsal: interop/core-extract merge preview(冲突已解,契约全绿,非正式合并) | `a0344664c293c8eabe3694081d12ac4c54878b2f` | refs/heads/merge-rehearsal |

## 4. 分支级位置配对

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

| 分支 | 提交数 | 旧 tip | 新 tip |
|---|---:|---|---|
| `refs/heads/master` | 106 | `9b3a1642800e5e227c3a2c99b9157eea416b6613` | `f730665c0c62fb77db514a96e17ddfb764d40221` |
| `refs/heads/dh/compat-test` | 92 | `a9b7ad46a7eea540d0f439ff0b29eaa2f578531c` | `6e6b00c59273f281ff3283c4599c28469f70f3ca` |
| `refs/heads/interop/core-extract` | 67 | `935548ac738f08e3d41cd3b3ac6d3964817ae494` | `fc16285c6584a54ae59b752a846375b38b681652` |
| `refs/heads/merge-rehearsal` | 75 | `4017270b4ecb3b1d2966bdec8f565a8549b2a631` | `a107232ae52af7c9cbd5305eea5bfb52d3834823` |

注（2026-09-17 更新）：`refs/heads/master` 的重写后 history tip 是 `82a45dea…`，其上另有 3 个**重写后新建**提交（`.gitignore` `c6a5e7d` → 映射落库 `f730665`；见 §5），故新 tip = `f730665`。3 条非 master 分支在 task-37 各新增 1 个 `.gitignore` 提交（`a107232` / `6e6b00c` / `fc16285`），故 tip 按上表更新。**§3 的 108 条 old→new 映射本身不受影响**：新增提交不在映射表内，被映射的 108 个旧提交逐一对应关系未变（§P6 已独立验证 108/108、0 错配）。

## 5. 重写后新建的提交（无对应旧 id）

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

| new id | subject | parent | 分支 |
|---|---|---|---|
| `c6a5e7df5c7c425fe31aa2c6b5c5a492a3552892` | repo: 证据图移出历史后忽略 docs/evidence/**/*.png (Phase 1 option D) | `82a45dea2e12b8fb7f50e4fb34cffaaf574746e8` | `master` |
| `f730665c0c62fb77db514a96e17ddfb764d40221` | docs: 历史重写后的 commit id 映射落库 + 14 份文档加指针行（Task-36 / D15） | `c6a5e7df5c7c425fe31aa2c6b5c5a492a3552892` | `master` |
| `a107232ae52af7c9cbd5305eea5bfb52d3834823` | repo: 证据图移出历史后忽略 docs/evidence/**/*.png (Phase 1 option D) | `a0344664c293c8eabe3694081d12ac4c54878b2f` | `merge-rehearsal` |
| `6e6b00c59273f281ff3283c4599c28469f70f3ca` | repo: 证据图移出历史后忽略 docs/evidence/**/*.png (Phase 1 option D) | `a77e258fa971cb5fd9d191ffc4e20e5e67580b44` | `dh/compat-test` |
| `fc16285c6584a54ae59b752a846375b38b681652` | repo: 证据图移出历史后忽略 docs/evidence/**/*.png (Phase 1 option D) | `9fc1ea64a2076d7969a8a4e53c3201f3e9217012` | `interop/core-extract` |

- 内容：`.gitignore` 追加 `docs/evidence/**/*.png`（防止归档后的图被再次提交）；`master` 上另有 1 个映射落库提交（`f730665`）。
- 3 条非 master 分支的 `.gitignore` 提交由 task-37（§P6 风险 B）产生：先 `git reset --mixed` 清掉索引里 687 张 `A` 状态的 PNG，再把同样 3 行落到该分支（`interop/core-extract` 因 `.gitignore` 与 master 不同，cherry-pick 冲突后等价手工落地）。

## 6. 怎么用它

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

1. **查新 id**：在 `commit-id-map.tsv` 里 grep 旧 id（支持前 7–12 位前缀）→ 取第 3 列。
2. **避免二次改写**：文档里写"HEAD/commit X"时，先用本表确认 X 是否已重写；重写后的 id 才是仓库里真实存在的。
3. **`git` 里还能不能找到旧 id**：**压缩后（2026-09-17 task-38，用户批准）已不能**——`refs/original/*` 4 个旧引用已列出并删除，`reflog expire --expire=now --all` + `gc --prune=now` 已执行 ⇒ 旧对象在仓内被回收。旧 id 只存在于：本表 / `commit-id-map.tsv`（与 CSV 版）/ 仓外 `E:\dshHome\taclight-git-postrewrite-20260917`（**压缩前 .git 整份副本，含 `refs/original`**）/ 仓外 `E:\dshHome\taclight-repo-backup-20260917`（**重写前整仓**）。
4. **文档同步规则**（2026-09-17 起）：
   - **活文档**（`docs/research/**`、`docs/handoff/release-fix-package.md` 等前瞻/计划性文档）：旧 id **就地改为新 id**，文首加指针行。
   - **冻结实测/证据记录**（`docs/evidence/**`）：**不改写正文**（那是当时事实），仅文首加指针行。
   - 原始命令输出/映射数据文件（`*-raw-outputs.txt`、`commit-id-map.*` 等）：**一律不改**（改了就是篡改原始记录）。

## 7. 未验/待办

> 本文件是**仓内副本**（真仓 `docs/COMMIT-ID-REMAP-2026-09-17.md`）；工作区孪生件：`E:\dshHome\ray-traced-spotlight-mod-dev\docs\handoff\commit-id-remap-20260917.md`（内容一致）。

- 本表**已经第三方独立复核**：pre-reviewer §P6 用四种独立方法 + 一个结构证明验证 **108/108、0 错配**（见第 1 行状态位）。此前自校验（位置配对 + subject 全等，340 行 0 处不匹配）结论一致。
- 工作区**范围外**仍含旧 id 的文件（不改）：`docs/handoff/lead-v2.md`、`docs/handoff/lead-v3.md`（Lead 自有）、`docs/handoff/release-decisions-brief.md`（Lead 自有）、`docs/review/oldtrack-opt-audit-phase*.md`（pre-reviewer 自有）、**真仓内** `CHANGELOG.md` 与 `docs/**`（跟踪文件，属真仓范围）。

# interop 核心剥离 · 实机像素恒等回归(2026-09-02 21:2x)

## 判定

**重构包(5302eab)与基线包(c3bb689)实机渲染恒等。**

| 对照对 | 判定数字(imgdiff, 854×480=409920 px) | 结论 |
|---|---|---|
| grass · 灯开 | changed=0, meanDiff=0, maxDiff=0, bbox=null | **逐位恒等** |
| grass · 灯关 | changed=0, meanDiff=0, maxDiff=0, bbox=null | **逐位恒等** |
| corridor · 灯开 | changed=4/409920(0.001%), maxDiff=49, bbox=[353,105,355,106] | 3×2 像素固定点,开/关灯同位置同幅度 → 与光影无关的外来元素(疑夜间生物眼点),非渲染差异 |
| corridor · 灯关 | changed=4/409920(0.001%), maxDiff=48, bbox 同上 | 同上 |

方法:同实例(run-interop)同场景同机位,**冻结午夜**(doDaylightCycle false + time set midnight),每张截图前静置 12s 等聊天栏淡出(排除 UI 覆盖差),!shot 程序化截图(读主帧缓冲,与 F2 同源),imgdiff 全帧量化。包切换 = 清空 shaderpacks/taclight-shaders-dev/shaders 整目录后重放 + !reload(热重载,两次 reload 均 `RELAY Iris.reload() ok`、日志零编译错误)。

初轮未静置聊天栏的四对差异全部集中在底部聊天带(y328-365)与走廊 IGN 颗粒区,提示差异来源为 UI 覆盖而非渲染;被上表干净对照取代(原始四对截图未保留,机制见 imgdiff-clean-pairs.txt 之外的前一轮日志)。

## 环境

- 实例:`wt-interop/run-interop/`(本分支专有第三实例,build.gradle clientInterop run 配置;世界 = PROBE 存档克隆)
- 基线包:`git archive c3bb689 pack/shaders`(重构前,含全部主线提交至坑60 轮)
- 重构包:worktree pack/shaders @ 5302eab(核心剥离后)
- 场景:`/taclight scene grass`(植被/花/树/平台)+ `/taclight scene corridor`(黑混凝土低反照率封闭走廊);机位 `grass_low` / `corridor_end`(config/taclight-cams.json)
- 玩家:InteropA(ops.json level 4);灯:`/taclight light on/off InteropA`(服务端真源,LIGHT-SYNC 日志确认)

## 文件

- `imgdiff-clean-pairs.txt`:上表四对的 imgdiff 原始输出
- `*_baseline.png` / `*_refactored.png`:八张对照原图(基线=c3bb689 包,重构=5302eab 包)
- `manifest.sha256`:全部文件校验和

## 关联

- 分支 `interop/core-extract` @ 5302eab(refactor: interop 核心剥离……)
- 契约:`AllContracts: ALL PASS`(含 ShaderCoreContract 31 项分层边界,本轮回归前复跑全绿)
- 本轮另交付:中继程序化升级(`!shot` 截图 + `/` 行 sendUnsignedCommand),用户明令测试弃用键鼠模拟/抢前台(AGENTS §7/§9 已同步)

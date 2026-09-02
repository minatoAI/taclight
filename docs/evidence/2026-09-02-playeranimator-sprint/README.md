# 2026-09-02 配件适配里程碑③:playerAnimator 引入 + 远程疾跑姿对比

## 结论

1. **playerAnimation-lib-forge 1.0.2-rc1+1.20 已接入 dev 运行时**(build.gradle
   runtimeOnly fg.deobf,libs/*.jar 不入库=红线1 零分发;boot 日志 refmap 重映射行为证)。
   本模组零代码引用,纯 TaCZ 消费的运行时伴生。
2. **远程疾跑姿差异实锤(B 端观察 A)**:疾跑中枪姿前指沿跑动方向、束线平伸、
   光池落在跑向正前方(sprint-mid 帧);与②(无 playerAnimator)的怠速下垂基线及
   07:0x 轮"疾跑枪横持抬起、灯池甩向左上"(evidence/2026-09-02-gunlight-pose-following/)
   明显不同 = TaCZ 默认包 rifle_default.player_animation.json 的 run 姿态接管第三人称。
3. **灯枪一致保持**:捕获链零改动即跟随新动画姿(束线/光池全程随姿),"捕获所见"
   设计在动画系统下依然成立。停止后姿态回落、捕获逐位稳定(diag-tp-post-sprint.txt:
   mdir 与 look 前向一致)。
4. **测试协议教训(坑67 预登记)**:本场景平台实域约 x∈[1995,2010],疾跑 8.4 格
   两次冲出东缘掉虚空(y=39/55,靠 /tp 回收);**疾跑测试先探边缘,路径留 ≥3 格余量**
   (W1500 序列改 W700)。

## 文件

- idle-baseline-before-sprint.png:怠速基线(下垂姿)
- sprint-mid-beam-forward-pool-ahead.png:疾跑中(枪前指、束平伸、池在前)★主图
- sprint-stop-still-tracking.png:疾跑停止后(束/池仍随姿)
- diag-tp-post-sprint.txt:DIAG-TP 样本 + playerAnimator 加载行

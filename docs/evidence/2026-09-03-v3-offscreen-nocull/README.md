# v3 屏外不剔除渲染 — 实机自检证据包(2026-09-03)

## 结论
B 端扫掠 120°(Dev 全程在屏外)+ 传送入场两段录制,**G 行 465 帧全部 fresh** ——
屏外捕获链不再沉默,入场无交接(无 blend/fallback 帧,交接差恒 0)。

## 判定数字(可复算:tools/.session/v3check5.js,列号见 G 行 34 列定义)
- s0001(扫掠 yaw 180→299.4°,119.4°):n=410,states={fresh:410},
  世界方向单帧步进 max 0.267°/p50 0.038°/p99 0.228°;位置步进 max 0.003 格。
- s0002(传送入场):n=55,states={fresh:55},
  方向步进 max 1.78°/p50 0.32°(传送帧重构吸收,≤旧 hard 版 0.73 格跳变族);
  位置步进 max 0.304 格(单传送帧)。
- 对照(修前):屏外捕获沉默→ hold/blend/fallback 混合,入场 blend 段平均 37° 转动。

## 原始数据
- s0001-sweep120-frames.csv(B 端 G 行,扫掠段)
- s0002-tp-entry-frames.csv(B 端 G 行,传送入场段)

## 环境
- 双端 LAN(端口 64805),duo 场景午夜,Dev 开灯(handheld+gun),坑39 三件套齐。
- B 端自身灯已关(!selflight → false,单变量观察远程枪灯)。
- 构建含 TpOffscreenRenderMixin(shouldRender 门禁)+ 自身灯总闸;契约 ALL PASS。

## 待用户体感
屏外疾跑时 B 端光晕是否跟真枪(侧墙)、入视野瞬间有无转动。

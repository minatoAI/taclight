# 2026-08-29 SOP-1 调试环境全流程演练证据包

主世界 bloom 小屋场景,机位 bloom_inside,手电照明,曝光锁定 1.0。
- bloom_a.png          基线(DBG=0,增益 0.55,场景完整态)
- dbg2/3/4/5_final.png 调试视图:遮挡系数 / SSO 掩码 / 体积光 / bloom 链(直出缓冲)
- dbg6_depth.png       DBG=6 线性深度(下界拍摄,Iris.reload 生效证明)
- bloom_b/b2.png       场景态漂移期截图(墙洞+降雨;30 格回滚问题证据)
- ab_a/ab_b/ab_heat.png 干净 A/B 对:BLOOM2_GAIN 0.55 vs 0.95(同状态 60s 内)+ 热图
- wall_front.png       SOP-2:月光石砖墙 + 墙前蜘蛛遮挡物
- manifest.sha256      全部 PNG 的 SHA-256

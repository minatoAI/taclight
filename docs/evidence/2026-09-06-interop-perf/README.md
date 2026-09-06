# 注入包性能对照 2026-09-06(窗口 1280x1392,走廊同机位 cam 2013.57/yRot 1885.2,枪灯开 atten20/beam0.25/knee2/tm+occl on)

同机位 !bench x3 中位,零编译错(坑105 grep)。

| 包 | 注入 | bench fps | 1%low | 目验 |
|---|---|---|---|---|
| taclight-shaders-dev(基线) | 原生 | 166.6/167.7/167.3 -> 中位 167.3 | ~128 | 光池正常 |
| iterationT 3.2.0 | +27166 chars | 49.1/53.4/56.5/57.2(爬升后稳 57) | ~50 | 光池/照亮手部正常渲染,有效测量 |
| ComplementaryReimagined | +8399 chars x多pass | 166.6/166.5/166.5 -> 166.5 | ~130 | 无光池/手部不亮=注入未渲染,负结果,fps数无效 |

判定:
- iterationT+注入有效成本约 1/3 帧(57 vs 167)。 caveat:iterationT 帧带雨,天气 Belle 负载不完全同口径。
- Complementary 本轮=注入命中但无效果(与 09-04 负结果史同族,当时 r5.9 v2 曾命中+4543)。166.5 不得引用为"注入成本",需另起诊断轮(未立项)。
- 回切 taclight-shaders-dev + reload,DIAG pack=TACLIGHT_PACK,旋钮(内存)与冻结默认一致,机位未动(20.45.14.png)。
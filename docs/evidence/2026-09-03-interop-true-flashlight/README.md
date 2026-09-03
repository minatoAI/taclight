# 2026-09-03 iterationT 注入真实感手电调参 — 实机证据包

用户体感:"光晕太亮,光线太强烈,照得什么都看不清,还很晃眼" → 做成更真实感的手电。

## 真实感手电三要素(业界共识:LearnOpenGL 聚光灯 / Three.js SpotLight / iterationT 自带手电)

1. **热点-泛光两级**:内锥全亮 → 外锥 smoothstep 半影软边(已有,不动)
2. **距离衰减够陡**:物理反平方(已有 taclight_attenuation 1+K(d/r)² 形态,不动);
   K 2.0→**5.0**:0.5r 处 50%→20% 亮度——近场强光压掉,远场尾部(>0.5r)保持。
   端点 atten(0)=1/atten(r)=0 不动;只改"中段肩部"。
3. **宿主量纲对齐**(本次根因):iterationT composite 尾部
   `finalComposite /= MAIN_OUTPUT_FACTOR(=2048,Lib/Settings.glsl:484)` 后再
   LinearToCurve——宿主内光照量级是"输出前量纲"。旧调用点直接加物理 radiance
   (surface_lighting 出口),高千倍 → 饱和糊死。修复=调用点
   `(radiance×GAIN/2048)→shoulder3(T=0.55/Q=0.15,本家包同参数)`。

## 判定数字(grass_low 机位,冻结午夜,hotspot bbox 280,190,560,340)

| 图 | 状态 | hotspot mean/p50/p90 | ge200 | frac250 |
|---|---|---|---|---|
| real_on.png | 修复前(K=2,无量纲对齐) | 123.5/154/224 | 12473 | 0.0074 |
| lv3_on.png | !lv 3 档(i=0.75,仍 K=2) | 61.7/71.8/115.2 | 46 | 0 |
| k5_rebuilt.png | **最终(K=5 + /2048 对齐)** | **71.7/79.4/136.3** | **334** | **≈0** |

- ge200:12473 → 334(−97%);饱和像素归零;草叶/砖墙纹理全程可读。
- 档位实证:!lv 2/3/4 三档画面与数字几乎逐位一致(lv2 mean66.8/lv3 61.7/lv4 65.4)——
  shoulder3 肩部在 T 以上收敛,证明**当前画面亮度由 shoulder 参数决定,SSBO 亮度
  已在线性段之外**;反推旧调用点 radiance 在肩部之上 ≈2.6×,与"高 2.6 倍过曝"一致。
- K5 稳定性:同场景 rebuild 前后 mean 71.9→71.7(雨天→晴天仅反射细节变化)。

## 文件

- `real_on.png` — 修复前(过曝基线)
- `lv_off.png` / `lv3_on.png` — 档位对照(off 全黑 / lv3 柔和)
- `k5_rebuilt.png` — 最终效果(晴天重建场景,纹理可读+远景墙可见)
- `log-excerpt.txt` — 注入(+20922 chars)/!lv 档位/!reload 日志原文

## 改动

- 模板调用点:`.../iterationT-3.2.0.json`(GAIN×shoulder3/2048 归一化)
- `pack/shaders/lib/taclight_core.glsl`:TACLIGHT_ATTEN_K 2.0→5.0
  (InlineCoreContract SHA 对账:内联副本同步,copyInlineCore 产物一致)
- `!lv` 档位覆盖层(`LightLevelOverride` + relay `!lv 0..6/off/status`,
  LightLevelOverrideContract 10 项):CLIENT config 热改 toml 不回读,重启才生效——
  覆盖层供零重启体感扫参;重启实例=覆盖清零。
- 契约:`AllContracts: ALL PASS`(TemplateLibraryContract 调用点归一化钉死+
  InlineCoreContract ATTEN_K=5.0 钉死)。

## 复现

```powershell
powershell -File tools/interop-session.ps1 -Shaders on
powershell -File tools/interop-smoke.ps1 -Tag k5 -User InteropA
node tools/lumastats.js run-interop/screenshots/k5_on.png --bbox 280,190,560,340
# 档位对照(实例内,中继一条一写):!lv 2 → !shot;!lv 4 → !shot;!lv off
```

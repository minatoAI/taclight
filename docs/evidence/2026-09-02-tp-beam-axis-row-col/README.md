# 2026-09-02 坑68:TP 束轴误读矩阵"第2行"——旁观者转视角灯晕跟着转(用户实机报告)

## 结论

用户实机报告两个症状:①"激光指示器指墙、照明光晕却在地面"(灯不跟枪口);
②"旁观者左右/上下转动视角,Dev 的灯光光晕跟着移动、大小角度也变"。
根因同一个:**TP 捕获把束骨矩阵的第 2 行(m02,m12,m22)当成了 +Z 轴的像**。
joml `mXY()` 语义 = 第X列第Y行(transformPosition 布局 x'=m00x+m10y+m20z+m30):
+Z 轴的像 = **(m20,m21,m22)**,而 (m02,m12,m22) 是第 2 **行** = 转置像(逆旋语义)
→ 相机旋转不但没被消掉,反而被"再施加"一次 → 世界方向随旁观者相机转动;
平移 (m30,m31,m32) 一直读对 → 位置链精确,把方向 bug 完全掩护
(里程碑②标定只用"位置"单向量定标,一个向量定不死旋转;方向从未被独立验证)。

## 判定数字(实机,Dev 姿态冻结,仅动旁观者机位)

| 实验 | 修复前(读第2行) | 修复后(读 m20/21/22) |
|---|---|---|
| 双机位 mdir 差 | **56.1°**(dot=0.5576) | **0.3°** |
| mdir-look 夹角 | 37.5°~72°(垃圾) | 0.2°~0.5° |
| mpos(位置链) | 逐位稳定 ✓(一直是对的) | 逐位稳定 ✓ |

数据见 `diag-tp-before-after.txt`(before 4 样本 / after 5 样本,verbatim)。

## 视觉证据(真机 F2,run-observer)

- `before-user-repro.png`:用户报告场景复现——激光点串伸向墙,光池在脚边地面(点串
  连线在屏幕上不穿过光池中心,同帧不同轴)。
- `before-profile-vantage.png`:南侧剖面机位,同样不同轴。
- `after-money-shot-convergence.png` ★:Dev 下俯 14°,激光点串从枪口一路汇聚,
  **光池椭圆正好落在点串汇聚处**(mdir-look 夹角 0.4°)。
- `after-v3-rotation-check.png`:换机位后光池仍钉在点串上(灯的世界方向不再随相机转)。
- `after-v1-east.png` / `after-v2-southeast.png` / `after-level-east.png`:其余机位对照。

## 修复(TDD)

- `MuzzlePoseMath.extractTpBeamAxis(Matrix4f)`:读 (m20,m21,m22) + 有限性/退化守卫;
  契约真值 = `matrix.transformDirection(0,0,1)`(joml 顶点变换布局)。
- `BeamRendererMixin` TP 分支改调该函数(一行读数修正 + 注释)。
- `MuzzlePoseMathContract` 新增 4 项:列像语义、null 拒绝、**相机不变性**
  (同一实体枪姿 × 两个相机 → 世界方向恒等)、世界方向 = 实体枪姿旋转·(+Z)。
  红灯实证:临时以旧读数实现跑契约 → `FAIL 束轴提取:+Z 轴列像`(13:52);
  修复后 `MuzzlePoseMathContract: ALL PASS (35 checks)`、`AllContracts: ALL PASS`。

## 波及面更正(诚实回写)

此前多轮"实机解读"被本 bug 污染,随本证据包一并更正:
- 里程碑②"束向下垂 26-32°=真实腰射枪姿"——**误判**,是行/列读数误差在该机位的假象;
- 步行态"束向反平行翻转"——是行读数随相机/姿态变化的表症,`alignBeamAway` 一直
  在给症状打补丁(保留:物理不变式仍作符号保险);
- 里程碑③"捕获链零改动跟随 playerAnimator"——单机位自洽验证,未暴露方向 bug;
  `2026-09-02-playeranimator-sprint/README.md` 结论 3 中"mdir 与 look 前向一致"
  与其自带 diag 样本(mdir-look 夹角 52.4°)矛盾,系误判,以本证据包为准。

## 坑位册

坑68 已入册 `docs/调试环境搭建计划.md` §6(含 joml mXY=列X行Y 速查)。

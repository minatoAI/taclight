// TacLight 调试视图总开关(单一入口;composite 与 final 共同消费 —— 改这一处值,然后 !reload)。
// 值语义(doc/调试环境搭建计划.md §3H):
//   0 关(正常画面)  1 法线审计盒(composite 左下)   2 colortex3.a 遮挡系数热图
//   3 SSO 屏蔽掩码全屏  4 体积光(colortex4)单独      5 bloom 链(colortex1+2,增益后)
//   6 视图空间线性深度  7 材质解码审计(composite:R=smoothness G=F0×2.5 B=金属)
//   8 表面光(colortex0,M1 照明,无光束/bloom;×6+γ0.45 显示增益)—— W3 同轴判定"表面光斑"腿
#define TACLIGHT_DBG_STRIP 0

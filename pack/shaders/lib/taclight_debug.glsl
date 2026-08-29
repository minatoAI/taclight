// TacLight 调试视图总开关(单一入口;composite 与 final 共同消费 —— 改这一处值,然后 !reload)。
// 值语义(doc/调试环境搭建计划.md §3H):
//   0 关(正常画面)  1 法线审计盒(composite 左下)   2 colortex3.a 遮挡系数热图
//   3 SSO 屏蔽掩码全屏  4 体积光(colortex4)单独      5 bloom 链(colortex1+2,增益后)
//   6 视图空间线性深度
#define TACLIGHT_DBG_STRIP 0

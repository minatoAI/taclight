# 2026-09-02 配件适配里程碑①:全枪械 allow 白名单 + ak47"装灯假失败"根因修复

## 结论

1. **全枪械白名单**:为 tacz 默认包 54 把枪生成 `tacz_tags/attachments/allow_attachments/<gun>.json`
   (tools/gen-gunlight-allow.js 生成,契约 GunLightAllowContract 钉死覆盖数与格式),
   AllowAttachmentTagMatcher.match 对全部枪通过。
2. **ak47 装灯"假失败"根因 = TaCZ 写读门不对称(坑62)**:installAttachment(2参)只过
   标签匹配(写 NBT 成功);getAttachment/getAttachmentTag 先过 per-gun 数据字段
   `allow_attachment_types`(ak47 无 LASER 项=读路径全拒)。**修=我方消费者绕过读门
   直读原始 NBT**(`Attachment<TypeName>` 键 + ItemStack.of,resolveLaserId 三级:
   gated→rawNBT→builtin),不改 TaCZ 语义。探针:KIT-INSTALL(allowed/slot/tags)+
   DIAG-ALLOW + GunLaserReaderContract 14 项。
3. **实机验证**:ak47 装灯→枪灯亮;归属证据=ak47-attribution-gunonly.png(手持灯关、
   地面光池仍在=枪灯贡献);hk416d-regression.png=原白名单枪零回归。
   机制行见 live-mechanism-lines.txt(现行日志;上午轮原始日志随 latest.log 轮转未存档,
   判定数字以本包截图+契约为准,机制可由 KIT-INSTALL 行复现)。

## 文件

- ak47-gunlight-on.png:ak47+gun_light 灯亮全景
- ak47-attribution-gunonly.png:归属对照(仅枪灯)
- hk416d-regression.png:hk416d 回归
- live-mechanism-lines.txt:KIT-INSTALL/DIAG-ALLOW 机制行

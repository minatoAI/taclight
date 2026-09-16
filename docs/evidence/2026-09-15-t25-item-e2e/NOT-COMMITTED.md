# 本目录"清单完整、但 4 张 PNG 不在仓内"是**预期状态**，不是缺件

**用户 2026-09-17 裁决：本目录的 4 张 PNG 有意不入库**（体积 + "截图不入库"政策）。
入仓的只有 3 个文本：`README.md`、`manifest.sha256`、`imgdiff-floor.json`（三者内容未被改动，sha256 与 `manifest.sha256` 中对应行一致）。

## 有意不入库的 4 张 PNG

| 文件 | 字节 | sha256（逐字取自 `manifest.sha256`，已在工作区实测复核） |
|---|---:|---|
| `taclight-fail-t25-neg-flashlight-1.png` | 5,822,435 | `9dd59ab20857b10f38a52c8ee4bd963721e032a0deed6f227210d0f076cd14bc` |
| `taclight-t25-chestplate-a.png` | 5,835,002 | `8483a772b7fb75bb3776d7c1f05e86a3eb6163857d8bd56f3ca40ebed12dedc3` |
| `taclight-t25-chestplate-b.png` | 5,833,285 | `bf4d798ef74ac7262f476c39d01b3e7dac5dbc20571a30eeab1a001a675784ec` |
| `taclight-t25-final-a.png` | 5,851,454 | `846c5ffebe181326c31994d0498be22ae2e6fdd1b930ec7c655d126534f48982` |
| **合计** | **23,342,176 B（22.26 MiB）** | |

## 因此，`manifest.sha256` 的这 4 行在本仓内**没有对应文件** —— 属**预期**，不是缺件

- 保留这些 sha256 行的目的正是**溯源**：图若在本地/交付介质上存在，可据此校验其未被篡改/替换。
- **入库时点核对**：`manifest.sha256` 的 6 行在提交前对工作区全部实测 MATCH（`README.md`、`imgdiff-floor.json` 与 4 张 PNG 全部一致）⇒ 清单本身有效，不是"指向不存在文件的假清单"。
- 图的取证语境见 `README.md`（task-25 一遍过证据帧、floor 门控对子、诚实失败对照帧）；量法数见 `imgdiff-floor.json`。

#!/usr/bin/env bash
# 生成全枪械 gun_light 许可覆盖文件(2026-09-02 配件适配里程碑①)。
# 用法: tools/gen-gunlight-allow.sh [tacz.jar路径,默认 libs/tacz-1.1.8-hotfix.jar]
# 机制: TaCZ AllowAttachmentTagMatcher 按枪械 id 读 allow_attachments/<id>.json,
#       空集/缺文件 = 全禁止。故生成规则 = 复制默认列表并追加 #taclight:taclight_laser;
#       无默认文件的枪(重武器等)新建只含我们 tag 的文件(只增不删)。
set -euo pipefail
cd "$(dirname "$0")/.."
JAR="${1:-libs/tacz-1.1.8-hotfix.jar}"
OUT="src/main/resources/assets/taclight/gunpack/data/tacz/tacz_tags/attachments/allow_attachments"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
TAR=/c/Windows/System32/tar.exe   # Windows 内置 bsdtar,可读 zip/jar(Git Bash 的 GNU tar 不行)
"$TAR" -xf "$JAR" -C "$TMP" \
  "assets/tacz/custom/tacz_default_gun/data/tacz/index/guns" \
  "assets/tacz/custom/tacz_default_gun/data/tacz/tacz_tags/attachments/allow_attachments"
node tools/gen-gunlight-allow.js \
  "$TMP/assets/tacz/custom/tacz_default_gun" "$OUT"

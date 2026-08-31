#!/bin/bash
# 移动循环驱动(09-01 深夜②):平移+缩放循环,视角锁死(纯位移,方向链静默)
# 用法: move-run.sh   —— 通过 A 端中继驱动 Dev;B 端 mcap 需已布防
A=/e/dshHome/mc-mod-spotlight-attachment/taclight/run/taclight-cmds.txt
send() { printf '%s\n' "$1" > "$A"; sleep 0.8; }

# 锚定双方 + 三件套(坑39)+ Dev 灯开
send "/tp Dev 2000.5 121 9 188 0"
send "/tp ObserverB 2002.5 121 6.5 188 0"
send "/kill @e[type=!minecraft:player]"
send "/gamerule doMobSpawning false"
send "/taclight light on Dev"
sleep 2

# 平移循环:沿 X 右移 6 步(0.3/步)再左移回(光斑沿墙平移)
x=2000.5
for i in 1 2 3 4 5 6; do x=$(awk "BEGIN{print $x+0.3}"); send "/tp Dev $x 121 9 188 0"; done
for i in 1 2 3 4 5 6; do x=$(awk "BEGIN{print $x-0.3}"); send "/tp Dev $x 121 9 188 0"; done
sleep 1.5

# 缩放循环:朝墙 6 步(z 9->7.2)再退回(光斑放大/缩小)
z=9
for i in 1 2 3 4 5 6; do z=$(awk "BEGIN{print $z-0.3}"); send "/tp Dev 2000.5 121 $z 188 0"; done
for i in 1 2 3 4 5 6; do z=$(awk "BEGIN{print $z+0.3}"); send "/tp Dev 2000.5 121 $z 188 0"; done
sleep 1.5
echo "move-run done"

#!/usr/bin/env python3
# v0.2 MCP 示例 server（零第三方依赖，纯 stdlib）。
#
# 把 taclight-agent 文件桥包装成 MCP tools，给 AI 客户端（MCP host）用：
#   drop_ticket  - 原子丢票到 inbox/（先写 .tmp 再改名，bridge 只认完整 .json）
#   read_receipt - 读 outbox/<ticket>.result.json（轮询等回执）
#   list_inbox / list_outbox - 列队（断链自查）
#
# 运行：
#   set TACLIGHT_AGENT_DIR=E:\...\taclight\run\taclight-agent   (缺省为此)
#   python tools/mcp-agent-server.py
# 本文件走 stdio JSON-RPC（MCP 2024-11：initialize / tools/list / tools/call，
# Content-Length 帧）。自测见 tools/mcp-agent-smoke.py。
"""TacLight agent file-bridge MCP server (v0.2 release artifact)."""

import json
import os
import sys
import time

BASE_DIR = os.environ.get(
    "TACLIGHT_AGENT_DIR",
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                 "run", "taclight-agent"),
)

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "taclight-agent", "version": "0.2.0"}


def _dir(kind):
    d = os.path.join(BASE_DIR, kind)
    os.makedirs(d, exist_ok=True)
    return d


def tool_drop_ticket(args):
    """args: {ticket: str, payload: dict} -> {accepted, path}"""
    name = str(args.get("ticket", "")).strip()
    if not name or "/" in name or "\\" in name or name.startswith("."):
        return {"ok": False, "error": "bad ticket name"}
    payload = args.get("payload")
    if not isinstance(payload, dict) or not isinstance(payload.get("ops"), list):
        return {"ok": False, "error": "payload.ops must be a list"}
    payload = dict(payload)
    payload.setdefault("ticket", name)
    inbox = _dir("inbox")
    tmp = os.path.join(inbox, name + ".json.tmp")
    dst = os.path.join(inbox, name + ".json")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    os.replace(tmp, dst)  # 原子发布：bridge 只读完整 .json
    return {"ok": True, "accepted": name, "path": dst}


def tool_read_receipt(args):
    """args: {ticket: str, timeout_s?: number} -> result json | {ok:false,...}"""
    name = str(args.get("ticket", "")).strip()
    try:
        timeout = float(args.get("timeout_s", 5))
    except (TypeError, ValueError):
        timeout = 5
    timeout = max(0.0, min(timeout, 300.0))
    path = os.path.join(_dir("outbox"), name + ".result.json")
    deadline = time.time() + timeout
    while True:
        if os.path.isfile(path):
            try:
                with open(path, encoding="utf-8") as f:
                    data = json.load(f)
                data["ok"] = True
                data["_receipt"] = path
                return data
            except (OSError, ValueError):
                pass
        if time.time() >= deadline:
            return {"ok": False, "error": "receipt timeout", "ticket": name}
        time.sleep(0.2)


def tool_list(args):
    """args: {which: inbox|outbox|done} -> {files:[...]}"""
    which = str(args.get("which", "inbox"))
    if which not in ("inbox", "outbox", "done"):
        return {"ok": False, "error": "which must be inbox|outbox|done"}
    d = _dir(which)
    files = sorted(f for f in os.listdir(d) if f.endswith(".json"))
    return {"ok": True, "which": which, "files": files}


TOOLS = {
    "drop_ticket": {
        "desc": "原子丢票到 taclight-agent/inbox（bridge 每 tick 收走执行）",
        "schema": {"type": "object",
                   "properties": {"ticket": {"type": "string"},
                                  "payload": {"type": "object"}},
                   "required": ["ticket", "payload"]},
        "fn": tool_drop_ticket,
    },
    "read_receipt": {
        "desc": "读 outbox 回执（可轮询等待）",
        "schema": {"type": "object",
                   "properties": {"ticket": {"type": "string"},
                                  "timeout_s": {"type": "number"}}},
        "fn": tool_read_receipt,
    },
    "list_queue": {
        "desc": "列 inbox/outbox/done（断链自查）",
        "schema": {"type": "object",
                   "properties": {"which": {"type": "string"}}},
        "fn": tool_list,
    },
}


def _respond(obj, out):
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    out.write(f"Content-Length: {len(body)}\r\n\r\n".encode("latin-1"))
    out.write(body)
    out.flush()


def _read_msg(inp):
    headers = {}
    while True:
        raw = inp.readline()
        if not raw:
            return None
        line = raw.decode("latin-1").strip()
        if not line:
            break
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    try:
        length = int(headers.get("content-length", "0"))
    except ValueError:
        return None
    if length <= 0:
        return None
    data = inp.read(length)
    if not data:
        return None
    return json.loads(data.decode("utf-8"))


def handle(msg):
    mid = msg.get("id")
    method = msg.get("method", "")
    params = msg.get("params", {}) or {}
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid,
                "result": {"protocolVersion": PROTOCOL_VERSION,
                           "capabilities": {"tools": {}},
                           "serverInfo": SERVER_INFO}}
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": mid,
                "result": {"tools": [
                    {"name": n, "description": t["desc"],
                     "inputSchema": t["schema"]}
                    for n, t in TOOLS.items()]}}
    if method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments", {}) or {}
        tool = TOOLS.get(name)
        if tool is None:
            return {"jsonrpc": "2.0", "id": mid,
                    "error": {"code": -32602, "message": f"unknown tool: {name}"}}
        try:
            result = tool["fn"](args)
        except Exception as e:  # noqa: BLE001 - 回执通道永不抛
            result = {"ok": False, "error": f"{type(e).__name__}: {e}"}
        return {"jsonrpc": "2.0", "id": mid,
                "result": {"content": [{"type": "text",
                                        "text": json.dumps(result, ensure_ascii=False)}]}}
    if method.startswith("notifications/"):
        return None
    return {"jsonrpc": "2.0", "id": mid,
            "error": {"code": -32601, "message": f"unknown method: {method}"}}


def main():
    inp = sys.stdin.buffer
    out = sys.stdout.buffer
    while True:
        msg = _read_msg(inp)
        if msg is None:
            break
        resp = handle(msg)
        if resp is not None:
            _respond(resp, out)


if __name__ == "__main__":
    main()

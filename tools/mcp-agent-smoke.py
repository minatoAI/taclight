#!/usr/bin/env python3
"""MCP server 自测：起子进程→initialize→tools/list→drop_ticket→伪造回执→read_receipt。
用临时 BASE_DIR，不碰 live 游戏目录。失败 exit 1。"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "mcp-agent-server.py")


class Client:
    def __init__(self, proc):
        self.proc = proc
        self._id = 0

    def call(self, method, params=None):
        self._id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self._id,
                           "method": method, "params": params or {}}).encode()
        self.proc.stdin.write(f"Content-Length: {len(body)}\r\n\r\n".encode("latin-1") + body)
        self.proc.stdin.flush()
        headers = {}
        while True:
            line = self.proc.stdout.readline().decode("latin-1").strip()
            if not line:
                break
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        data = self.proc.stdout.read(int(headers["content-length"]))
        return json.loads(data.decode("utf-8"))


def main():
    tmp = tempfile.mkdtemp(prefix="taclight-agent-smoke-")
    env = dict(os.environ, TACLIGHT_AGENT_DIR=tmp)
    proc = subprocess.Popen([sys.executable, SERVER], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
    try:
        c = Client(proc)
        init = c.call("initialize", {"protocolVersion": "2024-11-05"})
        assert "result" in init, init
        tools = c.call("tools/list")
        names = sorted(t["name"] for t in tools["result"]["tools"])
        assert names == ["drop_ticket", "list_queue", "read_receipt"], names
        print("tools:", names)

        drop = c.call("tools/call", {"name": "drop_ticket",
                                     "arguments": {"ticket": "smoke-1",
                                                   "payload": {"ops": [{"op": "state", "what": "all"}]}}})
        dtext = json.loads(drop["result"]["content"][0]["text"])
        assert dtext["ok"] and dtext["accepted"] == "smoke-1", dtext
        assert os.path.isfile(os.path.join(tmp, "inbox", "smoke-1.json"))
        assert not os.path.exists(os.path.join(tmp, "inbox", "smoke-1.json.tmp")), "tmp 残留！"
        print("drop_ticket OK:", dtext["path"])

        # 伪造 bridge 回执（真机由游戏内 bridge 落盘）
        os.makedirs(os.path.join(tmp, "outbox"), exist_ok=True)
        with open(os.path.join(tmp, "outbox", "smoke-1.result.json"), "w", encoding="utf-8") as f:
            json.dump({"ticket": "smoke-1", "ok": True, "trial": "t9",
                       "ops": [{"op": "state", "ok": True}]}, f)
        read = c.call("tools/call", {"name": "read_receipt",
                                     "arguments": {"ticket": "smoke-1", "timeout_s": 3}})
        rtext = json.loads(read["result"]["content"][0]["text"])
        assert rtext["ok"] is True and rtext["trial"] == "t9", rtext
        print("read_receipt OK: trial", rtext["trial"])

        lst = c.call("tools/call", {"name": "list_queue", "arguments": {"which": "outbox"}})
        ltext = json.loads(lst["result"]["content"][0]["text"])
        assert "smoke-1.result.json" in ltext["files"], ltext
        print("list_queue OK:", ltext["files"])

        miss = c.call("tools/call", {"name": "read_receipt",
                                     "arguments": {"ticket": "nope", "timeout_s": 0.5}})
        mtext = json.loads(miss["result"]["content"][0]["text"])
        assert mtext["ok"] is False, mtext
        print("timeout path OK")
    finally:
        proc.kill()
    print("MCP-SMOKE-ALL-PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())

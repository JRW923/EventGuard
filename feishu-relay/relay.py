#!/usr/bin/env python3
# Feishu relay: 接收 Alertmanager 的 webhook POST（其自有 JSON 格式），
# 转成飞书自定义机器人要求的文本消息，再转发到飞书群机器人。
# 零第三方依赖，仅用 Python 标准库。
import http.server
import json
import os
import sys
import time
import hashlib
import hmac
import base64
import urllib.parse
import urllib.request

FEISHU_WEBHOOK = os.environ.get("FEISHU_WEBHOOK", "")
FEISHU_SECRET = os.environ.get("FEISHU_SECRET", "")
PUSHPLUS_TOKEN = os.environ.get("PUSHPLUS_TOKEN", "")
HOST = os.environ.get("RELAY_HOST", "172.18.0.1")
PORT = int(os.environ.get("RELAY_PORT", "9102"))


def feishu_sign():
    """飞书自定义机器人开启"签名校验"时，需要带 timestamp + sign 参数。"""
    if not FEISHU_SECRET:
        return {}
    ts = str(int(time.time()))
    s = ts + "\n" + FEISHU_SECRET
    # 飞书签名：HMAC-SHA256(key=时间戳+"\n"+密钥, 对空串做 HMAC) 再 base64
    h = base64.b64encode(hmac.new(s.encode("utf-8"), digestmod=hashlib.sha256).digest()).decode("utf-8")
    return {"timestamp": ts, "sign": h}


def send_to_feishu(text):
    if not FEISHU_WEBHOOK:
        sys.stderr.write("FEISHU_WEBHOOK 未配置，跳过发送\n")
        return
    params = feishu_sign()
    url = FEISHU_WEBHOOK
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    payload = json.dumps({"msg_type": "text", "content": {"text": text}}).encode("utf-8")
    req = urllib.request.Request(
        url, data=payload, headers={"Content-Type": "application/json"}
    )
    try:
        resp = urllib.request.urlopen(req, timeout=10).read().decode()  # ponytail: 顺带记录飞书返回，机器人被移出群时返回非 0
        sys.stderr.write("飞书返回: %s\n" % resp)
    except Exception as e:
        sys.stderr.write("发送到飞书失败: %s\n" % e)


def send_to_pushplus(text):
    """PushPlus 个人微信：把同一条告警转发到 PushPlus 接口（需 PUSHPLUS_TOKEN）。"""
    if not PUSHPLUS_TOKEN:
        sys.stderr.write("PUSHPLUS_TOKEN 未配置，跳过发送\n")
        return
    payload = json.dumps({
        "token": PUSHPLUS_TOKEN,
        "title": "EventGuard 告警",
        "content": text,
    }).encode("utf-8")
    req = urllib.request.Request(
        "https://www.pushplus.plus/send",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    try:
        resp = urllib.request.urlopen(req, timeout=10).read()
        sys.stderr.write("PushPlus 返回: %s\n" % resp.decode("utf-8", "replace"))
    except Exception as e:
        sys.stderr.write("发送到 PushPlus 失败: %s\n" % e)


def build_text(alerts):
    parts = ["[EventGuard 告警通知]"]
    for a in alerts:
        labels = a.get("labels", {})
        ann = a.get("annotations", {})
        status = a.get("status", "")
        sev = labels.get("severity", "")
        name = labels.get("alertname", "")
        summary = ann.get("summary", name)
        desc = ann.get("description", "")
        parts.append("- 状态:%s 级别:%s" % (status, sev))
        parts.append("  摘要: %s" % summary)
        if desc:
            parts.append("  详情: %s" % desc)
    return "\n".join(parts)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(n) if n else b"{}"
            data = json.loads(body or b"{}")
            text = build_text(data.get("alerts", []))
            send_to_feishu(text)
            send_to_pushplus(text)
        except Exception as e:
            sys.stderr.write("处理告警失败: %s\n" % e)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    http.server.ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()

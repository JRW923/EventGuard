"""s07 鉴权 / RBAC：角色×端点矩阵 200/403 + 机器密钥受限 + 匿名/坏 token 401 + WS 握手校验。"""
from __future__ import annotations

from .base import Suite

# (角色令牌, 端点, 方法, 期望状态码, 描述)
MATRIX = [
    ("admin", "/users", "GET", 200, "admin 用户管理列表"),
    ("admin", "/approvals", "GET", 200, "admin 审批列表"),
    ("admin", "/orders", "POST", 200, "admin 下单"),
    ("operator", "/orders", "POST", 200, "operator 下单"),
    ("operator", "/approvals", "GET", 200, "operator 审批列表"),
    ("operator", "/users", "POST", 403, "operator 建用户被拒"),
    ("viewer", "/orders", "GET", 200, "viewer 只读列表"),
    ("viewer", "/orders", "POST", 403, "viewer 下单被拒"),
    ("viewer", "/approvals", "GET", 403, "viewer 审批列表被拒"),
]

# 机器密钥（X-API-Key）：仅授 order:read / anomaly:evaluate 等受限权限
MACHINE_MATRIX = [
    ("/anomaly/rules/evaluate", "POST", 200, "机器密钥规则评估"),
    ("/orders", "GET", 200, "机器密钥读订单"),
    ("/orders", "POST", 403, "机器密钥下单被拒"),
]


class AuthRbacSuite(Suite):
    id = "s07_auth_rbac"
    name = "鉴权 / RBAC（角色矩阵 + 机器密钥 + 匿名/坏 token + WS 握手）"

    def execute(self) -> None:
        passed = 0
        total = 0
        for role, path, method, expected, desc in MATRIX:
            total += 1
            payload = {"userId": f"bench-s07-{self.ctx.run_id}", "totalAmount": 10.0} if (
                method == "POST" and path == "/orders") else None
            status = self._http(method, path, role=role, payload=payload)
            ok = status == expected
            passed += ok
            self.add(f"matrix_{role}_{method}_{path.replace('/', '_')}", f"{desc}（期望 {expected}）",
                     ok, expected=str(expected), actual=str(status))
            self.pace()

        for path, method, expected, desc in MACHINE_MATRIX:
            total += 1
            payload = {"userId": "bench-machine", "totalAmount": 10.0} if (
                method == "POST" and path == "/orders") else (
                {"eventId": "11111111-2222-3333-4444-555555555555",
                 "aggregateId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                 "eventType": "OrderCreatedEvent", "version": 1,
                 "occurredAt": "2026-01-01T00:00:00Z",
                 "metadata": {}, "payload": {"userId": "bench-machine", "totalAmount": 10.0}} if path.endswith("/evaluate") else None)
            status = self._http(method, path, machine_key=self.ctx.cfg.machine_key, payload=payload)
            ok = status == expected
            passed += ok
            self.add(f"machine_{method}_{path.replace('/', '_')}", f"{desc}（期望 {expected}）",
                     ok, expected=str(expected), actual=str(status))
            self.pace()

        # 匿名 / 坏 token
        for label, token in (("anonymous", None), ("bad_token", "not-a-jwt")):
            status = self._http("GET", "/orders", token=token)
            ok = status == 401
            passed += ok
            total += 1
            self.add(f"unauth_{label}", f"{label} 访问被拒（期望 401）", ok, expected="401", actual=str(status))
            self.pace()

        # WS 握手：operator/viewer（anomaly:view）通过；无 token / 坏 token 拒绝
        ws = self._try_import_ws()
        if ws is not None:
            ws_url = self.ctx.cfg.server_base.replace("http", "ws") + "/ws/anomalies"
            for label, token, expect_ok in (
                ("operator", self.ctx.auth.token("operator"), True),
                ("viewer", self.ctx.auth.token("viewer"), True),
                ("no_token", None, False),
                ("bad_token", "not-a-jwt", False),
            ):
                result = self._ws_handshake(ws, ws_url, token)
                ok = result == expect_ok
                passed += ok
                total += 1
                self.add(f"ws_{label}", f"WS 握手 {label}（{'应通过' if expect_ok else '应拒绝'}）",
                         ok, expected=str(expect_ok), actual=str(result))
        else:
            self.result.method_notes.append("websocket-client 未安装，WS 握手校验跳过（不影响 HTTP 矩阵）。")

        rate = round(passed / total, 4) if total else 0.0
        self.result.metrics = {"pass_rate": rate, "passed": passed, "total": total}
        self.result.conclusion = f"RBAC 矩阵通过率 {passed}/{total}（{rate:.1%}）。"

    def _http(self, method: str, path: str, role: str | None = None, token: str | None = None,
              machine_key: str | None = None, payload: dict | None = None) -> int:
        tok = token if token is not None else (self.ctx.auth.token(role) if role else None)
        resp, _ = self.ctx.client.request(method, path, token=tok, machine_key=machine_key, json=payload)
        return resp.status_code

    @staticmethod
    def _try_import_ws():
        try:
            import websocket  # noqa: F401

            return websocket
        except ImportError:
            return None

    @staticmethod
    def _ws_handshake(ws, url: str, token: str | None) -> bool:
        try:
            full = url + (f"?token={token}" if token else "")
            conn = ws.create_connection(full, timeout=5)
            conn.close()
            return True
        except Exception:
            return False

"""账号登录与令牌管理：幂等处理种子账号 must_change_password + token_version 失效。

副作用（文档化）：首次运行会把 admin/operator/viewer 的种子密码改为 BENCH_PASSWORD。
"""
from __future__ import annotations

from .client import ApiClient


class Auth:
    """多角色令牌管理。token 字典 key = admin/operator/viewer。"""

    def __init__(self, client: ApiClient, cfg) -> None:
        self.client = client
        self.cfg = cfg
        self.tokens: dict[str, str] = {}
        self.events: list[dict] = []

    def ensure_roles(self) -> None:
        """确保 admin/operator/viewer 可用（幂等改密到稳定密码并登录取 token）。"""
        for role, username, seed_pw in (
            ("admin", self.cfg.admin_user, self.cfg.admin_password),
            ("operator", self.cfg.operator_user, self.cfg.operator_password),
            ("viewer", self.cfg.viewer_user, self.cfg.viewer_password),
        ):
            self._ensure_role(role, username, seed_pw)

    def _ensure_role(self, role: str, username: str, seed_pw: str) -> None:
        # 1) 先试稳定密码（前次评测已改密过）
        tok = self._try_login(username, self.cfg.bench_password)
        if tok:
            self.tokens[role] = tok
            self.events.append({"role": role, "action": "login_bench_password"})
            return
        # 2) 试种子密码；若 mustChangePassword → 用当前 token 改密到稳定值 → 重登
        resp, _ = self.client.post("/auth/login", json={"username": username, "password": seed_pw})
        if resp.status_code != 200:
            raise RuntimeError(
                f"账号 {username} 登录失败（HTTP {resp.status_code}）："
                f"请核对 BENCH_{role.upper()}_PASSWORD / EG_* 密码环境变量"
            )
        data = resp.json()
        tok = data.get("token")
        user = data.get("user") or {}
        if user.get("mustChangePassword"):
            change_resp, _ = self.client.post(
                "/auth/password",
                token=tok,
                json={"oldPassword": seed_pw, "newPassword": self.cfg.bench_password},
            )
            if change_resp.status_code != 200:
                raise RuntimeError(f"改密失败 {username}（HTTP {change_resp.status_code}）")
            # 改密递增 token_version：旧 token 立即失效，需重新登录
            tok = self._try_login(username, self.cfg.bench_password)
            action = "changed_seed_password"
        else:
            action = "login_seed_password"
        if not tok:
            raise RuntimeError(f"账号 {username} 改密后重新登录仍失败")
        self.tokens[role] = tok
        self.events.append({"role": role, "action": action})

    def _try_login(self, username: str, password: str) -> str | None:
        resp, _ = self.client.post("/auth/login", json={"username": username, "password": password})
        if resp.status_code == 200:
            return resp.json().get("token")
        return None

    def token(self, role: str = "operator") -> str:
        if role not in self.tokens:
            raise RuntimeError(f"未初始化角色令牌：{role}（先调用 ensure_roles）")
        return self.tokens[role]

"""HTTP 客户端：带计时与可选鉴权头，供各套件驱动真实 REST 链路。"""
from __future__ import annotations

import time
from typing import Any

import requests


class ApiClient:
    def __init__(self, base_url: str, timeout: float = 10.0) -> None:
        self.base = base_url.rstrip("/")
        self.session = requests.Session()
        self.timeout = timeout

    def request(
        self,
        method: str,
        path: str,
        token: str | None = None,
        machine_key: str | None = None,
        timeout: float | None = None,
        **kw: Any,
    ) -> tuple[requests.Response, float]:
        """返回 (response, 耗时毫秒)。path 以 / 开头。"""
        headers = dict(kw.pop("headers", {}) or {})
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if machine_key:
            headers["X-API-Key"] = machine_key
        if "json" in kw and "Content-Type" not in headers:
            headers["Content-Type"] = "application/json"
        t0 = time.time()
        resp = self.session.request(
            method, self.base + path, timeout=timeout or self.timeout, headers=headers, **kw
        )
        elapsed = (time.time() - t0) * 1000.0
        return resp, elapsed

    # —— 便捷方法 ——
    def get(self, path: str, **kw) -> tuple[requests.Response, float]:
        return self.request("GET", path, **kw)

    def post(self, path: str, **kw) -> tuple[requests.Response, float]:
        return self.request("POST", path, **kw)

    def close(self) -> None:
        self.session.close()

"""Prometheus 查询：仅做交叉验证/看板参考。延迟分位数以 bench 自身时间戳为准。"""
from __future__ import annotations

import requests


class Prometheus:
    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self.base = base_url.rstrip("/")
        self.timeout = timeout

    def query(self, expr: str) -> float | None:
        """instant query，返回单个样本的 value；无样本/出错返回 None。"""
        try:
            resp = requests.get(
                f"{self.base}/api/v1/query",
                params={"query": expr},
                timeout=self.timeout,
            )
            resp.raise_for_status()
            result = resp.json().get("data", {}).get("result", [])
            if not result:
                return None
            value = result[0].get("value")
            return float(value[1]) if value else None
        except (requests.RequestException, ValueError, KeyError):
            return None

    def healthy(self) -> bool:
        try:
            resp = requests.get(f"{self.base}/-/healthy", timeout=self.timeout)
            return resp.status_code == 200
        except requests.RequestException:
            return False

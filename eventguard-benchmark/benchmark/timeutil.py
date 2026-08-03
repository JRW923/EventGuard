"""纯时间/统计工具：无外部依赖，可无栈单测。"""
from __future__ import annotations

import math
import time
from datetime import datetime, timezone


def epoch_ms() -> float:
    return time.time() * 1000.0


def iso_to_epoch_ms(iso_str: str | None) -> float | None:
    """ISO8601（如 2026-07-21T10:00:00Z）→ epoch 毫秒；解析失败返回 None。"""
    if not iso_str:
        return None
    try:
        dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp() * 1000.0
    except (ValueError, TypeError):
        return None


def percentile(sorted_values: list[float], p: float) -> float | None:
    """有序样本的 p 分位（nearest-rank，0<p<=1），空样本返回 None。"""
    if not sorted_values:
        return None
    rank = max(1, min(len(sorted_values), math.ceil(p * len(sorted_values))))
    return float(sorted_values[rank - 1])


def percentiles(values: list[float], ps: tuple[float, ...] = (50, 95, 99)) -> dict[str, float | None]:
    """p50/p95/p99 快捷函数。"""
    sv = sorted(values)
    return {f"p{int(p)}_ms": percentile(sv, p / 100.0) for p in ps}


def mean(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def pct_str(value: float | None, digits: int = 1) -> str:
    return "—" if value is None else f"{value:.{digits}f}"

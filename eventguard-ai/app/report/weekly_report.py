"""运营周报（Item 7）：近期异常聚合 + 后端订单统计 → LLM 生成症状/建议。

聚合计数（by_rule / total_anomalies / top_orders）确定性计算，不信任 LLM；
LLM 只负责生成 symptoms / recommendations 文案，失败时降级为规则摘要。
"""
import json
import logging
from collections import Counter
from datetime import datetime, timedelta, timezone
from typing import Optional

from app.analyzer.llm_client import LLMClient
from app.query.backend_client import BackendClient
from app.store.anomaly_store import AnomalyStore, anomaly_store as default_anomaly_store

logger = logging.getLogger(__name__)

WEEKLY_PROMPT = """你是 EventGuard 电商订单运营数据分析师。基于以下数据，为运营周报写症状与建议。

周期：最近 {days} 天
异常分布（按规则）：
{by_rule}
订单状态统计：
{stats}

请输出严格 JSON，格式：
{{
  "symptoms": ["症状1", "症状2"],
  "recommendations": ["建议1", "建议2"]
}}
只输出 JSON。"""


class WeeklyReportGenerator:
    """运营周报生成器。"""

    def __init__(
        self,
        llm_client: Optional[LLMClient] = None,
        anomaly_store: Optional[AnomalyStore] = None,
        backend_client: Optional[BackendClient] = None,
    ):
        self.llm_client = llm_client or LLMClient()
        self.anomaly_store = anomaly_store or default_anomaly_store
        self.backend_client = backend_client or BackendClient()

    async def generate(self, days: int = 7) -> dict:
        now = datetime.now(timezone.utc)
        from_ = now - timedelta(days=days)
        since = from_.isoformat()

        # 1. 确定性聚合：近期异常
        anomalies = self.anomaly_store.list_recent(since=since, limit=500)
        by_rule = Counter(a.rule_id or "?" for a in anomalies)
        top_orders = sorted(
            Counter(a.aggregate_id for a in anomalies).items(), key=lambda kv: kv[1], reverse=True
        )[:5]

        # 2. 后端订单统计（失败降级为空列表，不阻断）
        stats: list = []
        try:
            stats = await self.backend_client.get_stats(None, from_.isoformat(), now.isoformat())
        except Exception as e:
            logger.warning("周报后端统计失败（降级）：%s", e)

        # 3. LLM 生成症状/建议（失败降级为规则摘要）
        symptoms, recommendations = await self._llm_summary(days, by_rule, stats)

        return {
            "period": {"days": days, "from": from_.isoformat(), "to": now.isoformat()},
            "total_anomalies": len(anomalies),
            "by_rule": [{"rule_id": r, "count": c} for r, c in by_rule.most_common()],
            "order_stats": stats,
            "symptoms": symptoms,
            "recommendations": recommendations,
            "top_orders": [{"aggregate_id": aid, "count": c} for aid, c in top_orders],
        }

    async def _llm_summary(self, days: int, by_rule: Counter, stats: list) -> tuple[list, list]:
        try:
            total_orders = sum(int(o.get("orderCount", 0)) for o in stats)
            prompt = WEEKLY_PROMPT.format(
                days=days,
                by_rule=json.dumps(dict(by_rule), ensure_ascii=False),
                stats=json.dumps(stats, ensure_ascii=False),
            )
            text = await self.llm_client.generate_json(prompt, operation="weekly_report")
            data = json.loads(text)
            return data.get("symptoms", []), data.get("recommendations", [])
        except Exception as e:
            logger.warning("周报 LLM 生成失败，降级为规则摘要：%s", e)
            top = "、".join(k for k, _ in by_rule.most_common(3)) or "无"
            return [f"检测到 {sum(by_rule.values())} 条异常，集中在 {top}"], [
                "请优先处理高优先级异常，并复核高频规则的触发条件"
            ]

"""根因分析器：LLM 生成结构化 JSON + Pydantic 校验 + 证据核验（Item 3：输出可靠性）

相比 MVP 的"一次 LLM 调用 + 白名单兜底"，此处升级为：
1. 结构化输出（generate_json，openai response_format / anthropic 强约束）
2. 错误反馈重试：JSON 解析失败 / Pydantic 校验失败时，把错误喂回 LLM 修正一次
3. 证据核验：evidence 中提及的事件类型必须存在于订单事件序列，否则要求重写
仍保持"LLM 输出不可信"假设：最终经白名单 + 证据自检双重校验，不自动执行任何动作。
"""

import json
import logging
import re
from typing import Optional

from pydantic import ValidationError

from app.analyzer.llm_client import LLMClient
from app.analyzer.prompt_builder import PromptBuilder
from app.model.anomaly import Anomaly
from app.model.analysis_report import AnalysisReport
from app.store.event_store_client import EventStoreClient

logger = logging.getLogger(__name__)

# 结构化输出 + 证据核验的最大尝试次数（1 次正常 + 1 次错误反馈重试）
MAX_ATTEMPTS = 2


class LLMResponseError(Exception):
    """LLM 响应解析或校验失败"""


class RootCauseAnalyzer:
    """根因分析：加载事件 → 构建 prompt → LLM 结构化输出 → Pydantic 校验 → 证据核验"""

    def __init__(
        self,
        llm_client: Optional[LLMClient] = None,
        event_store_client: Optional[EventStoreClient] = None,
    ):
        self.llm_client = llm_client or LLMClient()
        self.event_store_client = event_store_client or EventStoreClient()

    async def analyze(self, anomaly: Anomaly) -> AnalysisReport:
        """
        分析异常根因。

        Args:
            anomaly: 异常对象

        Returns:
            AnalysisReport 根因分析报告

        Raises:
            LLMResponseError: LLM 输出无法解析 / 校验失败 / 证据核验不通过
        """
        # 1. 加载事件历史
        events = self.event_store_client.load_events(anomaly.aggregate_id)

        # 2. 加载上下文（MVP 简化）
        context = {
            "anomaly_rule": anomaly.rule_id,
            "anomaly_description": anomaly.description,
        }

        # 3. 构建 prompt
        prompt = PromptBuilder.build(anomaly, events, context)

        # 4. LLM 结构化输出 + 错误反馈重试 + 证据核验
        feedback: Optional[str] = None
        last_error: Optional[Exception] = None
        for _ in range(MAX_ATTEMPTS):
            try:
                raw_response = await self.llm_client.generate_json(
                    prompt if feedback is None else prompt + "\n\n## 修正要求\n" + feedback
                )
            except Exception as e:  # 网络故障 + 响应结构异常统一归一口径
                logger.error("LLM 调用/响应异常: %s", e)
                raise LLMResponseError(f"LLM 调用或响应异常: {e}") from e

            # 5. 解析 JSON + Pydantic 校验
            try:
                data = json.loads(raw_response)
            except json.JSONDecodeError as e:
                logger.warning("LLM 输出 JSON 解析失败（将重试）：%s", e)
                last_error = e
                feedback = "JSON 解析失败。请只输出单个 JSON 对象，不要包含解释文字或 Markdown 代码块。"
                continue
            try:
                report = AnalysisReport(**data)
            except (ValidationError, TypeError) as e:
                logger.warning("AnalysisReport 校验失败（将重试）：%s", e)
                last_error = e
                feedback = f"报告校验失败：{e}。请修正后重新输出。"
                continue

            # 6. 证据核验：evidence 提及的事件类型须在订单事件序列中（防 LLM 编造证据）
            if not self._evidence_plausible(report, events):
                logger.warning("证据核验不通过（将重试）：evidence 事件不在序列中")
                last_error = None
                feedback = (
                    "证据核验失败：evidence 中提及的事件类型不在订单事件序列中。"
                    "请严格基于上面的事件序列修正证据，不要编造。"
                )
                continue

            return report

        raise LLMResponseError(f"LLM 输出不可用：{last_error}")

    @staticmethod
    def _evidence_plausible(report: AnalysisReport, events: list[dict]) -> bool:
        """证据核验：任一条 evidence 若提及事件类型（*Event），至少有一个须出现在事件序列中。

        无事件序列 / 证据未提及事件类型时不强校验（避免误伤基于库存、金额等非事件证据）。
        """
        if not events or not report.evidence:
            return True
        event_types = {e.get("event_type") for e in events if e.get("event_type")}
        if not event_types:
            return True
        for item in report.evidence:
            mentioned = set(re.findall(r"[A-Za-z]+Event", item or ""))
            if mentioned and not (mentioned & event_types):
                return False
        return True

"""根因分析器：LLM 生成结构化 JSON + Pydantic 校验"""

import json
import logging
from typing import Optional

from app.analyzer.llm_client import LLMClient
from app.analyzer.prompt_builder import PromptBuilder
from app.model.anomaly import Anomaly
from app.model.analysis_report import AnalysisReport
from app.store.event_store_client import EventStoreClient

logger = logging.getLogger(__name__)


class LLMResponseError(Exception):
    """LLM 响应解析或校验失败"""


class RootCauseAnalyzer:
    """根因分析：加载事件 → 构建 prompt → LLM 生成 → Pydantic 校验"""

    def __init__(
        self,
        llm_client: Optional[LLMClient] = None,
        event_store_client: Optional[EventStoreClient] = None,
    ):
        self.llm_client = llm_client or LLMClient()
        self.event_store_client = event_store_client or EventStoreClient()

    def analyze(self, anomaly: Anomaly) -> AnalysisReport:
        """
        分析异常根因。

        Args:
            anomaly: 异常对象

        Returns:
            AnalysisReport 根因分析报告

        Raises:
            LLMResponseError: LLM 输出无法解析或建议不在白名单
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

        # 4. LLM 生成
        raw_response = self.llm_client.generate(prompt)

        # 5. 解析 JSON
        try:
            data = json.loads(raw_response)
        except json.JSONDecodeError as e:
            logger.error("LLM 输出 JSON 解析失败: %s", e)
            raise LLMResponseError(f"LLM 输出不是合法 JSON: {e}") from e

        # 6. Pydantic 校验（建议白名单在 Suggestion.action 校验器中）
        try:
            report = AnalysisReport(**data)
        except Exception as e:
            logger.error("AnalysisReport 校验失败: %s", e)
            raise LLMResponseError(f"报告校验失败: {e}") from e

        return report

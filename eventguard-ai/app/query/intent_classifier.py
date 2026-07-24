"""意图分类器：LLM 分类 + 关键词兜底。

设计文档 7.3.3 第 3 层 NL 查询 MVP。
"""
import logging
from typing import Optional

from app.analyzer.llm_client import LLMClient
from app.query.prompts import INTENT_SYSTEM_PROMPT, INTENT_USER_TEMPLATE

logger = logging.getLogger(__name__)


class IntentClassifier:
    """意图分类器，3 类意图：event_lookup / stats_aggregation / trace_replay。

    LLM 优先，失败或返回非法标签时走关键词兜底。
    """

    VALID_INTENTS = ("event_lookup", "stats_aggregation", "trace_replay")

    # 关键词兜底规则（按优先级，第一个命中的返回）
    KEYWORD_RULES = (
        ("stats_aggregation", ("多少", "数量", "统计", "count", "总数", "占比")),
        ("trace_replay", ("状态变更", "经历了", "事件回放", "事件历史", "回放", "时间线")),
        ("event_lookup", ("当前", "状态是", "信息", "详情", "查询订单")),
    )

    def __init__(self, llm_client: Optional[LLMClient] = None):
        self.llm_client = llm_client or LLMClient()

    def classify(self, question: str) -> str:
        """对用户问题分类，返回 3 类意图之一。

        Args:
            question: 用户自然语言问题

        Returns:
            意图标签：event_lookup / stats_aggregation / trace_replay
        """
        # 1. LLM 分类
        intent = self._classify_by_llm(question)
        if intent is not None:
            return intent

        # 2. 关键词兜底
        fallback = self._classify_by_keyword(question)
        logger.info("LLM 分类失败，关键词兜底：%s -> %s", question, fallback)
        return fallback

    def _classify_by_llm(self, question: str) -> Optional[str]:
        """调用 LLM 分类，返回合法意图或 None。"""
        try:
            prompt = INTENT_SYSTEM_PROMPT + "\n" + INTENT_USER_TEMPLATE.format(question=question)
            raw = self.llm_client.generate(prompt)
            label = raw.strip().lower()
            # 兼容 LLM 可能返回带标点或多余文字
            for intent in self.VALID_INTENTS:
                if intent in label:
                    return intent
            logger.warning("LLM 返回非法意图标签：%s", raw)
            return None
        except Exception as e:
            logger.warning("LLM 分类异常：%s", e)
            return None

    def _classify_by_keyword(self, question: str) -> str:
        """关键词兜底分类。"""
        q = question.lower()
        for intent, keywords in self.KEYWORD_RULES:
            for kw in keywords:
                if kw in q:
                    return intent
        # 默认 event_lookup
        return "event_lookup"

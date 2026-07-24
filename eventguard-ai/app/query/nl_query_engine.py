"""NL 查询引擎：意图分类 → 模板执行 → LLM 润色回答。"""
import json
import logging
from typing import Optional

from app.analyzer.llm_client import LLMClient
from app.query.intent_classifier import IntentClassifier
from app.query.prompts import NL_ANSWER_SYSTEM_PROMPT, NL_ANSWER_USER_TEMPLATE
from app.query.query_result import QueryResult
from app.query.template_executor import TemplateExecutor

logger = logging.getLogger(__name__)


class NLQueryEngine:
    """自然语言查询引擎。

    流程：IntentClassifier 分类 → TemplateExecutor 模板查询 → LLMClient 润色回答。
    """

    def __init__(
        self,
        intent_classifier: Optional[IntentClassifier] = None,
        template_executor: Optional[TemplateExecutor] = None,
        llm_client: Optional[LLMClient] = None,
    ):
        self.intent_classifier = intent_classifier or IntentClassifier()
        self.template_executor = template_executor or TemplateExecutor()
        self.llm_client = llm_client or LLMClient()

    def query(self, question: str) -> QueryResult:
        """处理用户问题，返回 QueryResult。"""
        # 1. 意图分类
        intent = self.intent_classifier.classify(question)
        logger.info("NL 查询意图：%s（问题：%s）", intent, question)

        # 2. 模板路由（缺订单号/未知意图时返回友好结果而非 500）
        try:
            data = self._route_template(intent, question)
        except ValueError as e:
            # ponytail: MVP 不接 Text-to-SQL，无法从问题提取订单号时直接告知用户，避免裸异常 500
            logger.warning("NL 路由失败：%s", e)
            return QueryResult(intent=intent, data=None, answer=str(e))

        # 3. LLM 润色回答
        answer = self._generate_answer(question, intent, data)

        return QueryResult(intent=intent, data=data, answer=answer)

    def _route_template(self, intent: str, question: str):
        """根据意图路由到对应模板。"""
        if intent == "event_lookup":
            return self.template_executor.execute_event_lookup(question)
        elif intent == "stats_aggregation":
            return self.template_executor.execute_stats_aggregation(question)
        elif intent == "trace_replay":
            return self.template_executor.execute_trace_replay(question)
        else:
            raise ValueError(f"未知意图：{intent}")

    def _generate_answer(self, question: str, intent: str, data) -> str:
        """LLM 润色回答，失败时返回数据摘要。"""
        try:
            result_str = json.dumps(data, ensure_ascii=False, default=str)
            prompt = NL_ANSWER_SYSTEM_PROMPT + "\n" + NL_ANSWER_USER_TEMPLATE.format(
                question=question, intent=intent, result=result_str
            )
            return self.llm_client.generate(prompt).strip()
        except Exception as e:
            logger.warning("LLM 润色失败，返回数据摘要：%s", e)
            return self._fallback_answer(intent, data)

    def _fallback_answer(self, intent: str, data) -> str:
        """LLM 失败时的兜底回答（数据摘要）。"""
        if intent == "event_lookup" and isinstance(data, dict):
            return f"订单状态：{data.get('status', '未知')}，版本：{data.get('version', '未知')}。"
        elif intent == "stats_aggregation" and isinstance(data, list):
            parts = [f"{item.get('status')}: {item.get('orderCount')} 单" for item in data]
            return "统计结果：" + "；".join(parts) if parts else "未查询到数据。"
        elif intent == "trace_replay" and isinstance(data, list):
            events = [item.get("eventType", "?") for item in data]
            return f"事件序列：{' → '.join(events)}" if events else "未查询到事件。"
        return "查询完成。"

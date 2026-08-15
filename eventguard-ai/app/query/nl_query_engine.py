"""NL 查询引擎：意图分类 → 模板执行 → LLM 润色回答。"""
import asyncio
import inspect
import json
import logging
import time
from typing import Optional

from app import metrics as egm
from app.analyzer.llm_client import LLMClient
from app.config import settings
from app.query.conversation_store import Conversation
from app.query.conversation_store import conversation_store as default_conversation_store
from app.query.intent_classifier import IntentClassifier
from app.query.prompts import NL_ANSWER_SYSTEM_PROMPT, NL_ANSWER_USER_TEMPLATE
from app.query.query_result import QueryResult
from app.query.template_executor import TemplateExecutor
from app.trace.trace_log import trace_log

logger = logging.getLogger(__name__)

# LLM 润色回答的超时上界：LLM 底层 httpx 超时 30s，但前端 axios 只等 10s——
# 不加这个上界，慢 LLM 会让前端先中止、用户看到「查询失败」而非降级摘要。
# 8s 内 LLM 无响应 → _generate_answer 捕获超时 → 返回数据摘要，保证 10s 内必有回答。
# 可用 EG_NL_ANSWER_TIMEOUT_SECONDS 覆盖；改这个值时要同步确认仍小于前端 axios 的 10s。
LLM_ANSWER_TIMEOUT_SECONDS = settings.nl_answer_timeout_seconds
NL_QUERY_TIMEOUT_SECONDS = settings.nl_query_timeout_seconds
NL_INTENT_TIMEOUT_SECONDS = settings.nl_intent_timeout_seconds

# 缺参追问提示：目前唯一必填参数是 order_id（event_lookup / trace_replay 缺了无法查询）
PENDING_PARAM_HINTS = {
    "order_id": "请提供订单号（例如：订单 12345678-… 当前状态是什么？）",
}


class NLQueryEngine:
    """自然语言查询引擎（支持多轮追问）。

    流程：IntentClassifier 分类 → TemplateExecutor 模板查询 → LLMClient 润色回答。
    缺参（如订单号）时进入追问：向用户反问，下一轮携带 conversation_id 补齐参数后重查。
    """

    def __init__(
        self,
        intent_classifier: Optional[IntentClassifier] = None,
        template_executor: Optional[TemplateExecutor] = None,
        llm_client: Optional[LLMClient] = None,
        conversation_store: Optional[object] = None,
    ):
        self.intent_classifier = intent_classifier or IntentClassifier()
        self.template_executor = template_executor or TemplateExecutor()
        self.llm_client = llm_client or LLMClient()
        self.conversation_store = conversation_store or default_conversation_store

    async def query(
        self, question: str, conversation_id: Optional[str] = None, trace_id: Optional[str] = None
    ) -> QueryResult:
        """处理用户问题（可携带会话 id 续聊），返回 QueryResult。"""
        # 1. 取/建会话（无 conversation_id 即开新会话）
        _t0 = time.monotonic()
        conv = self.conversation_store.get_or_create(conversation_id)

        # 2. 意图分类
        try:
            intent = await asyncio.wait_for(
                self.intent_classifier.classify(question), timeout=NL_INTENT_TIMEOUT_SECONDS
            )
        except asyncio.TimeoutError:
            intent = self.intent_classifier._classify_by_keyword(question)
            logger.warning("NL 意图分类超时，使用关键词兜底：%s", intent)
        logger.info("NL 查询意图：%s（问题：%s，会话：%s）", intent, question, conv.conversation_id)

        fallback = "false"
        try:
            try:
                # 3. 模板路由（缺订单号/未知意图时抛出，转追问）
                data = await asyncio.wait_for(
                    self._route(intent, question, conv), timeout=self._remaining_timeout(_t0)
                )
            except ValueError as e:
                fallback = "true"
                hint = self._ask_for_param(intent, conv)
                self._append_history(conv, question, intent, None, hint, needs_input=True)
                logger.warning("NL 缺参追问：%s（%s）", hint, e)
                return QueryResult(
                    intent=intent, data=None, answer=hint,
                    conversation_id=conv.conversation_id, needs_input=True,
                )
            except asyncio.TimeoutError:
                fallback = "true"
                answer = "查询处理超时，已停止等待，请稍后重试。"
                self._append_history(conv, question, intent, None, answer)
                return QueryResult(
                    intent=intent, data=None, answer=answer,
                    conversation_id=conv.conversation_id,
                )

            # 4. LLM 润色回答
            answer = await self._generate_answer(
                question, intent, data, trace_id=trace_id, timeout=self._remaining_timeout(_t0)
            )
            # LLM 失败时 _generate_answer 内部已降级为数据摘要，这里据此标记 fallback
            if answer == self._fallback_answer(intent, data):
                fallback = "true"
            self._append_history(conv, question, intent, data, answer)
            return QueryResult(
                intent=intent, data=data, answer=answer, conversation_id=conv.conversation_id,
            )
        finally:
            elapsed = time.monotonic() - _t0
            egm.nl_query_duration.labels(intent=intent).observe(elapsed)
            egm.nl_query_total.labels(intent=intent, fallback=fallback).inc()
            trace_log.record(
                "nl_query", intent=intent, conversation_id=conv.conversation_id,
                latency_ms=round(elapsed * 1000, 1), fallback=fallback,
                trace_id=trace_id,
            )

    @staticmethod
    def _remaining_timeout(started: float) -> float:
        return max(0.01, NL_QUERY_TIMEOUT_SECONDS - (time.monotonic() - started))

    async def _route(self, intent: str, question: str, conv: Conversation):
        """模板路由；缺 order_id 时若会话上下文已有订单号则补参重试一次。"""
        try:
            data = await self._route_template(intent, question)
            self._capture_context(intent, question, conv)
            return data
        except ValueError:
            if conv.context.get("order_id"):
                # 追问轮次：把会话里的订单号补进问题再查一次
                augmented = f"{question} 订单号 {conv.context['order_id']}"
                return await self._route_template(intent, augmented)
            raise

    def _capture_context(self, intent: str, question: str, conv: Conversation) -> None:
        """路由成功后把问题里的订单号存进会话上下文，供后续追问补参。"""
        try:
            order_id = self.template_executor.resolve_order_id(question)
            if inspect.isawaitable(order_id):
                # TemplateExecutor 的参数提取是同步纯函数；忽略错误注入的异步实现，避免泄漏协程。
                if inspect.iscoroutine(order_id):
                    order_id.close()
                order_id = None
        except Exception:  # mock/异常执行器下防御
            order_id = None
        if isinstance(order_id, str) and order_id:
            conv.context["order_id"] = order_id
        conv.pending.pop("order_id", None)

    def _ask_for_param(self, intent: str, conv: Conversation) -> str:
        """缺参时登记待补参数并返回反问语。"""
        if intent in ("event_lookup", "trace_replay"):
            conv.pending["order_id"] = "uuid"
            conv.context.pop("order_id", None)  # 反问时不沿用旧上下文，避免指代歧义
            return PENDING_PARAM_HINTS["order_id"]
        return f"暂不支持该查询意图：{intent}"

    @staticmethod
    def _append_history(
        conv: Conversation, question: str, intent: str, data, answer: str,
        needs_input: bool = False,
    ) -> None:
        conv.history.append({
            "question": question,
            "intent": intent,
            "answer": answer,
            "needs_input": needs_input,
            "data": data,
        })
        if len(conv.history) > 20:
            conv.history = conv.history[-20:]

    async def _route_template(self, intent: str, question: str):
        """根据意图路由到对应模板。"""
        if intent == "event_lookup":
            return await self.template_executor.execute_event_lookup(question)
        elif intent == "stats_aggregation":
            return await self.template_executor.execute_stats_aggregation(question)
        elif intent == "trace_replay":
            return await self.template_executor.execute_trace_replay(question)
        else:
            raise ValueError(f"未知意图：{intent}")

    async def _generate_answer(
        self, question: str, intent: str, data, trace_id: Optional[str] = None, timeout: Optional[float] = None
    ) -> str:
        """LLM 润色回答，失败时返回数据摘要。"""
        try:
            result_str = json.dumps(data, ensure_ascii=False, default=str)
            prompt = NL_ANSWER_SYSTEM_PROMPT + "\n" + NL_ANSWER_USER_TEMPLATE.format(
                question=question, intent=intent, result=result_str
            )
            return (await asyncio.wait_for(
                self.llm_client.generate(prompt, operation="nl_answer", trace_id=trace_id),
                timeout=min(LLM_ANSWER_TIMEOUT_SECONDS, timeout or LLM_ANSWER_TIMEOUT_SECONDS),
            )).strip()
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

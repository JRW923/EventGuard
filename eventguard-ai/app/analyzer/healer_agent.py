"""ReAct 自愈 Agent（Item 6a · 只读分析闭环）。

对照设计文档 §7.3.4 的 HealerAgent：TOOLS=[query_order, query_events, query_stats]（本阶段只读），
MAX_STEPS=5，agent 多轮工具调用收集证据 → 最终结构化报告仍由 RootCauseAnalyzer 生成
（保证白名单校验 + 证据核验的可靠性），agent_trace 作为可解释的"分析过程"返回前端展示。

6b 将在此之上增加写工具（submit_compensation / request_approval）与审批闭环。
"""
import json
import logging
from datetime import datetime, timedelta, timezone
from typing import Optional

from app.analyzer.llm_client import LLMClient
from app.analyzer.root_cause import LLMResponseError, RootCauseAnalyzer
from app.model.analysis_report import AnalysisReport
from app.model.anomaly import Anomaly
from app.query.backend_client import BackendClient

logger = logging.getLogger(__name__)

# 每轮 agent 最大工具调用/推理步数（防 LLM 死循环；用尽则走确定性兜底）
MAX_STEPS = 5

AGENT_SYSTEM_PROMPT = """你是 EventGuard 电商订单异常处置助手。当前要分析一笔异常订单的根因。

你可以调用以下工具收集证据：
- query_order：查订单当前状态与基本信息
- query_events：查订单完整事件序列（版本升序），用于回放异常前后的因果链
- query_stats：按状态与时间窗查订单统计

流程：先调用工具收集必要证据（通常 1-3 次），当掌握足够信息后，直接回复「分析完成」并简要总结，
不要继续调用工具。你的工具调用会被记录为分析过程。"""

TASK_PROMPT = """请分析以下异常订单的根因，必要时先调用工具收集证据，最后给出结论。
- anomaly_id: {anomaly_id}
- rule_id: {rule_id}
- aggregate_id: {aggregate_id}
- event_type: {event_type}
- level: {level}
- description: {description}
"""

# 只读工具定义（OpenAI function schema，LLMClient 内部转换为 anthropic 格式）
TOOLS = [
    {
        "name": "query_order",
        "description": "查询订单当前状态与基本信息（金额、版本、更新时间）。",
        "parameters": {
            "type": "object",
            "properties": {"aggregate_id": {"type": "string"}},
            "required": ["aggregate_id"],
        },
    },
    {
        "name": "query_events",
        "description": "查询订单完整事件序列（按版本升序），回放异常前后的因果链。",
        "parameters": {
            "type": "object",
            "properties": {"aggregate_id": {"type": "string"}},
            "required": ["aggregate_id"],
        },
    },
    {
        "name": "query_stats",
        "description": "按订单状态与最近天数查询订单统计（总数/金额）。",
        "parameters": {
            "type": "object",
            "properties": {
                "status": {"type": "string", "description": "订单状态，如 PAID"},
                "days": {"type": "integer", "description": "最近 N 天"},
            },
            "required": [],
        },
    },
]


def _truncate(value, limit: int = 600) -> str:
    """工具结果截断，避免超大 payload 塞爆 LLM 上下文。"""
    s = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return s[:limit] + ("…（截断）" if len(s) > limit else "")


class HealerAgent:
    """ReAct 根因分析 agent：工具调用收集证据 → 结构化根因报告。"""

    def __init__(
        self,
        llm_client: Optional[LLMClient] = None,
        backend_client: Optional[BackendClient] = None,
        root_cause_analyzer: Optional[RootCauseAnalyzer] = None,
    ):
        self.llm_client = llm_client or LLMClient()
        self.backend_client = backend_client or BackendClient()
        self.root_cause_analyzer = root_cause_analyzer or RootCauseAnalyzer()

    async def heal(self, anomaly: Anomaly, trace_id: Optional[str] = None) -> dict:
        """运行 agent 分析循环，返回 {report, agent_trace, note?}。"""
        # 首轮必须含用户任务消息（anthropic 空 messages 会 400）；system 走顶层参数
        messages: list[dict] = [{
            "role": "user",
            "content": TASK_PROMPT.format(
                anomaly_id=anomaly.anomaly_id, rule_id=anomaly.rule_id,
                aggregate_id=anomaly.aggregate_id, event_type=anomaly.event_type,
                level=anomaly.level, description=anomaly.description,
            ),
        }]
        agent_trace: list[dict] = []
        final_text = ""

        for step in range(1, MAX_STEPS + 1):
            text, tool_calls = await self.llm_client.generate_with_tools(
                messages, TOOLS, system=AGENT_SYSTEM_PROMPT,
                operation="heal_agent", trace_id=trace_id,
            )
            final_text = text or final_text

            if not tool_calls:
                # agent 决定结束 → 生成最终结构化报告
                report = await self._final_report(anomaly, final_text, trace_id)
                return {"report": report.model_dump(), "agent_trace": agent_trace}

            messages.append({"role": "assistant", "content": text, "tool_calls": tool_calls})
            for tc in tool_calls:
                result = await self._run_tool(tc["name"], tc.get("input", {}))
                agent_trace.append({
                    "step": step,
                    "tool": tc["name"],
                    "input": tc.get("input", {}),
                    "output": _truncate(result),
                })
                messages.append({"role": "tool", "tool_call_id": tc["id"], "content": result})

        # 步数用尽：确定性兜底（复用已加固的根因分析器）
        logger.warning("Heal agent 超过 %s 步未收敛，走确定性根因分析", MAX_STEPS)
        report = await self.root_cause_analyzer.analyze(anomaly, trace_id=trace_id)
        return {
            "report": report.model_dump(),
            "agent_trace": agent_trace,
            "note": f"agent 超过最大步数 {MAX_STEPS}，改用确定性根因分析",
        }

    async def _run_tool(self, name: str, input_: dict) -> object:
        """执行只读工具，异常时返回错误串而非抛出（agent 继续收集其他证据）。"""
        try:
            if name == "query_order":
                return await self.backend_client.get_order(input_.get("aggregate_id", ""))
            if name == "query_events":
                return await self.backend_client.get_events(input_.get("aggregate_id", ""))
            if name == "query_stats":
                days = int(input_.get("days") or 7)
                now = datetime.now(timezone.utc)
                from_ = (now - timedelta(days=days)).isoformat()
                return await self.backend_client.get_stats(input_.get("status"), from_, now.isoformat())
            return {"error": f"未知工具 {name}"}
        except Exception as e:
            logger.warning("工具执行失败 %s：%s", name, e)
            return {"error": f"{name} 执行失败: {e}"}

    async def _final_report(
        self, anomaly: Anomaly, final_text: str, trace_id: Optional[str]
    ) -> AnalysisReport:
        """生成最终结构化报告：优先用已加固的根因分析器；失败时用 agent 结论文本兜底。"""
        try:
            return await self.root_cause_analyzer.analyze(anomaly, trace_id=trace_id)
        except LLMResponseError:
            logger.warning("根因分析器失败，用 agent 结论文本兜底")
            return AnalysisReport(
                anomaly_id=anomaly.anomaly_id,
                root_cause=final_text or anomaly.description,
                evidence=[],
                suggestions=[],
            )

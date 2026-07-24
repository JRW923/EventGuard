"""根因分析 Prompt 构建"""

import json

from app.model.anomaly import Anomaly
from app.model.analysis_report import ALLOWED_ACTIONS


class PromptBuilder:
    """构建 LLM Prompt：异常 + 事件序列 + 上下文 + 动作白名单"""

    @staticmethod
    def build(anomaly: Anomaly, events: list[dict], context: dict) -> str:
        event_summary = "\n".join(
            f"  - [{e.get('created_at', '?')}] {e.get('event_type', '?')}"
            for e in events[-20:]  # 最多 20 个事件
        )
        action_catalog = "\n".join(f"  - {a}" for a in sorted(ALLOWED_ACTIONS))

        return f"""请分析以下异常的根因，并给出补偿建议。

## 异常信息
- anomaly_id: {anomaly.anomaly_id}
- rule_id: {anomaly.rule_id}
- aggregate_id: {anomaly.aggregate_id}
- event_type: {anomaly.event_type}
- level: {anomaly.level}
- description: {anomaly.description}

## 事件序列（最近 20 个）
{event_summary}

## 上下文
{json.dumps(context, ensure_ascii=False, indent=2)}

## 建议动作白名单（必须从中选择）
{action_catalog}

## 输出要求
请输出严格的 JSON，格式如下：
{{
  "anomaly_id": "{anomaly.anomaly_id}",
  "root_cause": "根因分析文字描述",
  "evidence": ["证据1", "证据2", "证据3"],
  "suggestions": [
    {{"action": "白名单中的动作", "reason": "建议理由", "risk": "LOW|MEDIUM|HIGH"}}
  ]
}}

只输出 JSON，不要输出其他内容。
"""

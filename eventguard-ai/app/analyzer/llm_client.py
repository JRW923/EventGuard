"""LLM 客户端：兼容 OpenAI 兼容 API（含 Ollama /v1）与 Anthropic Messages API（含 DeepSeek /anthropic 端点）

Provider 探测：显式 EG_LLM_PROVIDER 优先（anthropic | openai）；否则 base_url 含 "/anthropic" 视为
anthropic，其余视为 openai（/chat/completions）。

对外 API：
- generate(prompt) -> str             单轮纯文本（保持原签名，供意图分类 / NL 润色 / 根因分析）
- generate_json(prompt) -> str        请求 JSON 输出（openai 用 response_format，anthropic 用强约束 prompt + 抽取）
- generate_with_tools(messages, tools) -> (text, tool_calls)   ReAct 工具调用
"""

import json
import logging
from typing import Any, Optional

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

# 请求超时：与 MVP 保持一致（单次 LLM 调用上界）
HTTP_TIMEOUT = 30.0

# 默认 system 消息：中性助手角色。JSON 强约束只在 generate_json 里加——历史实现把"只输出 JSON"
# 塞进默认 system，导致 LLM 真正可用时（DeepSeek Anthropic 端点）意图分类/NL 润色被带偏成 JSON。
DEFAULT_SYSTEM = "你是 EventGuard 电商订单事件溯源与异常检测平台的数据分析助手。请用简洁、准确的中文回答，严格基于提供的数据，不要编造。"


class LLMClient:
    """OpenAI-compatible / Anthropic-compatible 统一客户端"""

    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
        transport: Optional[httpx.AsyncBaseTransport] = None,
    ):
        self.base_url = (base_url or settings.llm_base_url).rstrip("/")
        self.api_key = api_key or settings.llm_api_key
        self.model = model or settings.llm_model
        self.max_tokens = max_tokens or settings.llm_max_tokens
        self.temperature = temperature if temperature is not None else settings.llm_temperature
        self.provider = self._detect_provider()
        self.transport = transport  # 测试注入 httpx.MockTransport，离线验证请求/响应形状

    # ---------------- provider 探测 ----------------

    def _detect_provider(self) -> str:
        explicit = (settings.llm_provider or "").strip().lower()
        if explicit:
            return "anthropic" if explicit == "anthropic" else "openai"
        return "anthropic" if "/anthropic" in self.base_url else "openai"

    @property
    def is_anthropic(self) -> bool:
        return self.provider == "anthropic"

    # ---------------- 公共 API ----------------

    async def generate(self, prompt: str) -> str:
        """单轮生成纯文本（保持原签名）。"""
        text, _, _ = await self._complete(
            [{"role": "user", "content": prompt}], system=DEFAULT_SYSTEM
        )
        return text

    async def generate_json(self, prompt: str) -> str:
        """请求 JSON 输出，返回原始文本（调用方负责解析；JSON 解析失败时由调用方重试/降级）。

        openai 用 response_format={"type":"json_object"} 强制 JSON；anthropic 用强约束 system prompt，
        结果经代码围栏抽取（部分模型会包 ```json ... ```）。
        """
        system = (
            DEFAULT_SYSTEM
            + " 你的输出必须是严格的单个 JSON 对象，不要包含任何解释文字、Markdown 代码块或额外字段。"
        )
        text, _, _ = await self._complete(
            [{"role": "user", "content": prompt}], system=system, json_mode=True
        )
        return self._strip_code_fence(text)

    async def generate_with_tools(
        self,
        messages: list[dict],
        tools: list[dict],
        tool_choice: Any = "auto",
    ) -> tuple[str, list[dict]]:
        """ReAct 工具调用。

        Args:
            messages: 中立消息列表（见 _to_anthropic_messages / _to_openai_messages 的入参约定）：
                - {"role": "system"|"user"|"assistant", "content": str}
                - {"role": "assistant", "content": str, "tool_calls": [{"id","name","input"}]}
                - {"role": "tool", "tool_call_id": str, "content": str|dict}
            tools: OpenAI function schema 列表（name/description/parameters），内部转换为提供商格式。
            tool_choice: "auto" | 工具名 | 对象。anthropic 用 {"type":"tool","name":...}。

        Returns:
            (text, tool_calls)：tool_calls 统一为 [{"id","name","input"}]，input 为已解析 dict。
        """
        text, tool_calls, _ = await self._complete(messages, tools=tools, tool_choice=tool_choice)
        return text, tool_calls

    # ---------------- 核心请求 ----------------

    async def _complete(
        self,
        messages: list[dict],
        system: Optional[str] = None,
        tools: Optional[list[dict]] = None,
        tool_choice: Any = None,
        json_mode: bool = False,
    ) -> tuple[str, list[dict], dict]:
        """统一请求入口，返回 (text, tool_calls, usage)。"""
        if self.is_anthropic:
            resp = await self._post_anthropic(messages, system, tools, tool_choice, json_mode)
        else:
            resp = await self._post_openai(messages, system, tools, tool_choice, json_mode)
        return self._parse_response(resp)

    async def _post_openai(
        self,
        messages: list[dict],
        system: Optional[str],
        tools: Optional[list[dict]],
        tool_choice: Any,
        json_mode: bool,
    ) -> dict:
        url = f"{self.base_url}/chat/completions"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }
        body: dict[str, Any] = {
            "model": self.model,
            "messages": self._to_openai_messages(messages, system),
            "temperature": self.temperature,
        }
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        if tools:
            body["tools"] = tools
            body["tool_choice"] = tool_choice or "auto"
        async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, transport=self.transport) as client:
            resp = await client.post(url, headers=headers, json=body)
            resp.raise_for_status()
            return resp.json()

    async def _post_anthropic(
        self,
        messages: list[dict],
        system: Optional[str],
        tools: Optional[list[dict]],
        tool_choice: Any,
        json_mode: bool,
    ) -> dict:
        url = f"{self.base_url}/v1/messages"
        headers = {
            "Content-Type": "application/json",
            "x-api-key": self.api_key,
            "anthropic-version": "2023-06-01",
        }
        body: dict[str, Any] = {
            "model": self.model,
            "max_tokens": self.max_tokens,
            "temperature": self.temperature,
            "messages": self._to_anthropic_messages(messages),
        }
        if system:
            body["system"] = system
        if tools:
            body["tools"] = [
                {
                    "name": t["name"],
                    "description": t.get("description", ""),
                    "input_schema": t.get("parameters", {"type": "object", "properties": {}}),
                }
                for t in tools
            ]
            body["tool_choice"] = (
                {"type": "auto"}
                if tool_choice == "auto"
                else (
                    {"type": "tool", "name": tool_choice}
                    if isinstance(tool_choice, str)
                    else tool_choice
                )
            )
        async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, transport=self.transport) as client:
            resp = await client.post(url, headers=headers, json=body)
            resp.raise_for_status()
            return resp.json()

    # ---------------- 消息格式转换（中立 → 提供商格式） ----------------

    @staticmethod
    def _to_openai_messages(messages: list[dict], system: Optional[str]) -> list[dict]:
        out: list[dict] = []
        if system:
            out.append({"role": "system", "content": system})
        for m in messages:
            role = m.get("role", "user")
            content = m.get("content", "")
            if role == "system":
                out.append({"role": "system", "content": content})
            elif role == "assistant":
                msg: dict[str, Any] = {"role": "assistant", "content": content}
                tool_calls = m.get("tool_calls")
                if tool_calls:
                    msg["tool_calls"] = [
                        {
                            "id": tc["id"],
                            "type": "function",
                            "function": {
                                "name": tc["name"],
                                "arguments": json.dumps(tc.get("input", {}), ensure_ascii=False),
                            },
                        }
                        for tc in tool_calls
                    ]
                out.append(msg)
            elif role == "tool":
                out.append(
                    {
                        "role": "tool",
                        "tool_call_id": m["tool_call_id"],
                        "content": LLMClient._serialize(content),
                    }
                )
            else:
                out.append({"role": "user", "content": content})
        return out

    @staticmethod
    def _to_anthropic_messages(messages: list[dict]) -> list[dict]:
        out: list[dict] = []
        for m in messages:
            role = m.get("role", "user")
            content = m.get("content", "")
            if role == "system":
                # anthropic system 走顶层 body["system"]，这里跳过
                continue
            elif role == "assistant":
                blocks: list[dict] = []
                if content:
                    blocks.append({"type": "text", "text": content})
                for tc in m.get("tool_calls", []):
                    blocks.append(
                        {"type": "tool_use", "id": tc["id"], "name": tc["name"], "input": tc.get("input", {})}
                    )
                out.append({"role": "assistant", "content": blocks})
            elif role == "tool":
                out.append(
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "tool_result",
                                "tool_use_id": m["tool_call_id"],
                                "content": LLMClient._serialize(content),
                            }
                        ],
                    }
                )
            else:
                out.append({"role": "user", "content": content})
        return out

    # ---------------- 响应解析 ----------------

    def _parse_response(self, data: dict) -> tuple[str, list[dict], dict]:
        if self.is_anthropic:
            text = "".join(
                b.get("text", "") for b in data.get("content", []) if b.get("type") == "text"
            )
            tool_calls = [
                {"id": b["id"], "name": b["name"], "input": b.get("input", {})}
                for b in data.get("content", [])
                if b.get("type") == "tool_use"
            ]
        else:
            message = (data.get("choices") or [{}])[0].get("message", {})
            text = message.get("content") or ""
            tool_calls = [
                {
                    "id": tc["id"],
                    "name": tc["function"]["name"],
                    "input": LLMClient._parse_json(tc["function"].get("arguments", "{}")),
                }
                for tc in (message.get("tool_calls") or [])
            ]
        usage = data.get("usage") or {}
        return text, tool_calls, {
            "prompt_tokens": usage.get("prompt_tokens") or usage.get("input_tokens") or 0,
            "completion_tokens": usage.get("completion_tokens") or usage.get("output_tokens") or 0,
        }

    # ---------------- 工具方法 ----------------

    @staticmethod
    def _strip_code_fence(text: str) -> str:
        """剥离 ```json ... ``` 围栏，返回其中 JSON 文本。"""
        stripped = text.strip()
        if stripped.startswith("```"):
            lines = stripped.splitlines()
            if lines and lines[0].startswith("```"):
                lines = lines[1:]
            if lines and lines[-1].strip() == "```":
                lines = lines[:-1]
            return "\n".join(lines).strip()
        return stripped

    @staticmethod
    def _parse_json(raw: str) -> Any:
        try:
            return json.loads(raw)
        except (ValueError, TypeError):
            return {}

    @staticmethod
    def _serialize(value: Any) -> str:
        if isinstance(value, str):
            return value
        return json.dumps(value, ensure_ascii=False)

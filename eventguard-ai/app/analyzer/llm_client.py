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
import time
from typing import Any, Optional

import httpx

from app import metrics as egm
from app.cache.llm_cache import LLMCache, llm_cache as default_llm_cache
from app.config import settings
from app.trace.trace_log import trace_log

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
        cache: Optional[LLMCache] = None,
    ):
        self.base_url = (base_url or settings.llm_base_url).rstrip("/")
        self.api_key = api_key or settings.llm_api_key
        self.model = model or settings.llm_model
        self.max_tokens = max_tokens or settings.llm_max_tokens
        self.temperature = temperature if temperature is not None else settings.llm_temperature
        self.provider = self._detect_provider()
        self.transport = transport  # 测试注入 httpx.MockTransport，离线验证请求/响应形状
        self.cache = cache or default_llm_cache  # Item 4：幂等读场景响应缓存

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

    async def generate(
        self,
        prompt: str,
        use_cache: bool = True,
        operation: str = "generate",
        trace_id: Optional[str] = None,
    ) -> str:
        """单轮生成纯文本（保持原签名）。

        Item 4：幂等读场景（意图分类 / NL 润色）默认走响应缓存；可解释性场景传 use_cache=False。
        """
        if use_cache:
            hit = self.cache.get(self.provider, self.model, self.temperature, prompt)
            if hit is not None:
                egm.llm_cache_hits.inc()
                trace_log.record("llm_cache", provider=self.provider, label=operation,
                                 hit=True, trace_id=trace_id)
                return hit
            egm.llm_cache_misses.inc()
        text, _, _ = await self._complete(
            [{"role": "user", "content": prompt}], system=DEFAULT_SYSTEM,
            operation=operation, trace_id=trace_id,
        )
        if use_cache:
            self.cache.set(self.provider, self.model, self.temperature, prompt, text)
        return text

    async def generate_json(
        self,
        prompt: str,
        operation: str = "generate_json",
        trace_id: Optional[str] = None,
    ) -> str:
        """请求 JSON 输出，返回原始文本（调用方负责解析；JSON 解析失败时由调用方重试/降级）。

        openai 用 response_format={"type":"json_object"} 强制 JSON；anthropic 用强约束 system prompt，
        结果经代码围栏抽取（部分模型会包 ```json ... ```）。可解释性场景，默认不缓存。
        """
        system = (
            DEFAULT_SYSTEM
            + " 你的输出必须是严格的单个 JSON 对象，不要包含任何解释文字、Markdown 代码块或额外字段。"
        )
        text, _, _ = await self._complete(
            [{"role": "user", "content": prompt}], system=system, json_mode=True,
            operation=operation, trace_id=trace_id,
        )
        return self._strip_code_fence(text)

    async def generate_with_tools(
        self,
        messages: list[dict],
        tools: list[dict],
        tool_choice: Any = "auto",
        operation: str = "generate_with_tools",
        trace_id: Optional[str] = None,
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
        text, tool_calls, _ = await self._complete(
            messages, tools=tools, tool_choice=tool_choice,
            operation=operation, trace_id=trace_id,
        )
        return text, tool_calls

    # ---------------- 核心请求 ----------------

    async def _complete(
        self,
        messages: list[dict],
        system: Optional[str] = None,
        tools: Optional[list[dict]] = None,
        tool_choice: Any = None,
        json_mode: bool = False,
        operation: str = "llm",
        trace_id: Optional[str] = None,
    ) -> tuple[str, list[dict], dict]:
        """统一请求入口，返回 (text, tool_calls, usage)。统一埋点 llm_call 指标与 trace。"""
        _t0 = time.time()
        try:
            if self.is_anthropic:
                resp = await self._post_anthropic(messages, system, tools, tool_choice, json_mode)
            else:
                resp = await self._post_openai(messages, system, tools, tool_choice, json_mode)
            text, tool_calls, usage = self._parse_response(resp)
            self._record_llm(operation, _t0, usage, ok=True, trace_id=trace_id)
            return text, tool_calls, usage
        except Exception as e:
            self._record_llm(operation, _t0, {}, ok=False, trace_id=trace_id)
            raise

    def _record_llm(
        self,
        operation: str,
        start: float,
        usage: dict,
        ok: bool,
        trace_id: Optional[str] = None,
    ) -> None:
        """LLM 调用埋点：调用计数 + token 消耗 + trace。"""
        latency_ms = (time.time() - start) * 1000
        egm.llm_calls.labels(provider=self.provider, operation=operation, ok="true" if ok else "false").inc()
        tokens = (usage.get("prompt_tokens") or 0) + (usage.get("completion_tokens") or 0)
        if tokens:
            egm.llm_tokens.labels(model=self.model, operation=operation).inc(tokens)
        trace_log.record(
            "llm_call",
            provider=self.provider, model=self.model, label=operation,
            latency_ms=round(latency_ms, 1), tokens=int(tokens), ok=ok, trace_id=trace_id,
        )

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

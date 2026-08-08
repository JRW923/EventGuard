"""LLMClient 提供商适配测试：anthropic / openai 双格式、JSON 模式、工具调用。

用 httpx.MockTransport 离线构造响应，同时断言发出的请求体形状。
"""
import json

import httpx
import pytest

from app.analyzer.llm_client import DEFAULT_SYSTEM, LLMClient


def _client(responses, base_url="https://api.deepseek.com/anthropic"):
    """构造携带 MockTransport 的客户端；responses 为按顺序返回的 (status, json_body)。"""
    iterator = iter(responses)

    def handler(request: httpx.Request) -> httpx.Response:
        try:
            status, body = next(iterator)
        except StopIteration:
            raise AssertionError("mock 响应不够用（请求多于预设响应）")
        return httpx.Response(status, json=body, request=request)

    transport = httpx.MockTransport(handler)
    return LLMClient(base_url=base_url, api_key="k", model="m", transport=transport), transport


# ---------------- provider 探测 ----------------

def test_detect_anthropic_from_base_url():
    c = LLMClient(base_url="https://api.deepseek.com/anthropic", api_key="k")
    assert c.is_anthropic is True


def test_detect_openai_default():
    c = LLMClient(base_url="http://ollama:11434/v1", api_key="k")
    assert c.is_anthropic is False


def test_detect_explicit_provider(monkeypatch):
    monkeypatch.setattr("app.analyzer.llm_client.settings.llm_provider", "openai")
    c = LLMClient(base_url="https://api.deepseek.com/anthropic", api_key="k")
    assert c.is_anthropic is False


# ---------------- generate：anthropic ----------------

@pytest.mark.asyncio
async def test_anthropic_generate_request_and_parse():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["headers"] = request.headers
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "content": [{"type": "text", "text": "  结果文本  "}],
                "usage": {"input_tokens": 10, "output_tokens": 5},
            },
            request=request,
        )

    client = LLMClient(
        base_url="https://api.deepseek.com/anthropic",
        api_key="secret",
        model="deepseek-v4-flash",
        transport=httpx.MockTransport(handler),
    )
    text = await client.generate("问题？")

    assert captured["url"] == "https://api.deepseek.com/anthropic/v1/messages"
    assert captured["headers"]["x-api-key"] == "secret"
    assert captured["body"]["model"] == "deepseek-v4-flash"
    assert captured["body"]["max_tokens"] == 2048
    assert captured["body"]["system"] == DEFAULT_SYSTEM
    assert captured["body"]["messages"] == [{"role": "user", "content": "问题？"}]
    assert text == "  结果文本  "


# ---------------- generate：openai ----------------

@pytest.mark.asyncio
async def test_openai_generate_request_and_parse():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["headers"] = request.headers
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"role": "assistant", "content": "答案"}}],
                "usage": {"prompt_tokens": 3, "completion_tokens": 2},
            },
            request=request,
        )

    client = LLMClient(
        base_url="http://ollama:11434/v1",
        api_key="ollama",
        model="qwen2.5:7b",
        transport=httpx.MockTransport(handler),
    )
    text = await client.generate("你好")

    assert captured["url"] == "http://ollama:11434/v1/chat/completions"
    assert captured["headers"]["Authorization"] == "Bearer ollama"
    assert captured["body"]["messages"] == [
        {"role": "system", "content": DEFAULT_SYSTEM},
        {"role": "user", "content": "你好"},
    ]
    assert text == "答案"


# ---------------- generate_json ----------------

@pytest.mark.asyncio
async def test_openai_generate_json_requests_response_format():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200, json={"choices": [{"message": {"content": '{"ok": 1}'}}]}, request=request
        )

    client = LLMClient(
        base_url="http://ollama:11434/v1", api_key="k", transport=httpx.MockTransport(handler)
    )
    text = await client.generate_json("统计问题")

    assert captured["body"]["response_format"] == {"type": "json_object"}
    assert json.loads(text) == {"ok": 1}


@pytest.mark.asyncio
async def test_generate_json_strips_code_fence():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": '```json\n{"a": 1}\n```'}}]},
            request=request,
        )

    client = LLMClient(
        base_url="http://ollama:11434/v1", api_key="k", transport=httpx.MockTransport(handler)
    )
    text = await client.generate_json("x")
    assert json.loads(text) == {"a": 1}


# ---------------- generate_with_tools ----------------

ANTHROPIC_TOOLS = [
    {"name": "query_order", "description": "查订单", "parameters": {"type": "object", "properties": {"order_id": {"type": "string"}}}}
]


@pytest.mark.asyncio
async def test_anthropic_tool_call_request_and_parse():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "content": [
                    {"type": "text", "text": "我来查一下"},
                    {
                        "type": "tool_use",
                        "id": "toolu_1",
                        "name": "query_order",
                        "input": {"order_id": "abc-123"},
                    },
                ]
            },
            request=request,
        )

    client = LLMClient(
        base_url="https://api.deepseek.com/anthropic", api_key="k", transport=httpx.MockTransport(handler)
    )
    text, tool_calls = await client.generate_with_tools(
        [{"role": "user", "content": "查订单 abc-123"}], ANTHROPIC_TOOLS
    )

    assert text == "我来查一下"
    assert tool_calls == [{"id": "toolu_1", "name": "query_order", "input": {"order_id": "abc-123"}}]
    # anthropic tools 转成 name/description/input_schema，tool_choice 转 object
    assert captured["body"]["tools"][0]["name"] == "query_order"
    assert captured["body"]["tools"][0]["input_schema"]["type"] == "object"
    assert captured["body"]["tool_choice"] == {"type": "auto"}


@pytest.mark.asyncio
async def test_anthropic_tool_result_roundtrip():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"content": [{"type": "text", "text": "完成"}]}, request=request)

    client = LLMClient(
        base_url="https://api.deepseek.com/anthropic", api_key="k", transport=httpx.MockTransport(handler)
    )
    messages = [
        {"role": "user", "content": "查单"},
        {"role": "assistant", "content": "我来查", "tool_calls": [{"id": "t1", "name": "query_order", "input": {"order_id": "x"}}]},
        {"role": "tool", "tool_call_id": "t1", "content": {"status": "PAID"}},
    ]
    await client.generate_with_tools(messages, ANTHROPIC_TOOLS)

    anthropic_messages = captured["body"]["messages"]
    # assistant 消息含 tool_use block
    assert anthropic_messages[1]["content"][1] == {"type": "tool_use", "id": "t1", "name": "query_order", "input": {"order_id": "x"}}
    # tool 结果 → user + tool_result block
    assert anthropic_messages[2]["role"] == "user"
    assert anthropic_messages[2]["content"][0]["type"] == "tool_result"
    assert anthropic_messages[2]["content"][0]["tool_use_id"] == "t1"


@pytest.mark.asyncio
async def test_openai_tool_call_parse_and_result_roundtrip():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        # 第二次调用（tool 结果回传）返回最终文本
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": "最终回答",
                            "tool_calls": [
                                {"id": "call_1", "type": "function", "function": {"name": "query_order", "arguments": '{"order_id": "x"}'}}
                            ],
                        }
                    }
                ]
            },
            request=request,
        )

    client = LLMClient(
        base_url="http://ollama:11434/v1", api_key="k", transport=httpx.MockTransport(handler)
    )
    text, tool_calls = await client.generate_with_tools(
        [{"role": "user", "content": "查单"}], ANTHROPIC_TOOLS
    )

    assert tool_calls == [{"id": "call_1", "name": "query_order", "input": {"order_id": "x"}}]
    assert captured["body"]["tools"] == ANTHROPIC_TOOLS
    assert captured["body"]["tool_choice"] == "auto"

"""LLM 客户端：调用 Ollama 或 OpenAI-compatible API"""

import logging
from typing import Optional

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class LLMClient:
    """OpenAI-compatible Chat Completions 客户端（兼容 Ollama /v1）"""

    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
    ):
        self.base_url = base_url or settings.llm_base_url
        self.api_key = api_key or settings.llm_api_key
        self.model = model or settings.llm_model

    def generate(self, prompt: str) -> str:
        """调用 LLM 生成文本"""
        url = f"{self.base_url}/chat/completions"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }
        body = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": "你是 EventGuard 电商订单异常根因分析助手。只输出 JSON。"},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.3,
        }

        try:
            with httpx.Client(timeout=30.0) as client:  # ponytail: LLM 调用 30s 硬超时,无重试;升级路径=退避重试/超时可配
                resp = client.post(url, headers=headers, json=body)
                resp.raise_for_status()
                data = resp.json()
                return data["choices"][0]["message"]["content"]
        except httpx.HTTPError as e:
            logger.error("LLM 调用失败: %s", e)
            raise

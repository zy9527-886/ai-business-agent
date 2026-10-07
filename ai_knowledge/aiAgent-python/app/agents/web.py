from __future__ import annotations

import asyncio

import httpx

from app.models.domain import AgentError, SpecialistResult
from app.tools.web_search import UnsafeSearchQuery


class WebAgent:
    def __init__(self, provider: object | None) -> None:
        self._provider = provider

    async def run(self, question: str) -> SpecialistResult:
        if self._provider is None:
            return SpecialistResult(errors=[AgentError(
                agent="web", code="WEB_UNAVAILABLE", retryable=False,
                public_message="外部网页检索未配置。",
            )])
        try:
            return SpecialistResult(evidence=await self._provider.search(question))
        except UnsafeSearchQuery:
            return SpecialistResult(errors=[AgentError(
                agent="web", code="WEB_QUERY_BLOCKED", retryable=False,
                public_message="该问题包含不能发送到外部搜索服务的信息。",
            )])
        except (TimeoutError, asyncio.TimeoutError, httpx.TimeoutException):
            return SpecialistResult(errors=[AgentError(
                agent="web", code="WEB_TIMEOUT", retryable=True,
                public_message="外部网页检索暂时超时。",
            )])
        except Exception:
            return SpecialistResult(errors=[AgentError(
                agent="web", code="WEB_FAILED", retryable=False,
                public_message="外部网页检索暂时不可用。",
            )])

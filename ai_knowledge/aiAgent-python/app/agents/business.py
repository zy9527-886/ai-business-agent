from __future__ import annotations

import asyncio
import re

from app.models.domain import AgentError, Evidence, SpecialistResult


class BusinessAgent:
    _patterns = (
        (re.compile(r"\bDEMO-\d+\b", re.IGNORECASE), "query_demo_order"),
        (re.compile(r"\bTICKET-\d+\b", re.IGNORECASE), "query_demo_ticket"),
        (re.compile(r"\bSKU-\d+\b", re.IGNORECASE), "query_demo_inventory"),
    )

    def __init__(self, java_client: object) -> None:
        self._java = java_client

    async def run(self, question: str) -> SpecialistResult:
        calls: list[object] = []
        for pattern, method_name in self._patterns:
            for identifier in dict.fromkeys(pattern.findall(question.upper())):
                calls.append(getattr(self._java, method_name)(identifier))
        if not calls:
            return SpecialistResult(errors=[AgentError(
                agent="business", code="BUSINESS_IDENTIFIER_REQUIRED", retryable=False,
                public_message="请提供演示订单、工单或库存编号。",
            )])
        results = await asyncio.gather(*calls, return_exceptions=True)
        evidence: list[Evidence] = []
        errors: list[AgentError] = []
        for result in results:
            if isinstance(result, Evidence):
                if result.demo:
                    evidence.append(result)
                else:
                    errors.append(self._error("BUSINESS_INVALID_RESPONSE", False))
            elif isinstance(result, (TimeoutError, asyncio.TimeoutError)):
                errors.append(self._error("BUSINESS_TOOL_TIMEOUT", True))
            else:
                errors.append(self._error("BUSINESS_TOOL_FAILED", False))
        return SpecialistResult(evidence=evidence, errors=errors)

    @staticmethod
    def _error(code: str, retryable: bool) -> AgentError:
        return AgentError(
            agent="business", code=code, retryable=retryable,
            public_message="演示业务查询部分不可用。",
        )

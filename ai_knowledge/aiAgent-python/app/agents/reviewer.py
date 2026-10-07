from __future__ import annotations

import json

from app.models.domain import Evidence, ReviewResult


class ReviewerAgent:
    def __init__(self, chat_provider: object) -> None:
        self._chat = chat_provider

    async def review(
        self,
        question: str,
        evidence: list[Evidence],
        draft: str,
        chat_profile: str,
    ) -> ReviewResult:
        if not evidence:
            return ReviewResult(
                decision="PASS", confidence=1.0, grounded=True,
                risk_level="low", issues=[],
            )
        try:
            return await self._chat.structured(
                chat_profile,
                ReviewResult,
                [
                    {
                        "role": "system",
                        "content": (
                            "审查回答是否完全基于证据、是否遗漏来源、是否把演示数据明确标记。"
                            "可返回 PASS、RETRY 或 HANDOFF。高风险或无法可靠修复时 HANDOFF。"
                        ),
                    },
                    {
                        "role": "user",
                        "content": json.dumps({
                            "question": question,
                            "evidence": [item.model_dump(mode="json", by_alias=True) for item in evidence],
                            "draft": draft,
                        }, ensure_ascii=False),
                    },
                ],
            )
        except Exception:
            return ReviewResult(
                decision="HANDOFF", confidence=0.0, grounded=False,
                risk_level="medium", issues=["review unavailable"],
            )

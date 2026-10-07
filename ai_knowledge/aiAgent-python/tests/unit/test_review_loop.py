from datetime import datetime, timezone

import pytest

from app.agents.answer import AnswerAgent
from app.agents.reviewer import ReviewerAgent
from app.models.domain import Evidence, ReviewResult


class SequencedChat:
    def __init__(self):
        self.text_calls = 0
        self.review_calls = 0

    async def text(self, profile, messages):
        self.text_calls += 1
        return f"draft-{self.text_calls}"

    async def structured(self, profile, schema, messages):
        self.review_calls += 1
        if self.review_calls == 1:
            return ReviewResult(
                decision="RETRY", confidence=0.4, grounded=False,
                risk_level="low", issues=["missing citation"],
            )
        return ReviewResult(
            decision="PASS", confidence=0.9, grounded=True,
            risk_level="low", issues=[],
        )


@pytest.mark.asyncio
async def test_answer_review_loop_rewrites_at_most_once() -> None:
    chat = SequencedChat()
    answer = AnswerAgent(chat)
    reviewer = ReviewerAgent(chat)
    evidence = [Evidence(
        source_type="knowledge", source_id="segment:1", title="制度",
        content="七天内退款", observed_at=datetime.now(timezone.utc),
    )]

    draft = await answer.draft("退款？", evidence, "default-chat")
    first = await reviewer.review("退款？", evidence, draft, "default-chat")
    rewritten = await answer.rewrite(
        "退款？", evidence, draft, first, "default-chat"
    )
    second = await reviewer.review("退款？", evidence, rewritten, "default-chat")

    assert rewritten == "draft-2"
    assert second.decision == "PASS"
    assert chat.text_calls == 2


@pytest.mark.asyncio
async def test_no_evidence_answer_is_deterministic_and_does_not_call_model() -> None:
    chat = SequencedChat()
    answer = await AnswerAgent(chat).draft("未知？", [], "default-chat")
    assert "资料不足" in answer
    assert chat.text_calls == 0

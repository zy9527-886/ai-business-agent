from __future__ import annotations

from typing import TypedDict

from pydantic import Field

from app.agents.router import RouteDecision
from app.models.api import ChatStreamRequest
from app.models.domain import AgentError, ApiModel, Evidence, ReviewResult


class GraphState(TypedDict, total=False):
    request: ChatStreamRequest
    allowed: list[str]
    blocked: bool
    block_reason: str | None
    route: RouteDecision
    evidence: list[Evidence]
    errors: list[AgentError]
    answer: str
    review: ReviewResult
    rewrite_count: int


class GraphOutcome(ApiModel):
    request_id: str
    conversation_id: str
    route: RouteDecision | None = None
    evidence: list[Evidence] = Field(default_factory=list)
    errors: list[AgentError] = Field(default_factory=list)
    answer: str = ""
    review: ReviewResult
    blocked: bool = False
    block_reason: str | None = None

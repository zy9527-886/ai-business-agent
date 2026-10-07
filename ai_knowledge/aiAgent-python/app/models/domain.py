from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class Evidence(ApiModel):
    source_type: Literal["knowledge", "business", "web"]
    source_id: str
    title: str
    content: str
    url: str | None = None
    score: float | None = Field(default=None, ge=0, le=1)
    observed_at: datetime
    demo: bool = False
    metadata: dict[str, Any] = Field(default_factory=dict)


class AgentError(ApiModel):
    agent: str
    code: str
    retryable: bool = False
    public_message: str


class ReviewResult(ApiModel):
    decision: Literal["PASS", "RETRY", "HANDOFF"]
    confidence: float = Field(ge=0, le=1)
    grounded: bool
    risk_level: Literal["low", "medium", "high"]
    issues: list[str] = Field(default_factory=list)


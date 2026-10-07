from __future__ import annotations

from pydantic import ConfigDict, Field, field_validator

from app.models.domain import ApiModel


class UserContext(ApiModel):
    user_id: int
    role: str
    permissions: list[str] = Field(default_factory=list)
    tenant_id: str | None = None


class ChatOptions(ApiModel):
    chat_model_profile: str = "default-chat"
    embedding_model_profile: str = "default-embedding"
    top_k: int = Field(default=5, ge=1, le=20)
    similarity_threshold: float = Field(default=0.2, ge=0, le=1)
    enabled_agents: list[str] = Field(
        default_factory=lambda: ["knowledge", "business", "web"]
    )


class ChatStreamRequest(ApiModel):
    request_id: str = Field(min_length=1, max_length=128)
    conversation_id: str = Field(min_length=1, max_length=128)
    question: str = Field(min_length=1, max_length=8_000)
    user: UserContext
    options: ChatOptions = Field(default_factory=ChatOptions)

    @field_validator("question")
    @classmethod
    def normalize_question(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("question must not be blank")
        return normalized


class EmbeddingRequest(ApiModel):
    model_config = ConfigDict(alias_generator=lambda value: value, populate_by_name=True)
    profile: str = Field(min_length=1, max_length=100)
    texts: list[str] = Field(min_length=1, max_length=64)

    @field_validator("texts")
    @classmethod
    def validate_texts(cls, values: list[str]) -> list[str]:
        if any(not value.strip() for value in values):
            raise ValueError("embedding texts must not be blank")
        if any(len(value) > 16_000 for value in values):
            raise ValueError("embedding text exceeds 16000 characters")
        return values


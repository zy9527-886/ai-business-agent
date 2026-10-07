from dataclasses import dataclass

import pytest
from pydantic import BaseModel

from app.llm.chat_provider import ChatProvider
from app.llm.embedding_provider import EmbeddingProvider
from app.llm.profiles import ModelProfile, ModelProfileRegistry


class Route(BaseModel):
    intent: str


class FakeStructuredClient:
    async def ainvoke(self, messages):
        return Route(intent="knowledge")


class FakeChatClient:
    def with_structured_output(self, schema):
        assert schema is Route
        return FakeStructuredClient()


@dataclass
class FakeEmbeddingClient:
    vectors: list[list[float]]

    async def aembed_documents(self, texts):
        assert texts == ["first", "second"]
        return self.vectors


def registry() -> ModelProfileRegistry:
    return ModelProfileRegistry(
        {
            "chat": ModelProfile(kind="chat", model="chat-model", api_key="secret"),
            "embed": ModelProfile(
                kind="embedding", model="embed-model", api_key="secret", dimension=3
            ),
        }
    )


@pytest.mark.asyncio
async def test_chat_provider_returns_validated_structured_output() -> None:
    provider = ChatProvider(registry(), client_factory=lambda profile: FakeChatClient())

    result = await provider.structured("chat", Route, [{"role": "user", "content": "route"}])

    assert result == Route(intent="knowledge")


@pytest.mark.asyncio
async def test_embedding_provider_preserves_order_and_checks_dimension() -> None:
    provider = EmbeddingProvider(
        registry(), client_factory=lambda profile: FakeEmbeddingClient([[1, 2, 3], [4, 5, 6]])
    )
    assert await provider.embed_documents("embed", ["first", "second"]) == [
        [1.0, 2.0, 3.0],
        [4.0, 5.0, 6.0],
    ]

    bad = EmbeddingProvider(
        registry(), client_factory=lambda profile: FakeEmbeddingClient([[1, 2], [3, 4]])
    )
    with pytest.raises(ValueError, match="dimension"):
        await bad.embed_documents("embed", ["first", "second"])


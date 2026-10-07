from __future__ import annotations

from collections.abc import Callable
from typing import Any

from langchain_openai import OpenAIEmbeddings

from app.llm.profiles import ModelProfile, ModelProfileRegistry


class EmbeddingProvider:
    def __init__(
        self,
        registry: ModelProfileRegistry,
        client_factory: Callable[[ModelProfile], Any] | None = None,
    ) -> None:
        self._registry = registry
        self._client_factory = client_factory or self._build_client

    @staticmethod
    def _build_client(profile: ModelProfile) -> OpenAIEmbeddings:
        return OpenAIEmbeddings(
            model=profile.model,
            api_key=profile.api_key,
            base_url=profile.base_url,
            dimensions=profile.dimension,
            request_timeout=60,
            max_retries=1,
        )

    async def embed_documents(self, profile_name: str, texts: list[str]) -> list[list[float]]:
        profile = self._registry.require(profile_name, "embedding")
        vectors = await self._client_factory(profile).aembed_documents(texts)
        if len(vectors) != len(texts):
            raise ValueError("embedding provider returned an unexpected vector count")
        expected = profile.dimension
        normalized = [[float(value) for value in vector] for vector in vectors]
        if any(len(vector) != expected for vector in normalized):
            raise ValueError(f"embedding dimension must be {expected}")
        return normalized

    def capabilities(self) -> list[dict[str, object]]:
        return [item for item in self._registry.capabilities() if item["kind"] == "embedding"]


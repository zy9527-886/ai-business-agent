from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import Any, TypeVar

from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from app.llm.profiles import ModelProfile, ModelProfileRegistry

T = TypeVar("T", bound=BaseModel)


class ChatProvider:
    def __init__(
        self,
        registry: ModelProfileRegistry,
        client_factory: Callable[[ModelProfile], Any] | None = None,
    ) -> None:
        self._registry = registry
        self._client_factory = client_factory or self._build_client

    @staticmethod
    def _build_client(profile: ModelProfile) -> ChatOpenAI:
        return ChatOpenAI(
            model=profile.model,
            api_key=profile.api_key,
            base_url=profile.base_url,
            temperature=profile.temperature,
            timeout=60,
            max_retries=1,
        )

    async def structured(
        self, profile_name: str, schema: type[T], messages: Sequence[dict[str, str]]
    ) -> T:
        profile = self._registry.require(profile_name, "chat")
        client = self._client_factory(profile).with_structured_output(schema)
        result = await client.ainvoke(list(messages))
        return result if isinstance(result, schema) else schema.model_validate(result)

    async def text(self, profile_name: str, messages: Sequence[dict[str, str]]) -> str:
        profile = self._registry.require(profile_name, "chat")
        result = await self._client_factory(profile).ainvoke(list(messages))
        content = getattr(result, "content", result)
        return str(content)

    def capabilities(self) -> list[dict[str, object]]:
        return [item for item in self._registry.capabilities() if item["kind"] == "chat"]


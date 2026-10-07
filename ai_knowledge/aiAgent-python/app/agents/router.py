from __future__ import annotations

from typing import Literal

from pydantic import Field

from app.models.domain import ApiModel


class RouteDecision(ApiModel):
    intent: Literal["knowledge", "business", "web", "mixed", "chitchat", "unsupported"]
    routes: list[Literal["knowledge", "business", "web"]] = Field(default_factory=list)
    reason: str
    risk_level: Literal["low", "medium", "high"] = "low"


class RouterAgent:
    def __init__(self, chat_provider: object) -> None:
        self._chat = chat_provider

    async def route(
        self, question: str, *, chat_profile: str, allowed: list[str]
    ) -> RouteDecision:
        try:
            decision = await self._chat.structured(
                chat_profile,
                RouteDecision,
                [
                    {
                        "role": "system",
                        "content": (
                            "只做路由，不回答问题。可选 knowledge/business/web；"
                            f"当前允许能力：{','.join(allowed)}。返回严格结构化结果。"
                        ),
                    },
                    {"role": "user", "content": question},
                ],
            )
            selected = [name for name in decision.routes if name in allowed]
            if not selected and "knowledge" in allowed:
                selected = ["knowledge"]
            return decision.model_copy(update={"routes": selected})
        except Exception:
            routes = ["knowledge"] if "knowledge" in allowed else []
            return RouteDecision(
                intent="knowledge" if routes else "unsupported",
                routes=routes,
                reason="router fallback",
                risk_level="low",
            )

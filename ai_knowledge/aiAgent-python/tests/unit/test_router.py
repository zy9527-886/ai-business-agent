import pytest

from app.agents.router import RouteDecision, RouterAgent


class FailingChat:
    async def structured(self, *args, **kwargs):
        raise RuntimeError("provider detail")


class ChoosingChat:
    async def structured(self, profile, schema, messages):
        return RouteDecision(
            intent="mixed", routes=["knowledge", "business", "web"],
            reason="mixed question", risk_level="low",
        )


@pytest.mark.asyncio
async def test_router_filters_model_choice_by_allowed_capabilities() -> None:
    decision = await RouterAgent(ChoosingChat()).route(
        "制度和订单", chat_profile="default-chat", allowed=["knowledge", "business"]
    )
    assert decision.routes == ["knowledge", "business"]


@pytest.mark.asyncio
async def test_router_failure_deterministically_falls_back_to_knowledge_only() -> None:
    decision = await RouterAgent(FailingChat()).route(
        "任意问题", chat_profile="default-chat", allowed=["knowledge", "business", "web"]
    )
    assert decision.intent == "knowledge"
    assert decision.routes == ["knowledge"]

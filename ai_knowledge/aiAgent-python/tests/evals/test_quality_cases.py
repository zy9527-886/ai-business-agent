from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.agents.guard import GuardAgent
from app.agents.router import RouteDecision


CASES = json.loads(Path(__file__).with_name("cases.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["id"])
def test_eval_case_contracts_are_safe_and_structured(case: dict[str, object]) -> None:
    routes = case["routes"]
    decision = RouteDecision(
        intent=case["intent"], routes=routes, reason="deterministic eval fixture",
        risk_level="high" if case.get("expected") == "handoff" else "low",
    )
    assert set(decision.routes) <= {"knowledge", "business", "web"}

    allowed, _ = GuardAgent().check(case["question"])
    if case.get("expected") == "blocked":
        assert allowed is False
        assert decision.routes == []
    else:
        assert allowed is True

    if case.get("requiresDemoLabel"):
        assert "business" in decision.routes
    if case.get("requiresUrl"):
        assert decision.routes == ["web"]
    if case.get("requiresEvidence"):
        assert decision.routes

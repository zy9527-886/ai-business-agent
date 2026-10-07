from __future__ import annotations

import asyncio
from typing import Any

from langgraph.graph import END, START, StateGraph

from app.agents.guard import GuardAgent
from app.agents.router import RouteDecision
from app.graph.routing import merge_specialist_results
from app.graph.state import GraphOutcome, GraphState
from app.models.api import ChatStreamRequest
from app.models.domain import AgentError, ReviewResult, SpecialistResult
from app.persistence.checkpoints import checkpoint_config


class MultiAgentRuntime:
    def __init__(
        self,
        *,
        router: object,
        specialists: dict[str, object],
        answer: object,
        reviewer: object,
        guard: GuardAgent | None = None,
        conversation_repository: object | None = None,
        run_repository: object | None = None,
        handoff_repository: object | None = None,
        checkpointer: object | None = None,
    ) -> None:
        self._router = router
        self._specialists = specialists
        self._answer = answer
        self._reviewer = reviewer
        self._guard = guard or GuardAgent()
        self._conversations = conversation_repository
        self._runs = run_repository
        self._handoffs = handoff_repository
        self._checkpointer = checkpointer
        self._graph = self._build_graph()

    def _build_graph(self):
        graph = StateGraph(GraphState)
        graph.add_node("guard", self._guard_node)
        graph.add_node("router", self._router_node)
        graph.add_node("specialists", self._specialists_node)
        graph.add_node("answer", self._answer_node)
        graph.add_node("review", self._review_node)
        graph.add_node("rewrite", self._rewrite_node)
        graph.add_edge(START, "guard")
        graph.add_edge("guard", "router")
        graph.add_edge("router", "specialists")
        graph.add_edge("specialists", "answer")
        graph.add_edge("answer", "review")
        graph.add_conditional_edges(
            "review", self._after_review, {"rewrite": "rewrite", "done": END}
        )
        graph.add_edge("rewrite", "review")
        return graph.compile(checkpointer=self._checkpointer)

    async def run(self, request: ChatStreamRequest, *, allowed: list[str]) -> GraphOutcome:
        enabled = set(request.options.enabled_agents)
        effective_allowed = [name for name in allowed if name in enabled]
        if self._conversations is not None:
            await self._conversations.ensure(
                request.conversation_id, request.user.user_id, request.question[:100]
            )
        if self._runs is not None:
            await self._runs.start(
                request.request_id,
                request.conversation_id,
                request.user.user_id,
                request.model_dump(mode="json", by_alias=True),
            )
        try:
            state = await self._graph.ainvoke(
                {
                    "request": request,
                    "allowed": effective_allowed,
                    "evidence": [],
                    "errors": [],
                    "rewrite_count": 0,
                },
                config=(
                    checkpoint_config(request.conversation_id, request.user.user_id)
                    if self._checkpointer is not None
                    else None
                ),
            )
        except asyncio.CancelledError:
            if self._runs is not None:
                await self._runs.finish(request.request_id, "cancelled", None, [], [])
            raise
        except BaseException:
            if self._runs is not None:
                await self._runs.finish(request.request_id, "failed", None, [], [])
            raise
        review = state.get("review") or ReviewResult(
            decision="HANDOFF", confidence=0.0, grounded=False,
            risk_level="medium", issues=["incomplete graph"],
        )
        outcome = GraphOutcome(
            request_id=request.request_id,
            conversation_id=request.conversation_id,
            route=state.get("route"),
            evidence=state.get("evidence", []),
            errors=state.get("errors", []),
            answer=state.get("answer", ""),
            review=review,
            blocked=state.get("blocked", False),
            block_reason=state.get("block_reason"),
        )
        if self._runs is not None:
            terminal_status = "handoff" if review.decision == "HANDOFF" else "completed"
            await self._runs.finish(
                request.request_id,
                terminal_status,
                outcome.route.model_dump(mode="json", by_alias=True) if outcome.route else None,
                [item.model_dump(mode="json", by_alias=True) for item in outcome.evidence],
                [item.model_dump(mode="json", by_alias=True) for item in outcome.errors],
            )
        if review.decision == "HANDOFF" and self._handoffs is not None:
            await self._handoffs.create(
                request.request_id,
                request.conversation_id,
                request.user.user_id,
                outcome.block_reason or "; ".join(review.issues) or "review handoff",
            )
        return outcome

    async def _guard_node(self, state: GraphState) -> dict[str, Any]:
        allowed, reason = self._guard.check(state["request"].question)
        return {"blocked": not allowed, "block_reason": reason}

    async def _router_node(self, state: GraphState) -> dict[str, Any]:
        if state.get("blocked"):
            return {"route": RouteDecision(
                intent="unsupported", routes=[], reason="guard blocked", risk_level="high"
            )}
        request = state["request"]
        route = await self._router.route(
            request.question,
            chat_profile=request.options.chat_model_profile,
            allowed=state["allowed"],
        )
        return {"route": route}

    async def _specialists_node(self, state: GraphState) -> dict[str, Any]:
        if state.get("blocked"):
            return {"evidence": [], "errors": []}
        request = state["request"]
        calls = []
        for name in state["route"].routes:
            specialist = self._specialists.get(name)
            if specialist is None:
                continue
            if name == "knowledge":
                calls.append(specialist.run(
                    request.question,
                    embedding_profile=request.options.embedding_model_profile,
                    top_k=request.options.top_k,
                    similarity_threshold=request.options.similarity_threshold,
                ))
            else:
                calls.append(specialist.run(request.question))
        if not calls:
            return {"evidence": [], "errors": []}
        raw_results = await asyncio.gather(*calls, return_exceptions=True)
        results: list[SpecialistResult] = []
        for result in raw_results:
            if isinstance(result, SpecialistResult):
                results.append(result)
            else:
                results.append(SpecialistResult(errors=[AgentError(
                    agent="orchestrator", code="AGENT_FAILED", retryable=False,
                    public_message="部分信息源暂时不可用。",
                )]))
        evidence, errors = merge_specialist_results(results)
        return {"evidence": evidence, "errors": errors}

    async def _answer_node(self, state: GraphState) -> dict[str, Any]:
        if state.get("blocked"):
            return {"answer": "该请求涉及受保护的系统信息，无法处理。"}
        request = state["request"]
        answer = await self._answer.draft(
            request.question, state.get("evidence", []), request.options.chat_model_profile
        )
        return {"answer": answer}

    async def _review_node(self, state: GraphState) -> dict[str, Any]:
        if state.get("blocked"):
            return {"review": ReviewResult(
                decision="HANDOFF", confidence=1.0, grounded=True,
                risk_level="high", issues=[state.get("block_reason") or "blocked"],
            )}
        request = state["request"]
        review = await self._reviewer.review(
            request.question,
            state.get("evidence", []),
            state.get("answer", ""),
            request.options.chat_model_profile,
        )
        if review.decision == "RETRY" and state.get("rewrite_count", 0) >= 1:
            review = review.model_copy(update={
                "decision": "HANDOFF",
                "issues": [*review.issues, "rewrite limit reached"],
            })
        return {"review": review}

    def _after_review(self, state: GraphState) -> str:
        if state["review"].decision == "RETRY" and state.get("rewrite_count", 0) < 1:
            return "rewrite"
        return "done"

    async def _rewrite_node(self, state: GraphState) -> dict[str, Any]:
        request = state["request"]
        answer = await self._answer.rewrite(
            request.question,
            state.get("evidence", []),
            state.get("answer", ""),
            state["review"],
            request.options.chat_model_profile,
        )
        return {"answer": answer, "rewrite_count": state.get("rewrite_count", 0) + 1}

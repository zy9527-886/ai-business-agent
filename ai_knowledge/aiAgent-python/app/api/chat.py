from __future__ import annotations

import json
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.models.api import ChatStreamRequest


def _event(name: str, data: object) -> str:
    if isinstance(data, str):
        payload = data.replace("\r", "").replace("\n", "\\n")
    else:
        payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {name}\ndata: {payload}\n\n"


def build_chat_router(
    runtime: object, capability_registry: object, auth_dependency: object
) -> APIRouter:
    router = APIRouter(
        prefix="/api/v1", tags=["chat"], dependencies=[Depends(auth_dependency)]
    )

    @router.post("/chat/stream")
    async def chat_stream(request: ChatStreamRequest) -> StreamingResponse:
        async def generate() -> AsyncIterator[str]:
            yield _event("meta", {
                "requestId": request.request_id,
                "conversationId": request.conversation_id,
            })
            yield _event("status", {"phase": "orchestrating"})
            outcome = await runtime.run(
                request, allowed=capability_registry.allowed_for(request.user)
            )
            yield _event(
                "sources",
                [item.model_dump(mode="json", by_alias=True) for item in outcome.evidence],
            )
            for error in outcome.errors:
                yield _event("error", error.model_dump(mode="json", by_alias=True))
            yield _event("review", outcome.review.model_dump(mode="json", by_alias=True))
            if outcome.review.decision == "HANDOFF":
                yield _event("handoff", {
                    "reason": outcome.block_reason or "; ".join(outcome.review.issues)
                })
            if outcome.answer:
                for start in range(0, len(outcome.answer), 80):
                    yield _event("message", outcome.answer[start:start + 80])
            yield _event("done", {"requestId": request.request_id})

        return StreamingResponse(
            generate(), media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    return router

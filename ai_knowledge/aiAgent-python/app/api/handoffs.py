from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field

from app.models.api import UserContext
from app.models.domain import ApiModel
from app.persistence.repositories import InvalidHandoffTransition


class HandoffSearchRequest(ApiModel):
    user: UserContext


class HandoffUpdateRequest(ApiModel):
    user: UserContext
    status: str = Field(pattern="^(in_progress|resolved|cancelled)$")


def build_handoff_router(repository: object, auth_dependency: object) -> APIRouter:
    router = APIRouter(
        prefix="/api/v1/handoffs",
        tags=["handoffs"],
        dependencies=[Depends(auth_dependency)],
    )

    @router.post("/search")
    async def search(request: HandoffSearchRequest) -> dict[str, object]:
        rows = await repository.list_for(
            user_id=request.user.user_id,
            is_admin=request.user.role.lower() == "admin",
        )
        return {"items": rows}

    @router.patch("/{handoff_id}")
    async def update(handoff_id: int, request: HandoffUpdateRequest) -> dict[str, str]:
        try:
            await repository.update_status(
                handoff_id,
                request.status,
                actor_user_id=request.user.user_id,
                is_admin=request.user.role.lower() == "admin",
            )
        except PermissionError as exc:
            raise HTTPException(status_code=403, detail="administrator role is required") from exc
        except LookupError as exc:
            raise HTTPException(status_code=404, detail="handoff not found") from exc
        except InvalidHandoffTransition as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        return {"status": request.status}

    return router

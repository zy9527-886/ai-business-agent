from collections.abc import Callable

from fastapi import APIRouter, HTTPException


def build_health_router(readiness_check: Callable[[], bool]) -> APIRouter:
    router = APIRouter(prefix="/health", tags=["health"])

    @router.get("/live")
    def live() -> dict[str, str]:
        return {"status": "ok"}

    @router.get("/ready")
    def ready() -> dict[str, str]:
        if not readiness_check():
            raise HTTPException(status_code=503, detail="AI runtime is not ready")
        return {"status": "ready"}

    return router


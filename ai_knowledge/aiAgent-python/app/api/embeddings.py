from typing import Protocol

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.models.api import EmbeddingRequest


class EmbeddingService(Protocol):
    async def embed_documents(self, profile: str, texts: list[str]) -> list[list[float]]: ...

    def capabilities(self) -> list[dict[str, object]]: ...


class EmbeddingResponse(BaseModel):
    profile: str
    dimension: int
    vectors: list[list[float]]


def build_embeddings_router(
    provider: EmbeddingService, auth_dependency: object
) -> APIRouter:
    router = APIRouter(prefix="/api/v1", tags=["models"], dependencies=[Depends(auth_dependency)])

    @router.post("/embeddings", response_model=EmbeddingResponse)
    async def embeddings(request: EmbeddingRequest) -> EmbeddingResponse:
        vectors = await provider.embed_documents(request.profile, request.texts)
        return EmbeddingResponse(
            profile=request.profile,
            dimension=len(vectors[0]),
            vectors=vectors,
        )

    @router.get("/capabilities/models")
    async def model_capabilities() -> dict[str, object]:
        return {"profiles": provider.capabilities()}

    return router


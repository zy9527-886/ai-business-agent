from collections.abc import Callable
from pathlib import Path
from typing import Any

from fastapi import FastAPI

from app.api.health import build_health_router
from app.core.config import get_settings
from app.core.security import InMemoryNonceStore, SignatureVerifier, build_auth_dependency
from app.llm.embedding_provider import EmbeddingProvider
from app.llm.profiles import ModelProfileRegistry


def _default_embedding_provider() -> EmbeddingProvider:
    registry = ModelProfileRegistry.load(Path(__file__).with_name("profiles.yaml"))
    return EmbeddingProvider(registry)


def create_app(
    readiness_check: Callable[[], bool] | None = None,
    embedding_provider: Any | None = None,
    shared_secret: str | None = None,
) -> FastAPI:
    settings = get_settings()
    app = FastAPI(title=settings.app_name, version="0.1.0")
    app.include_router(build_health_router(readiness_check or (lambda: True)))
    if embedding_provider is not None:
        from app.api.embeddings import build_embeddings_router

        verifier = SignatureVerifier(
            shared_secret or settings.shared_secret,
            InMemoryNonceStore(),
            settings.signature_max_clock_skew_seconds,
        )
        app.include_router(
            build_embeddings_router(embedding_provider, build_auth_dependency(verifier))
        )
    return app


app = create_app()


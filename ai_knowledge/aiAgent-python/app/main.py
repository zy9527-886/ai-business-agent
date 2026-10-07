from collections.abc import Callable
from pathlib import Path
from typing import Any

from fastapi import FastAPI

from app.api.health import build_health_router
from app.core.config import get_settings
from app.core.security import InMemoryNonceStore, SignatureVerifier, build_auth_dependency
from app.agents.answer import AnswerAgent
from app.agents.business import BusinessAgent
from app.agents.knowledge import KnowledgeAgent
from app.agents.reviewer import ReviewerAgent
from app.agents.router import RouterAgent
from app.agents.web import WebAgent
from app.graph.builder import MultiAgentRuntime
from app.llm.chat_provider import ChatProvider
from app.llm.embedding_provider import EmbeddingProvider
from app.llm.profiles import ModelProfileRegistry
from app.tools.java_client import JavaAgentClient
from app.tools.registry import CapabilityRegistry
from app.tools.web_search import TavilyWebSearchProvider


def _default_embedding_provider() -> EmbeddingProvider:
    registry = ModelProfileRegistry.load(Path(__file__).with_name("profiles.yaml"))
    return EmbeddingProvider(registry)


def create_app(
    readiness_check: Callable[[], bool] | None = None,
    embedding_provider: Any | None = None,
    chat_runtime: Any | None = None,
    capability_registry: Any | None = None,
    handoff_repository: Any | None = None,
    shared_secret: str | None = None,
) -> FastAPI:
    settings = get_settings()
    app = FastAPI(title=settings.app_name, version="0.1.0")
    app.include_router(build_health_router(readiness_check or (lambda: True)))
    if embedding_provider is not None or chat_runtime is not None or handoff_repository is not None:
        verifier = SignatureVerifier(
            shared_secret or settings.shared_secret,
            InMemoryNonceStore(),
            settings.signature_max_clock_skew_seconds,
        )
        auth_dependency = build_auth_dependency(verifier)
        if embedding_provider is not None:
            from app.api.embeddings import build_embeddings_router

            app.include_router(build_embeddings_router(embedding_provider, auth_dependency))
        if chat_runtime is not None:
            if capability_registry is None:
                raise ValueError("capability_registry is required with chat_runtime")
            from app.api.chat import build_chat_router

            app.include_router(
                build_chat_router(chat_runtime, capability_registry, auth_dependency)
            )
        if handoff_repository is not None:
            from app.api.handoffs import build_handoff_router

            app.include_router(build_handoff_router(handoff_repository, auth_dependency))
    return app


def create_default_app() -> FastAPI:
    settings = get_settings()
    registry = ModelProfileRegistry.load(Path(__file__).with_name("profiles.yaml"))
    embeddings = EmbeddingProvider(registry)
    chat = ChatProvider(registry)
    java = JavaAgentClient(
        settings.java_base_url,
        settings.shared_secret,
        timeout_seconds=settings.request_timeout_seconds,
    )
    web_provider = (
        TavilyWebSearchProvider(
            settings.tavily_api_key,
            timeout_seconds=min(settings.request_timeout_seconds, 30.0),
        )
        if settings.tavily_api_key
        else None
    )
    capabilities = CapabilityRegistry(web_enabled=web_provider is not None)
    database = None
    checkpointer = None
    conversations = None
    runs = None
    handoffs = None
    if settings.database_url:
        from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

        from app.persistence.database import Database
        from app.persistence.repositories import (
            ConversationRepository,
            HandoffRepository,
            RunRepository,
        )

        database = Database(settings.database_url)
        conversations = ConversationRepository(database.pool)
        runs = RunRepository(database.pool)
        handoffs = HandoffRepository(database.pool)
        checkpointer = AsyncPostgresSaver(database.pool)
    runtime = MultiAgentRuntime(
        router=RouterAgent(chat),
        specialists={
            "knowledge": KnowledgeAgent(embeddings, java),
            "business": BusinessAgent(java),
            "web": WebAgent(web_provider),
        },
        answer=AnswerAgent(chat),
        reviewer=ReviewerAgent(chat),
        conversation_repository=conversations,
        run_repository=runs,
        handoff_repository=handoffs,
        checkpointer=checkpointer,
    )
    default_app = create_app(
        readiness_check=lambda: any(
            item["enabled"] and item["kind"] == "chat"
            for item in registry.capabilities()
        ) and any(
            item["enabled"] and item["kind"] == "embedding"
            for item in registry.capabilities()
        ),
        embedding_provider=embeddings,
        chat_runtime=runtime,
        capability_registry=capabilities,
        handoff_repository=handoffs,
        shared_secret=settings.shared_secret,
    )

    async def start_persistence() -> None:
        if database is not None:
            await database.open()
            await database.bootstrap()
            await checkpointer.setup()

    async def close_clients() -> None:
        await java.aclose()
        if web_provider is not None:
            await web_provider.aclose()
        if database is not None:
            await database.close()

    default_app.router.add_event_handler("startup", start_persistence)
    default_app.router.add_event_handler("shutdown", close_clients)
    return default_app


app = create_default_app()


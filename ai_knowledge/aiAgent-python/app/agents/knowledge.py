from __future__ import annotations

from app.models.domain import AgentError, SpecialistResult


class KnowledgeAgent:
    def __init__(self, embedding_provider: object, java_client: object) -> None:
        self._embeddings = embedding_provider
        self._java = java_client

    async def run(
        self,
        question: str,
        *,
        embedding_profile: str,
        top_k: int,
        similarity_threshold: float,
        knowledge_base_ids: list[int] | None = None,
    ) -> SpecialistResult:
        try:
            vectors = await self._embeddings.embed_documents(
                embedding_profile, [question]
            )
            evidence = await self._java.search_knowledge(
                vectors[0],
                top_k=top_k,
                similarity_threshold=similarity_threshold,
                knowledge_base_ids=knowledge_base_ids,
            )
            return SpecialistResult(evidence=evidence)
        except TimeoutError:
            return SpecialistResult(errors=[AgentError(
                agent="knowledge", code="KNOWLEDGE_TIMEOUT", retryable=True,
                public_message="知识检索暂时超时，请稍后重试。",
            )])
        except Exception:
            return SpecialistResult(errors=[AgentError(
                agent="knowledge", code="KNOWLEDGE_FAILED", retryable=False,
                public_message="知识检索暂时不可用。",
            )])

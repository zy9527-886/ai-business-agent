# Python Multi-Agent Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-shaped `aiAgent-python` service and integrate it with the existing Java/Vue application so chat, embeddings, routing, knowledge retrieval, demo business tools, real web search, review, handoff, persistence, SSE, and fallback form one working loop.

**Architecture:** Java remains the authenticated business/data gateway and pgvector owner. Python directly owns chat/embedding providers, LangGraph orchestration, agent state, web search, persistence, and SSE; it calls only signed Java vector/business tools. Existing Java RAG remains a feature-flagged fallback.

**Tech Stack:** Python 3.12, FastAPI, LangGraph, langchain-openai, Pydantic v2, HTTPX, psycopg 3, PostgreSQL, pytest; Java 21, Spring Boot 3.5, WebClient/MVC SSE, JUnit 5; Vue 3/Vite.

**Spec:** `.skills/target.md`

## Global Constraints

- New chat and embedding model calls run directly in Python; Java must not expose a model gateway.
- Python never reads Java business tables directly; Java never receives Python model API keys.
- Knowledge retrieval uses Python query embeddings and Java pgvector search with a fixed dimension check.
- Business tools are read-only demo endpoints whose evidence always carries `demo=true`.
- Web search is real Tavily access when configured and absent from capabilities when unconfigured.
- Existing `sources`, `message`, `error`, and `done` SSE semantics remain compatible.
- Review retry count is at most one; total request timeout is 120 seconds.
- Do not modify or commit the user's pre-existing `log/smart-manage-info.log` change.
- Preserve the user's current datasource values in `application.yml`; new Java settings use environment-backed configuration defaults.

## Review Focus

- A duplicate/stale HMAC nonce must be rejected by both services without reaching a tool; covered in Tasks 1 and 3.
- Embedding dimensions that differ from pgvector configuration must fail before any vector row is written; covered in Tasks 2 and 4.
- A partial parallel-agent failure must retain successful evidence and complete safely; covered in Task 6.
- Business demo evidence must remain visibly demo data through answer and SSE serialization; covered in Tasks 5 and 6.
- Client cancellation must stop downstream Python graph/model/tool work and Java SSE forwarding; covered in Tasks 6 and 8.

---

### Task 1: Python service foundation, contracts, and service authentication

**Files:**
- Create: `ai_knowledge/aiAgent-python/pyproject.toml`
- Create: `ai_knowledge/aiAgent-python/.env.example`
- Create: `ai_knowledge/aiAgent-python/app/main.py`
- Create: `ai_knowledge/aiAgent-python/app/core/config.py`
- Create: `ai_knowledge/aiAgent-python/app/core/security.py`
- Create: `ai_knowledge/aiAgent-python/app/models/api.py`
- Create: `ai_knowledge/aiAgent-python/app/models/domain.py`
- Create: `ai_knowledge/aiAgent-python/app/api/health.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_security.py`
- Test: `ai_knowledge/aiAgent-python/tests/contract/test_api_models.py`
- Test: `ai_knowledge/aiAgent-python/tests/integration/test_health.py`

**Interfaces:**
- Produces: `Settings`, `verify_service_signature(request, body)`, `ChatStreamRequest`, `EmbeddingRequest`, `Evidence`, `AgentError`, `ReviewResult`, `create_app()`.

- [ ] Write tests for valid signature, stale timestamp, duplicate nonce, modified body, DTO aliases, health and readiness.
- [ ] Run `python -m pytest tests/unit/test_security.py tests/contract/test_api_models.py tests/integration/test_health.py -q`; expect collection/import failures.
- [ ] Implement the minimal FastAPI application, typed settings, bounded in-memory nonce store, HMAC-SHA256 dependency, contracts and health routes.
- [ ] Re-run the task tests; expect all pass.
- [ ] Commit only Task 1 files with `feat(agent-python): scaffold service contracts`.

### Task 2: Direct chat/embedding providers and embedding API

**Files:**
- Create: `ai_knowledge/aiAgent-python/app/llm/profiles.py`
- Create: `ai_knowledge/aiAgent-python/app/llm/chat_provider.py`
- Create: `ai_knowledge/aiAgent-python/app/llm/embedding_provider.py`
- Create: `ai_knowledge/aiAgent-python/app/profiles.yaml`
- Create: `ai_knowledge/aiAgent-python/app/api/embeddings.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_profiles.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_providers.py`
- Test: `ai_knowledge/aiAgent-python/tests/integration/test_embeddings_api.py`

**Interfaces:**
- Consumes: Task 1 `Settings`, `EmbeddingRequest` and HMAC dependency.
- Produces: `ModelProfileRegistry`, `ChatProvider.with_structured_output(schema)`, `EmbeddingProvider.embed_documents(texts)`, `POST /api/v1/embeddings`, `GET /api/v1/capabilities/models`.

- [ ] Write tests for profile secret indirection, unknown/disabled profiles, structured chat output, ordered batch embeddings, batch/text limits and dimension mismatch.
- [ ] Run the focused tests; expect failures because providers/routes do not exist.
- [ ] Implement OpenAI-compatible providers with injected fake clients for tests; secrets come only from environment variables.
- [ ] Implement authenticated embedding/capability endpoints and readiness checks for enabled profiles.
- [ ] Re-run focused tests; expect all pass.
- [ ] Commit with `feat(agent-python): add direct model providers`.

### Task 3: Java internal service authentication, vector search, capabilities, and demo business tools

**Files:**
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/config/AgentPythonProperties.java`
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/common/config/AgentServiceAuthInterceptor.java`
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/entity/agent/VectorSearchRequest.java`
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/entity/agent/AgentEvidence.java`
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/controller/InternalAgentController.java`
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/service/AgentToolService.java`
- Modify: `ai_knowledge/aiAgent-back/src/main/java/com/example/common/config/WebConfig.java`
- Modify: `ai_knowledge/aiAgent-back/src/main/java/com/example/service/PgVectorService.java`
- Test: `ai_knowledge/aiAgent-back/src/test/java/com/example/common/config/AgentServiceAuthInterceptorTest.java`
- Test: `ai_knowledge/aiAgent-back/src/test/java/com/example/service/AgentToolServiceTest.java`

**Interfaces:**
- Consumes: vector list from Python and existing `PgVectorService.searchSimilar`.
- Produces: signed `/internal/agent/v1/knowledge/search`, `/capabilities`, `/demo/orders/{id}`, `/demo/tickets/{id}`, `/demo/inventory/{sku}`.

- [ ] Write interceptor tests for valid/stale/replayed/tampered signatures and service tests for dimension validation, title enrichment, fixed demo fixtures, not-found errors, and `demo=true`.
- [ ] Run targeted Maven tests; expect compilation failures.
- [ ] Implement environment-backed properties, separate internal interceptor registration, vector search DTO/service/controller and immutable demo fixtures.
- [ ] Re-run targeted tests; expect all pass.
- [ ] Commit with `feat(agent-back): expose signed agent tools`.

### Task 4: Python embedding migration for Java knowledge ingestion

**Files:**
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/client/PythonEmbeddingClient.java`
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/entity/agent/EmbeddingRequest.java`
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/entity/agent/EmbeddingResponse.java`
- Modify: `ai_knowledge/aiAgent-back/src/main/java/com/example/service/KnowledgeSegmentService.java`
- Modify: `ai_knowledge/aiAgent-back/src/main/java/com/example/controller/KnowledgeSegmentController.java`
- Modify: `ai_knowledge/aiAgent-back/src/main/java/com/example/entity/VectorizeRequest.java`
- Modify: `ai_knowledge/aiAgent-front/src/views/manager/KnowledgeSegment.vue`
- Test: `ai_knowledge/aiAgent-back/src/test/java/com/example/client/PythonEmbeddingClientTest.java`
- Test: `ai_knowledge/aiAgent-back/src/test/java/com/example/service/KnowledgeSegmentServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `POST /api/v1/embeddings`; Task 3 HMAC settings; existing `PgVectorService.saveSegmentVector`.
- Produces: `PythonEmbeddingClient.embed(profile, texts)`, `vectorizeById(id, profile)`, `vectorizeBatch(ids, profile)`.

- [ ] Write failing tests for signed embedding requests, response order, timeout, dimension mismatch, segment status success/failure and batch behavior.
- [ ] Run targeted Maven tests; expect failures.
- [ ] Implement the HTTP client and replace new-path `EmbeddingService` usage with Python batches while leaving the legacy service for fallback.
- [ ] Change controller/request/frontend from integer model IDs to string embedding Profile names loaded through proxied Python capabilities.
- [ ] Re-run targeted backend tests and `npm.cmd run build`; expect pass.
- [ ] Commit with `feat(knowledge): use python embeddings`.

### Task 5: Python Java-tool client, capability registry, and specialist agents

**Files:**
- Create: `ai_knowledge/aiAgent-python/app/tools/java_client.py`
- Create: `ai_knowledge/aiAgent-python/app/tools/registry.py`
- Create: `ai_knowledge/aiAgent-python/app/tools/web_search.py`
- Create: `ai_knowledge/aiAgent-python/app/agents/knowledge.py`
- Create: `ai_knowledge/aiAgent-python/app/agents/business.py`
- Create: `ai_knowledge/aiAgent-python/app/agents/web.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_java_client.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_registry.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_specialist_agents.py`

**Interfaces:**
- Consumes: Task 2 embedding provider; Task 3 internal APIs; Task 1 `Evidence`.
- Produces: `JavaAgentClient.search_knowledge/query_demo_*`, `CapabilityRegistry.allowed_for(user)`, `KnowledgeAgent.run`, `BusinessAgent.run`, `WebAgent.run`.

- [ ] Write tests for signed Java calls, permission filtering, Tavily-disabled behavior, SSRF/secret-exfiltration guards, source normalization, partial timeout and demo preservation.
- [ ] Run focused tests; expect failures.
- [ ] Implement the async client, deterministic capability registry, real Tavily HTTP provider, and three typed specialist agents.
- [ ] Re-run focused tests; expect all pass.
- [ ] Commit with `feat(agent-python): add specialist agents`.

### Task 6: LangGraph router, aggregation, answer/review loop, and SSE

**Files:**
- Create: `ai_knowledge/aiAgent-python/app/graph/state.py`
- Create: `ai_knowledge/aiAgent-python/app/graph/routing.py`
- Create: `ai_knowledge/aiAgent-python/app/graph/builder.py`
- Create: `ai_knowledge/aiAgent-python/app/agents/router.py`
- Create: `ai_knowledge/aiAgent-python/app/agents/answer.py`
- Create: `ai_knowledge/aiAgent-python/app/agents/reviewer.py`
- Create: `ai_knowledge/aiAgent-python/app/agents/guard.py`
- Create: `ai_knowledge/aiAgent-python/app/api/chat.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_router.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_review_loop.py`
- Test: `ai_knowledge/aiAgent-python/tests/integration/test_graph.py`
- Test: `ai_knowledge/aiAgent-python/tests/integration/test_chat_stream.py`

**Interfaces:**
- Consumes: Tasks 1, 2, and 5 contracts/providers/agents.
- Produces: compiled graph, `run_chat(request, cancel_scope)`, authenticated `POST /api/v1/chat/stream` emitting `meta/status/sources/message/review/handoff/error/done`.

- [ ] Write failing tests for guard rejection, structured routing, deterministic fallback, parallel evidence merge, partial branch failure, evidence conflict, one retry maximum, demo wording, no-evidence handling and cancellation.
- [ ] Run focused tests; expect failures.
- [ ] Implement typed state/reducers, explicit nodes/conditional edges and bounded retry policies.
- [ ] Implement SSE serialization that buffers the reviewed draft but emits compatible message chunks after PASS.
- [ ] Re-run focused tests; expect all pass.
- [ ] Commit with `feat(agent-python): implement multi-agent graph`.

### Task 7: PostgreSQL persistence and human handoff

**Files:**
- Create: `ai_knowledge/aiAgent-python/app/persistence/schema.sql`
- Create: `ai_knowledge/aiAgent-python/app/persistence/database.py`
- Create: `ai_knowledge/aiAgent-python/app/persistence/repositories.py`
- Create: `ai_knowledge/aiAgent-python/app/persistence/checkpoints.py`
- Create: `ai_knowledge/aiAgent-python/app/api/handoffs.py`
- Modify: `ai_knowledge/aiAgent-python/app/graph/builder.py`
- Test: `ai_knowledge/aiAgent-python/tests/unit/test_repositories.py`
- Test: `ai_knowledge/aiAgent-python/tests/integration/test_persistence.py`

**Interfaces:**
- Consumes: Task 6 graph state and handoff decisions.
- Produces: `ConversationRepository`, `RunRepository`, `HandoffRepository`, PostgreSQL checkpointer factory, signed admin handoff list/update endpoints.

- [ ] Write failing repository tests for ownership, redaction, run lifecycle, handoff state transitions and checkpoint `thread_id` isolation.
- [ ] Run focused tests; expect failures or skip only when `TEST_DATABASE_URL` is absent as explicitly marked integration tests.
- [ ] Implement schema bootstrap, psycopg repositories, LangGraph PostgreSQL checkpointer wiring and handoff API.
- [ ] Re-run unit tests plus database integration tests when configured; expect pass.
- [ ] Commit with `feat(agent-python): persist runs and handoffs`.

### Task 8: Java Python-chat SSE gateway, cancellation, and fallback

**Files:**
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/client/PythonAgentClient.java`
- Create: `ai_knowledge/aiAgent-back/src/main/java/com/example/service/MultiAgentChatService.java`
- Modify: `ai_knowledge/aiAgent-back/src/main/java/com/example/controller/KnowledgeChatController.java`
- Modify: `ai_knowledge/aiAgent-back/src/main/java/com/example/entity/KnowledgeChatRequest.java`
- Modify: `ai_knowledge/aiAgent-back/pom.xml`
- Test: `ai_knowledge/aiAgent-back/src/test/java/com/example/client/PythonAgentClientTest.java`
- Test: `ai_knowledge/aiAgent-back/src/test/java/com/example/service/MultiAgentChatServiceTest.java`

**Interfaces:**
- Consumes: Task 6 Python chat SSE and Task 3 signing properties.
- Produces: existing `/knowledgeChat/askStream` and `/frontAskStream` backed by Python when enabled, with legacy `KnowledgeChatService` fallback.

- [ ] Write failing tests for request mapping, signed streaming, event passthrough, downstream error, timeout, browser cancellation and enabled/disabled fallback.
- [ ] Run targeted Maven tests; expect failures.
- [ ] Implement WebClient-based streaming client and `SseEmitter` bridge without full-response buffering.
- [ ] Wire controller to `MultiAgentChatService`; preserve legacy service untouched as fallback.
- [ ] Re-run targeted tests; expect all pass.
- [ ] Commit with `feat(agent-back): proxy multi-agent chat`.

### Task 9: Frontend status/review/handoff UI and model Profiles

**Files:**
- Modify: `ai_knowledge/aiAgent-front/src/views/manager/KnowledgeChat.vue`
- Modify: `ai_knowledge/aiAgent-front/src/views/front/Home.vue`
- Modify: `ai_knowledge/aiAgent-front/src/views/manager/KnowledgeSegment.vue`
- Modify: `ai_knowledge/aiAgent-front/src/views/manager/AiModelConfig.vue`
- Create: `ai_knowledge/aiAgent-front/src/utils/sse.js`
- Test: `ai_knowledge/aiAgent-front/src/utils/sse.test.js`
- Modify: `ai_knowledge/aiAgent-front/package.json`

**Interfaces:**
- Consumes: Tasks 2, 4, 6, and 8 model capability and SSE contracts.
- Produces: shared SSE parser; management trace/review/source/demo/handoff display; Profile selectors; compatible user chat.

- [ ] Add Vitest and write failing parser tests for multiline JSON, CRLF, split chunks, all event types and malformed event isolation.
- [ ] Run the focused frontend test; expect failure.
- [ ] Implement shared parser and update all three screens; replace new-path Java model IDs with Python Profile names.
- [ ] Run frontend tests and `npm.cmd run build`; expect pass.
- [ ] Commit with `feat(agent-front): display multi-agent workflow`.

### Task 10: Deployment, documentation, and full-loop verification

**Files:**
- Create: `ai_knowledge/aiAgent-python/Dockerfile`
- Create: `ai_knowledge/aiAgent-python/README.md`
- Create: `ai_knowledge/aiAgent-python/tests/evals/cases.json`
- Create: `ai_knowledge/aiAgent-python/tests/evals/test_quality_cases.py`
- Modify: `README.md`
- Modify: `ai_knowledge.sql`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: reproducible startup, non-secret environment template, runtime schema setup, evaluation fixtures and complete operator documentation.

- [ ] Write evaluation tests for knowledge, demo business, web, mixed, unsupported, injection and handoff cases using deterministic fake providers.
- [ ] Run Python, Java, and frontend suites once before documentation completion; record failures and fix by systematic debugging.
- [ ] Add container/startup docs, environment variable table, migration SQL and end-to-end smoke procedure without real secrets.
- [ ] Run `python -m pytest -q`, Maven `test`, and `npm.cmd run build`; expect all pass.
- [ ] Run an optional real-provider smoke only when explicit model/Tavily/test database credentials exist; otherwise report it as not executed, not passed.
- [ ] Commit with `docs: complete multi-agent deployment and verification`.

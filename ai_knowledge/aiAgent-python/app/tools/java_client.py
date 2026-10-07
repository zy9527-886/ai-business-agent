from __future__ import annotations

import json
import time
import uuid
from collections.abc import Callable
from typing import Any
from urllib.parse import quote

import httpx

from app.core.security import sign_request
from app.models.domain import Evidence


class JavaToolError(RuntimeError):
    """A stable boundary error; response bodies and internal URLs stay private."""


class JavaAgentClient:
    def __init__(
        self,
        base_url: str,
        shared_secret: str,
        *,
        timeout_seconds: float = 20.0,
        timestamp: Callable[[], int] | None = None,
        nonce: Callable[[], str] | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._secret = shared_secret
        self._timestamp = timestamp or (lambda: int(time.time()))
        self._nonce = nonce or (lambda: str(uuid.uuid4()))
        self._client = client or httpx.AsyncClient(timeout=timeout_seconds)
        self._owns_client = client is None

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def search_knowledge(
        self,
        query_vector: list[float],
        *,
        top_k: int,
        similarity_threshold: float,
        knowledge_base_ids: list[int] | None = None,
    ) -> list[Evidence]:
        data = await self._request(
            "POST",
            "/internal/agent/v1/knowledge/search",
            {
                "queryVector": query_vector,
                "topK": top_k,
                "similarityThreshold": similarity_threshold,
                "knowledgeBaseIds": knowledge_base_ids or [],
            },
        )
        if not isinstance(data, list):
            raise JavaToolError("invalid knowledge response")
        return [Evidence.model_validate(item) for item in data]

    async def query_demo_order(self, order_no: str) -> Evidence:
        return await self._demo("orders", order_no)

    async def query_demo_ticket(self, ticket_no: str) -> Evidence:
        return await self._demo("tickets", ticket_no)

    async def query_demo_inventory(self, sku: str) -> Evidence:
        return await self._demo("inventory", sku)

    async def _demo(self, resource: str, identifier: str) -> Evidence:
        safe_identifier = quote(identifier, safe="")
        data = await self._request(
            "GET", f"/internal/agent/v1/demo/{resource}/{safe_identifier}", None
        )
        evidence = Evidence.model_validate(data)
        if evidence.source_type != "business" or not evidence.demo:
            raise JavaToolError("invalid demo evidence")
        return evidence

    async def _request(self, method: str, path: str, payload: dict[str, Any] | None) -> Any:
        body = (
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            if payload is not None
            else b""
        )
        timestamp = str(self._timestamp())
        nonce = self._nonce()
        headers = {
            "X-Timestamp": timestamp,
            "X-Nonce": nonce,
            "X-Signature": sign_request(
                self._secret, method, path, timestamp, nonce, body
            ),
        }
        if payload is not None:
            headers["Content-Type"] = "application/json"
        try:
            response = await self._client.request(
                method, self._base_url + path, content=body, headers=headers
            )
            response.raise_for_status()
            envelope = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise JavaToolError("Java tool request failed") from exc
        if not isinstance(envelope, dict) or envelope.get("code") != "200":
            raise JavaToolError("Java tool rejected request")
        return envelope.get("data")

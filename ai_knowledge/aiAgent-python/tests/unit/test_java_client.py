from __future__ import annotations

import json

import httpx
import pytest
import respx

from app.core.security import sign_request
from app.tools.java_client import JavaAgentClient


@pytest.mark.asyncio
@respx.mock
async def test_java_client_signs_exact_body_and_normalizes_evidence() -> None:
    route = respx.post("http://java.test/internal/agent/v1/knowledge/search").mock(
        return_value=httpx.Response(
            200,
            json={
                "code": "200",
                "data": [{
                    "sourceType": "knowledge",
                    "sourceId": "segment:1",
                    "title": "制度",
                    "content": "七天内退款",
                    "score": 0.9,
                    "observedAt": "2026-10-07T10:00:00+08:00",
                    "demo": False,
                    "metadata": {},
                }],
            },
        )
    )
    client = JavaAgentClient(
        "http://java.test", "test-secret", timestamp=lambda: 1_700_000_000,
        nonce=lambda: "nonce-1",
    )

    evidence = await client.search_knowledge([0.1, 0.2], top_k=3, similarity_threshold=0.4)

    request = route.calls.last.request
    body = request.content
    assert json.loads(body) == {
        "queryVector": [0.1, 0.2], "topK": 3,
        "similarityThreshold": 0.4, "knowledgeBaseIds": [],
    }
    assert request.headers["X-Signature"] == sign_request(
        "test-secret", "POST", "/internal/agent/v1/knowledge/search",
        "1700000000", "nonce-1", body,
    )
    assert evidence[0].source_id == "segment:1"
    assert evidence[0].score == 0.9


@pytest.mark.asyncio
@respx.mock
async def test_java_client_preserves_demo_marker() -> None:
    respx.get("http://java.test/internal/agent/v1/demo/orders/DEMO-1001").mock(
        return_value=httpx.Response(200, json={"code": "200", "data": {
            "sourceType": "business", "sourceId": "order:DEMO-1001",
            "title": "演示订单", "content": "演示数据",
            "observedAt": "2026-10-07T10:00:00+08:00", "demo": True,
        }})
    )
    client = JavaAgentClient("http://java.test", "test-secret")

    evidence = await client.query_demo_order("DEMO-1001")

    assert evidence.demo is True
    assert evidence.source_type == "business"

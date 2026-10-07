import json

from fastapi.testclient import TestClient

from app.agents.router import RouteDecision
from app.core.security import sign_request
from app.graph.state import GraphOutcome
from app.main import create_app
from app.models.domain import ReviewResult


class FakeRuntime:
    async def run(self, request, allowed):
        return GraphOutcome(
            request_id=request.request_id,
            conversation_id=request.conversation_id,
            route=RouteDecision(
                intent="knowledge", routes=["knowledge"], reason="test", risk_level="low"
            ),
            answer="最终答案",
            review=ReviewResult(
                decision="PASS", confidence=0.9, grounded=True,
                risk_level="low", issues=[],
            ),
        )


class Registry:
    def allowed_for(self, user):
        return ["knowledge"]


def test_chat_stream_requires_signature_and_emits_reviewed_message(monkeypatch) -> None:
    monkeypatch.setattr("time.time", lambda: 1000)
    app = create_app(
        chat_runtime=FakeRuntime(), capability_registry=Registry(),
        shared_secret="test-secret",
    )
    client = TestClient(app)
    payload = {
        "requestId": "r1", "conversationId": "c1", "question": "退款？",
        "user": {"userId": 1, "role": "user", "permissions": []},
    }
    body = json.dumps(payload, separators=(",", ":")).encode()
    path = "/api/v1/chat/stream"
    headers = {
        "X-Timestamp": "1000", "X-Nonce": "chat-nonce",
        "X-Signature": sign_request(
            "test-secret", "POST", path, "1000", "chat-nonce", body
        ),
        "Content-Type": "application/json",
    }

    assert client.post(path, content=body).status_code == 401
    response = client.post(path, content=body, headers=headers)

    assert response.status_code == 200
    assert "event: review" in response.text
    assert "event: message\ndata: 最终答案" in response.text
    assert response.text.index("event: review") < response.text.index("event: message")
    assert "event: done" in response.text

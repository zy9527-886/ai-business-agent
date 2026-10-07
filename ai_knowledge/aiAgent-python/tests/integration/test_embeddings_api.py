import hashlib
import hmac
import json

from fastapi.testclient import TestClient

from app.main import create_app


class FakeEmbeddingProvider:
    async def embed_documents(self, profile: str, texts: list[str]) -> list[list[float]]:
        assert profile == "default-embedding"
        return [[float(len(text)), 0.0, 1.0] for text in texts]

    def capabilities(self):
        return [{"name": "default-embedding", "kind": "embedding", "model": "fake", "dimension": 3}]


def signed_headers(secret: str, path: str, body: bytes) -> dict[str, str]:
    timestamp = "1000"
    nonce = "nonce-api"
    body_hash = hashlib.sha256(body).hexdigest()
    canonical = "\n".join(("POST", path, timestamp, nonce, body_hash))
    signature = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return {
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
        "Content-Type": "application/json",
    }


def test_embedding_endpoint_requires_signature_and_returns_dimension(monkeypatch) -> None:
    monkeypatch.setattr("time.time", lambda: 1000)
    client = TestClient(create_app(embedding_provider=FakeEmbeddingProvider(), shared_secret="test-secret"))
    payload = {"profile": "default-embedding", "texts": ["abc", "hello"]}
    body = json.dumps(payload, separators=(",", ":")).encode()

    unauthorized = client.post("/api/v1/embeddings", content=body, headers={"Content-Type": "application/json"})
    assert unauthorized.status_code == 401

    response = client.post(
        "/api/v1/embeddings",
        content=body,
        headers=signed_headers("test-secret", "/api/v1/embeddings", body),
    )
    assert response.status_code == 200
    assert response.json() == {
        "profile": "default-embedding",
        "dimension": 3,
        "vectors": [[3.0, 0.0, 1.0], [5.0, 0.0, 1.0]],
    }

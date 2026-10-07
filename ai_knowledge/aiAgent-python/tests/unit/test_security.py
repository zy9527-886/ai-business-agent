import hashlib
import hmac

import pytest

from app.core.security import InMemoryNonceStore, SignatureVerifier


def sign(secret: str, method: str, path: str, timestamp: str, nonce: str, body: bytes) -> str:
    body_hash = hashlib.sha256(body).hexdigest()
    canonical = "\n".join((method.upper(), path, timestamp, nonce, body_hash))
    return hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()


def test_accepts_valid_signature_once() -> None:
    verifier = SignatureVerifier("secret", InMemoryNonceStore(), max_clock_skew_seconds=60)
    body = b'{"question":"hello"}'
    signature = sign("secret", "POST", "/api/v1/chat/stream", "1000", "n-1", body)

    verifier.verify("POST", "/api/v1/chat/stream", "1000", "n-1", body, signature, now=1000)

    with pytest.raises(ValueError, match="nonce"):
        verifier.verify("POST", "/api/v1/chat/stream", "1000", "n-1", body, signature, now=1000)


def test_rejects_stale_or_tampered_signature() -> None:
    verifier = SignatureVerifier("secret", InMemoryNonceStore(), max_clock_skew_seconds=60)
    body = b"{}"
    signature = sign("secret", "POST", "/api/v1/embeddings", "1000", "n-2", body)

    with pytest.raises(ValueError, match="timestamp"):
        verifier.verify("POST", "/api/v1/embeddings", "1000", "n-2", body, signature, now=1061)

    with pytest.raises(ValueError, match="signature"):
        verifier.verify("POST", "/api/v1/embeddings", "1000", "n-3", b'{"changed":true}', signature, now=1000)


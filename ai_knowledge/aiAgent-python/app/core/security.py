from __future__ import annotations

import hashlib
import hmac
import time
from collections import OrderedDict
from threading import Lock

from fastapi import Header, HTTPException, Request


class InMemoryNonceStore:
    def __init__(self, max_entries: int = 10_000) -> None:
        self._max_entries = max_entries
        self._values: OrderedDict[str, float] = OrderedDict()
        self._lock = Lock()

    def use_once(self, nonce: str, expires_at: float, now: float) -> bool:
        with self._lock:
            expired = [key for key, expiry in self._values.items() if expiry < now]
            for key in expired:
                self._values.pop(key, None)
            if nonce in self._values:
                return False
            self._values[nonce] = expires_at
            while len(self._values) > self._max_entries:
                self._values.popitem(last=False)
            return True


class SignatureVerifier:
    def __init__(
        self,
        secret: str,
        nonce_store: InMemoryNonceStore,
        max_clock_skew_seconds: int = 300,
    ) -> None:
        self._secret = secret.encode("utf-8")
        self._nonce_store = nonce_store
        self._max_clock_skew = max_clock_skew_seconds

    def verify(
        self,
        method: str,
        path: str,
        timestamp: str,
        nonce: str,
        body: bytes,
        signature: str,
        *,
        now: float | None = None,
    ) -> None:
        current = time.time() if now is None else now
        try:
            signed_at = int(timestamp)
        except (TypeError, ValueError) as exc:
            raise ValueError("invalid timestamp") from exc
        if abs(current - signed_at) > self._max_clock_skew:
            raise ValueError("stale timestamp")
        body_hash = hashlib.sha256(body).hexdigest()
        canonical = "\n".join((method.upper(), path, timestamp, nonce, body_hash))
        expected = hmac.new(self._secret, canonical.encode("utf-8"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature):
            raise ValueError("invalid signature")
        if not nonce or not self._nonce_store.use_once(
            nonce, signed_at + self._max_clock_skew, current
        ):
            raise ValueError("replayed nonce")


def sign_request(
    secret: str, method: str, path: str, timestamp: str, nonce: str, body: bytes
) -> str:
    body_hash = hashlib.sha256(body).hexdigest()
    canonical = "\n".join((method.upper(), path, timestamp, nonce, body_hash))
    return hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()


def build_auth_dependency(verifier: SignatureVerifier):
    async def verify_request(
        request: Request,
        x_timestamp: str | None = Header(default=None),
        x_nonce: str | None = Header(default=None),
        x_signature: str | None = Header(default=None),
    ) -> None:
        if not x_timestamp or not x_nonce or not x_signature:
            raise HTTPException(status_code=401, detail="missing service signature")
        body = await request.body()
        try:
            verifier.verify(
                request.method,
                request.url.path,
                x_timestamp,
                x_nonce,
                body,
                x_signature,
            )
        except ValueError as exc:
            raise HTTPException(status_code=401, detail=str(exc)) from exc

    return verify_request


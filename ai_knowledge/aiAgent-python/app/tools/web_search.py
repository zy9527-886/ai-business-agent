from __future__ import annotations

import ipaddress
import re
from datetime import datetime, timezone
from urllib.parse import urlparse

import httpx

from app.models.domain import Evidence


class UnsafeSearchQuery(ValueError):
    pass


_URL_PATTERN = re.compile(r"https?://|www\.", re.IGNORECASE)
_SECRET_PATTERN = re.compile(
    r"api[ _-]?key|access[ _-]?token|password|secret|private[ _-]?key|"
    r"密码|密钥|令牌|身份证|银行卡",
    re.IGNORECASE,
)


class TavilyWebSearchProvider:
    def __init__(
        self,
        api_key: str,
        *,
        timeout_seconds: float = 15.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("Tavily API key is required")
        self._api_key = api_key
        self._client = client or httpx.AsyncClient(timeout=timeout_seconds)
        self._owns_client = client is None

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def search(self, query: str, *, max_results: int = 5) -> list[Evidence]:
        self._validate_query(query)
        response = await self._client.post(
            "https://api.tavily.com/search",
            headers={"Authorization": f"Bearer {self._api_key}"},
            json={
                "query": query,
                "search_depth": "basic",
                "max_results": min(max(max_results, 1), 10),
                "include_answer": False,
                "include_raw_content": False,
            },
        )
        response.raise_for_status()
        payload = response.json()
        observed_at = datetime.now(timezone.utc)
        evidence: list[Evidence] = []
        seen: set[str] = set()
        for index, item in enumerate(payload.get("results", [])):
            url = str(item.get("url", "")).strip()
            if not self._safe_public_url(url) or url in seen:
                continue
            seen.add(url)
            evidence.append(
                Evidence(
                    source_type="web",
                    source_id=f"web:{index}:{url}",
                    title=str(item.get("title") or "网页来源")[:300],
                    content=str(item.get("content") or "")[:4000],
                    url=url,
                    score=self._normalize_score(item.get("score")),
                    observed_at=observed_at,
                    metadata={"publishedDate": item.get("published_date")},
                )
            )
        return evidence

    @staticmethod
    def _validate_query(query: str) -> None:
        normalized = query.strip()
        if not normalized or len(normalized) > 1000:
            raise UnsafeSearchQuery("invalid web search query")
        if _URL_PATTERN.search(normalized) or _SECRET_PATTERN.search(normalized):
            raise UnsafeSearchQuery("web search query is not safe to externalize")

    @staticmethod
    def _safe_public_url(value: str) -> bool:
        try:
            parsed = urlparse(value)
            if parsed.scheme not in {"http", "https"} or not parsed.hostname:
                return False
            hostname = parsed.hostname.lower().rstrip(".")
            if hostname == "localhost" or hostname.endswith(".localhost"):
                return False
            if hostname in {"metadata.google.internal", "metadata.azure.internal"}:
                return False
            try:
                address = ipaddress.ip_address(hostname)
            except ValueError:
                return True
            return not (
                address.is_private
                or address.is_loopback
                or address.is_link_local
                or address.is_reserved
                or address.is_multicast
                or address.is_unspecified
            )
        except ValueError:
            return False

    @staticmethod
    def _normalize_score(value: object) -> float | None:
        if value is None:
            return None
        try:
            return min(max(float(value), 0.0), 1.0)
        except (TypeError, ValueError):
            return None

from __future__ import annotations

from typing import Any

from psycopg.types.json import Jsonb


_SENSITIVE_KEYS = {
    "token", "password", "secret", "sharedsecret", "apikey", "api_key",
    "authorization", "cookie", "jwt",
}


def redact_sensitive(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: "[REDACTED]" if key.lower().replace("-", "") in _SENSITIVE_KEYS
            else redact_sensitive(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact_sensitive(item) for item in value]
    return value


class ConversationRepository:
    def __init__(self, pool: object) -> None:
        self._pool = pool

    async def ensure(self, conversation_id: str, user_id: int, title: str | None = None) -> None:
        async with self._pool.connection() as connection:
            await connection.execute(
                """INSERT INTO agent_runtime.conversations(id, user_id, title)
                   VALUES (%s, %s, %s) ON CONFLICT (id) DO NOTHING""",
                (conversation_id, user_id, title),
            )
            cursor = await connection.execute(
                "SELECT user_id FROM agent_runtime.conversations WHERE id = %s",
                (conversation_id,),
            )
            row = await cursor.fetchone()
            if row is None or row["user_id"] != user_id:
                raise PermissionError("conversation belongs to another user")


class RunRepository:
    _terminal = {"completed", "failed", "cancelled", "handoff"}

    def __init__(self, pool: object) -> None:
        self._pool = pool

    async def start(
        self,
        request_id: str,
        conversation_id: str,
        user_id: int,
        request_payload: dict[str, Any],
    ) -> None:
        async with self._pool.connection() as connection:
            await connection.execute(
                """INSERT INTO agent_runtime.runs
                   (request_id, conversation_id, user_id, status, request_payload)
                   VALUES (%s, %s, %s, 'running', %s)""",
                (
                    request_id,
                    conversation_id,
                    user_id,
                    Jsonb(redact_sensitive(request_payload)),
                ),
            )

    async def finish(
        self,
        request_id: str,
        status: str,
        route: dict[str, Any] | None,
        evidence: list[dict[str, Any]],
        errors: list[dict[str, Any]],
    ) -> None:
        if status not in self._terminal:
            raise ValueError("invalid terminal run status")
        async with self._pool.connection() as connection:
            await connection.execute(
                """UPDATE agent_runtime.runs
                   SET status = %s, route = %s, evidence = %s, errors = %s,
                       finished_at = now()
                   WHERE request_id = %s AND status = 'running'""",
                (
                    status,
                    Jsonb(redact_sensitive(route)) if route is not None else None,
                    Jsonb(redact_sensitive(evidence)),
                    Jsonb(redact_sensitive(errors)),
                    request_id,
                ),
            )


class InvalidHandoffTransition(ValueError):
    pass


class HandoffRepository:
    _transitions = {
        "pending": {"in_progress", "cancelled"},
        "in_progress": {"resolved", "cancelled"},
        "resolved": set(),
        "cancelled": set(),
    }

    def __init__(self, pool: object) -> None:
        self._pool = pool

    async def create(
        self, request_id: str, conversation_id: str, user_id: int, reason: str
    ) -> int:
        async with self._pool.connection() as connection:
            cursor = await connection.execute(
                """INSERT INTO agent_runtime.handoffs
                   (request_id, conversation_id, user_id, reason)
                   VALUES (%s, %s, %s, %s) RETURNING id""",
                (request_id, conversation_id, user_id, reason[:1000]),
            )
            row = await cursor.fetchone()
            return int(row["id"])

    async def list_for(self, *, user_id: int, is_admin: bool) -> list[dict[str, Any]]:
        async with self._pool.connection() as connection:
            if is_admin:
                cursor = await connection.execute(
                    "SELECT * FROM agent_runtime.handoffs ORDER BY created_at DESC LIMIT 200"
                )
            else:
                cursor = await connection.execute(
                    """SELECT * FROM agent_runtime.handoffs
                       WHERE user_id = %s ORDER BY created_at DESC LIMIT 200""",
                    (user_id,),
                )
            return list(await cursor.fetchall())

    async def update_status(
        self,
        handoff_id: int,
        status: str,
        *,
        actor_user_id: int,
        is_admin: bool,
    ) -> None:
        if not is_admin:
            raise PermissionError("administrator role is required")
        async with self._pool.connection() as connection:
            cursor = await connection.execute(
                "SELECT status FROM agent_runtime.handoffs WHERE id = %s FOR UPDATE",
                (handoff_id,),
            )
            row = await cursor.fetchone()
            if row is None:
                raise LookupError("handoff not found")
            current = row["status"]
            if status not in self._transitions.get(current, set()):
                raise InvalidHandoffTransition(f"invalid transition: {current} -> {status}")
            await connection.execute(
                """UPDATE agent_runtime.handoffs SET status = %s, updated_at = now()
                   WHERE id = %s""",
                (status, handoff_id),
            )
            await connection.execute(
                """INSERT INTO agent_runtime.handoff_audit
                   (handoff_id, actor_user_id, from_status, to_status)
                   VALUES (%s, %s, %s, %s)""",
                (handoff_id, actor_user_id, current, status),
            )

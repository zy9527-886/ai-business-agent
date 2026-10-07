from __future__ import annotations

import pytest

from app.persistence.repositories import (
    ConversationRepository,
    HandoffRepository,
    InvalidHandoffTransition,
    RunRepository,
    redact_sensitive,
)


class Result:
    def __init__(self, row=None, rows=None):
        self.row = row
        self.rows = rows or []

    async def fetchone(self):
        return self.row

    async def fetchall(self):
        return self.rows


class Connection:
    def __init__(self, results):
        self.results = list(results)
        self.calls = []

    async def execute(self, sql, params=()):
        self.calls.append((" ".join(sql.split()), params))
        return self.results.pop(0) if self.results else Result()


class ConnectionContext:
    def __init__(self, connection):
        self.connection = connection

    async def __aenter__(self):
        return self.connection

    async def __aexit__(self, *args):
        return False


class Pool:
    def __init__(self, connection):
        self.value = connection

    def connection(self):
        return ConnectionContext(self.value)


def test_redaction_removes_secrets_recursively() -> None:
    assert redact_sensitive({
        "question": "ok", "token": "jwt", "nested": {"apiKey": "key", "value": 3}
    }) == {"question": "ok", "token": "[REDACTED]", "nested": {
        "apiKey": "[REDACTED]", "value": 3,
    }}


@pytest.mark.asyncio
async def test_conversation_rejects_cross_user_ownership() -> None:
    connection = Connection([Result(), Result(row={"user_id": 7})])
    repository = ConversationRepository(Pool(connection))
    with pytest.raises(PermissionError):
        await repository.ensure("conversation-1", user_id=8)


@pytest.mark.asyncio
async def test_run_payload_is_redacted_and_lifecycle_is_explicit() -> None:
    connection = Connection([Result(), Result()])
    repository = RunRepository(Pool(connection))
    await repository.start(
        "request-1", "conversation-1", 7, {"question": "hello", "password": "secret"}
    )
    await repository.finish("request-1", "completed", {"intent": "knowledge"}, [], [])

    assert connection.calls[0][1][3].obj["password"] == "[REDACTED]"
    assert connection.calls[1][1][0] == "completed"


@pytest.mark.asyncio
async def test_handoff_requires_admin_and_valid_state_transition() -> None:
    repository = HandoffRepository(Pool(Connection([])))
    with pytest.raises(PermissionError):
        await repository.update_status(1, "in_progress", actor_user_id=2, is_admin=False)

    connection = Connection([Result(row={"status": "resolved"})])
    repository = HandoffRepository(Pool(connection))
    with pytest.raises(InvalidHandoffTransition):
        await repository.update_status(1, "in_progress", actor_user_id=1, is_admin=True)

from __future__ import annotations

import os
import uuid

import pytest

from app.persistence.database import Database
from app.persistence.repositories import ConversationRepository, HandoffRepository, RunRepository


pytestmark = pytest.mark.skipif(
    not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL is not configured"
)


@pytest.mark.asyncio
async def test_postgres_run_and_handoff_lifecycle() -> None:
    database = Database(os.environ["TEST_DATABASE_URL"])
    await database.open()
    try:
        await database.bootstrap()
        suffix = uuid.uuid4().hex
        conversation_id = f"c-{suffix}"
        request_id = f"r-{suffix}"
        conversations = ConversationRepository(database.pool)
        runs = RunRepository(database.pool)
        handoffs = HandoffRepository(database.pool)

        await conversations.ensure(conversation_id, 77)
        await runs.start(request_id, conversation_id, 77, {"question": "test"})
        await runs.finish(request_id, "completed", {"intent": "knowledge"}, [], [])
        handoff_id = await handoffs.create(request_id, conversation_id, 77, "review")
        await handoffs.update_status(
            handoff_id, "in_progress", actor_user_id=1, is_admin=True
        )
        rows = await handoffs.list_for(user_id=77, is_admin=False)
        assert any(row["id"] == handoff_id for row in rows)
    finally:
        await database.close()

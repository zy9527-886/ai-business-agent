from __future__ import annotations

from contextlib import asynccontextmanager
from collections.abc import AsyncIterator

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver


def checkpoint_config(conversation_id: str, user_id: int) -> dict[str, object]:
    return {
        "configurable": {
            "thread_id": f"user:{user_id}:conversation:{conversation_id}"
        }
    }


@asynccontextmanager
async def postgres_checkpointer(database_url: str) -> AsyncIterator[AsyncPostgresSaver]:
    async with AsyncPostgresSaver.from_conn_string(database_url) as saver:
        await saver.setup()
        yield saver

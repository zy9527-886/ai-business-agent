from __future__ import annotations

from pathlib import Path

from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool


class Database:
    def __init__(self, database_url: str) -> None:
        self.pool = AsyncConnectionPool(
            conninfo=database_url,
            min_size=1,
            max_size=10,
            open=False,
            kwargs={"autocommit": True, "row_factory": dict_row},
        )

    async def open(self) -> None:
        await self.pool.open(wait=True)

    async def close(self) -> None:
        await self.pool.close()

    async def bootstrap(self) -> None:
        schema = Path(__file__).with_name("schema.sql").read_text(encoding="utf-8")
        async with self.pool.connection() as connection:
            await connection.execute(schema, prepare=False)

from __future__ import annotations

import re


class GuardAgent:
    _blocked = re.compile(
        r"system prompt|系统提示词|developer message|开发者消息|api[ _-]?key|"
        r"shared[ _-]?secret|共享密钥|数据库密码|private[ _-]?key",
        re.IGNORECASE,
    )

    def check(self, question: str) -> tuple[bool, str | None]:
        if self._blocked.search(question):
            return False, "请求包含系统机密或提示词提取意图"
        return True, None

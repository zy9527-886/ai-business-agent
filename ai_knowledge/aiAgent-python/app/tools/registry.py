from __future__ import annotations

from dataclasses import dataclass

from app.models.api import UserContext


@dataclass(frozen=True)
class Capability:
    name: str
    enabled: bool
    permission: str | None = None


class CapabilityRegistry:
    def __init__(self, *, web_enabled: bool) -> None:
        self._capabilities = (
            Capability("knowledge", True),
            Capability("business", True, "agent:business:demo"),
            Capability("web", web_enabled, "agent:web:search"),
        )

    def allowed_for(self, user: UserContext) -> list[str]:
        is_admin = user.role.lower() == "admin"
        permissions = set(user.permissions)
        return [
            item.name
            for item in self._capabilities
            if item.enabled
            and (is_admin or item.permission is None or item.permission in permissions)
        ]

    def capabilities(self) -> list[dict[str, object]]:
        return [
            {"name": item.name, "enabled": item.enabled, "permission": item.permission}
            for item in self._capabilities
        ]

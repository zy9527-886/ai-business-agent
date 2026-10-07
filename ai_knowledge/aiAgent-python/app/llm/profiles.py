from __future__ import annotations

import os
from pathlib import Path
from typing import Literal, Mapping

import yaml
from pydantic import BaseModel, Field, SecretStr


class ModelProfile(BaseModel):
    kind: Literal["chat", "embedding"]
    model: str
    base_url: str | None = None
    api_key_env: str | None = None
    api_key: SecretStr | None = Field(default=None, exclude=True)
    enabled: bool = True
    dimension: int | None = Field(default=None, gt=0)
    temperature: float = Field(default=0.1, ge=0, le=2)


class ModelProfileRegistry:
    def __init__(self, profiles: dict[str, ModelProfile]) -> None:
        self._profiles = profiles

    @classmethod
    def load(
        cls, path: str | Path, environment: Mapping[str, str] | None = None
    ) -> "ModelProfileRegistry":
        values = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
        env = os.environ if environment is None else environment
        profiles: dict[str, ModelProfile] = {}
        for name, raw in (values.get("profiles") or {}).items():
            data = dict(raw)
            secret_name = data.get("api_key_env")
            data["api_key"] = env.get(secret_name) if secret_name else None
            profiles[name] = ModelProfile.model_validate(data)
        return cls(profiles)

    def require(self, name: str, kind: Literal["chat", "embedding"]) -> ModelProfile:
        profile = self._profiles.get(name)
        if profile is None:
            raise ValueError(f"unknown model profile: {name}")
        if not profile.enabled:
            raise ValueError(f"model profile is disabled: {name}")
        if profile.kind != kind:
            raise ValueError(f"model profile {name} is not {kind}")
        if profile.api_key is None or not profile.api_key.get_secret_value():
            raise ValueError(f"model profile secret is missing: {name}")
        if kind == "embedding" and profile.dimension is None:
            raise ValueError(f"embedding profile dimension is missing: {name}")
        return profile

    def capabilities(self) -> list[dict[str, object]]:
        return [
            {
                "name": name,
                "kind": profile.kind,
                "model": profile.model,
                "dimension": profile.dimension,
                "enabled": profile.enabled and profile.api_key is not None,
            }
            for name, profile in self._profiles.items()
        ]


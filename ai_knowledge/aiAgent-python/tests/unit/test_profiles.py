import pytest

from app.llm.profiles import ModelProfileRegistry


from pathlib import Path


PROFILE_FIXTURE = Path(__file__).parents[1] / "fixtures" / "profiles.yaml"


def test_loads_profiles_and_resolves_secret_without_serializing_it() -> None:
    registry = ModelProfileRegistry.load(
        PROFILE_FIXTURE, {"CHAT_SECRET": "chat-key", "EMBED_SECRET": "embed-key"}
    )

    profile = registry.require("embed-main", "embedding")
    assert profile.api_key.get_secret_value() == "embed-key"
    assert profile.dimension == 3
    assert "embed-key" not in str(profile.model_dump())


def test_rejects_unknown_disabled_or_missing_secret_profiles() -> None:
    registry = ModelProfileRegistry.load(PROFILE_FIXTURE, {"CHAT_SECRET": "chat-key"})

    with pytest.raises(ValueError, match="unknown model profile"):
        registry.require("missing", "chat")
    with pytest.raises(ValueError, match="disabled"):
        registry.require("disabled", "chat")
    with pytest.raises(ValueError, match="secret"):
        registry.require("embed-main", "embedding")



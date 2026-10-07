from app.models.api import UserContext
from app.tools.registry import CapabilityRegistry


def test_registry_filters_by_configuration_and_permissions() -> None:
    registry = CapabilityRegistry(web_enabled=False)
    ordinary = UserContext(user_id=1, role="user", permissions=[])
    business_user = UserContext(
        user_id=2, role="user", permissions=["agent:business:demo", "agent:web:search"]
    )

    assert registry.allowed_for(ordinary) == ["knowledge"]
    assert registry.allowed_for(business_user) == ["knowledge", "business"]


def test_admin_gets_all_configured_capabilities_in_stable_order() -> None:
    registry = CapabilityRegistry(web_enabled=True)
    admin = UserContext(user_id=1, role="admin", permissions=[])

    assert registry.allowed_for(admin) == ["knowledge", "business", "web"]

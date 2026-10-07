from fastapi.testclient import TestClient

from app.main import create_app


def test_liveness_and_readiness() -> None:
    client = TestClient(create_app(readiness_check=lambda: True))

    assert client.get("/health/live").json() == {"status": "ok"}
    assert client.get("/health/ready").json() == {"status": "ready"}


def test_readiness_reports_unavailable_dependency() -> None:
    client = TestClient(create_app(readiness_check=lambda: False))

    response = client.get("/health/ready")
    assert response.status_code == 503
    assert response.json()["detail"] == "AI runtime is not ready"

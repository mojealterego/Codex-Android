
from fastapi.testclient import TestClient

from app.main import create_app


class Service:
    pass


class Events:
    def stream(self, session_id):
        return iter(())


def test_diagnostics_requires_bearer_and_reports_runtime_configuration():
    app = create_app(
        service=Service(),
        event_source=Events(),
        access_token="secret",
        runtime_diagnostics={
            "storage_backend": "postgres",
            "persistent_storage": True,
            "agents_api": "configured",
            "github_private_access": False,
        },
    )
    client = TestClient(app)

    unauthorized = client.get("/v1/system/diagnostics")
    assert unauthorized.status_code == 401

    response = client.get(
        "/v1/system/diagnostics",
        headers={"Authorization": "Bearer secret"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "storage_backend": "postgres",
        "persistent_storage": True,
        "agents_api": "configured",
        "github_private_access": False,
    }

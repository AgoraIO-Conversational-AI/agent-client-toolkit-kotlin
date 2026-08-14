from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from fastapi.testclient import TestClient

import src.server as server_module
from src.agent import AgentOperationError, ClientConfig
from src.server import create_app


@dataclass
class FakeService:
    start_error: Exception | None = None

    def __post_init__(self) -> None:
        self.config_calls: list[tuple[str | None, str | None]] = []
        self.start_calls: list[dict[str, Any]] = []
        self.stop_calls: list[str] = []
        self.stop_all_calls = 0

    def create_client_config(
        self, channel: str | None, uid: str | None
    ) -> ClientConfig:
        self.config_calls.append((channel, uid))
        return ClientConfig(
            app_id="app-id",
            token="007-token",
            uid="1001",
            agent_uid="10000001",
            channel_name=channel or "generated-channel",
        )

    async def start_agent(self, **kwargs: Any) -> str:
        if self.start_error:
            raise self.start_error
        self.start_calls.append(kwargs)
        return "agent-id"

    async def stop_agent(self, agent_id: str) -> None:
        self.stop_calls.append(agent_id)

    async def stop_all_agents(self) -> None:
        self.stop_all_calls += 1


def test_module_entry_runs_current_app(monkeypatch: Any) -> None:
    captured: dict[str, Any] = {}

    def fake_run(app: Any, **kwargs: Any) -> None:
        captured.update({"app": app, **kwargs})

    monkeypatch.setattr("uvicorn.run", fake_run)
    monkeypatch.setenv("PORT", "8123")

    server_module.run_server()

    assert captured == {
        "app": server_module.app,
        "host": "0.0.0.0",
        "port": 8123,
        "reload": False,
    }


def test_openapi_reports_backend_version() -> None:
    with TestClient(create_app(service=FakeService())) as client:
        response = client.get("/openapi.json")

    assert response.status_code == 200
    assert response.json()["info"]["version"] == "1.0.0"


def test_get_config_returns_shared_envelope_and_snake_case_data() -> None:
    service = FakeService()
    with TestClient(create_app(service=service)) as client:
        response = client.get(
            "/get_config", params={"channel": "channel-kotlin-1", "uid": "1001"}
        )

    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert response.json() == {
        "code": 0,
        "data": {
            "app_id": "app-id",
            "token": "007-token",
            "uid": "1001",
            "agent_uid": "10000001",
            "channel_name": "channel-kotlin-1",
        },
        "msg": "success",
    }
    assert service.config_calls == [("channel-kotlin-1", "1001")]


def test_start_and_stop_routes_map_camel_case_contract() -> None:
    service = FakeService()
    with TestClient(create_app(service=service)) as client:
        start_response = client.post(
            "/startAgent",
            json={
                "channelName": "channel-kotlin-1",
                "agentUid": 10_000_001,
                "userUid": 1001,
                "startOfSpeechMode": "manual",
                "endOfSpeechMode": "semantic",
            },
        )
        stop_response = client.post("/stopAgent", json={"agentId": "agent-id"})

    assert start_response.status_code == 200
    assert start_response.json()["data"] == {
        "agent_id": "agent-id",
        "channel_name": "channel-kotlin-1",
        "status": "started",
    }
    assert service.start_calls == [
        {
            "channel_name": "channel-kotlin-1",
            "agent_uid": 10_000_001,
            "user_uid": 1001,
            "start_of_speech_mode": "manual",
            "end_of_speech_mode": "semantic",
        }
    ]
    assert stop_response.status_code == 200
    assert stop_response.json() == {"code": 0, "data": None, "msg": "success"}
    assert service.stop_calls == ["agent-id"]


def test_validation_errors_use_non_success_envelope() -> None:
    with TestClient(create_app(service=FakeService())) as client:
        response = client.post(
            "/startAgent",
            json={
                "channelName": "channel-kotlin-1",
                "agentUid": 0,
                "userUid": 1001,
                "startOfSpeechMode": "other",
                "endOfSpeechMode": "semantic",
            },
        )

    body = response.json()
    assert response.status_code == 422
    assert body["code"] != 0
    assert body["data"] is None
    assert body["msg"].startswith("Invalid request:")


def test_sdk_failure_uses_safe_error_envelope() -> None:
    service = FakeService(start_error=AgentOperationError("Failed to start agent"))
    with TestClient(create_app(service=service), raise_server_exceptions=False) as client:
        response = client.post(
            "/startAgent",
            json={
                "channelName": "channel-kotlin-1",
                "agentUid": 10_000_001,
                "userUid": 1001,
                "startOfSpeechMode": "vad",
                "endOfSpeechMode": "semantic",
            },
        )

    assert response.status_code == 502
    assert response.json() == {
        "code": 5020,
        "data": None,
        "msg": "Failed to start agent",
    }


def test_health_and_lifespan_cleanup_use_shared_contract() -> None:
    service = FakeService()
    with TestClient(create_app(service=service)) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "code": 0,
        "data": {"status": "ok"},
        "msg": "success",
    }
    assert service.stop_all_calls == 1

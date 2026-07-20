from __future__ import annotations

import asyncio
import random
from types import SimpleNamespace
from typing import Any

import pytest
from agora_agent.agentkit import Agent

import src.agent as agent_module
from src.agent import (
    AgentOperationError,
    AgentService,
    BackendSettings,
    ConfigurationError,
    DEFAULT_GREETING,
    DEFAULT_PROMPT,
    MAX_RTC_UID,
    SDK_REQUEST_TIMEOUT_SECONDS,
    TOKEN_LIFETIME_SECONDS,
    valid_rtc_uid,
)


def valid_environment() -> dict[str, str]:
    return {
        "AGORA_APP_ID": "0" * 32,
        "AGORA_APP_CERTIFICATE": "1" * 32,
        "AGENT_PROMPT": "Test system prompt",
        "AGENT_GREETING": "Test greeting",
    }


class FakeAgentsClient:
    def __init__(self) -> None:
        self.start_calls: list[dict[str, Any]] = []
        self.stop_calls: list[tuple[str, str]] = []
        self.start_error: Exception | None = None
        self.stop_error: Exception | None = None

    async def start(self, app_id: str, **kwargs: Any) -> Any:
        if self.start_error:
            raise self.start_error
        self.start_calls.append({"app_id": app_id, **kwargs})
        return SimpleNamespace(agent_id="agent-123")

    async def stop(self, app_id: str, agent_id: str, **_kwargs: Any) -> None:
        if self.stop_error:
            raise self.stop_error
        self.stop_calls.append((app_id, agent_id))


class FakeSDKClient:
    area_scope = "global"

    def __init__(self, settings: BackendSettings) -> None:
        self.app_id = settings.agora_app_id
        self.app_certificate = settings.agora_app_certificate
        self.agents = FakeAgentsClient()
        self.stateless_stop_calls: list[str] = []
        self.stateless_stop_error: Exception | None = None

    async def stop_agent(self, agent_id: str) -> None:
        if self.stateless_stop_error:
            raise self.stateless_stop_error
        self.stateless_stop_calls.append(agent_id)


def dump_model(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump(by_alias=True, exclude_none=True)
    if hasattr(value, "dict"):
        return value.dict(by_alias=True, exclude_none=True)
    return value


@pytest.fixture
def settings() -> BackendSettings:
    return BackendSettings.from_environment(valid_environment())


def test_settings_require_agora_credentials() -> None:
    environment = valid_environment()
    environment["AGORA_APP_CERTIFICATE"] = ""

    with pytest.raises(ConfigurationError, match="AGORA_APP_CERTIFICATE"):
        BackendSettings.from_environment(environment)


def test_settings_reject_example_placeholders() -> None:
    with pytest.raises(ConfigurationError, match="AGORA_APP_ID"):
        BackendSettings.from_environment(
            {
                "AGORA_APP_ID": "your_agora_app_id",
                "AGORA_APP_CERTIFICATE": "your_agora_app_certificate",
            }
        )


def test_settings_use_optional_agent_copy_and_defaults() -> None:
    configured = BackendSettings.from_environment(valid_environment())
    assert configured.agent_prompt == "Test system prompt"
    assert configured.agent_greeting == "Test greeting"

    defaults = BackendSettings.from_environment(
        {"AGORA_APP_ID": "0" * 32, "AGORA_APP_CERTIFICATE": "1" * 32}
    )
    assert defaults.agent_prompt == DEFAULT_PROMPT
    assert defaults.agent_greeting == DEFAULT_GREETING


def test_numeric_uid_range_is_shared_with_android() -> None:
    assert valid_rtc_uid(MAX_RTC_UID)
    assert not valid_rtc_uid(MAX_RTC_UID + 1)


def test_sdk_timeout_finishes_before_mobile_timeout(
    settings: BackendSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    captured: dict[str, Any] = {}

    def fake_async_agora(**kwargs: Any) -> FakeSDKClient:
        captured.update(kwargs)
        return FakeSDKClient(settings)

    monkeypatch.setattr(agent_module, "AsyncAgora", fake_async_agora)
    AgentService(settings)

    assert captured["timeout"] == SDK_REQUEST_TIMEOUT_SECONDS
    assert SDK_REQUEST_TIMEOUT_SECONDS < 30


def test_get_config_uses_requested_token_subject(
    settings: BackendSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    captured: dict[str, Any] = {}

    def fake_generate_token(**kwargs: Any) -> str:
        captured.update(kwargs)
        return "007-test-token"

    monkeypatch.setattr(agent_module, "generate_convo_ai_token", fake_generate_token)
    service = AgentService(
        settings,
        client=FakeSDKClient(settings),
        random_source=random.Random(7),
    )

    config = service.create_client_config("channel_kotlin_123456", "1234")

    assert config.app_id == settings.agora_app_id
    assert config.token == "007-test-token"
    assert config.uid == "1234"
    assert config.channel_name == "channel_kotlin_123456"
    assert captured["channel_name"] == config.channel_name
    assert captured["uid"] == 1234
    assert captured["token_expire"] == TOKEN_LIFETIME_SECONDS
    assert "app_certificate" in captured


def test_get_config_replaces_invalid_channel_and_uid(settings: BackendSettings) -> None:
    service = AgentService(
        settings,
        client=FakeSDKClient(settings),
        random_source=random.Random(7),
    )

    config = service.create_client_config("bad channel", "not-a-number")

    assert config.channel_name.startswith("channel_kotlin_")
    assert config.uid.isdigit()
    assert int(config.uid) > 0
    assert config.agent_uid.isdigit()
    assert config.agent_uid != config.uid
    assert config.token.startswith("007")


def test_start_builds_managed_pipeline_and_turn_modes(
    settings: BackendSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    client = FakeSDKClient(settings)
    service = AgentService(settings, client=client)
    session_options: dict[str, Any] = {}
    create_async_session = Agent.create_async_session

    def capture_create_async_session(agent: Agent, **kwargs: Any) -> Any:
        session_options.update(kwargs)
        return create_async_session(agent, **kwargs)

    monkeypatch.setattr(Agent, "create_async_session", capture_create_async_session)
    agent_id = asyncio.run(
        service.start_agent(
            channel_name="channel_kotlin_123456",
            agent_uid=98_765_432,
            user_uid=123_456,
            start_of_speech_mode="manual",
            end_of_speech_mode="semantic",
        )
    )

    assert agent_id == "agent-123"
    assert session_options["name"].startswith("android-")
    assert session_options["agent_uid"] == "98765432"
    assert session_options["remote_uids"] == ["123456"]
    assert session_options["idle_timeout"] == 120
    assert session_options["expires_in"] == TOKEN_LIFETIME_SECONDS
    assert session_options["enable_string_uid"] is False
    properties = dump_model(client.agents.start_calls[0]["properties"])
    assert properties["turn_detection"]["config"] == {
        "start_of_speech": {"mode": "manual"},
        "end_of_speech": {"mode": "semantic"},
    }
    assert properties["advanced_features"] == {
        "enable_rtm": True,
        "enable_sal": False,
    }
    assert properties["parameters"]["data_channel"] == "rtm"
    assert properties["parameters"]["enable_metrics"] is True
    assert properties["parameters"]["enable_error_message"] is True
    assert properties["asr"] == {"vendor": "fengming", "language": "en-US"}
    assert "api_key" not in properties["llm"]
    assert "key" not in properties["tts"]["params"]


def test_start_rejects_invalid_turn_mode_before_network(
    settings: BackendSettings,
) -> None:
    client = FakeSDKClient(settings)
    service = AgentService(settings, client=client)

    with pytest.raises(ValueError, match="turn detection mode"):
        asyncio.run(
            service.start_agent(
                "channel_kotlin_123456", 10_000_001, 1001, "invalid", "vad"
            )
        )
    assert client.agents.start_calls == []


def test_stop_uses_tracked_session_then_stateless_fallback(
    settings: BackendSettings,
) -> None:
    client = FakeSDKClient(settings)
    service = AgentService(settings, client=client)
    asyncio.run(
        service.start_agent(
            "channel_kotlin_123456", 10_000_001, 1001, "vad", "semantic"
        )
    )

    asyncio.run(service.stop_agent("agent-123"))
    asyncio.run(service.stop_agent("unknown-agent"))

    assert client.agents.stop_calls == [(settings.agora_app_id, "agent-123")]
    assert client.stateless_stop_calls == ["unknown-agent"]


def test_stop_all_agents_stops_tracked_sessions_once(
    settings: BackendSettings,
) -> None:
    client = FakeSDKClient(settings)
    service = AgentService(settings, client=client)
    asyncio.run(
        service.start_agent(
            "channel_kotlin_123456", 10_000_001, 1001, "vad", "semantic"
        )
    )

    asyncio.run(service.stop_all_agents())
    asyncio.run(service.stop_all_agents())

    assert client.agents.stop_calls == [(settings.agora_app_id, "agent-123")]
    assert client.stateless_stop_calls == []


def test_stop_treats_not_found_as_idempotent(settings: BackendSettings) -> None:
    class NotFoundError(Exception):
        status_code = 404

    client = FakeSDKClient(settings)
    client.stateless_stop_error = NotFoundError()
    service = AgentService(settings, client=client)

    asyncio.run(service.stop_agent("expired-agent"))


def test_start_wraps_sdk_error_with_safe_message(
    settings: BackendSettings,
) -> None:
    client = FakeSDKClient(settings)
    client.agents.start_error = RuntimeError("upstream response with private details")
    service = AgentService(settings, client=client)

    with pytest.raises(AgentOperationError, match="Failed to start agent") as error:
        asyncio.run(
            service.start_agent(
                "channel_kotlin_123456", 10_000_001, 1001, "vad", "semantic"
            )
        )
    assert "private details" not in str(error.value)

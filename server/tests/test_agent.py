from __future__ import annotations

import asyncio
import random
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from agora_agent import Area
from agora_agent.agentkit import Agent
from agora_agent.core.api_error import ApiError
from dotenv import dotenv_values

import src.agent as agent_module
from src.agent import (
    AgentOperationError,
    AgentService,
    BackendSettings,
    ConfigurationError,
    MAX_RTC_UID,
    SDK_REQUEST_TIMEOUT_SECONDS,
    TOKEN_LIFETIME_SECONDS,
    valid_rtc_uid,
)


def valid_environment() -> dict[str, str]:
    environment = {
        name: value or ""
        for name, value in dotenv_values(
            Path(__file__).resolve().parents[1] / ".env.example"
        ).items()
    }
    environment.update(
        AGORA_APP_ID="0" * 32,
        AGORA_APP_CERTIFICATE="1" * 32,
        AGENT_PROMPT="Test system prompt",
        AGENT_GREETING="Test greeting",
        AGENT_FAILURE_MESSAGE="Test failure message",
    )
    return environment


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


@pytest.mark.parametrize(
    "name", ["CARTESIA_API_KEY", "CARTESIA_VOICE_ID", "CARTESIA_MODEL_ID"]
)
@pytest.mark.parametrize("value", [None, "", " ", "your_cartesia_value"])
def test_settings_require_selected_cartesia_configuration(
    name: str, value: str | None
) -> None:
    environment = valid_environment()
    environment.update(AGENT_TTS_VENDOR="cartesia", CARTESIA_API_KEY="test-cartesia-api-key")
    if value is None:
        environment.pop(name, None)
    else:
        environment[name] = value

    with pytest.raises(ConfigurationError, match=name):
        BackendSettings.from_environment(environment)


@pytest.mark.parametrize("vendor", [None, "", " ", "unsupported"])
def test_settings_reject_missing_or_unknown_tts_vendor(vendor: str | None) -> None:
    environment = valid_environment()
    if vendor is None:
        environment.pop("AGENT_TTS_VENDOR")
    else:
        environment["AGENT_TTS_VENDOR"] = vendor

    with pytest.raises(ConfigurationError, match="AGENT_TTS_VENDOR"):
        BackendSettings.from_environment(environment)


@pytest.mark.parametrize(
    ("vendor", "api_key"),
    [
        (None, None),
        ("minimax", ""),
        (" minimax ", "your_cartesia_api_key"),
        ("MINIMAX", "unused-cartesia-key"),
    ],
)
def test_default_minimax_starts_without_cartesia_credentials(
    vendor: str | None, api_key: str | None
) -> None:
    environment = valid_environment()
    environment.pop("CARTESIA_VOICE_ID")
    environment.pop("CARTESIA_MODEL_ID")
    if vendor is not None:
        environment["AGENT_TTS_VENDOR"] = vendor
    if api_key is not None:
        environment["CARTESIA_API_KEY"] = api_key
    settings = BackendSettings.from_environment(environment)
    client = FakeSDKClient(settings)
    service = AgentService(settings, client=client)

    assert asyncio.run(
        service.start_agent("channel-test", 98765432, 123456, "vad", "vad")
    ) == "agent-123"

    start_call = client.agents.start_calls[0]
    properties = dump_model(start_call["properties"])
    assert start_call["preset"] == "openai_gpt_4o_mini,minimax_speech_2_6_turbo"
    assert properties["tts"] == {
        "vendor": "minimax",
        "params": {"voice_setting": {"voice_id": "English_captivating_female1"}},
    }


def test_settings_reject_example_placeholders() -> None:
    with pytest.raises(ConfigurationError, match="AGORA_APP_ID"):
        BackendSettings.from_environment(
            {
                "AGORA_APP_ID": "your_agora_app_id",
                "AGORA_APP_CERTIFICATE": "your_agora_app_certificate",
            }
        )


def test_settings_read_agent_copy_from_environment() -> None:
    environment = valid_environment()
    configured = BackendSettings.from_environment(environment)
    assert configured.agent_prompt == "Test system prompt"
    assert configured.agent_greeting == "Test greeting"

    assert configured.agent_failure_message == "Test failure message"
    assert configured.tts_vendor == environment["AGENT_TTS_VENDOR"]
    assert configured.cartesia_voice_id == environment["CARTESIA_VOICE_ID"]
    assert configured.cartesia_model_id == environment["CARTESIA_MODEL_ID"]

    for name in ("AGENT_PROMPT", "AGENT_GREETING", "AGENT_FAILURE_MESSAGE"):
        environment.pop(name)
    unconfigured = BackendSettings.from_environment(environment)
    assert unconfigured.agent_prompt == ""
    assert unconfigured.agent_greeting == ""
    assert unconfigured.agent_failure_message == ""


@pytest.mark.parametrize(
    ("voice_id", "model_id"),
    [(None, None), (" custom-voice ", " sonic-2 ")],
)
def test_cartesia_byok_environment_overrides(
    voice_id: str | None, model_id: str | None
) -> None:
    environment = valid_environment()
    environment.update(
        AGENT_TTS_VENDOR=" Cartesia ",
        CARTESIA_API_KEY="test-cartesia-api-key",
    )
    if voice_id is not None:
        environment["CARTESIA_VOICE_ID"] = voice_id
    if model_id is not None:
        environment["CARTESIA_MODEL_ID"] = model_id
    settings = BackendSettings.from_environment(environment)
    service = AgentService(settings, client=FakeSDKClient(settings))

    assert service.build_agent("vad", "semantic").config["tts"] == {
        "vendor": "cartesia",
        "credential_mode": "byok",
        "params": {
            "api_key": "test-cartesia-api-key",
            "model_id": environment["CARTESIA_MODEL_ID"].strip(),
            "voice": {"mode": "id", "id": environment["CARTESIA_VOICE_ID"].strip()},
        },
    }


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

    assert captured["area"] is Area.US
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


@pytest.mark.parametrize(
    "cartesia_api_key", ["test-cartesia-api-key", " second-cartesia-api-key "]
)
def test_start_builds_byok_pipeline_and_turn_modes(
    monkeypatch: pytest.MonkeyPatch,
    cartesia_api_key: str,
) -> None:
    environment = valid_environment()
    environment.update(AGENT_TTS_VENDOR="cartesia", CARTESIA_API_KEY=cartesia_api_key)
    settings = BackendSettings.from_environment(environment)
    assert settings.cartesia_api_key not in repr(settings)
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
    assert properties["asr"] == {"vendor": "ares", "language": "en-US"}
    assert client.agents.start_calls[0]["preset"] == "openai_gpt_4o_mini"
    assert "api_key" not in properties["llm"]
    assert properties["llm"]["system_messages"] == [
        {"role": "system", "content": settings.agent_prompt}
    ]
    assert properties["llm"]["greeting_message"] == settings.agent_greeting
    assert properties["llm"]["failure_message"] == settings.agent_failure_message
    assert properties["tts"] == {
        "vendor": "cartesia",
        "credential_mode": "byok",
        "params": {
            "api_key": cartesia_api_key.strip(),
            "model_id": settings.cartesia_model_id,
            "voice": {"mode": "id", "id": settings.cartesia_voice_id},
        },
    }


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


@pytest.mark.parametrize(
    "sdk_error",
    [
        RuntimeError("upstream response with private details"),
        ApiError(
            status_code=400,
            headers={
                "x-request-id": "test-request-id",
                "authorization": "Bearer private details",
            },
            body={
                "reason": "InvalidFieldValue",
                "detail": "Unsupported field value",
                "api_key": "private details",
                "nested": {"token": "private details"},
            },
        ),
    ],
)
def test_start_wraps_sdk_error_with_safe_message(
    settings: BackendSettings,
    caplog: pytest.LogCaptureFixture,
    sdk_error: Exception,
) -> None:
    client = FakeSDKClient(settings)
    client.agents.start_error = sdk_error
    service = AgentService(settings, client=client)

    with pytest.raises(AgentOperationError, match="Failed to start agent") as error:
        asyncio.run(
            service.start_agent(
                "channel_kotlin_123456", 10_000_001, 1001, "vad", "semantic"
            )
        )
    assert "private details" not in str(error.value)
    assert "private details" not in caplog.text
    assert error.value.__cause__ is sdk_error
    if isinstance(sdk_error, ApiError):
        assert "status_code=400" in caplog.text
        assert "request_id=test-request-id" in caplog.text
        assert "response_body=" in caplog.text
        assert "InvalidFieldValue" in caplog.text
        assert "Unsupported field value" in caplog.text
        assert "[REDACTED]" in caplog.text

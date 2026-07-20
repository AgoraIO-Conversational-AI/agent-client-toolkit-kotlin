"""Agora Conversational AI agent lifecycle service."""

from __future__ import annotations

import asyncio
import logging
import os
import random
import re
import time
import uuid
from dataclasses import dataclass
from typing import Any, Mapping

from agora_agent import Area, AsyncAgora
from agora_agent.agentkit import Agent, generate_convo_ai_token
from agora_agent.agentkit.vendors import FengmingSTT, MiniMaxTTS, OpenAI

logger = logging.getLogger("voiceagent.backend")

MAX_RTC_UID = 2_147_483_647
CHANNEL_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
TURN_DETECTION_MODES = frozenset({"vad", "semantic", "manual"})
TOKEN_LIFETIME_SECONDS = 24 * 60 * 60
SDK_REQUEST_TIMEOUT_SECONDS = 25.0

DEFAULT_PROMPT = (
    "You are a concise, helpful voice assistant. Keep replies short unless the "
    "user asks for more detail."
)
DEFAULT_GREETING = "Hello! How can I help you today?"
DEFAULT_FAILURE_MESSAGE = "Please wait a moment."


class ConfigurationError(ValueError):
    """Raised when required backend configuration is missing or invalid."""


class AgentOperationError(RuntimeError):
    """A safe error that can be returned to the mobile client."""


@dataclass(frozen=True)
class BackendSettings:
    agora_app_id: str
    agora_app_certificate: str
    agent_prompt: str
    agent_greeting: str

    @classmethod
    def from_environment(cls, environment: Mapping[str, str] | None = None) -> "BackendSettings":
        values = environment if environment is not None else os.environ
        required = (
            "AGORA_APP_ID",
            "AGORA_APP_CERTIFICATE",
        )
        invalid = [
            key
            for key in required
            if not values.get(key, "").strip()
            or values.get(key, "").strip().startswith("your_")
        ]
        if invalid:
            raise ConfigurationError(
                "Missing or placeholder required environment variables: "
                + ", ".join(invalid)
            )

        return cls(
            agora_app_id=values["AGORA_APP_ID"].strip(),
            agora_app_certificate=values["AGORA_APP_CERTIFICATE"].strip(),
            agent_prompt=values.get("AGENT_PROMPT", "").strip() or DEFAULT_PROMPT,
            agent_greeting=values.get("AGENT_GREETING", "").strip()
            or DEFAULT_GREETING,
        )


@dataclass(frozen=True)
class ClientConfig:
    app_id: str
    token: str
    uid: str
    agent_uid: str
    channel_name: str


def valid_channel_name(value: str | None) -> bool:
    return value is not None and CHANNEL_PATTERN.fullmatch(value) is not None


def valid_rtc_uid(value: int | str | None) -> bool:
    if value is None or isinstance(value, bool):
        return False
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return False
    return 0 < parsed <= MAX_RTC_UID and str(parsed) == str(value).strip()


def validate_turn_detection_mode(value: str) -> str:
    normalized = value.strip().lower()
    if normalized not in TURN_DETECTION_MODES:
        raise ValueError(
            "turn detection mode must be one of: manual, semantic, vad"
        )
    return normalized


class AgentService:
    def __init__(
        self,
        settings: BackendSettings,
        client: Any | None = None,
        random_source: random.Random | random.SystemRandom | None = None,
    ) -> None:
        self.settings = settings
        self.client = client or AsyncAgora(
            area=Area.US,
            app_id=settings.agora_app_id,
            app_certificate=settings.agora_app_certificate,
            timeout=SDK_REQUEST_TIMEOUT_SECONDS,
        )
        self._random = random_source or random.SystemRandom()
        self._sessions: dict[str, Any] = {}
        self._sessions_lock = asyncio.Lock()

    def create_client_config(
        self,
        requested_channel: str | None = None,
        requested_uid: int | str | None = None,
    ) -> ClientConfig:
        channel_name = (
            requested_channel
            if valid_channel_name(requested_channel)
            else f"channel_kotlin_{self._random.randint(100000, 999999)}"
        )
        uid = (
            int(requested_uid)
            if valid_rtc_uid(requested_uid)
            else self._random.randint(1000, 9_999_999)
        )
        agent_uid = self._random.randint(10_000_000, 99_999_999)
        while agent_uid == uid:
            agent_uid = self._random.randint(10_000_000, 99_999_999)

        token = generate_convo_ai_token(
            app_id=self.settings.agora_app_id,
            app_certificate=self.settings.agora_app_certificate,
            channel_name=channel_name,
            uid=uid,
            token_expire=TOKEN_LIFETIME_SECONDS,
        )
        return ClientConfig(
            app_id=self.settings.agora_app_id,
            token=token,
            uid=str(uid),
            agent_uid=str(agent_uid),
            channel_name=channel_name,
        )

    def build_agent(self, start_of_speech_mode: str, end_of_speech_mode: str) -> Agent:
        sos_mode = validate_turn_detection_mode(start_of_speech_mode)
        eos_mode = validate_turn_detection_mode(end_of_speech_mode)

        stt = FengmingSTT()
        llm = OpenAI(
            model="gpt-4o-mini",
            system_messages=[
                {"role": "system", "content": self.settings.agent_prompt}
            ],
            greeting_message=self.settings.agent_greeting,
            failure_message=DEFAULT_FAILURE_MESSAGE,
            max_history=50,
            params={
                "max_tokens": 1024,
                "temperature": 0.7,
                "top_p": 0.95,
            },
        )
        tts = MiniMaxTTS(
            model="speech_2_6_turbo",
            voice_id="English_captivating_female1",
        )

        return (
            Agent(
                client=self.client,
                turn_detection={
                    "mode": "default",
                    "language": "en-US",
                    "config": {
                        "start_of_speech": {"mode": sos_mode},
                        "end_of_speech": {"mode": eos_mode},
                    },
                },
                advanced_features={
                    "enable_sal": False,
                    "enable_rtm": True,
                },
                parameters={
                    "data_channel": "rtm",
                    "enable_metrics": True,
                    "enable_error_message": True,
                },
            )
            .with_stt(stt)
            .with_llm(llm)
            .with_tts(tts)
        )

    async def start_agent(
        self,
        channel_name: str,
        agent_uid: int,
        user_uid: int,
        start_of_speech_mode: str,
        end_of_speech_mode: str,
    ) -> str:
        if not valid_channel_name(channel_name):
            raise ValueError(
                "channelName must contain 1-64 letters, numbers, underscores, or hyphens"
            )
        if not valid_rtc_uid(agent_uid):
            raise ValueError("agentUid must be a positive 32-bit numeric UID")
        if not valid_rtc_uid(user_uid):
            raise ValueError("userUid must be a positive 32-bit numeric UID")

        agent = self.build_agent(start_of_speech_mode, end_of_speech_mode)
        session = agent.create_async_session(
            name=f"android-{int(time.time() * 1000)}-{uuid.uuid4().hex[:8]}",
            channel=channel_name,
            agent_uid=str(agent_uid),
            remote_uids=[str(user_uid)],
            enable_string_uid=False,
            idle_timeout=120,
            expires_in=TOKEN_LIFETIME_SECONDS,
        )

        try:
            agent_id = await session.start()
        except Exception as exc:
            logger.error(
                "Agent start failed channel=%s agent_uid=%s user_uid=%s error_type=%s",
                channel_name,
                agent_uid,
                user_uid,
                type(exc).__name__,
            )
            raise AgentOperationError("Failed to start agent") from exc

        if not agent_id:
            raise AgentOperationError("Agent start returned an empty agent ID")

        async with self._sessions_lock:
            self._sessions[agent_id] = session
        logger.info("Agent started agent_id=%s channel=%s", agent_id, channel_name)
        return agent_id

    async def stop_agent(self, agent_id: str) -> None:
        normalized_agent_id = agent_id.strip()
        if not normalized_agent_id:
            raise ValueError("agentId must not be empty")

        async with self._sessions_lock:
            session = self._sessions.pop(normalized_agent_id, None)

        await self._stop_agent_session(normalized_agent_id, session)

    async def stop_all_agents(self) -> None:
        async with self._sessions_lock:
            sessions = tuple(self._sessions.items())
            self._sessions.clear()

        if not sessions:
            return

        results = await asyncio.gather(
            *(
                self._stop_agent_session(agent_id, session)
                for agent_id, session in sessions
            ),
            return_exceptions=True,
        )
        failed_count = sum(isinstance(result, BaseException) for result in results)
        if failed_count:
            logger.error(
                "Failed to stop some agents during shutdown failed_count=%s total_count=%s",
                failed_count,
                len(sessions),
            )

    async def _stop_agent_session(
        self, normalized_agent_id: str, session: Any | None
    ) -> None:
        if session is not None:
            try:
                await session.stop()
                logger.info("Tracked agent stopped agent_id=%s", normalized_agent_id)
                return
            except Exception as exc:
                if _is_not_found_error(exc):
                    return
                logger.warning(
                    "Tracked stop failed; using stateless fallback agent_id=%s error_type=%s",
                    normalized_agent_id,
                    type(exc).__name__,
                )

        try:
            await self.client.stop_agent(normalized_agent_id)
        except Exception as exc:
            if _is_not_found_error(exc):
                return
            logger.error(
                "Agent stop failed agent_id=%s error_type=%s",
                normalized_agent_id,
                type(exc).__name__,
            )
            raise AgentOperationError("Failed to stop agent") from exc


def _is_not_found_error(error: Exception) -> bool:
    return getattr(error, "status_code", None) == 404

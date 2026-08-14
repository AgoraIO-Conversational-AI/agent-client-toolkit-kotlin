"""FastAPI facade used by the Android quickstart."""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path
from typing import Any, AsyncIterator, Literal

from dotenv import load_dotenv
from fastapi import FastAPI, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .agent import (
    AgentOperationError,
    AgentService,
    BackendSettings,
    ConfigurationError,
    MAX_RTC_UID,
)

logger = logging.getLogger("voiceagent.backend")
SERVER_DIR = Path(__file__).resolve().parent.parent


class StartAgentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    channelName: str = Field(min_length=1, max_length=64)
    agentUid: int = Field(gt=0, le=MAX_RTC_UID)
    userUid: int = Field(gt=0, le=MAX_RTC_UID)
    startOfSpeechMode: Literal["vad", "semantic", "manual"]
    endOfSpeechMode: Literal["vad", "semantic", "manual"]


class StopAgentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    agentId: str = Field(min_length=1, max_length=128)

    @field_validator("agentId")
    @classmethod
    def validate_agent_id(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("agentId must not be empty")
        return normalized


def envelope(data: Any = None, *, code: int = 0, msg: str = "success") -> dict[str, Any]:
    return {"code": code, "data": data, "msg": msg}


def create_app(
    *,
    settings: BackendSettings | None = None,
    service: AgentService | Any | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        resolved_service = service
        if resolved_service is None:
            resolved_settings = settings
            if resolved_settings is None:
                load_dotenv(SERVER_DIR / ".env.local", override=False)
                resolved_settings = BackendSettings.from_environment()
            resolved_service = AgentService(resolved_settings)
        app.state.agent_service = resolved_service
        try:
            yield
        finally:
            await resolved_service.stop_all_agents()

    app = FastAPI(
        title="Agora Conversational AI Android Backend",
        version="1.0.0",
        description="Local token and Agora Conversational AI agent service",
        lifespan=lifespan,
    )

    @app.exception_handler(RequestValidationError)
    async def request_validation_error(
        _request: Request, error: RequestValidationError
    ) -> JSONResponse:
        first_error = error.errors()[0] if error.errors() else {}
        message = str(first_error.get("msg", "Invalid request"))
        return JSONResponse(
            status_code=422,
            content=envelope(code=4220, msg=f"Invalid request: {message}"),
        )

    @app.exception_handler(ValueError)
    async def value_error(_request: Request, error: ValueError) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content=envelope(code=4000, msg=str(error)),
        )

    @app.exception_handler(AgentOperationError)
    async def agent_operation_error(
        _request: Request, error: AgentOperationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=502,
            content=envelope(code=5020, msg=str(error)),
        )

    @app.exception_handler(Exception)
    async def unhandled_error(_request: Request, error: Exception) -> JSONResponse:
        logger.exception("Unhandled backend error error_type=%s", type(error).__name__)
        return JSONResponse(
            status_code=500,
            content=envelope(code=5000, msg="Internal server error"),
        )

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return envelope({"status": "ok"})

    @app.get("/get_config")
    async def get_config(
        request: Request,
        response: Response,
        channel: str | None = Query(default=None),
        uid: str | None = Query(default=None),
    ) -> dict[str, Any]:
        response.headers["Cache-Control"] = "no-store"
        agent_service = request.app.state.agent_service
        config = agent_service.create_client_config(channel, uid)
        return envelope(asdict(config))

    @app.post("/startAgent")
    async def start_agent(
        request: Request, body: StartAgentRequest
    ) -> dict[str, Any]:
        agent_service = request.app.state.agent_service
        agent_id = await agent_service.start_agent(
            channel_name=body.channelName,
            agent_uid=body.agentUid,
            user_uid=body.userUid,
            start_of_speech_mode=body.startOfSpeechMode,
            end_of_speech_mode=body.endOfSpeechMode,
        )
        return envelope(
            {
                "agent_id": agent_id,
                "channel_name": body.channelName,
                "status": "started",
            }
        )

    @app.post("/stopAgent")
    async def stop_agent(
        request: Request, body: StopAgentRequest
    ) -> dict[str, Any]:
        agent_service = request.app.state.agent_service
        await agent_service.stop_agent(body.agentId)
        return envelope()

    return app


app = create_app()


def run_server() -> None:
    import uvicorn

    try:
        uvicorn.run(
            app,
            host="0.0.0.0",
            port=int(os.getenv("PORT", "8000")),
            reload=False,
        )
    except ConfigurationError as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    run_server()

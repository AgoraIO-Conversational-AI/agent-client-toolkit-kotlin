# Architecture - Conversational AI Quickstart Android Kotlin

## Overview

This repository contains two runtime layers:

- an Android Views/XML voice client using Agora RTC, RTM, and the reusable
  `:conversational-ai` toolkit module
- a local Python FastAPI backend using `agora-agents==2.4.1`

```text
Android app -- config/start/stop --> Python FastAPI -- Agora SDK --> ConvoAI
     |                                                        |
     +---------------- Agora RTC + RTM channels --------------+
```

The Android APK owns the realtime client experience. The backend owns App
Certificate usage, user token generation, managed AI pipeline configuration,
and runtime Agent lifecycle.

## Project Structure

```text
app/
|- src/main/java/io/agora/agent/toolkit/sample/
|  |- api/AgentBackendClient.kt
|  `- ui/AgentChatViewModel.kt
|- src/debug/res/xml/common_security_config.xml
`- src/test/.../AgentBackendClientTest.kt

conversational-ai/
`- src/main/java/io/agora/conversational/api/  # reusable toolkit module

server/
|- src/agent.py        # AsyncAgora, managed pipeline, session lifecycle
|- src/server.py       # FastAPI routes and response envelopes
|- tests/              # mocked SDK/HTTP contract tests
`- .env.example

scripts/start_backend.sh
```

The migration does not change the public API of `:conversational-ai` and does
not copy toolkit source into the sample app.

## Ownership

Android owns:

- microphone permission and UI state
- RTC join/audio publish/audio subscribe
- RTM login and message subscription
- toolkit callbacks for Agent state, transcript, metrics, and errors
- mute, interrupt, text/image chat, and manual SOS/EOS controls
- immediate local cleanup on startup failure or hangup

Python owns:

- Agora App ID and App Certificate
- unified user RTC+RTM token generation
- Agent RTC token and Agora REST authentication through `agora-agents`
- explicit Agora Fengming STT plus managed OpenAI LLM and MiniMax TTS
  configuration
- independent startup SOS/EOS mapping
- Agent session start and stateful/stateless stop

## Connection Flow

```text
Tap Start
  -> request microphone permission
  -> GET /get_config?channel=<channel>&uid=<stable-user-uid>
  -> initialize RTC + RTM + ConversationalAIAPI with returned App ID/UID
  -> loadAudioSettings(AUDIO_SCENARIO_AI_CLIENT)
  -> log in RTM with the returned unified token
  -> join RTC and wait for the RTC join callback
  -> subscribeMessage(returnedChannel)
  -> wait for successful subscription callback
  -> POST /startAgent with channel, user UID, agent UID, SOS, and EOS
  -> save non-empty runtime agentId
  -> uiState = Connected
```

Agent startup is gated by all of:

- current connection attempt
- RTC joined
- RTM logged in
- toolkit message subscription succeeded
- returned user UID and agent UID available
- startup SOS/EOS modes fixed

`/startAgent` acceptance does not fabricate Agent state. Live state,
transcripts, metrics, and Agent-side errors continue to arrive through RTM and
the toolkit callbacks.

## Backend API

Every success response uses:

```json
{"code": 0, "data": {}, "msg": "success"}
```

Every route failure uses a non-2xx HTTP status and:

```json
{"code": 502, "data": null, "msg": "safe message"}
```

`AgentBackendClient` checks both the HTTP status and envelope `code`.

### Get Config

`GET /get_config` accepts optional `channel` and positive numeric `uid`. The
Android app supplies both. The backend returns:

```json
{
  "app_id": "<app-id>",
  "token": "<user-rtc-rtm-token>",
  "uid": "123456",
  "agent_uid": "87654321",
  "channel_name": "channel_kotlin_123456"
}
```

The user token lifetime is 24 hours for the local demo. Android logs in to RTM
with `uid.toString()` and joins RTC with the same numeric UID, so both identities
match the token subject.

### Start Agent

```json
{
  "channelName": "channel_kotlin_123456",
  "agentUid": 87654321,
  "userUid": 123456,
  "startOfSpeechMode": "vad",
  "endOfSpeechMode": "semantic"
}
```

`server/src/agent.py` maps the request into an SDK `Agent` and
`create_async_session()` with:

- unique session name
- numeric string `agent_uid` and `remote_uids`
- `enable_string_uid=false`
- `idle_timeout=120`
- `advanced_features.enable_sal=false`
- `advanced_features.enable_rtm=true`
- `parameters.data_channel=rtm`
- metrics and error events enabled
- session credential lifetime of 24 hours

The pipeline explicitly uses Agora Fengming STT, managed OpenAI `gpt-4o-mini`,
and MiniMax `speech_2_6_turbo` / `English_captivating_female1`.

### Stop Agent

`POST /stopAgent` accepts the runtime `agentId`. The backend first uses the
tracked `AsyncAgentSession`; when the process no longer has that session, it
falls back to `AsyncAgora.stop_agent(agentId)`. The SDK treats an already
stopped Agent as success.

FastAPI lifespan drains all tracked sessions during backend shutdown, so
Ctrl+C does not leave locally started Agents running.

## Stop And Failure Cleanup

Startup failure:

```text
log safe failure
  -> invalidate the current attempt
  -> unsubscribe and destroy the toolkit
  -> leave and destroy RTC
  -> log out and release RTM
  -> clear transient UIDs/Agent ID/state
  -> uiState = Idle
```

Hangup:

```text
capture runtime agentId
  -> invalidate the current attempt
  -> immediately destroy Toolkit/RTC/RTM and reset UI
  -> asynchronously POST /stopAgent when an agentId exists
```

A delayed or failed backend stop is logged but cannot hold the Android app in
`Connected`. Final ViewModel teardown also sends a best-effort stop for a known
Agent ID. If teardown cancels an in-flight `/startAgent`, the backend request is
allowed to finish in a process-level cleanup scope so any late Agent ID can be
stopped immediately; `CancellationException` is still propagated to the caller.

## Transcript And Controls

```text
RTM message
  -> ConversationalAIAPI
  -> AgentChatViewModel
  -> StateFlow
  -> AgentChatActivity
```

Transcripts are upserted by `(turnId, type)`. Completed-turn latency metrics are
attached to the matching Agent transcript. Chat, interrupt, and manual SOS/EOS
publish through `ConversationalAIAPI` using the backend-returned agent UID.

## Local Network Configuration

`./scripts/start_backend.sh` starts FastAPI on `0.0.0.0`, detects the active
development-machine LAN IP, and updates this root `local.properties` entry:

```properties
agent.backend.url=http://<development-machine-lan-ip>:8000
```

`PORT` may override the default, and the startup script writes the matching URL
only after the selected port is available and the backend passes `/health`.

Gradle maps it to `BuildConfig.AGENT_BACKEND_URL`. `localhost` is not valid for
a physical phone because it refers to the phone. No USB tunnel, `adb reverse`,
Gradle property override, or in-app host editor is used.

Changing this host does not require USB. Installing the rebuilt app may use
Android Studio USB or wireless device pairing.

Debug resources allow local cleartext HTTP. Main/release network security keeps
cleartext disabled.

## Security Boundary

The Android build must not contain:

- App Certificate
- provider API keys
- local AccessToken2 signing code
- Agent RTC token
- Agora REST auth token/header
- direct Agora `/join` or `/leave` URL

`server/.env.local`, `server/.venv`, and root `local.properties` are ignored by
Git. The local backend is a quickstart service, not a hosted production design.

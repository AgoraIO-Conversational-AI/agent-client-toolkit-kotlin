# Conversational AI Quickstart Android — AI Assistant Guide

## How to Use This Project

This is a complete, runnable Android demo for real-time voice conversation with an AI agent.

- If you don't have an existing project, use this project directly. Modify it based on user requirements.
- If you already have a project, refer to the key parts of this project (connection flow, UI structure, ConversationalAIAPI integration) and adapt them into the existing codebase.

## Python Backend Contract

The Android app does not call Agora Conversational AI REST endpoints directly.
It calls the local FastAPI service through `AgentBackendClient`; the service
uses `agora-agents==2.4.1` for token generation and agent lifecycle operations.

Current managed pipeline in `server/src/agent.py`:

- STT: Agora Fengming, configured explicitly
- LLM: OpenAI `gpt-4o-mini`
- TTS: MiniMax `speech_2_6_turbo` with `English_captivating_female1`
- no third-party provider keys are required for the managed path

Turn detection remains a startup-time client choice:

- `SOS` maps to `turn_detection.config.start_of_speech.mode`
- `EOS` maps to `turn_detection.config.end_of_speech.mode`
- both Android settings support `vad`, `semantic`, and `manual`
- the backend maps the two settings independently and does not add implicit
  `vad_config` or `semantic_config`

If the backend contract or SDK session shape changes, update these together:

1. `server/src/server.py`
2. `server/src/agent.py`
3. `server/tests/`
4. `AgentBackendClient.kt` and its tests
5. `app/build.gradle.kts`
6. `README.md`, `ARCHITECTURE.md`, and `docs/python-backend-migration.md`
7. this file

## Project Overview

Conversational AI Quickstart — Android real-time voice conversation client.

The Android client obtains session config from the Python backend, joins Agora
RTC/RTM, subscribes the toolkit message channel, then asks the backend to start
the Agent. App credentials and provider configuration remain on the server.

Current quickstart scope is limited to voice session startup, independent startup-time selection for SOS / EOS detection, transcript display with optional latency metrics, state rendering, mute, text / image URL message sending, interrupt, a capability panel for enabled manual trigger buttons, and stop.

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin |
| UI Framework | View + XML Layout + ViewBinding |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | API 36 |
| Build Tool | Gradle (Kotlin DSL) |
| State Management | ViewModel + StateFlow |
| Networking | OkHttp 5.0.0-alpha.14 |
| RTC SDK | Agora RTC SDK (`io.agora.rtc:full-sdk:4.5.1`) |
| RTM SDK | Agora RTM SDK (`io.agora:agora-rtm-lite:2.2.3`) |
| Coroutines | Kotlin Coroutines 1.9.0 |
| ConversationalAIAPI | Standalone `:conversational-ai` Gradle module |
| Backend | Python FastAPI + `agora-agents==2.4.1` |

For runtime structure, see `ARCHITECTURE.md`. For entry files, see `README.md`.

## Core Modules

### AgentChatViewModel

- Manages RTC Engine and RTM Client lifecycle
- Subscribes to RTM messages via ConversationalAIAPI, parses Agent state and transcripts
- Exposes four StateFlows:
  - `uiState: StateFlow<ConversationUiState>` — connection state (Idle/Connecting/Connected) + mute
  - `agentState: StateFlow<AgentState>` — Agent state (IDLE/SILENT/LISTENING/THINKING/SPEAKING)
  - `transcriptList: StateFlow<List<TranscriptItem>>` — transcript list (deduplicated/updated by turnId + type) plus optional per-turn latency metrics
  - `debugLogList: StateFlow<List<String>>` — debug logs (max 20 entries)
- Auto flow: get backend config → login RTM → join RTC → subscribe RTM messages → backend start Agent
- Startup failure flow: backend/transport/subscription/startup failures release partial startup side effects and return UI state to `Idle`
- Message flow: connected UI can send text messages, image URL messages, and interrupt requests through ConversationalAIAPI
- Manual flow: the top-right Settings sheet chooses SOS / EOS detection modes before startup; after connection, the capability panel exposes buttons only for modes set to `manual`
- `userId` is a stable locally generated non-zero UID for the app process. The backend honors it when generating the unified user token and returns a separate agent UID.

### AgentBackendClient

- `getConfig(channel, uid)`: obtains App ID, unified user RTC+RTM token,
  resolved user UID, agent UID, and channel
- `startAgent(...)`: sends channel, UIDs, and independent SOS/EOS values to the backend
- `stopAgent(agentId)`: asks the backend to stop the runtime Agent
- validates HTTP status and the `{code,data,msg}` envelope
- preserves safe backend messages in app debug logs
- never sends an App Certificate, provider key, agent token, or Agora REST auth token

### Python Backend

- `server/src/server.py` exposes `/health`, `/get_config`, `/startAgent`, and `/stopAgent`
- `server/src/agent.py` owns the `AsyncAgora` client, managed provider pipeline,
  SDK sessions, and stateful/stateless stop paths
- FastAPI lifespan stops every tracked Agent before the local backend exits
- issues user RTC+RTM tokens with a 24-hour lifetime for this local demo
- starts SDK sessions with `enable_string_uid=false`, `idle_timeout=120`, RTM
  events, metrics, and error messages enabled
- keeps App ID/App Certificate in Git-ignored `server/.env.local`
- does not return or log the App Certificate, provider credentials, agent token,
  or Agora REST authorization header

### ConversationalAIAPI

- Standalone Gradle module `:conversational-ai` (package `io.agora.conversational.api`), depended on by `:app` via `implementation(project(":conversational-ai"))`
- Wraps RTM message subscription/parsing
- The quickstart currently reacts to:
  - `onAgentListeningChanged`
  - `onAgentThinkingChanged`
  - `onAgentSpeakingChanged`
  - `onTranscriptUpdated`
  - `onTurnFinished` (latency metrics)
  - `onAgentError` (logged through ViewModel state/logs)
  - `onMessageError`
  - `onMessageReceiptUpdated`
  - `onUserManualSosEvent`
  - `onUserManualEosEvent`
  - `onAgentManualEosEvent`
  - `onDebugLog`
- The connected UI can call `chat(agentUserId, TextMessage/ImageMessage, completion)` and `interrupt(agentUserId, completion)`
- The public manual turn methods are `manualSOS(agentUserId, completion)` and `manualEOS(agentUserId, completion)`; the toolkit generates `requestId` internally and returns it through the completion callback
- Audio settings: `loadAudioSettings(AUDIO_SCENARIO_AI_CLIENT)` (must be called before joinChannel)

## Startup Review Guardrails

For AI / PR reviews, use the current demo flow and business rules as the source of truth. Do not propose generic startup refactors unless they fix a concrete violation.

- `loadAudioSettings(AUDIO_SCENARIO_AI_CLIENT)` must run before `joinChannel()`.
- RTC/RTM clients are initialized only after `/get_config` returns App ID and user UID.
- RTM login completes before RTC join, matching the iOS quickstart flow.
- backend `/startAgent` is gated by RTC joined, RTM logged in, successful
  `subscribeMessage(channelName)`, and selected startup SOS/EOS modes.
- Connected UI is opened only after `/startAgent` returns a non-empty `agentId`.
  Agent state, transcript, and agent-side errors still come from toolkit callbacks.
- Stop / hangup owns both the stop request and local cleanup path. Local state, `agentId`, RTC, RTM, and message subscription cleanup must not depend on late RTM events.
- Backend stop is best effort. Local cleanup must happen immediately even when
  `/stopAgent` is delayed or fails.
- Startup failure, Stop, and final teardown destroy the Toolkit, RTM client, and
  RTC engine. Every new session creates fresh Agora clients.
- Final ViewModel teardown must best-effort stop a known Agent ID. Backend start
  response ownership must outlive a cancelled `viewModelScope` long enough to
  stop any late Agent ID, while preserving `CancellationException` semantics.
- Keep the demo startup state simple. Do not add extra milestone models, login-state layers, or attempt identifiers beyond the existing flow unless there is a proven business bug.

## Configuration

### Configuration Flow

```text
server/.env.local -> FastAPI -> agora-agents -> Agora
local.properties agent.backend.url -> BuildConfig.AGENT_BACKEND_URL -> AgentBackendClient
```

Backend fields:

| Field | Required | Purpose |
|-------|----------|---------|
| `AGORA_APP_ID` | yes | Agora project App ID |
| `AGORA_APP_CERTIFICATE` | yes | Server-only token and SDK authentication |
| `AGENT_PROMPT` | no | Agent system prompt |
| `AGENT_GREETING` | no | Initial greeting |
| `PORT` | no | Local FastAPI port, default `8000` |

Android has one local value: `agent.backend.url` in the root
`local.properties`. `./scripts/start_backend.sh` detects the development
machine LAN IP and updates this entry after `/health` succeeds while preserving
other properties. A shell `PORT` value overrides the value in `.env.local`.

The Android build has a `10.0.2.2` fallback for buildability, but physical
devices use the generated LAN URL. No `-PagentBackendUrl`, `adb reverse`, or
in-app backend host setting is part of the supported flow.

## API Endpoints

Android calls the local backend only:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | local startup readiness |
| `/get_config?channel=...&uid=...` | GET | user token and RTC/RTM identities |
| `/startAgent` | POST | start SDK Agent session |
| `/stopAgent` | POST | stop runtime Agent session |

All responses use `{ "code": 0, "data": ..., "msg": "success" }`. Errors
use a non-2xx status, non-zero `code`, `data: null`, and a safe `msg`.

`/startAgent` body:

```json
{
  "channelName": "channel_kotlin_123456",
  "agentUid": 10000001,
  "userUid": 1001,
  "startOfSpeechMode": "vad",
  "endOfSpeechMode": "semantic"
}
```

`SOS` and `EOS` are independent settings:

| Setting | Request field | Options |
|---------|---------------|---------|
| `SOS` | `start_of_speech.mode` | `vad`, `semantic`, `manual` |
| `EOS` | `end_of_speech.mode` | `vad`, `semantic`, `manual` |

When either setting is `manual`, the demo shows the corresponding manual
capability button after startup. The request still uses the same
`turn_detection` block; only the selected `mode` under `start_of_speech` /
`end_of_speech` changes.

## Data Flow

```text
User Action → ViewModel → Agora SDK (RTC/RTM)
                  ↓
            StateFlow ← ConversationalAIAPI event callbacks
                  ↓
            Activity observes → UI update
```

## Event Flow

1. User taps Start Agent → check microphone permission
2. `GET /get_config` with requested channel and stable local user UID
3. Initialize RTC/RTM/toolkit with returned App ID and user UID
4. Login RTM with the returned unified user token
5. Join RTC and wait for the real join callback
6. Require successful `subscribeMessage(channelName)`
7. `POST /startAgent` with channel, returned UIDs, and selected SOS/EOS modes
8. ConversationalAIAPI receives Agent events via RTM → update StateFlow → UI responds
9. User can send text/image messages, interrupt, mute, and manual SOS/EOS
10. User taps Stop → immediately destroy local Toolkit/RTC/RTM and reset UI → best-effort backend `/stopAgent`

## How to Change Request Parameters

Change managed pipeline and SDK session properties in `server/src/agent.py`.
Change HTTP route models and envelopes in `server/src/server.py`. Keep Android,
Python tests, and all contract documents synchronized.

## Key Constraints

1. **Server-only credentials**: App Certificate and provider credentials must never be added to Android resources, BuildConfig, logs, or source.
2. **Backend ownership**: Android must not build `/join` payloads or call Agora agent REST endpoints directly.
3. **Local demo**: The bundled FastAPI service is a local quickstart backend, not a production deployment design.
4. **Token lifetime**: The local demo issues 24-hour user tokens and does not implement in-session renewal. Production integrations must add RTC/RTM renewal.
5. **Resource Cleanup**: Session side effects are cleared in `hangup()` and `onCleared()`; both paths best-effort stop known backend sessions, late start responses are stopped outside the cancelled ViewModel scope, and every Toolkit, RTM client, and RTC engine is destroyed before the next session
6. **Permissions**: Requires `RECORD_AUDIO` and `INTERNET` permissions
7. **ConversationalAIAPI module boundary**: Files under `:conversational-ai` (`conversational-ai/src/main/java/io/agora/conversational/api/`) are reusable toolkit components packaged for Maven / AAR release. Keep the public API minimal and update `conversational-ai/README.md` when the API changes. The sample app depends on it via `implementation(project(":conversational-ai"))`.
8. **Audio Settings**: `loadAudioSettings()` must be called before `joinChannel()`; Avatar mode uses `AUDIO_SCENARIO_DEFAULT`

## Internal Maven Release

Rehoboam is the internal Maven / AAR release platform. Do not document Rehoboam, Jenkins download URLs, or internal release requests in public-facing README files.

The public `conversational-ai/README.md` should describe developer-facing API usage only.

Release strategy:

- Do not publish release candidates. Only formal SemVer versions are allowed.
- Validate the formal package through staging / platform validation plus sample or clean-app consumption before publishing.
- If a problem is found after the final version is published, do not overwrite or delete that version; publish a new version such as `2.9.1`.

To prepare the Rehoboam upload zip:

```bash
VERSION=2.9.0 scripts/build_rehoboam_maven_input_zip.sh
```

The script accepts only formal SemVer versions.

The generated zip is:

```text
conversational-ai/build/distributions/agora-agent-client-toolkit-<version>-maven-rehoboam-input.zip
```

The zip contains:

```text
agora-agent-client-toolkit/
├── agora-agent-client-toolkit-<version>.pom
├── agora-agent-client-toolkit-<version>.aar
├── agora-agent-client-toolkit-<version>-sources.jar
└── agora-agent-client-toolkit-<version>-javadoc.jar
```

Rehoboam form values for this single-AAR module:

| Field | Value |
|-------|-------|
| `Release Channel` | `Maven / AAR` |
| `Group ID` | `io.agora.agents` |
| `Artifacts Version` | Same as the explicit `VERSION` passed to `scripts/build_rehoboam_maven_input_zip.sh` |
| `File Link / File URL` | Jenkins-accessible URL for the generated zip |
| `Part Release List / SO_LIST` | Empty for full release |
| `Subspec Publish` | Off |

Rehoboam uses the POM `groupId` as an Android manifest package in its `aar-template` validation flow, so the group ID must be a valid Java package name. Keep the public Maven coordinate in `conversational-ai/README.md` as `io.agora.agents:agora-agent-client-toolkit`.

This project does not need `.target` files unless it is changed into Rehoboam multi-module target publishing.

## File Naming

- Kotlin files: `PascalCase.kt`
- Resource files: `snake_case.xml`
- Layout files: `activity_*.xml` / `item_*.xml` / `dialog_*.xml`

## Documentation Navigation

| Document | Description |
|----------|-------------|
| AGENTS.md | AI Agent development guidelines and project constraints |
| ARCHITECTURE.md | Technical architecture details (data flows, threading, lifecycle) |
| README.md | Quick start and usage guide |

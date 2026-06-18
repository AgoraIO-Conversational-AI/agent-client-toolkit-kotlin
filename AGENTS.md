# Conversational AI Quickstart Android — AI Assistant Guide

## How to Use This Project

This is a complete, runnable Android demo for real-time voice conversation with an AI agent.

- If you don't have an existing project, use this project directly. Modify it based on user requirements.
- If you already have a project, refer to the key parts of this project (connection flow, UI structure, ConversationalAIAPI integration) and adapt them into the existing codebase.

## RESTful Request Shape

The RESTful startup payload is built in `app/src/main/java/io/agora/agent/toolkit/sample/api/AgentStarter.kt` → `buildJsonPayload()`.

Current client-side request shape:

- top-level `preset`: `deepgram_nova_3`
- `properties.asr.language`: `en`
- common fields: `channel`, `token`, `agent_rtc_uid`, `remote_rtc_uids`, `idle_timeout`
- transport fields: `advanced_features.enable_rtm`, `parameters.data_channel`
- turn detection: `turn_detection`

Current constraint:

- RESTful startup **no longer configures LLM or TTS from the client request**
- `llm` and `tts` blocks should not be added back unless the API contract changes again
- `env.properties` should only carry `APP_ID` / `APP_CERTIFICATE` for the current flow

If the upstream REST API changes and requires client-side `llm` or `tts` again, update all of these together:

1. `AgentStarter.buildJsonPayload()`
2. `env.example.properties`
3. `app/build.gradle.kts`
4. `README.md`
5. `ARCHITECTURE.md`
6. this file

## Project Overview

Conversational AI Quickstart — Android real-time voice conversation client.

The client directly calls Agora RESTful API to start/stop Agent, authenticated via HTTP token (`Authorization: agora token=<token>`). This auth mode requires APP_CERTIFICATE to be enabled.

Current quickstart scope is limited to voice session startup, transcript display, state rendering, mute, and stop. It does not expose text or image message sending UI.

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
| RTM SDK | Agora RTM SDK (`io.agora:agora-rtm-lite:2.2.6`) |
| Coroutines | Kotlin Coroutines 1.9.0 |
| ConversationalAIAPI | Standalone `:conversational-ai` Gradle module, do not modify |

For runtime structure, see `ARCHITECTURE.md`. For entry files, see `README.md`.

## Core Modules

### AgentChatViewModel

- Manages RTC Engine and RTM Client lifecycle
- Subscribes to RTM messages via ConversationalAIAPI, parses Agent state and transcripts
- Exposes four StateFlows:
  - `uiState: StateFlow<ConversationUiState>` — connection state (Idle/Connecting/Connected/Error) + mute
  - `agentState: StateFlow<AgentState>` — Agent state (IDLE/SILENT/LISTENING/THINKING/SPEAKING)
  - `transcriptList: StateFlow<List<Transcript>>` — transcript list (deduplicated/updated by turnId + type)
  - `debugLogList: StateFlow<List<String>>` — debug logs (max 20 entries)
- Auto flow: joinRTC + loginRTM → both ready → generateToken → startAgent
- `userId` / `agentUid` are randomly generated in the companion object, and `channelName` format is `channel_kotlin_<6-digit-random>`

### AgentStarter

- `startAgentAsync()`: POST `/join`
  - Preset: `deepgram_nova_3`
  - `properties.asr`: `language: "en"`
  - Advanced features: `enable_rtm: true`, `data_channel: "rtm"`, `enable_string_uid: false`, `idle_timeout: 120`
  - Remote UIDs: `remote_rtc_uids: ["<currentUserUid>"]`
- `stopAgentAsync()`: POST `/agents/{agentId}/leave`
- Authentication: `Authorization: agora token=<authToken>` (requires APP_CERTIFICATE enabled)

### TokenGenerator (Demo Only)

- Generates RTC/RTM tokens via demo service at `https://service.apprtc.cn/toolbox/v2/token/generate`
- Sends `appId`, `appCertificate`, `channelName`, `uid`, `types` (1=RTC, 2=RTM) in POST body
- Returns a unified token usable for both RTC and RTM
- **Requires APP_CERTIFICATE**: the demo token service needs `appCertificate` to generate valid tokens
- Demo only — production must use your own backend for token generation

### ConversationalAIAPI

- Standalone Gradle module `:conversational-ai` (package `io.agora.conversational.api`), depended on by `:app` via `implementation(project(":conversational-ai"))`
- Wraps RTM message subscription/parsing
- The quickstart currently reacts to:
  - `onAgentStateChanged`
  - `onTranscriptUpdated`
  - `onAgentError` (logged through ViewModel state/logs)
  - `onDebugLog`
- Audio settings: `loadAudioSettings(AUDIO_SCENARIO_AI_CLIENT)` (must be called before joinChannel)

## Configuration

### Configuration Flow

```text
env.properties → Gradle buildConfigField → BuildConfig → KeyCenter → AgentStarter / TokenGenerator
```

Gradle validates all required properties at build time. If any are missing or empty, the build fails with a clear error message listing the missing fields.

### Configuration Fields (env.properties)

| Field | Description | Required | Default |
|-------|-------------|----------|---------|
| `APP_ID` | Agora App ID | ✅ | — |
| `APP_CERTIFICATE` | Agora App Certificate (must be enabled) | ✅ | — |

### APP_CERTIFICATE Must Be Enabled

This project uses HTTP token auth (`Authorization: agora token=<token>`) for REST API calls, and the demo `TokenGenerator` sends `appCertificate` to the token service. Both require the App Certificate to be enabled. If `APP_CERTIFICATE` is empty or the certificate is not enabled in the Agora console, token generation and REST API calls will fail.

Make sure to:
1. Enable the primary certificate for your App ID in the [Agora Console](https://console.shengwang.cn/)
2. Fill in the certificate value in `env.properties` under `APP_CERTIFICATE`

### Build-Time Validation

`build.gradle.kts` validates the following properties are non-empty at build time:
`APP_ID`, `APP_CERTIFICATE`

If any are missing, the build fails with a message listing the missing properties.

## API Endpoints

Client directly calls Agora REST API (Demo mode):

| Endpoint | Method | Auth Header | Description |
|----------|--------|-------------|-------------|
| `api.agora.io/api/conversational-ai-agent/v2/projects/{appId}/join` | POST | `Authorization: agora token=<authToken>` | Start Agent |
| `api.agora.io/api/conversational-ai-agent/v2/projects/{appId}/agents/{agentId}/leave` | POST | `Authorization: agora token=<authToken>` | Stop Agent |

Token generated via Demo service (must be replaced with your own backend in production):

| Endpoint | Method | Description |
|----------|--------|-------------|
| `service.apprtc.cn/toolbox/v2/token/generate` | POST | Generate RTC/RTM Token (requires appId + appCertificate) |

### Start Agent Request Body Structure

```json
{
  "name": "<channelName>",
  "preset": "deepgram_nova_3",
  "properties": {
    "channel": "<channelName>",
    "token": "<agentToken>",
    "agent_rtc_uid": "<agentRtcUid>",
    "remote_rtc_uids": ["<currentUserUid>"],
    "enable_string_uid": false,
    "idle_timeout": 120,
    "advanced_features": { "enable_rtm": true },
    "asr": {
      "language": "en"
    },
    "parameters": {
      "audio_scenario": "chorus",
      "data_channel": "rtm",
      "enable_error_message": true
    },
    "turn_detection": {
      "mode": "default",
      "config": {
        "speech_threshold": "0.6",
        "start_of_speech": { "model": "vad", "vad_config": { "interrupt_duration_ms": 500, "prefix_padding_ms": 800 } },
        "end_of_speech": { "model": "semantic", "semantic_config": { "max_wait_ms": 1200, "silence_duration_ms": 480 } }
      }
    }
  }
}
```

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
2. Generate userToken (unified for RTC+RTM, channelName is empty string, uid=userId)
3. Parallel: join RTC channel + login RTM (both use the same userToken)
4. Both ready → subscribeMessage(channelName) → generate agentToken + authToken (uid=agentUid, channelName=current channel)
5. Call `AgentStarter.startAgentAsync(channelName, agentRtcUid, agentToken, authToken, remoteRtcUid)` to start Agent, where `remoteRtcUid` is the current user RTC UID
6. ConversationalAIAPI receives Agent events via RTM → update StateFlow → UI responds
7. User taps Stop → unsubscribeMessage → `AgentStarter.stopAgentAsync(agentId, authToken)` → leave RTC → clean up state

## How to Change Request Parameters

The agent start request body is built in `AgentStarter.kt` → `buildJsonPayload()` as a nested `JSONObject`. Key sections:

| Section | What it controls | Where in the JSON |
|---------|-----------------|-------------------|
| `preset` | Managed ASR preset | top-level `preset` |
| `asr` | Speech-to-text language | `properties.asr` |
| `parameters` | Data channel (`rtm`), error message toggle | `properties.parameters` |
| `advanced_features` | RTM enable flag | `properties.advanced_features` |
| Top-level | Channel name, agent UID, idle timeout, token | `properties.*` |

To modify request parameters: edit `buildJsonPayload()` in `AgentStarter.kt`.

## Key Constraints

1. **APP_CERTIFICATE required**: This project uses HTTP token auth for REST API and token generation. APP_CERTIFICATE must be enabled in the Agora console and configured in `env.properties`. Build will fail if it's empty.
2. **Demo Mode**: Config injected via `env.properties` → BuildConfig, client directly calls REST API
3. **Production**: Sensitive info (`appCertificate`) must be on backend; client only fetches Token and starts Agent through backend
4. **Token Generation**: `TokenGenerator.kt` is Demo-only; production must use your own server
5. **Resource Cleanup**: RTC/RTM resources fully released in `hangup()` and `onCleared()`; ConversationalAIAPI released via `destroy()`
6. **Permissions**: Requires `RECORD_AUDIO` and `INTERNET` permissions
7. **ConversationalAIAPI is read-only**: All files under the `:conversational-ai` module (`conversational-ai/src/main/java/io/agora/conversational/api/`) are standalone components — **do not modify directly**. The module is published to Maven Central as a reusable library; the app depends on it via `implementation(project(":conversational-ai"))`. See `conversational-ai/README.md` for usage.
8. **Audio Settings**: `loadAudioSettings()` must be called before `joinChannel()`; Avatar mode uses `AUDIO_SCENARIO_DEFAULT`

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

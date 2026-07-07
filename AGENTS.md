# Conversational AI Quickstart Android — AI Assistant Guide

## How to Use This Project

This is a complete, runnable Android demo for real-time voice conversation with an AI agent.

- If you don't have an existing project, use this project directly. Modify it based on user requirements.
- If you already have a project, refer to the key parts of this project (connection flow, UI structure, ConversationalAIAPI integration) and adapt them into the existing codebase.

## RESTful Request Shape

The RESTful startup payload is built in `app/src/main/java/io/agora/agent/toolkit/sample/api/AgentStarter.kt` → `buildJsonPayload()`.

Current client-side request shape:

- explicit `properties.asr`, `properties.llm`, and `properties.tts` blocks
- common fields: `channel`, `token`, `agent_rtc_uid`, `remote_rtc_uids`, `idle_timeout`
- transport fields: `advanced_features.enable_rtm`, `parameters.data_channel`
- event/debug fields: `parameters.enable_metrics`, `parameters.enable_error_message`, `parameters.transcript`
- turn detection selected before startup:
  - `SOS` controls `start_of_speech.mode`
  - `EOS` controls `end_of_speech.mode`
  - both settings support `vad`, `semantic`, and `manual`

Current constraint:

- RESTful startup uses explicit ASR / LLM / TTS blocks in `AgentStarter.buildJsonPayload()`
- Manual SOS/EOS capability is decided before `POST /join`. The demo stores independent SOS / EOS detection settings in `AgentChatViewModel` state and passes them into `AgentStarter.startAgentAsync(...)`.
- Demo ASR / LLM / TTS values come from `env.example.properties` defaults plus local `env.properties` overrides through `BuildConfig` / `KeyCenter`
- Provider keys in the demo payload are placeholders; production should move this config to a backend
- `env.properties` carries required local `APP_ID` / `APP_CERTIFICATE`, and demo ASR / LLM / TTS overrides

If the upstream REST API changes the client-side ASR / LLM / TTS request shape, update all of these together:

1. `AgentStarter.buildJsonPayload()`
2. `env.example.properties`
3. `app/build.gradle.kts`
4. `README.md`
5. `ARCHITECTURE.md`
6. this file

## Project Overview

Conversational AI Quickstart — Android real-time voice conversation client.

The client directly calls Agora RESTful API to start/stop Agent, authenticated via HTTP token (`Authorization: agora token=<token>`).

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
- Auto flow: joinRTC + loginRTM → both ready → generateToken → startAgent
- Startup failure flow: transport/token/startup failures release partial startup side effects and return UI state to `Idle`
- Message flow: connected UI can send text messages, image URL messages, and interrupt requests through ConversationalAIAPI
- Manual flow: the top-right Settings sheet chooses `/join` SOS / EOS detection modes before startup; after connection, the capability panel exposes SOS / EOS buttons only for detection modes set to `manual`
- `userId` / `agentUid` are randomly generated in the companion object, and `channelName` format is `channel_kotlin_<6-digit-random>`

### AgentStarter

- `startAgentAsync()`: POST `/join`
  - Explicit ASR / LLM / TTS blocks
  - `sosDetectionMode`: controls `start_of_speech.mode`
  - `eosDetectionMode`: controls `end_of_speech.mode`
  - Advanced features: `enable_rtm: true`, `enable_bhvs: true`, `enable_string_uid: false`, `idle_timeout: 120`
  - Remote UIDs: `remote_rtc_uids: ["<currentUserUid>"]`
- `stopAgentAsync()`: POST `/agents/{agentId}/leave`
- Authentication: `Authorization: agora token=<authToken>`

### TokenGenerator (Demo Only)

- Generates unified RTC + RTM AccessToken2 locally from `APP_CERTIFICATE`
- Returns a unified token usable for RTC join, RTM login, and ConvoAI REST auth
- Demo only — production must use your own backend for token generation and must not ship `APP_CERTIFICATE` in the app
- The demo implementation is in `app/src/main/java/io/agora/agent/toolkit/sample/api/TokenGenerator.kt`
  and uses the local Java `RtcTokenBuilder2` (`app/src/main/java/io/agora/dynamickey/media`) instead of a remote toolbox service.

### ConversationalAIAPI

- Standalone Gradle module `:conversational-ai` (package `io.agora.conversational.api`), depended on by `:app` via `implementation(project(":conversational-ai"))`
- Wraps RTM message subscription/parsing
- The quickstart currently reacts to:
  - `onAgentStateChanged`
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

## Configuration

### Configuration Flow

```text
env.example.properties + env.properties → Gradle buildConfigField → BuildConfig → KeyCenter → AgentStarter / TokenGenerator
```

Gradle validates all required properties at build time. If any are missing or empty, the build fails with a clear error message listing the missing fields.

### Configuration Fields (env.properties)

| Field | Description | Required | Default |
|-------|-------------|----------|-------|
| `APP_ID` | Agora App ID | ✅ | — |
| `APP_CERTIFICATE` | Agora App Certificate. Required only for the local demo token generator; production apps must keep this on a backend. | ✅ | — |
| `ASR_VENDOR` | ASR provider name | ❌ | `soniox` |
| `ASR_API_KEY` | ASR provider key | ❌ | `xxx` |
| `ASR_MODEL` | ASR model | ❌ | `stt-rt-preview-v2` |
| `LLM_URL` | OpenAI-compatible LLM endpoint | ❌ | `https://api.groq.com/openai/v1/chat/completions` |
| `LLM_API_KEY` | LLM API key | ❌ | empty |
| `LLM_MODEL` | LLM model | ❌ | `llama-3.3-70b-versatile` |
| `TTS_VENDOR` | TTS provider name | ❌ | `elevenlabs` |
| `TTS_KEY` | TTS provider key | ❌ | `sk_xxx` |
| `TTS_MODEL_ID` | TTS model ID | ❌ | `eleven_flash_v2_5` |
| `TTS_VOICE_ID` | TTS voice ID | ❌ | empty |
| `TTS_SAMPLE_RATE` | TTS sample rate | ❌ | `44100` |

### APP_CERTIFICATE

This project uses HTTP token auth (`Authorization: agora token=<token>`) for REST API calls. The demo `TokenGenerator` generates a unified RTC + RTM AccessToken2 locally from `APP_CERTIFICATE`.

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

Token generation in Demo mode (must be replaced with your own backend in production):

| Endpoint | Method | Description |
|----------|--------|-------------|
| Local AccessToken2 builder | — | Uses `APP_CERTIFICATE` to create a unified RTC + RTM token |

### Start Agent Request Body Structure

```json
{
  "name": "<channelName>",
  "properties": {
    "channel": "<channelName>",
    "token": "<agentToken>",
    "agent_rtc_uid": "<agentRtcUid>",
    "remote_rtc_uids": ["<currentUserUid>"],
    "enable_string_uid": false,
    "idle_timeout": 120,
    "advanced_features": {
      "enable_aivad": false,
      "enable_bhvs": true,
      "enable_sal": false,
      "enable_rtm": true
    },
    "asr": {
      "vendor": "soniox",
      "params": { "api_key": "xxx", "model": "stt-rt-preview-v2" }
    },
    "tts": {
      "vendor": "elevenlabs",
      "params": { "key": "sk_xxx", "model_id": "eleven_flash_v2_5", "voice_id": "xxxx", "sample_rate": 44100 }
    },
    "llm": {
      "url": "https://api.groq.com/openai/v1/chat/completions",
      "api_key": "",
      "params": { "model": "llama-3.3-70b-versatile" },
      "greeting_message": "hello man, I am an AI robot, I can do anything for you",
      "failure_message": "Sorry, I don't know how to answer your question"
    },
    "parameters": {
      "enable_metrics": true,
      "enable_error_message": true,
      "output_audio_codec": "OPUSFB",
      "audio_scenario": "default",
      "transcript": { "enable": true, "protocol_version": "v2", "enable_words": false },
      "data_channel": "rtm"
    },
    "turn_detection": {
      "mode": "default",
      "config": {
        "speech_threshold": 0.6,
        "start_of_speech": {
          "mode": "vad",
          "vad_config": { "interrupt_duration_ms": 500, "speaking_interrupt_duration_ms": 300, "prefix_padding_ms": 800 }
        },
        "end_of_speech": {
          "mode": "semantic",
          "semantic_config": { "silence_duration_ms": 480, "max_wait_ms": 1200, "pause_state_enabled": false }
        }
      }
    }
  }
}
```

`SOS` and `EOS` are independent settings:

| Setting | Request field | Options |
|---------|---------------|---------|
| `SOS` | `start_of_speech.mode` | `vad`, `semantic`, `manual` |
| `EOS` | `end_of_speech.mode` | `vad`, `semantic`, `manual` |

When either setting is `manual`, the demo shows the corresponding manual
capability button after startup. The request still uses the same
`turn_detection` block; only the selected `mode` and mode-specific config under
`start_of_speech` / `end_of_speech` changes.

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
2. Generate userToken (unified for RTC+RTM, channelName is current session channel, uid=userId)
3. Parallel: join RTC channel + login RTM (both use the same userToken)
4. Both ready → subscribeMessage(channelName) → generate agentToken + authToken (uid=agentUid, channelName=current channel)
5. Call `AgentStarter.startAgentAsync(channelName, agentRtcUid, agentToken, authToken, remoteRtcUid, sosDetectionMode, eosDetectionMode)` to start Agent, where `remoteRtcUid` is the current user RTC UID
6. ConversationalAIAPI receives Agent events via RTM → update StateFlow → UI responds
7. User can send text / image URL messages or interrupt requests through the connected controls
8. If a manual turn capability was enabled at startup, user taps the visible SOS / EOS action → `manualSOS(...)` / `manualEOS(...)` publishes the marker and logs the later server result callback
9. User taps Stop → unsubscribeMessage → `AgentStarter.stopAgentAsync(agentId, authToken)` → leave RTC → clean up state

## How to Change Request Parameters

The agent start request body is built in `AgentStarter.kt` → `buildJsonPayload()` as a nested `JSONObject`. Key sections:

| Section | What it controls | Where in the JSON |
|---------|-----------------|-------------------|
| `asr` | Speech-to-text provider and model | `properties.asr` |
| `llm` | LLM endpoint and model | `properties.llm` |
| `tts` | Text-to-speech provider and voice | `properties.tts` |
| `parameters` | Data channel (`rtm`), metrics/errors, transcript output | `properties.parameters` |
| `advanced_features` | RTM enable flag | `properties.advanced_features` |
| Top-level | Channel name, agent UID, idle timeout, token | `properties.*` |

To change demo ASR / LLM / TTS values, edit `env.properties`. To change the
request JSON structure, edit `buildJsonPayload()` in `AgentStarter.kt`.

## Key Constraints

1. **APP_CERTIFICATE is required by the local demo token flow**: This project uses HTTP token auth for REST API. The demo `TokenGenerator` uses `APP_CERTIFICATE` for local AccessToken2 generation.
2. **Demo Mode**: Config injected via `env.example.properties` + `env.properties` → BuildConfig, client directly calls REST API
3. **Production**: Sensitive info (`appCertificate`) must be on backend; client only fetches Token and starts Agent through backend
4. **Token Generation**: `TokenGenerator.kt` is Demo-only; production must use your own server and must not embed `APP_CERTIFICATE`
5. **Resource Cleanup**: RTC/RTM resources fully released in `hangup()` and `onCleared()`; ConversationalAIAPI released via `destroy()`
6. **Permissions**: Requires `RECORD_AUDIO` and `INTERNET` permissions
7. **ConversationalAIAPI module boundary**: Files under `:conversational-ai` (`conversational-ai/src/main/java/io/agora/conversational/api/`) are reusable toolkit components packaged for Maven / AAR release. Keep the public API minimal and update `conversational-ai/README.md` when the API changes. The sample app depends on it via `implementation(project(":conversational-ai"))`.
8. **Audio Settings**: `loadAudioSettings()` must be called before `joinChannel()`; Avatar mode uses `AUDIO_SCENARIO_DEFAULT`

## Internal Maven Release

Rehoboam is the internal Maven / AAR release platform. Do not document Rehoboam, Jenkins download URLs, or internal release requests in public-facing README files.

The public `conversational-ai/README.md` should describe developer-facing API usage only.

Release strategy:

- Do not use the final release version as the first validation artifact.
- Package and publish an RC first, for example `2.9.0-rc.1`.
- Validate the RC through staging / platform validation plus sample or clean-app consumption.
- If fixes are needed before formal publish, drop the staging deployment and publish the next RC.
- Publish the final version, for example `2.9.0`, only after the RC passes.
- If a problem is found after the final version is published, do not overwrite or delete that version; publish a new version such as `2.9.1`.

To prepare the Rehoboam upload zip:

```bash
VERSION=2.9.0-rc.1 scripts/build_rehoboam_maven_input_zip.sh
```

The generated zip is:

```text
conversational-ai/build/distributions/agora-agent-client-toolkit-<version>-maven-release.zip
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

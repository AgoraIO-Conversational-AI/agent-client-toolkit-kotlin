# Android Python Backend Migration

## Status

The code migration is implemented.

- Python and Android unit tests pass.
- Android debug compilation passes.
- Client-side App Certificate, provider config, AccessToken2 builder, and direct
  Agora Agent REST calls have been removed.
- Physical-device Agent join and voice round-trip acceptance still require a
  valid `server/.env.local` and a phone on the same LAN.

Automated tests and compilation are not proof of audible RTC/RTM/Agent behavior.

## Goal

Move user token generation and Conversational AI Agent lifecycle ownership out
of the Android APK and into a local Python FastAPI service using
[`agora-agents`](https://github.com/AgoraIO/agora-agents-python).

```text
Android -> Python FastAPI -> agora-agents -> Agora ConvoAI
Android <-> Agora RTC/RTM <-> managed Agent
```

The Android app keeps the existing Views/XML UI, RTC/RTM client lifecycle, and
standalone `:conversational-ai` toolkit module.

## Source Alignment

The implementation was adapted from:

- official `agent-quickstart-python`
- `agora-agents` Python SDK source at version `2.4.1`
- the Android structure in `recipe-client-android-quickstart`

The migration keeps the existing Android app architecture rather than copying
the reference Compose UI or vendoring another toolkit implementation.

## Developer Flow

Physical Android devices are the primary path.

1. Connect the development machine and phone to the same LAN.
2. Create backend config:

   ```bash
   cp server/.env.example server/.env.local
   ```

3. Set `AGORA_APP_ID` and `AGORA_APP_CERTIFICATE`.
4. Start the backend:

   ```bash
   ./scripts/start_backend.sh
   ```

5. Allow incoming Python connections in the development-machine firewall.
6. Build and run `:app` on the phone.

The startup script:

- creates `server/.venv` and installs dependencies on first use
- starts FastAPI on `0.0.0.0:<PORT>`
- detects the current development-machine LAN IP
- waits for `/health`
- preserves unrelated root `local.properties` values
- writes `agent.backend.url=http://<current-lan-ip>:<PORT>`
- writes the client URL only after the selected port is available and `/health`
  succeeds

`192.168.1.20` is not fixed configuration; it would only be one possible LAN
address. `localhost` points to the phone when used from the Android app.
Changing the backend host does not require an active USB connection and does
not use `adb reverse`, `-PagentBackendUrl`, or an in-app host setting. App
installation may use Android Studio USB or wireless device pairing.

## Ownership Boundary

Python owns:

- Agora App ID and App Certificate
- user RTC+RTM token generation
- Agent RTC token and Agora REST auth through the SDK
- managed STT/LLM/TTS configuration
- turn detection mapping
- Agent session start/stop
- active `agentId` to SDK session correlation
- HTTP validation and safe error envelopes

Android owns:

- microphone permission
- RTC join and RTM login
- toolkit message subscription and callbacks
- transcript, latency, Agent state, and error rendering
- mute, chat, interrupt, and manual SOS/EOS controls
- immediate local cleanup on failures and hangup

The APK no longer contains:

- `APP_CERTIFICATE`
- provider keys or LLM/TTS config
- Java AccessToken2 signing code
- Agent RTC tokens
- Agora REST auth tokens or headers
- direct Agora Agent `/join` or `/leave` URLs

## Python SDK Pipeline

The server pins:

```text
agora-agents==2.4.1
```

It uses `AsyncAgora` and `Agent.create_async_session()` because FastAPI routes
are asynchronous.

Managed pipeline:

| Stage | Provider | Model |
|-------|----------|-------|
| STT | Agora Fengming | default configuration |
| LLM | OpenAI | `gpt-4o-mini` |
| TTS | MiniMax | `speech_2_6_turbo` |

The TTS voice is `English_captivating_female1`. No third-party provider keys
are needed for this managed path.

Session settings:

- unique Agent name per session
- numeric string Agent/remote RTC UIDs
- `enable_string_uid=false`
- `idle_timeout=120`
- 24-hour SDK credential expiry
- `advanced_features.enable_sal=false`
- `advanced_features.enable_rtm=true`
- `parameters.data_channel=rtm`
- metrics and error messages enabled

## Backend Contract

Success:

```json
{"code": 0, "data": {}, "msg": "success"}
```

Failure:

```json
{"code": 502, "data": null, "msg": "safe message"}
```

Failures use non-2xx HTTP status codes. Android validates both HTTP status and
envelope `code`.

### `GET /get_config`

Optional query parameters:

- `channel`: requested channel
- `uid`: requested positive numeric user UID

Android supplies both. The backend honors valid values and returns:

```json
{
  "code": 0,
  "data": {
    "app_id": "<agora-app-id>",
    "token": "<user-rtc-rtm-token>",
    "uid": "123456",
    "agent_uid": "87654321",
    "channel_name": "channel_kotlin_123456"
  },
  "msg": "success"
}
```

The token lifetime is 24 hours. The RTM identity, RTC UID, and token subject
use the same numeric user UID.

### `POST /startAgent`

```json
{
  "channelName": "channel_kotlin_123456",
  "agentUid": 87654321,
  "userUid": 123456,
  "startOfSpeechMode": "vad",
  "endOfSpeechMode": "semantic"
}
```

The backend accepts `vad`, `semantic`, or `manual` and maps the values
independently to:

- `turn_detection.config.start_of_speech.mode`
- `turn_detection.config.end_of_speech.mode`

It does not add `vad_config` or `semantic_config`.

### `POST /stopAgent`

```json
{"agentId": "<runtime-agent-id>"}
```

The backend uses the tracked `AsyncAgentSession` first, then falls back to
`AsyncAgora.stop_agent(agentId)`. SDK 404/already-stopped handling is treated as
idempotent success.

## Android Startup Sequence

```text
Tap Start
  -> GET /get_config
  -> initialize RTC/RTM/toolkit from returned App ID and UID
  -> loadAudioSettings(AUDIO_SCENARIO_AI_CLIENT)
  -> login RTM with returned token
  -> join RTC and wait for the real join callback
  -> subscribeMessage(channel)
  -> wait for subscription success
  -> POST /startAgent
  -> require non-empty agentId
  -> Connected
```

Startup remains `Idle -> Connecting -> Connected`. Existing attempt IDs reject
late callbacks and late start responses. Any backend, RTC, RTM, subscription,
or Agent start failure releases partial side effects and returns to `Idle`.

## Stop Sequence

```text
capture agentId
  -> invalidate current attempt
  -> immediately unsubscribe and destroy toolkit
  -> leave and destroy RTC
  -> log out and release RTM
  -> reset transient state and UI to Idle
  -> asynchronously POST /stopAgent
```

Backend delay or failure is logged and cannot block local cleanup.
Final ViewModel teardown also sends a best-effort stop for a known Agent ID.
An in-flight `/startAgent` request is owned by a process-level cleanup scope so
that cancellation is rethrown while any late successful Agent ID is stopped.

## Repository Changes

Added:

- `server/src/agent.py`
- `server/src/server.py`
- `server/tests/`
- `server/.env.example`
- `server/requirements*.txt`
- `scripts/start_backend.sh`
- `AgentBackendClient.kt` and MockWebServer tests
- debug-only cleartext network security resource

Removed:

- `AgentStarter.kt`
- `TokenGenerator.kt`
- `KeyCenter.kt`
- local Java AccessToken2 builder sources
- `env.example.properties`
- old AgentStarter/TokenGenerator tests

## Verification

Executed successfully:

```bash
server/.venv/bin/pytest server/tests -q
./gradlew :app:testDebugUnitTest :app:lintDebug :conversational-ai:testDebugUnitTest
./gradlew :app:assembleDebug
git diff --check
```

Current results:

- Python: 18 tests passed
- Android: unit test task passed, including backend HTTP contract tests
- Android lint and toolkit unit tests passed
- debug APK assembly passed; removed client secret/REST identifiers were not found in the APK
- diff whitespace validation passed

## Remaining Physical Acceptance

Not yet verified in this environment:

- phone reaches the generated LAN URL
- real RTC join and RTM login
- Agent joins and produces audible TTS
- transcript, latency, Agent state, chat, interrupt, mute, and manual SOS/EOS
- hangup behavior while the real backend stop is delayed or unavailable

These checks require valid local Agora credentials and a physical phone. Do not
treat compilation or mocked tests as voice round-trip proof.

## Non-Goals

- hosted production backend or deployment automation
- public Internet exposure of the local service
- embedding shared credentials in the app
- emulator-first audio validation
- video capture/rendering
- toolkit public API changes
- a more complex Android startup state machine

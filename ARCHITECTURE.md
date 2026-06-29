# Architecture — Conversational AI Quickstart Android Kotlin

## Architecture Overview

This quickstart is a single-screen voice conversation demo built with Android Views + XML.

Current scope:

- Start Agent
- RTC join + RTM login
- Startup-time selection for independent SOS / EOS turn detection
- Real-time transcript rendering
- Optional completed-turn latency metrics
- Agent status rendering
- Mute / unmute
- Text message sending
- Image URL message sending
- Agent interrupt
- Manual SOS / EOS trigger buttons shown by selected detection modes
- Stop Agent and cleanup

Out of scope for this quickstart:

- Multi-screen business flow
- Backend-owned token / agent startup flow

## Page Layout

The Activity page is intentionally single-page and is organized into these regions:

- title and subtitle
- log panel
- transcript panel
- capability panel inside the transcript area for optional component abilities
- transcript header with agent status and real-time data toggle
- bottom start / mute / chat / stop controls
- interrupt action over the transcript panel while connected

## Project Structure

```text
app/src/main/java/
└── io/agora/agent/toolkit/sample/
    ├── ui/            # AgentChatActivity + ViewModel + manual turn / chat UI helpers
    ├── api/           # AgentStarter + TokenGenerator + OkHttp config
    ├── tools/         # Permission helpers
    ├── KeyCenter.kt
    └── AgentApp.kt

conversational-ai/src/main/java/io/agora/conversational/api/
└── ...                # Reusable toolkit API, RTM parsing, transcript component
```

## Runtime Shape

```text
AgentChatActivity / AgentChatViewModel /
RTC / RTM / ConversationalAIAPI / TokenGenerator / AgentStarter
```

`:conversational-ai` parses RTM payloads, emits agent / transcript callbacks, and publishes RTM control messages such as interrupt and manual SOS/EOS.

## Connection Flow (User taps Start Agent)

```text
Tap Start Agent
  → use the top-right settings sheet's selected SOS / EOS detection modes
  → check microphone permission
  → generate userToken
  → join RTC + login RTM
  → subscribe RTM channel
  → generate agentToken + authToken
  → POST /join with explicit ASR / LLM / TTS blocks and selected turn-detection shape
  → save agentId
  → uiState = Connected
```

Kotlin-specific conventions:

- `userId` and `agentUid` are random 6-digit integers and do not conflict
- `channelName` format is `channel_kotlin_<6-digit-random>`
- REST auth header is `Authorization: agora token=<authToken>`

## Transcript Data Flow

```text
RTM message
  → ConversationalAIAPI
  → TranscriptController
  → AgentChatViewModel.addTranscript(...)
  → transcriptList update
  → AgentChatActivity refreshes transcript bubbles
```

The current UI renders:

- agent transcript on the left with `AI`
- user transcript on the right with `Me`
- optional completed-turn latency metrics for agent messages

## Chat / Interrupt Flow

```text
Tap chat
  → choose Text or Image URL
  → AgentChatViewModel.sendTextMessage(...) / sendImageUrlMessage(...)
  → ConversationalAIAPI.chat(agentUserId, TextMessage/ImageMessage, completion)
  → onMessageReceiptUpdated / onMessageError
  → debugLogList update

Tap Interrupt
  → AgentChatViewModel.sendInterrupt()
  → ConversationalAIAPI.interrupt(agentUserId, completion)
  → onAgentInterrupted / state callback updates
```

The message sheet accepts natural language text or an HTTP(S) image URL. Empty
messages are rejected locally before publishing. Message receipts and errors are
shown in the log panel.

## Manual Turn Flow

```text
Choose SOS and EOS detection modes in the top-right settings sheet before startup
  → AgentStarter.startAgentAsync(..., sosDetectionMode, eosDetectionMode)
  → /join uses the selected start_of_speech.mode / end_of_speech.mode values
  → connected UI shows the capability panel for enabled manual actions
Tap SOS or EOS
  → AgentChatViewModel.manualSOS() / manualEOS()
  → ConversationalAIAPI.manualSOS(...) / manualEOS(...)
  → RTM publish with customType user.manual_sos / user.manual_eos
  → server result event
  → onUserManualSosEvent / onUserManualEosEvent / onAgentManualEosEvent
  → debugLogList update
  → AgentChatActivity refreshes log panel
```

Each manual request uses a toolkit-generated non-empty `requestId` so the
publish attempt can be correlated with the later server result callback.
By default, the session uses `start_of_speech.mode = vad` and
`end_of_speech.mode = semantic`, and the demo hides the capability panel. The
`SOS` action is visible only when `SOS = manual`; the `EOS`
action is visible only when `EOS = manual`.

## UI State Rendering

```text
uiState        → Start / Connecting / Mute / Chat / Stop buttons + optional capability and interrupt panels
agentState     → transcript header status color + text
transcriptList → transcript panel content + optional latency metrics
debugLogList   → log panel content
```

## Token Flow

The quickstart generates three token roles through the ConvoAI toolbox token service:

| Token | Purpose | Usage |
|-------|---------|-------|
| `userToken` | User RTC join + RTM login | `joinRtcChannel()` / `loginRtm()` |
| `agentToken` | Agent RTC join credential | Request body `properties.token` |
| `authToken` | REST API authentication | `Authorization: agora token=<authToken>` |

Notes:

- `userToken` uses `channelName=""` in the current demo flow
- `agentToken` and `authToken` are generated after RTC / RTM are both ready
- token requests go to `TOOLBOX_SERVER_HOST` + `/v2/token/generate`
- token requests do not include an extra token-generation auth header
- Production should replace the demo token service with a backend

## Agent Lifecycle

```text
IDLE
  → LISTENING
  → THINKING
  → SPEAKING
  → LISTENING
```

Additional behavior:

- `SILENT` can appear after interruption
- tapping `Stop Agent` unsubscribes RTM, stops the Agent, leaves RTC, and resets UI state back toward idle

## Config Contract

```text
env.example.properties + env.properties
  → BuildConfig
  → KeyCenter
  → AgentStarter / TokenGenerator / ViewModel
```

Build-time required fields:

- `APP_ID`

The demo toolbox host is injected through `BuildConfig.TOOLBOX_SERVER_HOST`.
The explicit ASR / LLM / TTS startup values are also injected from
the merged properties through `BuildConfig` and `KeyCenter`. Local
`env.properties` overrides `env.example.properties`; `APP_ID` and
`APP_CERTIFICATE` are read only from local `env.properties`.

Current default request:

- ASR: Soniox `stt-rt-preview-v2`
- LLM: Groq OpenAI-compatible `llama-3.3-70b-versatile`
- TTS: ElevenLabs `eleven_flash_v2_5`

Provider keys in `env.example.properties` are placeholders. Production should
move provider configuration and token generation to a backend.

## Constraints

- This is a demo; token generation and agent startup are client-side for convenience
- Production should move token generation and REST startup to a backend
- `:conversational-ai` is the reusable toolkit module; keep app-specific demo code in `:app`

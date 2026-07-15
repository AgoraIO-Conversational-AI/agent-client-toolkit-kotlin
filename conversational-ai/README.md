# agora-agent-client-toolkit

Android library for consuming Agora Conversational AI RTM events, tracking agent state, rendering transcripts, and sending RTM-based messages to an agent.

The library is designed to sit on top of an app's existing Agora RTC and RTM setup. It does not create RTC/RTM clients, generate tokens, join RTC channels, or start the Conversational AI agent.

## Install

See the root [README.md](../README.md) for Maven dependency setup.

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Android minSdk | 26+ |
| Agora RTC SDK | `io.agora.rtc:full-sdk:4.5.1` |
| Agora RTM SDK | `io.agora:agora-rtm-lite:2.2.3` |
| Kotlin | 2.0.21 |

The host app owns:

- RTC engine creation and lifecycle
- RTM client creation, login, and logout
- token generation and renewal
- joining and leaving RTC channels
- starting and stopping the Conversational AI agent

## Quick Start

```kotlin
val conversationalAIAPI = ConversationalAIAPIImpl(
    ConversationalAIAPIConfig(
        rtcEngine = rtcEngine,
        rtmClient = rtmClient,
        renderMode = TranscriptRenderMode.Word,
        enableLog = true
    )
)

val handler = object : IConversationalAIAPIEventHandler {
    override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
        // Existing aggregate-state integrations remain supported.
    }
    override fun onAgentListeningChanged(agentUserId: String, isListening: Boolean) {}
    override fun onAgentThinkingChanged(agentUserId: String, isThinking: Boolean) {}
    override fun onAgentSpeakingChanged(agentUserId: String, isSpeaking: Boolean) {}

    override fun onAgentInterrupted(agentUserId: String, event: InterruptEvent) {
        // Handle interruption.
    }

    override fun onAgentMetrics(agentUserId: String, metric: Metric) {
        // Observe module latency metrics.
    }

    override fun onTurnFinished(agentUserId: String, turn: Turn) {
        // Observe completed-turn latency.
    }

    override fun onAgentError(agentUserId: String, error: ModuleError) {
        // Handle agent-side errors.
    }

    override fun onMessageError(agentUserId: String, error: MessageError) {
        // Handle message errors.
    }

    override fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt) {
        // Handle message receipts.
    }

    override fun onAgentVoiceprintStateChanged(
        agentUserId: String,
        event: VoiceprintStateChangeEvent
    ) {
        // Handle voiceprint state changes.
    }

    override fun onUserManualSosEvent(agentUserId: String, event: UserManualSosEvent) {
        // Handle manual SOS result.
    }

    override fun onUserManualEosEvent(agentUserId: String, event: UserManualEosEvent) {
        // Handle manual EOS result.
    }

    override fun onAgentManualEosEvent(agentUserId: String, event: AgentManualEosEvent) {
        // Handle automatic EOS in manual mode.
    }

    override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
        // Render user or agent transcript.
    }

    override fun onDebugLog(log: String) {
        // Forward debug logs if needed.
    }
}

conversationalAIAPI.addHandler(handler)

conversationalAIAPI.loadAudioSettings(Constants.AUDIO_SCENARIO_AI_CLIENT)
rtcEngine.joinChannel(token, channelName, uid, channelOptions)

conversationalAIAPI.subscribeMessage(channelName) { error ->
    if (error != null) {
        // Handle ConversationalAIAPIError.
        return@subscribeMessage
    }

    // Start the Conversational AI agent through your app or backend flow.
}
```

## Configuration Reference

`ConversationalAIAPIConfig` fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `rtcEngine` | `RtcEngine` | Yes | Existing Agora RTC engine owned by the host app |
| `rtmClient` | `RtmClient` | Yes | Existing Agora RTM client owned by the host app |
| `renderMode` | `TranscriptRenderMode` | No | `Word` or `Text`; defaults to `Word` |
| `enableLog` | `Boolean` | No | Enables toolkit logs written through the RTC SDK log path; defaults to `true` |
| `enableRenderModeFallback` | `Boolean` | No | Falls back from `Word` to `Text` when word-level transcript data is unavailable; defaults to `true` |

## API Reference

### `ConversationalAIAPIImpl`

Create an instance with `ConversationalAIAPIConfig`:

```kotlin
val api: IConversationalAIAPI = ConversationalAIAPIImpl(config)
```

### `IConversationalAIAPI`

```kotlin
fun addHandler(handler: IConversationalAIAPIEventHandler)
fun removeHandler(handler: IConversationalAIAPIEventHandler)
fun subscribeMessage(channelName: String, completion: (ConversationalAIAPIError?) -> Unit)
fun unsubscribeMessage(channelName: String, completion: (ConversationalAIAPIError?) -> Unit)
fun chat(agentUserId: String, message: ChatMessage, completion: (ConversationalAIAPIError?) -> Unit)
fun interrupt(agentUserId: String, completion: (ConversationalAIAPIError?) -> Unit)
fun manualSOS(agentUserId: String, completion: (String, ConversationalAIAPIError?) -> Unit)
fun manualEOS(agentUserId: String, completion: (String, ConversationalAIAPIError?) -> Unit)
fun loadAudioSettings(scenario: Int = Constants.AUDIO_SCENARIO_AI_CLIENT)
fun destroy()
```

`loadAudioSettings()` must be called before every `RtcEngine.joinChannel()` call.

For Avatar mode, use:

```kotlin
conversationalAIAPI.loadAudioSettings(Constants.AUDIO_SCENARIO_DEFAULT)
```

For standard voice mode, use:

```kotlin
conversationalAIAPI.loadAudioSettings(Constants.AUDIO_SCENARIO_AI_CLIENT)
```

## Events

Implement `IConversationalAIAPIEventHandler` to receive callbacks.

| Callback | Payload | Description |
|----------|---------|-------------|
| `onAgentStateChanged` | `StateChangeEvent` | Deprecated but supported aggregate lifecycle state |
| `onAgentListeningChanged` | `Boolean` | Independent listening state (recommended) |
| `onAgentThinkingChanged` | `Boolean` | Independent thinking state (recommended) |
| `onAgentSpeakingChanged` | `Boolean` | Independent speaking state (recommended) |
| `onAgentInterrupted` | `InterruptEvent` | Agent turn was interrupted |
| `onAgentMetrics` | `Metric` | Module latency or performance metric |
| `onTurnFinished` | `Turn` | Completed-turn latency data |
| `onAgentError` | `ModuleError` | Agent module error |
| `onMessageError` | `MessageError` | Chat message delivery or processing error |
| `onMessageReceiptUpdated` | `MessageReceipt` | Chat message receipt update |
| `onAgentVoiceprintStateChanged` | `VoiceprintStateChangeEvent` | Voiceprint status update |
| `onUserManualSosEvent` | `UserManualSosEvent` | Server result for `manualSOS(...)` |
| `onUserManualEosEvent` | `UserManualEosEvent` | Server result for `manualEOS(...)` |
| `onAgentManualEosEvent` | `AgentManualEosEvent` | Server automatic EOS notification in manual mode |
| `onTranscriptUpdated` | `Transcript` | User or agent transcript update |
| `onDebugLog` | `String` | Toolkit debug log |

Some events require corresponding fields in the agent start request:

| Event | Required agent start parameter |
|-------|--------------------------------|
| Agent state and message events | `advanced_features.enable_rtm: true` and `parameters.data_channel: "rtm"` |
| Agent metrics | `parameters.enable_metrics: true` |
| Agent errors | `parameters.enable_error_message: true` |

## Transcript Rendering

`TranscriptRenderMode.Word` renders word-level transcripts when the server provides word timing data. If word-level data is unavailable and `enableRenderModeFallback` is `true`, the library falls back to `TranscriptRenderMode.Text`.

`onTranscriptUpdated()` may be called frequently. If your UI stores a transcript list, deduplicate or update by `turnId` and `type`.

Important transcript types:

| Type | Values |
|------|--------|
| `TranscriptRenderMode` | `Word`, `Text` |
| `TranscriptType` | `AGENT`, `USER` |
| `TranscriptStatus` | `IN_PROGRESS`, `END`, `INTERRUPTED`, `UNKNOWN` |

## Sending Messages

Send text:

```kotlin
conversationalAIAPI.chat(
    agentUserId,
    TextMessage(
        priority = Priority.INTERRUPT,
        responseInterruptable = true,
        text = "Hello"
    )
) { error ->
    // error is null on success.
}
```

Send image:

```kotlin
conversationalAIAPI.chat(
    agentUserId,
    ImageMessage(
        uuid = "image-1",
        imageUrl = "https://example.com/image.jpg"
    )
) { error ->
    // error is null on success.
}
```

Use `imageUrl` for large images. `imageBase64` must stay within RTM message size limits.

Interrupt the agent:

```kotlin
conversationalAIAPI.interrupt(agentUserId) { error ->
    // error is null on success.
}
```

Trigger manual start/end of speech:

```kotlin
conversationalAIAPI.manualSOS(agentUserId) { requestId, error ->
    // error is null when RTM publish succeeds.
    // requestId is generated by the toolkit and sent as request_id.
    // Server processing result arrives in onUserManualSosEvent.
}

conversationalAIAPI.manualEOS(agentUserId) { requestId, error ->
    // error is null when RTM publish succeeds.
    // requestId is generated by the toolkit and sent as request_id.
    // Server processing result arrives in onUserManualEosEvent.
}
```

The toolkit generates a non-empty `requestId` for every manual request and
returns it from the publish completion so callers can correlate the publish
attempt with the later server result callback. The toolkit does not decide
whether manual mode is currently allowed; server validation results are reported
through manual turn callbacks.

## Important Types

| Type | Purpose |
|------|---------|
| `ConversationalAIAPIConfig` | Supplies `RtcEngine`, `RtmClient`, transcript render mode, and logging options |
| `IConversationalAIAPI` | Main API for handlers, subscription, chat, interrupt, manual SOS/EOS, audio settings, and destroy |
| `IConversationalAIAPIEventHandler` | Main callback interface for state, transcripts, errors, metrics, receipts, manual turn results, and debug logs |
| `Transcript` | UI-ready transcript payload with turn ID, user ID, text, status, type, and render mode |
| `AgentState` | Agent lifecycle state: `IDLE`, `SILENT`, `LISTENING`, `THINKING`, `SPEAKING`, `UNKNOWN` |
| `UserManualSosEvent` | Result for a user-triggered manual SOS request |
| `UserManualEosEvent` | Result for a user-triggered manual EOS request |
| `AgentManualEosEvent` | Automatic EOS notification in manual mode |
| `ConversationalAIAPIError` | Error wrapper for RTM, RTC, and unknown failures |
| `Priority` | Chat priority: `INTERRUPT`, `APPEND`, `IGNORE` |

## Lifecycle Checklist

1. Create and configure `RtcEngine`.
2. Create and log in `RtmClient`.
3. Create `ConversationalAIAPIImpl`.
4. Register `IConversationalAIAPIEventHandler`.
5. Call `loadAudioSettings()` before `joinChannel()`.
6. Join RTC.
7. Call `subscribeMessage(channelName)`.
8. Start the Conversational AI agent through your app or backend flow.
9. Render callbacks from `IConversationalAIAPIEventHandler`.
10. On exit, call `unsubscribeMessage()`, leave RTC, remove handlers, and call `destroy()`.

## Troubleshooting

### Events are not firing

Check that the agent start request enables RTM events:

```json
{
  "properties": {
    "advanced_features": {
      "enable_rtm": true
    },
    "parameters": {
      "data_channel": "rtm"
    }
  }
}
```

Metrics and agent errors require extra parameters:

```json
{
  "properties": {
    "parameters": {
      "enable_metrics": true,
      "enable_error_message": true
    }
  }
}
```

### Word-level transcripts are empty

Use `TranscriptRenderMode.Text`, or keep `enableRenderModeFallback = true` so the toolkit can fall back to text rendering when word-level data is unavailable.

### Audio behavior is incorrect

Make sure `loadAudioSettings()` is called before each `RtcEngine.joinChannel()` call. Use `Constants.AUDIO_SCENARIO_DEFAULT` for Avatar mode and `Constants.AUDIO_SCENARIO_AI_CLIENT` for standard voice mode.

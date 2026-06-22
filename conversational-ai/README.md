# Conversational AI Android Library

Standalone Android library for consuming Agora Conversational AI RTM events and rendering conversation state in your app.

Module directory:

- `conversational-ai/`

Package namespace:

- `io.agora.conversational.api.*`
- `io.agora.conversational.api.transcript.*`

## Installation

Use the published Maven artifact:

```groovy
dependencies {
    implementation 'io.agora.agents:agora-agent-client-toolkit:<version>'
}
```

This library expects the host app to provide Agora RTC and RTM SDK instances. Add those dependencies in your app:

```groovy
dependencies {
    implementation 'io.agora.rtc:full-sdk:4.5.1'
    implementation 'io.agora:agora-rtm-lite:2.2.6'
}
```

## Dependency Model

The library keeps Agora RTC and RTM as `compileOnly` dependencies.

The host app owns:

- RTC engine creation and lifecycle
- RTM client creation, login, and logout
- token generation and renewal
- joining and leaving RTC channels
- starting and stopping the Conversational AI agent

The library consumes existing `RtcEngine` and `RtmClient` instances through `ConversationalAIAPIConfig`, subscribes to RTM message channels, parses agent events, and emits callbacks for UI/business logic.

The published AAR declares a low `minCompileSdk` so apps on Android Gradle Plugin 9 can consume it without needing to match this project's `compileSdk`. RTC and RTM are not bundled in this artifact; if your app uses AGP 9, make sure the Agora RTC/RTM versions you choose are also AGP 9 compatible.

## Core API

Create an API instance with existing RTC and RTM objects:

```kotlin
val conversationalAIAPI = ConversationalAIAPIImpl(
    ConversationalAIAPIConfig(
        rtcEngine = rtcEngine,
        rtmClient = rtmClient,
        renderMode = TranscriptRenderMode.Word,
        enableLog = true
    )
)
```

Register callbacks:

```kotlin
val handler = object : IConversationalAIAPIEventHandler {
    override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
        // Update agent state UI.
    }

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

    override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
        // Render user or agent transcript.
    }

    override fun onDebugLog(log: String) {
        // Forward debug logs if needed.
    }
}

conversationalAIAPI.addHandler(handler)
```

## Audio Settings

Call `loadAudioSettings()` before every `RtcEngine.joinChannel()` call.

```kotlin
conversationalAIAPI.loadAudioSettings(Constants.AUDIO_SCENARIO_AI_CLIENT)
rtcEngine.joinChannel(token, channelName, uid, channelOptions)
```

For Avatar mode, use `Constants.AUDIO_SCENARIO_DEFAULT`.

## Subscribe to Agent Events

After the host app has joined RTC and logged in to RTM, subscribe to the RTM message channel:

```kotlin
conversationalAIAPI.subscribeMessage(channelName) { error ->
    if (error != null) {
        // Handle ConversationalAIAPIError.
        return@subscribeMessage
    }

    // Now start the agent through your app/backend flow.
}
```

When the session ends, unsubscribe and release resources:

```kotlin
conversationalAIAPI.unsubscribeMessage(channelName) { error ->
    // Leave RTC and continue cleanup.
}

conversationalAIAPI.removeHandler(handler)
conversationalAIAPI.destroy()
```

## Optional Messages

The library can send text or image messages to an agent through RTM point-to-point messages.

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

You can also interrupt the agent:

```kotlin
conversationalAIAPI.interrupt(agentUserId) { error ->
    // error is null on success.
}
```

## Important Types

| Type | Purpose |
|------|---------|
| `ConversationalAIAPIConfig` | Supplies `RtcEngine`, `RtmClient`, transcript render mode, and logging options |
| `IConversationalAIAPI` | Main API for handlers, subscription, chat, interrupt, audio settings, and destroy |
| `IConversationalAIAPIEventHandler` | Main callback interface for agent state, transcripts, errors, metrics, and receipts |
| `Transcript` | UI-ready transcript payload with turn ID, user ID, text, status, type, and render mode |
| `AgentState` | Agent lifecycle state: `IDLE`, `SILENT`, `LISTENING`, `THINKING`, `SPEAKING`, `UNKNOWN` |
| `ConversationalAIAPIError` | Error wrapper for RTM, RTC, and unknown failures |

## Transcript Rendering

`TranscriptRenderMode.Word` renders word-level transcripts when the server provides word timestamps. If word-level data is unavailable and fallback is enabled, the library falls back to `TranscriptRenderMode.Text`.

`onTranscriptUpdated()` may be called frequently. If your UI stores a transcript list, deduplicate or update by `turnId` and `type`.

## Lifecycle Checklist

1. Create and configure RTC engine.
2. Create RTM client and log in.
3. Create `ConversationalAIAPIImpl`.
4. Call `loadAudioSettings()` before `joinChannel()`.
5. Join RTC.
6. Call `subscribeMessage(channelName)`.
7. Start the Conversational AI agent through your app/backend flow.
8. Render callbacks from `IConversationalAIAPIEventHandler`.
9. On exit, call `unsubscribeMessage()`, leave RTC, remove handlers, and call `destroy()`.

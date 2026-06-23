# Agora Conversational AI Toolkit for Android

A client-side Android toolkit for adding Agora Conversational AI features to apps already using Agora RTC and RTM. The library consumes existing RTC/RTM instances, adds agent state tracking, transcript parsing, and RTM-based messaging controls, and leaves token generation plus agent startup ownership to the host app.

## Install

Add the Maven repositories and the Conversational AI toolkit dependency:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.agora.agents:agora-agent-client-toolkit:<version>")
}
```

The toolkit expects the host app to provide Agora RTC and RTM SDK instances:

```kotlin
dependencies {
    implementation("io.agora.rtc:full-sdk:4.5.1")
    implementation("io.agora:agora-rtm-lite:2.2.6")
}
```

## Quick Start

Create the toolkit API with your existing RTC engine and RTM client:

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

Register event callbacks:

```kotlin
conversationalAIAPI.addHandler(object : IConversationalAIAPIEventHandler {
    override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
        // Render agent state.
    }

    override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
        // Render user or agent transcript.
    }

    override fun onAgentError(agentUserId: String, error: ModuleError) {
        // Handle agent-side errors.
    }
})
```

Load audio settings before joining RTC, then subscribe to the RTM message channel after RTC/RTM are ready:

```kotlin
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

See [conversational-ai/README.md](./conversational-ai/README.md) for the full component API guide.

## Maintainers

For Maven / AAR packaging, see [docs/publishing.md](./docs/publishing.md).

## License

MIT

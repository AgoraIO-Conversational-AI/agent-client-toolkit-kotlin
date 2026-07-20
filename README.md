# Agora Conversational AI Toolkit for Android

A client-side Android toolkit for adding Agora Conversational AI features to apps already using Agora RTC and RTM. The library consumes existing RTC/RTM instances, adds agent state tracking, transcript parsing, and RTM-based messaging controls, and leaves token generation plus agent startup ownership to the host app.

## Run The Android Demo

The demo uses a local Python FastAPI backend built on
[`agora-agents`](https://github.com/AgoraIO/agora-agents-python). A physical
Android phone is the primary development path because it gives a representative
microphone, speaker, echo cancellation, and network experience.

Prerequisites:

- Python 3.10 or newer
- Android Studio with Android SDK 36
- an Android phone and development machine on the same LAN
- an Agora project with RTC, RTM, and Conversational AI enabled

Configure the backend:

```bash
cp server/.env.example server/.env.local
```

Set `AGORA_APP_ID` and `AGORA_APP_CERTIFICATE` in `server/.env.local`, then run:

```bash
./scripts/start_backend.sh
```

On first use, the script creates `server/.venv` and installs the pinned Python
dependencies. It starts FastAPI on `0.0.0.0:8000`, detects the development
machine's active LAN IP, waits for `/health`, and updates only this Git-ignored
entry in the root `local.properties`:

```properties
agent.backend.url=http://<development-machine-lan-ip>:8000
```

Set `PORT` in `server/.env.local` or before the command to use another free
port. The script checks the selected port and waits for the backend health check
before updating `local.properties`.

The address is the current development machine's LAN address, not a fixed
`192.168.1.20`. `localhost` would point to the phone itself. No USB tunnel,
`adb reverse`, Gradle `-P` option, or in-app host setting is required.

Changing the backend host does not require an active USB connection. Rebuild
and install the app through Android Studio using either USB or wireless device
pairing when you are ready to run it.

Allow incoming Python connections in the development machine's firewall when
prompted. Then build and run `:app` on the phone from Android Studio.

Opening the printed backend URL with `/docs` shows the local API summary.
The Android app itself contacts the backend only after **Start Agent** is
tapped, so simply launching the Activity produces no backend request.

Only debug builds allow cleartext HTTP for this local LAN workflow. Release
builds keep cleartext disabled. The fallback `http://10.0.2.2:8000` value exists
only so builds remain self-contained when `local.properties` has not been
generated; it is not the physical-device path.

Backend details and tests are in [server/README.md](./server/README.md). The
migration contract and verification status are in
[docs/python-backend-migration.md](./docs/python-backend-migration.md).

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
    implementation("io.agora:agora-rtm-lite:2.2.3")
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

Register your event handler. A typical handler starts with callbacks like:

```kotlin
conversationalAIAPI.addHandler(object : IConversationalAIAPIEventHandler {
    override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
        // Existing aggregate-state integrations remain supported.
    }
    override fun onAgentListeningChanged(agentUserId: String, isListening: Boolean) {}
    override fun onAgentThinkingChanged(agentUserId: String, isThinking: Boolean) {}
    override fun onAgentSpeakingChanged(agentUserId: String, isSpeaking: Boolean) {}

    override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
        // Render user or agent transcript.
    }

    override fun onAgentError(agentUserId: String, error: ModuleError) {
        // Handle agent-side errors.
    }

    // Implement the remaining required callbacks for your app.
})
```

The aggregate `onAgentStateChanged` callback is deprecated but remains supported
and continues to be delivered. Existing integrations do not need to migrate. Use
the independent callbacks when multiple activity flags are needed.

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

See [conversational-ai/README.md](./conversational-ai/README.md) for the
complete event handler interface and component API guide.

## Manual SOS/EOS

If the agent is started with manual turn detection, use the toolkit to publish
manual speech markers through RTM:

```kotlin
conversationalAIAPI.manualSOS(agentUserId) { requestId, error ->
    // error is null when RTM publish succeeds.
    // requestId is generated by the toolkit and sent as request_id.
}

conversationalAIAPI.manualEOS(agentUserId) { requestId, error ->
    // error is null when RTM publish succeeds.
    // requestId is generated by the toolkit and sent as request_id.
}
```

The server processing results are delivered through `onUserManualSosEvent` and
`onUserManualEosEvent`. The toolkit only sends the RTM marker and returns the
generated `requestId`; the host app still owns the agent start request and the
choice of SOS / EOS detection mode.

## Maintainers

For release notes, see [CHANGELOG.md](./CHANGELOG.md).
For Maven / AAR packaging, see [docs/publishing.md](./docs/publishing.md).

## License

MIT

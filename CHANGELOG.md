# Changelog

All notable changes to the Android Agora Conversational AI Toolkit will be documented in this file.

The format follows Keep a Changelog style. This first public release establishes the compatibility baseline for future Android releases.

## [2.10.1] - Unreleased

### Changed

- Defaulted on-device AINS to disabled in `loadAudioSettings`. Applications can
  explicitly enable it with `enableAins = true`; audio route changes retain the
  selected value. Existing calls remain supported and disable AINS.

## [2.10.0] - 2026-08-13

### Added

- Added RTM `speak(...)` and `think(...)` APIs for direct TTS broadcasts and
  per-state LLM instructions.
- Added `SpeakMessage`, `ThinkMessage`, and type-safe listening, thinking, and
  speaking action enums. `SpeakMessage` reuses the existing `Priority` enum.

### Changed

- Deprecated the aggregate `onAgentStateChanged(...)` callback. Use
  `onAgentListeningChanged(...)`, `onAgentThinkingChanged(...)`, and
  `onAgentSpeakingChanged(...)` to track independent activity states. The
  aggregate callback remains supported and continues to be delivered.
- Removed release candidate support from Maven upload zip generation.

## [2.9.0] - 2026-07-02

Initial public release.

### Added

- Published `io.agora.agents:agora-agent-client-toolkit:2.9.0` as the Android ConvoAI client toolkit artifact.
- Added `IConversationalAIAPI` for host apps that already manage Agora RTC and RTM engine instances.
- Added transcript parsing and rendering support through `TranscriptRenderMode`, `Transcript`, `TranscriptType`, and `TranscriptStatus`.
- Added agent state callbacks for state, listening, thinking, speaking, interrupt, metrics, turn-finished latency, message receipt, message error, module error, and voiceprint status events.
- Added text and image message publishing through `chat(...)` with `TextMessage` and `ImageMessage`.
- Added direct conversation control APIs: `interrupt(...)`, `manualSOS(...)`, and `manualEOS(...)`.
- Added manual turn result callbacks: `onUserManualSosEvent(...)`, `onUserManualEosEvent(...)`, and `onAgentManualEosEvent(...)`.
- Added maintainer packaging support for Maven / AAR release bundles.

### Compatibility

- Android library artifact: `io.agora.agents:agora-agent-client-toolkit`.
- Minimum SDK follows the repo Android configuration.
- Java/Kotlin target: JVM 17.
- Agora RTC and RTM are `compileOnly` dependencies; host apps must provide compatible RTC and RTM SDK instances.
- Supported public protocol events include `message.metrics`, `turn.finished`, `message.error`, `message.info`, `message.sal_status`, `assistant.transcription`, `user.transcription`, `message.interrupt`, `message.state`, `user.manual_sos.result`, `user.manual_eos.result`, and `assistant.manual_eos.result`.

### Known Limitations

- The toolkit does not create agents, generate app credentials, generate tokens, or own backend start/stop flows. Host apps must provide those flows.
- Shared cross-platform JSON fixture tests are planned but not part of this first release baseline.

# Conversational AI Quickstart — Android Kotlin

## Overview

This sample shows how to integrate Agora Conversational AI into an Android app for real-time voice conversation with an AI agent.

It demonstrates:

- real-time audio interaction through Agora RTC
- message and state synchronization through Agora RTM
- live transcript rendering for user and agent turns
- connection, agent, mute, and transcript state management
- automatic RTC join, RTM login, and agent startup flow
- a single-page UI with logs, status, transcripts, and controls

## Use Cases

- AI voice customer support
- voice assistant apps
- real-time voice transcription
- voice-enabled games
- interactive education or training apps

## Prerequisites

- Android SDK API level 26 or later
- Agora developer account: [Console](https://console.shengwang.cn/)
- RTM enabled in the Agora Console
- Agora App ID and App Certificate

## Quick Start

### 1. Clone the project

```bash
git clone https://github.com/AgoraIO-Community/conversational-ai-quickstart-native.git
cd conversational-ai-quickstart-native/android-kotlin
```

### 2. Configure the Android project

Open the project in Android Studio.

Copy `env.example.properties` to `env.properties`:

```bash
cp env.example.properties env.properties
```

Fill in your Agora credentials:

```properties
APP_ID=your_agora_app_id
APP_CERTIFICATE=your_agora_app_certificate
```

Configuration fields:

- `APP_ID`: required Agora App ID
- `APP_CERTIFICATE`: required App Certificate for token generation and REST API authentication

The current RESTful startup request only needs Agora project credentials. LLM and TTS are no longer configured from the client request, so no third-party keys are required.

`AgentStarter.buildJsonPayload()` currently uses:

- ASR preset: `deepgram_nova_3`

Before using the Conversational AI engine, create an Agora project and enable the Conversational AI service in the Agora Console. See [Enable service](https://doc.shengwang.cn/doc/convoai/restful/get-started/enable-service).

Notes:

- `env.properties` contains sensitive values and must not be committed.
- Each app launch generates a random channel name in the format `channel_kotlin_<6-digit-random>`.
- `TokenGenerator.kt` is demo-only. Production apps must generate tokens from a backend service.
- The build currently validates only `APP_ID` and `APP_CERTIFICATE`.

Wait for Gradle sync to finish after configuration.

### 3. Start the agent

No extra setup is required for the demo flow. The Android app directly calls the Agora RESTful API to start the agent.

Requirements:

- `APP_ID` and `APP_CERTIFICATE` must be configured in `env.properties`.

This mode is intended for:

- quick evaluation
- feature verification
- local development without an additional backend

Important:

- This client-side flow is for demo and development only. It is not recommended for production.
- `APP_CERTIFICATE` is packaged into the client and sent to the demo token service, so it can leak.

Production requirements:

- Store sensitive values such as `appCertificate` on your backend.
- Let the client request tokens from your backend.
- Let the client request agent startup through your backend, and have the backend call the Agora RESTful API.
- See `../server-python/agora_http_server.py` for a reference backend implementation.

## Key Files

- `AgentChatActivity.kt`: main screen with logs, agent status, transcript bubbles, and control buttons
- `AgentChatViewModel.kt`: business logic for RTC, RTM, token generation, agent startup, and UI state
- `AgentStarter.kt`: agent start/stop REST API wrapper using `Authorization: agora token=<token>`
- `TokenGenerator.kt`: demo-only token generator; production must use a backend
- `io/agora/convoai/convoaiApi/`: Agora ConversationalAIAPI wrapper; do not modify directly

## License

See [LICENSE](./LICENSE).

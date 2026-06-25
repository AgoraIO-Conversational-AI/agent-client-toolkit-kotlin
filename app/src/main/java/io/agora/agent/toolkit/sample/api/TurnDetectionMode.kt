package io.agora.agent.toolkit.sample.api

enum class TurnDetectionMode(
    val displayName: String,
    val value: String
) {
    VAD(
        displayName = "VAD",
        value = "vad"
    ),
    SEMANTIC(
        displayName = "Semantic",
        value = "semantic"
    ),
    MANUAL(
        displayName = "Manual",
        value = "manual"
    );
}

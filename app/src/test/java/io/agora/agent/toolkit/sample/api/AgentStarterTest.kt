package io.agora.agent.toolkit.sample.api

import io.agora.agent.toolkit.sample.KeyCenter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentStarterTest {
    @Test
    fun apiBaseUrl_usesProductionConversationalAiEndpoint() {
        val field = AgentStarter::class.java.getDeclaredField("API_BASE_URL")
        field.isAccessible = true

        assertEquals(
            "https://api.agora.io/api/conversational-ai-agent/v2/projects",
            field.get(AgentStarter)
        )
    }

    @Test
    fun buildJsonPayload_usesServerDefaultAsrAndExplicitLlmTtsConfiguration() {
        val payload = buildPayload()

        assertFalse(payload.has("preset"))

        val properties = payload.getJSONObject("properties")
        val llm = properties.getJSONObject("llm")
        val tts = properties.getJSONObject("tts")

        assertFalse(properties.has("asr"))
        assertEquals(KeyCenter.LLM_URL, llm.getString("url"))
        assertEquals(KeyCenter.LLM_API_KEY, llm.getString("api_key"))
        assertEquals(KeyCenter.LLM_MODEL, llm.getJSONObject("params").getString("model"))
        assertEquals(
            "hello man, I am an AI robot, I can do anything for you",
            llm.getString("greeting_message")
        )
        assertEquals(
            "Sorry, I don't know how to answer your question",
            llm.getString("failure_message")
        )
        assertFalse(llm.has("style"))
        assertEquals(KeyCenter.TTS_VENDOR, tts.getString("vendor"))
        assertEquals(KeyCenter.TTS_KEY, tts.getJSONObject("params").getString("key"))
        assertEquals(KeyCenter.TTS_MODEL_ID, tts.getJSONObject("params").getString("model_id"))
        assertEquals(KeyCenter.TTS_VOICE_ID, tts.getJSONObject("params").getString("voice_id"))
        assertEquals(KeyCenter.TTS_SAMPLE_RATE, tts.getJSONObject("params").getInt("sample_rate"))
        assertFalse(tts.getJSONObject("params").has("region"))
        assertFalse(tts.getJSONObject("params").has("voice_name"))
        assertFalse(tts.getJSONObject("params").has("speed"))
        assertFalse(tts.getJSONObject("params").has("volume"))
    }

    @Test
    fun buildJsonPayload_usesDefaultTurnDetectionShape() {
        val payload = buildPayload()

        val properties = payload.getJSONObject("properties")
        val turnDetection = properties.getJSONObject("turn_detection")
        val config = turnDetection.getJSONObject("config")
        val startOfSpeech = config.getJSONObject("start_of_speech")
        val endOfSpeech = config.getJSONObject("end_of_speech")
        val parameters = properties.getJSONObject("parameters")

        assertEquals("default", turnDetection.getString("mode"))
        assertFalse(config.has("speech_threshold"))
        assertTurnModeOnly(startOfSpeech, "vad")
        assertTurnModeOnly(endOfSpeech, "semantic")
        assertFalse(parameters.has("sos_eos"))
        assertFalse(parameters.has("silence_config"))
        assertFalse(parameters.has("farewell_config"))
    }

    @Test
    fun buildJsonPayload_usesIndependentManualEosShape() {
        val payload = buildPayload(eosDetectionMode = TurnDetectionMode.MANUAL)

        val properties = payload.getJSONObject("properties")
        val turnDetection = properties.getJSONObject("turn_detection")
        val config = turnDetection.getJSONObject("config")
        val startOfSpeech = config.getJSONObject("start_of_speech")
        val endOfSpeech = config.getJSONObject("end_of_speech")
        val parameters = properties.getJSONObject("parameters")

        assertEquals("default", turnDetection.getString("mode"))
        assertFalse(config.has("speech_threshold"))
        assertTurnModeOnly(startOfSpeech, "vad")
        assertTurnModeOnly(endOfSpeech, "manual")
        assertFalse(parameters.has("sos_eos"))
        assertFalse(parameters.has("silence_config"))
        assertFalse(parameters.has("farewell_config"))
    }

    @Test
    fun buildJsonPayload_usesIndependentManualSosShape() {
        val payload = buildPayload(
            sosDetectionMode = TurnDetectionMode.MANUAL,
            eosDetectionMode = TurnDetectionMode.SEMANTIC
        )

        val properties = payload.getJSONObject("properties")
        val turnDetection = properties.getJSONObject("turn_detection")
        val config = turnDetection.getJSONObject("config")
        val startOfSpeech = config.getJSONObject("start_of_speech")
        val endOfSpeech = config.getJSONObject("end_of_speech")
        val parameters = properties.getJSONObject("parameters")

        assertEquals("default", turnDetection.getString("mode"))
        assertFalse(config.has("speech_threshold"))
        assertTurnModeOnly(startOfSpeech, "manual")
        assertTurnModeOnly(endOfSpeech, "semantic")
        assertFalse(parameters.has("sos_eos"))
        assertFalse(parameters.has("silence_config"))
        assertFalse(parameters.has("farewell_config"))
    }

    @Test
    fun buildJsonPayload_supportsSemanticSosAndVadEosShape() {
        val payload = buildPayload(
            sosDetectionMode = TurnDetectionMode.SEMANTIC,
            eosDetectionMode = TurnDetectionMode.VAD
        )

        val properties = payload.getJSONObject("properties")
        val config = properties.getJSONObject("turn_detection").getJSONObject("config")
        val startOfSpeech = config.getJSONObject("start_of_speech")
        val endOfSpeech = config.getJSONObject("end_of_speech")

        assertFalse(config.has("speech_threshold"))
        assertTurnModeOnly(startOfSpeech, "semantic")
        assertTurnModeOnly(endOfSpeech, "vad")
    }

    private fun assertTurnModeOnly(config: JSONObject, mode: String) {
        assertEquals(mode, config.getString("mode"))
        assertFalse(config.has("model"))
        assertFalse(config.has("vad_config"))
        assertFalse(config.has("semantic_config"))
    }

    private fun buildPayload(
        sosDetectionMode: TurnDetectionMode = TurnDetectionMode.VAD,
        eosDetectionMode: TurnDetectionMode = TurnDetectionMode.SEMANTIC
    ): JSONObject {
        val method = AgentStarter::class.java.getDeclaredMethod(
            "buildJsonPayload",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            TurnDetectionMode::class.java,
            TurnDetectionMode::class.java
        )
        method.isAccessible = true

        return method.invoke(
            AgentStarter,
            "channel_kotlin_123456",
            "channel_kotlin_123456",
            "123456",
            "token-value",
            listOf("654321"),
            sosDetectionMode,
            eosDetectionMode
        ) as JSONObject
    }
}

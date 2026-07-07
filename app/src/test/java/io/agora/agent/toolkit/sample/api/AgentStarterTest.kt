package io.agora.agent.toolkit.sample.api

import io.agora.agent.toolkit.sample.KeyCenter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun buildJsonPayload_usesExplicitAsrLlmTtsConfiguration() {
        val payload = buildPayload()

        assertFalse(payload.has("preset"))

        val properties = payload.getJSONObject("properties")
        val asr = properties.getJSONObject("asr")
        val llm = properties.getJSONObject("llm")
        val tts = properties.getJSONObject("tts")

        assertEquals(KeyCenter.ASR_VENDOR, asr.getString("vendor"))
        assertFalse(asr.has("language"))
        assertEquals(KeyCenter.ASR_API_KEY, asr.getJSONObject("params").getString("api_key"))
        assertEquals(KeyCenter.ASR_MODEL, asr.getJSONObject("params").getString("model"))
        assertFalse(asr.getJSONObject("params").has("language_hints"))
        assertFalse(asr.getJSONObject("params").has("key"))
        assertFalse(asr.getJSONObject("params").has("language"))
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
        assertEquals(0.6, config.getDouble("speech_threshold"), 0.0)
        assertEquals("vad", startOfSpeech.getString("mode"))
        assertEquals("semantic", endOfSpeech.getString("mode"))
        assertFalse(startOfSpeech.has("model"))
        assertFalse(endOfSpeech.has("model"))
        assertEquals(500, startOfSpeech.getJSONObject("vad_config").getInt("interrupt_duration_ms"))
        assertEquals(300, startOfSpeech.getJSONObject("vad_config").getInt("speaking_interrupt_duration_ms"))
        assertEquals(800, startOfSpeech.getJSONObject("vad_config").getInt("prefix_padding_ms"))
        assertFalse(startOfSpeech.has("semantic_config"))
        assertFalse(startOfSpeech.getJSONObject("vad_config").has("ignored_words"))
        assertEquals(480, endOfSpeech.getJSONObject("semantic_config").getInt("silence_duration_ms"))
        assertEquals(1200, endOfSpeech.getJSONObject("semantic_config").getInt("max_wait_ms"))
        assertFalse(endOfSpeech.getJSONObject("semantic_config").getBoolean("pause_state_enabled"))
        assertFalse(endOfSpeech.has("vad_config"))
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
        assertEquals(0.6, config.getDouble("speech_threshold"), 0.0)
        assertEquals("vad", startOfSpeech.getString("mode"))
        assertEquals("manual", endOfSpeech.getString("mode"))
        assertFalse(startOfSpeech.has("model"))
        assertFalse(endOfSpeech.has("model"))
        assertEquals(500, startOfSpeech.getJSONObject("vad_config").getInt("interrupt_duration_ms"))
        assertEquals(300, startOfSpeech.getJSONObject("vad_config").getInt("speaking_interrupt_duration_ms"))
        assertEquals(800, startOfSpeech.getJSONObject("vad_config").getInt("prefix_padding_ms"))
        assertFalse(startOfSpeech.has("semantic_config"))
        assertFalse(startOfSpeech.getJSONObject("vad_config").has("ignored_words"))
        assertFalse(endOfSpeech.has("vad_config"))
        assertFalse(endOfSpeech.has("semantic_config"))
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
        assertEquals(0.6, config.getDouble("speech_threshold"), 0.0)
        assertEquals("manual", startOfSpeech.getString("mode"))
        assertEquals("semantic", endOfSpeech.getString("mode"))
        assertFalse(startOfSpeech.has("model"))
        assertFalse(endOfSpeech.has("model"))
        assertFalse(startOfSpeech.has("vad_config"))
        assertFalse(startOfSpeech.has("semantic_config"))
        assertFalse(startOfSpeech.has("keywords_config"))
        assertFalse(startOfSpeech.has("disabled_config"))
        assertFalse(endOfSpeech.has("vad_config"))
        assertEquals(480, endOfSpeech.getJSONObject("semantic_config").getInt("silence_duration_ms"))
        assertEquals(1200, endOfSpeech.getJSONObject("semantic_config").getInt("max_wait_ms"))
        assertFalse(endOfSpeech.getJSONObject("semantic_config").getBoolean("pause_state_enabled"))
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

        assertEquals("semantic", startOfSpeech.getString("mode"))
        assertEquals(200, startOfSpeech.getJSONObject("semantic_config").getInt("interrupt_duration_ms"))
        assertFalse(startOfSpeech.has("vad_config"))
        assertEquals("vad", endOfSpeech.getString("mode"))
        assertEquals(660, endOfSpeech.getJSONObject("vad_config").getInt("silence_duration_ms"))
        assertFalse(endOfSpeech.has("semantic_config"))
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

package io.agora.agent.toolkit.sample.api

import io.agora.agent.toolkit.sample.KeyCenter
import io.agora.agent.toolkit.sample.api.net.SecureOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent Starter
 *
 * Starts/stops Conversational AI agents via Agora REST API.
 * Uses HTTP token auth mode: Authorization header is "agora token=<convoai_token>".
 * Pipeline configuration is read from env.properties through KeyCenter.
 */
object AgentStarter {
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    private const val API_BASE_URL =
        "https://api.agora.io/api/conversational-ai-agent/v2/projects"
    private const val DEFAULT_LLM_GREETING_MESSAGE =
        "hello man, I am an AI robot, I can do anything for you"
    private const val DEFAULT_LLM_FAILURE_MESSAGE =
        "Sorry, I don't know how to answer your question"

    private val okHttpClient: OkHttpClient by lazy {
        SecureOkHttpClient.create()
            .build()
    }

    /**
     * Start an agent with inline RESTful request configuration.
     *
     * @param channelName Channel name for the agent
     * @param agentRtcUid Agent RTC UID
     * @param agentToken Token for the agent to join the RTC channel
     * @param authToken Agora token for REST API authorization
     * @param remoteRtcUid Current user RTC UID that the agent should subscribe to
     * @param sosDetectionMode Start-of-speech detection mode
     * @param eosDetectionMode End-of-speech detection mode
     * @return Result containing agentId or exception
     */
    suspend fun startAgentAsync(
        channelName: String,
        agentRtcUid: String,
        agentToken: String,
        authToken: String,
        remoteRtcUid: String,
        sosDetectionMode: TurnDetectionMode,
        eosDetectionMode: TurnDetectionMode
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val projectId = KeyCenter.APP_ID
            val url = "$API_BASE_URL/$projectId/join"

            val requestBody = buildJsonPayload(
                name = channelName,
                channel = channelName,
                agentRtcUid = agentRtcUid,
                token = agentToken,
                remoteRtcUids = listOf(remoteRtcUid),
                sosDetectionMode = sosDetectionMode,
                eosDetectionMode = eosDetectionMode
            )

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", JSON_MEDIA_TYPE)
                .addHeader("Authorization", "agora token=$authToken")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw RuntimeException("Start agent error: httpCode=${response.code}, httpMsg=$errorBody")
            }

            val body = response.body.string()
            val bodyJson = JSONObject(body)
            val agentId = bodyJson.optString("agent_id", "")

            if (agentId.isBlank()) {
                throw RuntimeException("Failed to parse agentId from response: $body")
            }

            Result.success(agentId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build JSON payload for the Agora Conversational AI REST API v2 request.
     * Matches the Agora Conversational AI REST API v2 format.
     */
    private fun buildJsonPayload(
        name: String,
        channel: String,
        agentRtcUid: String,
        token: String,
        remoteRtcUids: List<String>,
        sosDetectionMode: TurnDetectionMode,
        eosDetectionMode: TurnDetectionMode
    ): JSONObject {
        return JSONObject().apply {
            put("name", name)

            put("properties", JSONObject().apply {
                put("channel", channel)
                put("token", token)
                put("agent_rtc_uid", agentRtcUid)
                put("remote_rtc_uids", JSONArray(remoteRtcUids))
                put("enable_string_uid", false)
                put("idle_timeout", 120)

                // Advanced features
                put("advanced_features", JSONObject().apply {
                    put("enable_aivad", false)
                    put("enable_bhvs", true)
                    put("enable_sal", false)
                    put("enable_rtm", true)
                })

                put("asr", JSONObject().apply {
                    put("vendor", KeyCenter.ASR_VENDOR)
                    put("params", JSONObject().apply {
                        put("api_key", KeyCenter.ASR_API_KEY)
                        put("model", KeyCenter.ASR_MODEL)
                    })
                })

                put("tts", JSONObject().apply {
                    put("vendor", KeyCenter.TTS_VENDOR)
                    put("params", JSONObject().apply {
                        put("key", KeyCenter.TTS_KEY)
                        put("model_id", KeyCenter.TTS_MODEL_ID)
                        put("voice_id", KeyCenter.TTS_VOICE_ID)
                        put("sample_rate", KeyCenter.TTS_SAMPLE_RATE)
                    })
                })

                put("llm", JSONObject().apply {
                    put("url", KeyCenter.LLM_URL)
                    put("api_key", KeyCenter.LLM_API_KEY)
                    put("params", JSONObject().apply {
                        put("model", KeyCenter.LLM_MODEL)
                    })
                    put("greeting_message", DEFAULT_LLM_GREETING_MESSAGE)
                    put("failure_message", DEFAULT_LLM_FAILURE_MESSAGE)
                })

                // Parameters
                put("parameters", JSONObject().apply {
                    put("enable_metrics", true)
                    put("enable_error_message", true)
                    put("output_audio_codec", "OPUSFB")
                    put("audio_scenario", "default")
                    put("transcript", JSONObject().apply {
                        put("enable", true)
                        put("protocol_version", "v2")
                        put("enable_words", false)
                    })
                    put("data_channel", "rtm")
                })

                put("turn_detection", JSONObject().apply {
                    put("mode", "default")
                    put("config", JSONObject().apply {
                        put("speech_threshold", 0.6)
                        put("start_of_speech", JSONObject().apply {
                            put("mode", sosDetectionMode.value)
                            when (sosDetectionMode) {
                                TurnDetectionMode.VAD -> {
                                    put("vad_config", JSONObject().apply {
                                        put("interrupt_duration_ms", 500)
                                        put("speaking_interrupt_duration_ms", 300)
                                        put("prefix_padding_ms", 800)
                                    })
                                }
                                TurnDetectionMode.SEMANTIC -> {
                                    put("semantic_config", JSONObject().apply {
                                        put("interrupt_duration_ms", 200)
                                        put("prefix_padding_ms", 920)
                                        put("speaking_interrupt_duration_ms", 350)
                                        put("ignored_words", JSONArray(listOf("uh-huh", "okay")))
                                    })
                                }
                                TurnDetectionMode.MANUAL -> Unit
                            }
                        })
                        put("end_of_speech", JSONObject().apply {
                            put("mode", eosDetectionMode.value)
                            when (eosDetectionMode) {
                                TurnDetectionMode.VAD -> {
                                    put("vad_config", JSONObject().apply {
                                        put("silence_duration_ms", 660)
                                    })
                                }
                                TurnDetectionMode.SEMANTIC -> {
                                    put("semantic_config", JSONObject().apply {
                                        put("silence_duration_ms", 480)
                                        put("max_wait_ms", 1200)
                                        put("pause_state_enabled", false)
                                    })
                                }
                                TurnDetectionMode.MANUAL -> Unit
                            }
                        })
                    })
                })
            })
        }
    }

    /**
     * Stop an agent
     *
     * @param agentId Agent ID to stop
     * @param authToken Agora token for REST API authorization
     * @return Result containing success or exception
     */
    suspend fun stopAgentAsync(
        agentId: String,
        authToken: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val projectId = KeyCenter.APP_ID
            val url = "$API_BASE_URL/$projectId/agents/$agentId/leave"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "agora token=$authToken")
                .post("".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw RuntimeException("Stop agent error: httpCode=${response.code}, httpMsg=$errorBody")
            }

            response.body.close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

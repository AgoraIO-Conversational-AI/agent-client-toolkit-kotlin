package io.agora.agent.toolkit.sample.api

import io.agora.agent.toolkit.sample.KeyCenter
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
 * This auth mode requires APP_CERTIFICATE to be enabled in the agora console.
 * Pipeline configuration is supplied inline in the request body.
 */
object AgentStarter {
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    private const val API_BASE_URL = "https://api.agora.io/api/conversational-ai-agent/v2/projects"

    private val okHttpClient: OkHttpClient by lazy {
        _root_ide_package_.io.agora.agent.toolkit.sample.api.net.SecureOkHttpClient.create()
            .build()
    }
    /**
     * Start an agent with inline RESTful request configuration.
     *
     * @param channelName Channel name for the agent
     * @param agentRtcUid Agent RTC UID
     * @param agentToken Token for the agent to join the RTC channel
     * @param authToken Agora token for REST API authorization (requires APP_CERTIFICATE enabled)
     * @param remoteRtcUid Current user RTC UID that the agent should subscribe to
     * @return Result containing agentId or exception
     */
    suspend fun startAgentAsync(
        channelName: String,
        agentRtcUid: String,
        agentToken: String,
        authToken: String,
        remoteRtcUid: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val projectId = KeyCenter.APP_ID
            val url = "$API_BASE_URL/$projectId/join"

            val requestBody = buildJsonPayload(
                name = channelName,
                channel = channelName,
                agentRtcUid = agentRtcUid,
                token = agentToken,
                remoteRtcUids = listOf(remoteRtcUid)
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
        remoteRtcUids: List<String>
    ): JSONObject {
        return JSONObject().apply {
            put("name", name)

            put("preset", "deepgram_nova_3")

            put("properties", JSONObject().apply {
                put("channel", channel)
                put("token", token)
                put("agent_rtc_uid", agentRtcUid)
                put("remote_rtc_uids", JSONArray(remoteRtcUids))
                put("enable_string_uid", false)
                put("idle_timeout", 120)

                // Advanced features
                put("advanced_features", JSONObject().apply {
                    put("enable_rtm", true)
                })

                put("asr", JSONObject().apply {
                    put("language", "en")
                })

                // Parameters
                put("parameters", JSONObject().apply {
                    put("audio_scenario", "chorus")
                    put("data_channel", "rtm")
                    put("enable_error_message", true)
                    put("silence_config", JSONObject().apply {
                        put("action", "speak")
                        put("timeout_ms", 0)
                    })
                    put("farewell_config", JSONObject().apply {
                        put("graceful_enabled", "false")
                        put("graceful_timeout_seconds", 0)
                    })
                })

                put("turn_detection", JSONObject().apply {
                    put("mode","default")
                    put("config",JSONObject().apply {
                        put("speech_threshold", "0.6")
                        put("start_of_speech", JSONObject().apply {
                            put("model","vad")
                            put("vad_config",JSONObject().apply {
                                put("interrupt_duration_ms",500)
                                put("prefix_padding_ms",800)
                                put("speaking_interrupt_duration_ms",300)
                            })

                        })
                        put("end_of_speech", JSONObject().apply {
                            put("model","semantic")
                            put("semantic_config",JSONObject().apply {
                                put("max_wait_ms",1200)
                                put("pause_state_enabled",false)
                                put("silence_duration_ms",480)
                            })
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
     * @param authToken Agora token for REST API authorization (requires APP_CERTIFICATE enabled)
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

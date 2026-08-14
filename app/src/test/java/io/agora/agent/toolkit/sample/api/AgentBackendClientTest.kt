package io.agora.agent.toolkit.sample.api

import io.agora.agent.toolkit.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AgentBackendClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AgentBackendClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AgentBackendClient(
            backendUrl = server.url("/").toString(),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getConfig_encodesQueryAndParsesEnvelope() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"app_id":"app-id","token":"token-value","uid":"123456","agent_uid":"87654321","channel_name":"channel with space"},"msg":"success"}"""
            )
        )

        val config = client.getConfig("channel with space", 123456).getOrThrow()

        assertEquals("app-id", config.appId)
        assertEquals(123456, config.userUid)
        assertEquals(87654321, config.agentUid)
        assertEquals("channel with space", config.channelName)
        val request = server.takeRequest()
        assertEquals("/get_config", request.requestUrl?.encodedPath)
        assertEquals("channel with space", request.requestUrl?.queryParameter("channel"))
        assertEquals("123456", request.requestUrl?.queryParameter("uid"))
    }

    @Test
    fun startAgent_sendsIndependentTurnModesAndReturnsAgentId() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"agent_id":"agent-1"},"msg":"success"}"""
            )
        )

        val agentId = client.startAgent(
            channelName = "channel-test",
            agentUid = 87654321,
            userUid = 123456,
            sosDetectionMode = TurnDetectionMode.MANUAL,
            eosDetectionMode = TurnDetectionMode.SEMANTIC,
        ).getOrThrow()

        assertEquals("agent-1", agentId)
        val request = server.takeRequest()
        assertEquals("/startAgent", request.requestUrl?.encodedPath)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(87654321, body.getInt("agentUid"))
        assertEquals(123456, body.getInt("userUid"))
        assertEquals("manual", body.getString("startOfSpeechMode"))
        assertEquals("semantic", body.getString("endOfSpeechMode"))
        assertFalse(body.has("token"))
    }

    @Test
    fun startAgent_cancellationStopsAgentFromLateResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(300, TimeUnit.MILLISECONDS)
                .setBody("""{"code":0,"data":{"agent_id":"agent-late"},"msg":"success"}""")
        )
        server.enqueue(
            MockResponse().setBody("""{"code":0,"data":null,"msg":"success"}""")
        )

        val startJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.startAgent(
                channelName = "channel-test",
                agentUid = 87654321,
                userUid = 123456,
                sosDetectionMode = TurnDetectionMode.VAD,
                eosDetectionMode = TurnDetectionMode.SEMANTIC,
            )
        }

        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        startJob.cancelAndJoin()

        val stopRequest = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(stopRequest)
        assertEquals("/stopAgent", stopRequest?.requestUrl?.encodedPath)
        assertEquals("agent-late", JSONObject(stopRequest!!.body.readUtf8()).getString("agentId"))
    }

    @Test
    fun getConfig_rethrowsCancellationException() {
        val cancellingClient = AgentBackendClient(
            backendUrl = server.url("/").toString(),
            client = OkHttpClient.Builder()
                .addInterceptor { throw CancellationException("cancelled") }
                .build(),
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                cancellingClient.getConfig("channel-test", 123456)
            }
        }
    }

    @Test
    fun httpFailure_preservesSafeBackendMessage() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setBody("""{"code":502,"data":null,"msg":"Failed to start agent"}""")
        )

        val error = client.startAgent(
            "channel-test",
            87654321,
            123456,
            TurnDetectionMode.VAD,
            TurnDetectionMode.SEMANTIC,
        ).exceptionOrNull()

        assertTrue(error is AgentBackendException)
        assertTrue(error?.message?.contains("Failed to start agent") == true)
        assertEquals(502, (error as AgentBackendException).httpCode)
    }

    @Test
    fun nonZeroEnvelopeCodeFailsEvenForHttp200() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":1001,"data":null,"msg":"Agent is unavailable"}"""
            )
        )

        val error = client.stopAgent("agent-1").exceptionOrNull()

        assertTrue(error is AgentBackendException)
        assertEquals(1001, (error as AgentBackendException).backendCode)
    }

    @Test
    fun stopAgent_sendsOnlyRuntimeAgentId() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":0,"data":null,"msg":"success"}""")
        )

        client.stopAgent("agent-1").getOrThrow()

        val requestBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("agent-1", requestBody.getString("agentId"))
        assertEquals(1, requestBody.length())
    }

    @Test
    fun buildConfig_exposesBackendUrlWithoutClientCredentials() {
        val fields = BuildConfig::class.java.declaredFields.map { it.name }

        assertTrue(fields.contains("AGENT_BACKEND_URL"))
        assertFalse(fields.contains("APP_ID"))
        assertFalse(fields.contains("APP_CERTIFICATE"))
        assertFalse(fields.any { it.startsWith("LLM_") || it.startsWith("TTS_") })
    }
}

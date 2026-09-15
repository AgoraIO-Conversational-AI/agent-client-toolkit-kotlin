package io.agora.conversational.api

import io.agora.rtc2.Constants
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSettingsApiTest {
    @Test
    fun callerCanUseLegacyCallsAndExplicitAinsWithDefaultOrCustomScenario() {
        val calls = mutableListOf<List<Any?>>()
        val api = Proxy.newProxyInstance(
            IConversationalAIAPI::class.java.classLoader,
            arrayOf(IConversationalAIAPI::class.java)
        ) { _, _, args ->
            calls.add(args?.toList().orEmpty())
            null
        } as IConversationalAIAPI

        api.loadAudioSettings()
        api.loadAudioSettings(Constants.AUDIO_SCENARIO_DEFAULT)
        api.loadAudioSettings(enableAins = true)
        api.loadAudioSettings(Constants.AUDIO_SCENARIO_DEFAULT, true)
        api.loadAudioSettings(enableAins = false)

        assertEquals(
            listOf(
                listOf(Constants.AUDIO_SCENARIO_AI_CLIENT),
                listOf(Constants.AUDIO_SCENARIO_DEFAULT),
                listOf(Constants.AUDIO_SCENARIO_AI_CLIENT, true),
                listOf(Constants.AUDIO_SCENARIO_DEFAULT, true),
                listOf(Constants.AUDIO_SCENARIO_AI_CLIENT, false)
            ),
            calls
        )
    }
}

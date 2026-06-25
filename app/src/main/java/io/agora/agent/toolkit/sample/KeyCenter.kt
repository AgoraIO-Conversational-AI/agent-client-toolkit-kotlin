package io.agora.agent.toolkit.sample

import io.agora.agent.toolkit.BuildConfig

object KeyCenter {
    // Agora App Credentials
    val APP_ID: String = BuildConfig.APP_ID
    val APP_CERTIFICATE: String = BuildConfig.APP_CERTIFICATE
    val TOOLBOX_SERVER_HOST: String = BuildConfig.TOOLBOX_SERVER_HOST

    val ASR_VENDOR: String = BuildConfig.ASR_VENDOR
    val ASR_API_KEY: String = BuildConfig.ASR_API_KEY
    val ASR_MODEL: String = BuildConfig.ASR_MODEL

    val LLM_URL: String = BuildConfig.LLM_URL
    val LLM_API_KEY: String = BuildConfig.LLM_API_KEY
    val LLM_MODEL: String = BuildConfig.LLM_MODEL

    val TTS_VENDOR: String = BuildConfig.TTS_VENDOR
    val TTS_KEY: String = BuildConfig.TTS_KEY
    val TTS_MODEL_ID: String = BuildConfig.TTS_MODEL_ID
    val TTS_VOICE_ID: String = BuildConfig.TTS_VOICE_ID
    val TTS_SAMPLE_RATE: Int = BuildConfig.TTS_SAMPLE_RATE
}

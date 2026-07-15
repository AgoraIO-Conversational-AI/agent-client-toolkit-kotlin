package io.agora.agent.toolkit.sample.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.agora.agent.toolkit.sample.AgentApp
import io.agora.agent.toolkit.sample.KeyCenter
import io.agora.agent.toolkit.sample.api.AgentStarter
import io.agora.agent.toolkit.sample.api.TokenGenerator
import io.agora.agent.toolkit.sample.api.TurnDetectionMode
import io.agora.conversational.api.AgentManualEosEvent
import io.agora.conversational.api.AgentState
import io.agora.conversational.api.ChatMessage
import io.agora.conversational.api.ConversationalAIAPIConfig
import io.agora.conversational.api.ConversationalAIAPIImpl
import io.agora.conversational.api.IConversationalAIAPI
import io.agora.conversational.api.IConversationalAIAPIEventHandler
import io.agora.conversational.api.ImageMessage
import io.agora.conversational.api.InterruptEvent
import io.agora.conversational.api.MessageError
import io.agora.conversational.api.MessageReceipt
import io.agora.conversational.api.Metric
import io.agora.conversational.api.ModuleError
import io.agora.conversational.api.Priority
import io.agora.conversational.api.StateChangeEvent
import io.agora.conversational.api.TextMessage
import io.agora.conversational.api.Transcript
import io.agora.conversational.api.TranscriptType
import io.agora.conversational.api.Turn
import io.agora.conversational.api.UserManualEosEvent
import io.agora.conversational.api.UserManualSosEvent
import io.agora.conversational.api.VoiceprintStateChangeEvent
import io.agora.rtc2.Constants
import io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER
import io.agora.rtc2.Constants.ERR_OK
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtm.ErrorInfo
import io.agora.rtm.LinkStateEvent
import io.agora.rtm.PresenceEvent
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.UUID

internal fun resolveAgentState(
    isListening: Boolean,
    isThinking: Boolean,
    isSpeaking: Boolean,
): AgentState = when {
    isSpeaking -> AgentState.SPEAKING
    isThinking -> AgentState.THINKING
    isListening -> AgentState.LISTENING
    else -> AgentState.SILENT
}

/**
 * ViewModel for managing conversation-related business logic
 */
class AgentChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "ConversationViewModel"
        val userId = (100000..999999).random()
        val agentUid: Int = generateUniqueUid(userId)

        /**
         * Generate a unique UID that doesn't conflict with the given uid
         */
        private fun generateUniqueUid(excludeUid: Int): Int {
            var uid: Int
            do {
                uid = (100000..999999).random()
            } while (uid == excludeUid)
            return uid
        }

        /**
         * Generate a random channel name
         */
        fun generateRandomChannelName(): String {
            return "channel_kotlin_${(100000..999999).random()}"
        }
    }

    /**
     * Connection state enum
     */
    enum class ConnectionState {
        Idle,
        Connecting,
        Connected
    }

    // UI State - shared between AgentHomeFragment and VoiceAssistantFragment
    data class ConversationUiState constructor(
        val isMuted: Boolean = false,
        val sosDetectionMode: TurnDetectionMode = TurnDetectionMode.VAD,
        val eosDetectionMode: TurnDetectionMode = TurnDetectionMode.SEMANTIC,
        val agentId: String? = null,
        // Connection state
        val connectionState: ConnectionState = ConnectionState.Idle
    ) {
        val isManualSosEnabled: Boolean
            get() = sosDetectionMode == TurnDetectionMode.MANUAL

        val isManualEosEnabled: Boolean
            get() = eosDetectionMode == TurnDetectionMode.MANUAL

        val isManualTurnDetectionEnabled: Boolean
            get() = isManualSosEnabled || isManualEosEnabled

        val canChangeTurnDetectionMode: Boolean
            get() = connectionState == ConnectionState.Idle
    }

    data class TurnLatencyMetrics(
        val turnId: Long,
        val e2eLatencyMs: Int?,
        val transportLatencyMs: Int?,
        val algorithmProcessingLatencyMs: Int?,
        val asrLatencyMs: Int?,
        val llmLatencyMs: Int?,
        val ttsLatencyMs: Int?,
    )

    data class TranscriptItem(
        val transcript: Transcript,
        val latencyMetrics: TurnLatencyMetrics? = null,
    )

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    // Transcript list - separate from UI state
    private val _transcriptList = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val transcriptList: StateFlow<List<TranscriptItem>> = _transcriptList.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.IDLE)
    val agentState: StateFlow<AgentState?> = _agentState.asStateFlow()
    private var isAgentListening = false
    private var isAgentThinking = false
    private var isAgentSpeaking = false

    // Debug log list - for displaying logs in UI
    private val _debugLogList = MutableStateFlow<List<String>>(emptyList())
    val debugLogList: StateFlow<List<String>> = _debugLogList.asStateFlow()

    private var unifiedToken: String? = null

    private var conversationalAIAPI: IConversationalAIAPI? = null

    private var channelName: String = ""

    private var rtcJoined = false
    private var rtmLoggedIn = false
    private var connectionAttemptId = 0

    // Agent management
    private var agentId: String? = null
    private var isStartingAgent = false
    // Auth token for REST API (app-credentials mode)
    private var authToken: String? = null

    // RTC and RTM instances
    private var rtcEngine: RtcEngineEx? = null
    private var rtmClient: RtmClient? = null
    private var isRtmLogin = false
    private var isLoggingIn = false
    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            viewModelScope.launch {
                if (channel != channelName || _uiState.value.connectionState != ConnectionState.Connecting) {
                    Log.d(TAG, "Ignore stale RTC join callback, channel: $channel, currentChannel: $channelName")
                    return@launch
                }
                rtcJoined = true
                addStatusLog("Rtc onJoinChannelSuccess, channel:${channel} uid:$uid")
                Log.d(TAG, "RTC joined channel: $channel, uid: $uid")
                checkJoinAndLoginComplete()
            }
        }

        override fun onLeaveChannel(stats: RtcStats?) {
            super.onLeaveChannel(stats)
            viewModelScope.launch {
                addStatusLog("Rtc onLeaveChannel")
            }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            viewModelScope.launch {
                addStatusLog("Rtc onUserJoined, uid:$uid")
                if (uid == agentUid) {
                    Log.d(TAG, "Agent joined the channel, uid: $uid")
                } else {
                    Log.d(TAG, "User joined the channel, uid: $uid")
                }
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            viewModelScope.launch {
                addStatusLog("Rtc onUserOffline, uid:$uid")
                if (uid == agentUid) {
                    Log.d(TAG, "Agent left the channel, uid: $uid, reason: $reason")
                } else {
                    Log.d(TAG, "User left the channel, uid: $uid, reason: $reason")
                }
            }
        }

        override fun onError(err: Int) {
            viewModelScope.launch {
                addStatusLog("Rtc onError: $err")
                Log.e(TAG, "RTC error: $err")
                handleTransportFailureDuringStartup(connectionAttemptId)
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            Log.d(TAG, "RTC onTokenPrivilegeWillExpire $channelName")
        }
    }

    // RTM event listener
    private val rtmEventListener = object : RtmEventListener {
        override fun onLinkStateEvent(event: LinkStateEvent?) {
            super.onLinkStateEvent(event)
            event ?: return

            Log.d(TAG, "Rtm link state changed: ${event.currentState}")

            when (event.currentState) {
                RtmConstants.RtmLinkState.CONNECTED -> {
                    Log.d(TAG, "Rtm connected successfully")
                    isRtmLogin = true
                    addStatusLog("Rtm connected successfully")
                }

                RtmConstants.RtmLinkState.FAILED -> {
                    Log.d(TAG, "RTM connection failed, need to re-login")
                    isRtmLogin = false
                    isLoggingIn = false
                    viewModelScope.launch {
                        addStatusLog("Rtm connected failed")
                        handleTransportFailureDuringStartup(connectionAttemptId)
                    }
                }

                else -> {
                    // nothing
                }
            }
        }

        override fun onTokenPrivilegeWillExpire(channelName: String) {
            Log.d(TAG, "RTM onTokenPrivilegeWillExpire $channelName")
        }

        override fun onPresenceEvent(event: PresenceEvent) {
            super.onPresenceEvent(event)
            // Handle presence events if needed
        }
    }

    private val conversationalAIAPIEventHandler = object : IConversationalAIAPIEventHandler {
        @Suppress("DEPRECATION")
        override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {}

        override fun onAgentListeningChanged(agentUserId: String, isListening: Boolean) {
            isAgentListening = isListening
            updateAgentActivityState()
        }

        override fun onAgentThinkingChanged(agentUserId: String, isThinking: Boolean) {
            isAgentThinking = isThinking
            updateAgentActivityState()
        }

        override fun onAgentSpeakingChanged(agentUserId: String, isSpeaking: Boolean) {
            isAgentSpeaking = isSpeaking
            updateAgentActivityState()
        }

        override fun onAgentInterrupted(agentUserId: String, event: InterruptEvent) {
            // Handle interruption

        }

        override fun onAgentMetrics(agentUserId: String, metric: Metric) {
            // Handle metrics
        }

        override fun onTurnFinished(
            agentUserId: String,
            turn: Turn
        ) {
            updateTurnLatencyMetrics(turn)
        }

        override fun onAgentError(agentUserId: String, error: ModuleError) {
            addStatusLog("Agent error: type=${error.type.value}, code=${error.code}, msg=${error.message}")
        }

        override fun onMessageError(agentUserId: String, error: MessageError) {
            addStatusLog(
                "Message error: type=${error.chatMessageType.value}, code=${error.code}, msg=${error.message}"
            )
        }

        override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
            // Handle transcript updates with typing animation for agent messages
            addTranscript(transcript)
        }

        override fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt) {
            addStatusLog(
                "Message receipt: type=${receipt.chatMessageType.value}, module=${receipt.type.value}, turn=${receipt.turnId}"
            )
        }

        override fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent) {
            // Update voice print state to notify Activity

        }

        override fun onUserManualSosEvent(agentUserId: String, event: UserManualSosEvent) {
            addStatusLog(ManualTurnDemoUi.formatUserResultLog(ManualTurnDemoUi.Action.SOS, event.payload))
        }

        override fun onUserManualEosEvent(agentUserId: String, event: UserManualEosEvent) {
            addStatusLog(ManualTurnDemoUi.formatUserResultLog(ManualTurnDemoUi.Action.EOS, event.payload))
        }

        override fun onAgentManualEosEvent(agentUserId: String, event: AgentManualEosEvent) {
            addStatusLog(ManualTurnDemoUi.formatAgentEosLog(event.payload))
        }

        override fun onDebugLog(log: String) {
            // Only log to system log, don't collect for UI display
            // UI will only show ViewModel status messages (statusMessage)
            Log.d("conversationalAIAPI", log)
        }
    }

    private fun updateAgentActivityState() {
        _agentState.value = resolveAgentState(
            isListening = isAgentListening,
            isThinking = isAgentThinking,
            isSpeaking = isAgentSpeaking,
        )
    }

    init {
        // Create RTC engine and RTM client during initialization
        Log.d(TAG, "Initializing RTC engine and RTM client...")
        // Init RTC engine
        initRtcEngine()
        // Init RTM client
        initRtmClient()
        if (rtcEngine != null && rtmClient != null) {
            conversationalAIAPI = ConversationalAIAPIImpl(
                ConversationalAIAPIConfig(
                    rtcEngine = rtcEngine!!,
                    rtmClient = rtmClient!!,
                    enableLog = true
                )
            )
            conversationalAIAPI?.loadAudioSettings(Constants.AUDIO_SCENARIO_AI_CLIENT)
            conversationalAIAPI?.addHandler(conversationalAIAPIEventHandler)
            Log.d(TAG, "RTC engine and RTM client created successfully")
        } else {
            Log.e(TAG, "Failed to create RTC engine or RTM client")
            addStatusLog("Failed to create RTC engine or RTM client")
        }
    }

    /**
     * Init RTC engine
     */
    private fun initRtcEngine() {
        if (rtcEngine != null) {
            return
        }
        val config = RtcEngineConfig()
        config.mContext = AgentApp.instance()
        config.mAppId = KeyCenter.APP_ID
        config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
        config.mAudioScenario = Constants.AUDIO_SCENARIO_DEFAULT
        config.mEventHandler = rtcEventHandler
        try {
            rtcEngine = (RtcEngine.create(config) as RtcEngineEx).apply {
                // load extension provider for AI-QoS
                loadExtensionProvider("ai_echo_cancellation_extension")
                loadExtensionProvider("ai_noise_suppression_extension")
            }
            Log.d(TAG, "initRtcEngine success")
            Log.d(TAG, "current sdk version: ${RtcEngine.getSdkVersion()}")
            addStatusLog("RtcEngine init successfully")
        } catch (e: Exception) {
            Log.e(TAG, "initRtcEngine error: $e")
            addStatusLog("RtcEngine init failed")
        }
    }

    /**
     * Init RTM client
     */
    private fun initRtmClient() {
        if (rtmClient != null) {
            return
        }

        val rtmConfig = RtmConfig.Builder(KeyCenter.APP_ID, userId.toString()).build()
        try {
            rtmClient = RtmClient.create(rtmConfig)
            rtmClient?.addEventListener(rtmEventListener)
            Log.d(TAG, "RTM initRtmClient successfully")
            addStatusLog("RtmClient init successfully")
        } catch (e: Exception) {
            Log.e(TAG, "RTM initRtmClient error: ${e.message}")
            e.printStackTrace()
            addStatusLog("RtmClient init failed")
        }
    }

    /**
     * Login RTM
     */
    private fun loginRtm(rtmToken: String, completion: (Exception?) -> Unit) {
        Log.d(TAG, "Starting RTM login")

        if (isLoggingIn) {
            completion.invoke(Exception("Login already in progress"))
            Log.d(TAG, "Login already in progress")
            return
        }

        if (isRtmLogin) {
            completion.invoke(null) // Already logged in
            Log.d(TAG, "Already logged in")
            return
        }

        val client = this.rtmClient ?: run {
            completion.invoke(Exception("RTM client not initialized"))
            Log.d(TAG, "RTM client not initialized")
            return
        }

        isLoggingIn = true
        Log.d(TAG, "Performing logout to ensure clean environment before login")

        // Force logout first (synchronous flag update)
        isRtmLogin = false
        client.logout(object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                Log.d(TAG, "Logout completed, starting login")
                performRtmLogin(client, rtmToken, completion)
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                Log.d(TAG, "Logout failed but continuing with login: ${errorInfo?.errorReason}")
                performRtmLogin(client, rtmToken, completion)
            }
        })
    }

    private fun performRtmLogin(client: RtmClient, rtmToken: String, completion: (Exception?) -> Unit) {
        client.login(rtmToken, object : ResultCallback<Void> {
            override fun onSuccess(p0: Void?) {
                isRtmLogin = true
                isLoggingIn = false
                Log.d(TAG, "RTM login successful")
                completion.invoke(null)
                addStatusLog("Rtm login successful")
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                isRtmLogin = false
                isLoggingIn = false
                Log.e(TAG, "RTM token login failed: ${errorInfo?.errorReason}")
                completion.invoke(Exception("${errorInfo?.errorCode}"))
                addStatusLog("Rtm login failed, code: ${errorInfo?.errorCode}")
            }
        })
    }

    /**
     * Logout RTM
     */
    private fun logoutRtm() {
        Log.d(TAG, "RTM start logout")
        rtmClient?.logout(object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                isRtmLogin = false
                Log.d(TAG, "RTM logout successful")
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                Log.e(TAG, "RTM logout failed: ${errorInfo?.errorCode}")
                // Still mark as logged out since we attempted logout
                isRtmLogin = false
            }
        })
    }

    /**
     * Join RTC channel
     */
    private fun joinRtcChannel(rtcToken: String, channelName: String, uid: Int): Boolean {
        Log.d(TAG, "joinChannel channelName: $channelName, localUid: $uid")
        val engine = rtcEngine ?: run {
            Log.e(TAG, "Join RTC room failed, rtcEngine is null")
            addStatusLog("Rtc joinChannel failed: rtcEngine is null")
            return false
        }
        // join rtc channel
        val channelOptions = ChannelMediaOptions().apply {
            clientRoleType = CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true
            publishCameraTrack = false
            autoSubscribeAudio = true
            autoSubscribeVideo = false
        }
        val ret = engine.joinChannel(rtcToken, channelName, uid, channelOptions)
        Log.d(TAG, "Joining RTC channel: $channelName, uid: $uid")
        return if (ret == ERR_OK) {
            Log.d(TAG, "Join RTC room success")
            true
        } else {
            Log.e(TAG, "Join RTC room failed, ret: $ret")
            addStatusLog("Rtc joinChannel failed ret: $ret")
            false
        }
    }

    /**
     * Leave RTC channel
     */
    private fun leaveRtcChannel() {
        Log.d(TAG, "leaveChannel")
        rtcEngine?.leaveChannel()
    }

    /**
     * Mute local audio
     */
    private fun muteLocalAudio(mute: Boolean) {
        Log.d(TAG, "muteLocalAudio $mute")
        rtcEngine?.adjustRecordingSignalVolume(if (mute) 0 else 100)
    }

    /**
     * Check if both RTC and RTM are connected, then start agent
     */
    private fun checkJoinAndLoginComplete() {
        if (_uiState.value.connectionState == ConnectionState.Connecting && rtcJoined && rtmLoggedIn) {
            startAgent(connectionAttemptId)
        }
    }

    /**
     * Start agent (called automatically after RTC and RTM are connected)
     */
    fun startAgent() {
        startAgent(connectionAttemptId)
    }

    private fun startAgent(attemptId: Int) {
        viewModelScope.launch {
            if (!isCurrentConnectionAttempt(attemptId)) {
                Log.d(TAG, "Ignore stale startAgent attempt: $attemptId")
                return@launch
            }
            if (_uiState.value.connectionState != ConnectionState.Connecting || !rtcJoined || !rtmLoggedIn) {
                Log.d(TAG, "Ignore startAgent before RTC/RTM are ready")
                return@launch
            }
            if (agentId != null || isStartingAgent) {
                Log.d(TAG, "Agent already started, agentId: $agentId")
                return@launch
            }
            isStartingAgent = true

            // Generate token for agent (always required)
            val tokenResult = TokenGenerator.generateTokensAsync(
                channelName = channelName,
                uid = agentUid.toString()
            )

            val agentToken = tokenResult.fold(
                onSuccess = { token ->
                    addStatusLog("Generate agent token successfully")
                    token
                },
                onFailure = { exception ->
                    addStatusLog("Generate agent token failed")
                    Log.e(TAG, "Failed to generate agent token: ${exception.message}", exception)
                    failStartupAttempt(attemptId)
                    return@launch
                }
            )
            if (!isCurrentConnectionAttempt(attemptId)) {
                isStartingAgent = false
                return@launch
            }

            // Generate auth token for REST API.
            val authTokenResult = TokenGenerator.generateTokensAsync(
                channelName = channelName,
                uid = agentUid.toString()
            )

            val restAuthToken = authTokenResult.fold(
                onSuccess = { token ->
                    authToken = token
                    addStatusLog("Generate auth token successfully")
                    token
                },
                onFailure = { exception ->
                    addStatusLog("Generate auth token failed")
                    Log.e(TAG, "Failed to generate auth token: ${exception.message}", exception)
                    failStartupAttempt(attemptId)
                    return@launch
                }
            )
            if (!isCurrentConnectionAttempt(attemptId)) {
                isStartingAgent = false
                return@launch
            }

            val startAgentResult = AgentStarter.startAgentAsync(
                channelName = channelName,
                agentRtcUid = agentUid.toString(),
                agentToken = agentToken,
                authToken = restAuthToken,
                remoteRtcUid = userId.toString(),
                sosDetectionMode = _uiState.value.sosDetectionMode,
                eosDetectionMode = _uiState.value.eosDetectionMode
            )
            if (!isCurrentConnectionAttempt(attemptId)) {
                startAgentResult.getOrNull()?.let { staleAgentId ->
                    AgentStarter.stopAgentAsync(staleAgentId, restAuthToken)
                }
                isStartingAgent = false
                return@launch
            }
            startAgentResult.fold(
                onSuccess = { agentId ->
                    this@AgentChatViewModel.agentId = agentId
                    isStartingAgent = false
                    _uiState.value = _uiState.value.copy(
                        agentId = agentId,
                        connectionState = ConnectionState.Connected
                    )
                    addStatusLog("Agent start successfully")
                    Log.d(TAG, "Agent started successfully, agentId: $agentId")
                },
                onFailure = { exception ->
                    isStartingAgent = false
                    addStatusLog("Agent start failed")
                    Log.e(TAG, "Failed to start agent: ${exception.message}", exception)
                    failStartupAttempt(attemptId)
                }
            )
        }
    }

    /**
     * Generate unified token for RTC and RTM
     *
     * @return Token string on success, null on failure
     */
    private suspend fun generateUserToken(): String? {
        // Get unified token for both RTC and RTM
        val tokenResult = TokenGenerator.generateTokensAsync(
            channelName = channelName,
            uid = userId.toString(),
        )

        return tokenResult.fold(
            onSuccess = { token ->
                addStatusLog("Generate user token successfully")
                unifiedToken = token
                token
            },
            onFailure = { exception ->
                addStatusLog("Generate user token failed")
                Log.e(TAG, "Failed to get token: ${exception.message}", exception)
                null
            }
        )
    }

    /**
     * Join RTC channel and login RTM
     * @param channelName Channel name to join
     */
    fun joinChannelAndLogin(channelName: String) {
        viewModelScope.launch {
            val currentConnectionState = _uiState.value.connectionState
            if (currentConnectionState == ConnectionState.Connecting || currentConnectionState == ConnectionState.Connected) {
                addStatusLog("Start ignored: connection is already ${currentConnectionState.name.lowercase()}")
                return@launch
            }

            val attemptId = nextConnectionAttemptId()

            this@AgentChatViewModel.channelName = channelName
            rtcJoined = false
            rtmLoggedIn = false

            _uiState.value = _uiState.value.copy(
                agentId = null,
                connectionState = ConnectionState.Connecting
            )

            // Get token if not available, otherwise use existing token
            val token = generateUserToken() ?: run {
                failStartupAttempt(attemptId)
                return@launch
            }
            if (!isCurrentConnectionAttempt(attemptId)) {
                return@launch
            }

            // Join RTC channel with the unified token
            if (!joinRtcChannel(token, channelName, userId)) {
                failStartupAttempt(attemptId)
                return@launch
            }

            // Login RTM with the same unified token
            loginRtm(token) { exception ->
                viewModelScope.launch {
                    if (!isCurrentConnectionAttempt(attemptId) || this@AgentChatViewModel.channelName != channelName) {
                        Log.d(TAG, "Ignore stale RTM login callback for channel: $channelName")
                        return@launch
                    }
                    if (exception == null) {
                        rtmLoggedIn = true
                        conversationalAIAPI?.subscribeMessage(channelName) { errorInfo ->
                            if (errorInfo != null) {
                                Log.e(TAG, "Subscribe message error: ${errorInfo}")
                            }
                        }
                        checkJoinAndLoginComplete()
                    } else {
                        Log.e(TAG, "RTM login failed: ${exception.message}", exception)
                        failStartupAttempt(attemptId)
                    }
                }
            }

        }
    }

    private fun nextConnectionAttemptId(): Int {
        connectionAttemptId += 1
        return connectionAttemptId
    }

    private fun isCurrentConnectionAttempt(attemptId: Int): Boolean {
        return attemptId == connectionAttemptId
    }

    private suspend fun handleTransportFailureDuringStartup(attemptId: Int) {
        if (_uiState.value.connectionState == ConnectionState.Connecting) {
            failStartupAttempt(attemptId)
        }
    }

    private suspend fun failStartupAttempt(attemptId: Int) {
        if (!isCurrentConnectionAttempt(attemptId)) return
        if (_uiState.value.connectionState != ConnectionState.Connecting) return
        connectionAttemptId += 1
        releaseStartupSideEffects()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.Idle
        )
    }

    private suspend fun releaseStartupSideEffects() {
        val previousChannelName = channelName
        if (previousChannelName.isNotEmpty()) {
            conversationalAIAPI?.unsubscribeMessage(previousChannelName) { errorInfo ->
                if (errorInfo != null) {
                    Log.e(TAG, "Unsubscribe message error: ${errorInfo}")
                }
            }
        }

        leaveRtcChannel()
        logoutRtm()
        rtcJoined = false
        rtmLoggedIn = false
        isRtmLogin = false
        isLoggingIn = false
        isStartingAgent = false
        authToken = null
        unifiedToken = null
        agentId = null
        _uiState.value = _uiState.value.copy(agentId = null)
    }

    /**
     * Toggle microphone mute state
     */
    fun toggleMute() {
        val newMuteState = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(
            isMuted = newMuteState
        )
        muteLocalAudio(newMuteState)
        Log.d(TAG, "Microphone muted: $newMuteState")
    }

    fun setSosDetectionMode(sosDetectionMode: TurnDetectionMode) {
        val currentState = _uiState.value
        if (!currentState.canChangeTurnDetectionMode) {
            addStatusLog("Turn detection mode cannot be changed after startup")
            return
        }
        _uiState.value = currentState.copy(sosDetectionMode = sosDetectionMode)
    }

    fun setEosDetectionMode(eosDetectionMode: TurnDetectionMode) {
        val currentState = _uiState.value
        if (!currentState.canChangeTurnDetectionMode) {
            addStatusLog("Turn detection mode cannot be changed after startup")
            return
        }
        _uiState.value = currentState.copy(eosDetectionMode = eosDetectionMode)
    }

    fun manualSOS() {
        if (!_uiState.value.isManualSosEnabled) {
            addStatusLog("Manual SOS publish failed error=Manual SOS is disabled for this session")
            return
        }
        publishManualTurn(ManualTurnDemoUi.Action.SOS)
    }

    fun manualEOS() {
        if (!_uiState.value.isManualEosEnabled) {
            addStatusLog("Manual EOS publish failed error=Manual EOS is disabled for this session")
            return
        }
        publishManualTurn(ManualTurnDemoUi.Action.EOS)
    }

    fun sendTextMessage(text: String): Boolean {
        val content = text.trim()
        if (content.isEmpty()) {
            addStatusLog("Send text failed error=Text is empty")
            return false
        }
        return sendChatMessage(
            label = "Text",
            message = TextMessage(
                priority = Priority.INTERRUPT,
                responseInterruptable = true,
                text = content
            )
        )
    }

    fun sendImageUrlMessage(imageUrl: String): Boolean {
        val url = imageUrl.trim()
        if (url.isEmpty()) {
            addStatusLog("Send image failed error=Image URL is empty")
            return false
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            addStatusLog("Send image failed error=Image URL must start with http:// or https://")
            return false
        }
        return sendChatMessage(
            label = "Image",
            message = ImageMessage(
                uuid = UUID.randomUUID().toString(),
                imageUrl = url
            )
        )
    }

    fun sendInterrupt() {
        val api = requireConnectedConversationalAIAPI("Interrupt") ?: return
        api.interrupt(agentUid.toString()) { error ->
            if (error != null) {
                addStatusLog("Interrupt failed error=${error.errorMessage}")
            } else {
                addStatusLog("Interrupt sent successfully")
            }
        }
    }

    private fun sendChatMessage(label: String, message: ChatMessage): Boolean {
        val api = requireConnectedConversationalAIAPI("Send $label") ?: return false
        api.chat(agentUid.toString(), message) { error ->
            if (error != null) {
                addStatusLog("Send $label failed error=${error.errorMessage}")
            } else {
                addStatusLog("Send $label successfully")
            }
        }
        return true
    }

    private fun requireConnectedConversationalAIAPI(action: String): IConversationalAIAPI? {
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            addStatusLog("$action failed error=Agent is not connected")
            return null
        }
        return conversationalAIAPI ?: run {
            addStatusLog("$action failed error=ConversationalAIAPI is not ready")
            null
        }
    }

    private fun publishManualTurn(action: ManualTurnDemoUi.Action) {
        val api = conversationalAIAPI ?: run {
            addStatusLog("Manual ${action.label} publish failed error=ConversationalAIAPI is not ready")
            return
        }
        val completion: (String, io.agora.conversational.api.ConversationalAIAPIError?) -> Unit = { requestId, error ->
            if (error != null) {
                addStatusLog(ManualTurnDemoUi.formatPublishFailureLog(action, requestId, error.errorMessage))
            } else {
                addStatusLog(ManualTurnDemoUi.formatPublishLog(action, requestId))
            }
        }

        when (action) {
            ManualTurnDemoUi.Action.SOS -> api.manualSOS(agentUid.toString(), completion)
            ManualTurnDemoUi.Action.EOS -> api.manualEOS(agentUid.toString(), completion)
        }
    }

    /**
     * Add a new transcript to the list
     */
    fun addTranscript(transcript: Transcript) {
        viewModelScope.launch {
            val currentList = _transcriptList.value.toMutableList()
            // Update existing transcript if same turnId, otherwise add new
            val existingIndex =
                currentList.indexOfFirst {
                    it.transcript.turnId == transcript.turnId && it.transcript.type == transcript.type
                }
            if (existingIndex >= 0) {
                currentList[existingIndex] = currentList[existingIndex].copy(
                    transcript = transcript,
                    latencyMetrics = currentList[existingIndex].latencyMetrics
                        ?: if (transcript.type == TranscriptType.AGENT) {
                            pendingTurnLatencyMetrics.remove(transcript.turnId)
                        } else {
                            null
                        }
                )
            } else {
                currentList.add(
                    TranscriptItem(
                        transcript = transcript,
                        latencyMetrics = if (transcript.type == TranscriptType.AGENT) {
                            pendingTurnLatencyMetrics.remove(transcript.turnId)
                        } else {
                            null
                        }
                    )
                )
            }
            _transcriptList.value = currentList
        }
    }

    private val pendingTurnLatencyMetrics = mutableMapOf<Long, TurnLatencyMetrics>()

    private fun updateTurnLatencyMetrics(turn: Turn) {
        viewModelScope.launch {
            val metrics = turn.toLatencyMetrics()
            val currentList = _transcriptList.value.toMutableList()
            val index = currentList.indexOfFirst {
                it.transcript.turnId == turn.turnId && it.transcript.type == TranscriptType.AGENT
            }
            if (index >= 0) {
                currentList[index] = currentList[index].copy(latencyMetrics = metrics)
                _transcriptList.value = currentList
            } else {
                pendingTurnLatencyMetrics[turn.turnId] = metrics
            }
        }
    }

    private fun Turn.toLatencyMetrics(): TurnLatencyMetrics {
        return TurnLatencyMetrics(
            turnId = turnId,
            e2eLatencyMs = e2eLatency.toLatencyMsOrNull(),
            transportLatencyMs = segmentedLatency.transport.toLatencyMsOrNull(),
            algorithmProcessingLatencyMs = segmentedLatency.algorithmProcessing.toLatencyMsOrNull(),
            asrLatencyMs = segmentedLatency.asrTTLW.toLatencyMsOrNull(),
            llmLatencyMs = segmentedLatency.llmTTFT.toLatencyMsOrNull(),
            ttsLatencyMs = segmentedLatency.ttsTTFB.toLatencyMsOrNull(),
        )
    }

    private fun Double.toLatencyMs(): Int = roundToInt()

    private fun Double.toLatencyMsOrNull(): Int? {
        if (this <= 0.0) return null
        return toLatencyMs()
    }

    /**
     * Add a status message to debug log list
     * This is used to track ViewModel state changes that are shown via SnackbarHelper
     */
    private fun addStatusLog(message: String) {
        if (message.isEmpty()) return
        viewModelScope.launch {
            val currentLogs = _debugLogList.value.toMutableList()
            currentLogs.add(message)
            // Keep only last 100 logs to avoid memory issues
            if (currentLogs.size > 20) {
                currentLogs.removeAt(0)
            }
            _debugLogList.value = currentLogs
        }
    }

    /**
     * Hang up and cleanup connections
     */
    fun hangup() {
        viewModelScope.launch {
            try {
                conversationalAIAPI?.unsubscribeMessage(channelName) { errorInfo ->
                    if (errorInfo != null) {
                        Log.e(TAG, "Unsubscribe message error: ${errorInfo}")
                    }
                }

                // Stop agent if it was started
                if (agentId != null) {
                    val stopResult = AgentStarter.stopAgentAsync(
                        agentId = agentId!!,
                        authToken = authToken ?: ""
                    )
                    stopResult.fold(
                        onSuccess = {
                            Log.d(TAG, "Agent stopped successfully")
                            addStatusLog("Agent stopped successfully")
                        },
                        onFailure = { exception ->
                            Log.e(TAG, "Failed to stop agent: ${exception.message}", exception)
                        }
                    )
                    agentId = null
                }

                leaveRtcChannel()
                rtcJoined = false
                authToken = null
                _uiState.value = _uiState.value.copy(
                    agentId = null,
                    connectionState = ConnectionState.Idle
                )
                _transcriptList.value = emptyList()
                pendingTurnLatencyMetrics.clear()
                isAgentListening = false
                isAgentThinking = false
                isAgentSpeaking = false
                _agentState.value = AgentState.IDLE
                Log.d(TAG, "Hangup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during hangup: ${e.message}", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        conversationalAIAPI?.destroy()
        conversationalAIAPI = null
        leaveRtcChannel()
        logoutRtm()

        // Cleanup RTM client
        rtmClient?.let { client ->
            try {
                client.removeEventListener(rtmEventListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing RTM event listener: ${e.message}")
            }
        }

        // Note: RtcEngine.destroy() should be called carefully as it's a global operation
        // Consider managing RTC engine lifecycle at Application level
        rtcEngine = null
        rtmClient = null
    }
}

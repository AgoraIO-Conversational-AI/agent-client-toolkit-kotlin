package io.agora.agent.toolkit.sample.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.agora.agent.toolkit.BuildConfig
import io.agora.agent.toolkit.R
import io.agora.agent.toolkit.databinding.ActivityAgentChatBinding
import io.agora.agent.toolkit.databinding.BottomSheetChatMessageBinding
import io.agora.agent.toolkit.databinding.BottomSheetSettingsBinding
import io.agora.agent.toolkit.databinding.ItemTranscriptAgentBinding
import io.agora.agent.toolkit.databinding.ItemTranscriptUserBinding
import io.agora.agent.toolkit.sample.api.TurnDetectionMode
import io.agora.agent.toolkit.sample.tools.PermissionHelp
import io.agora.agent.toolkit.sample.ui.common.BaseActivity
import io.agora.conversational.api.AgentState
import io.agora.conversational.api.ConversationalAIAPI_VERSION
import io.agora.conversational.api.Priority
import io.agora.conversational.api.ThinkListeningAction
import io.agora.conversational.api.ThinkSpeakingAction
import io.agora.conversational.api.ThinkThinkingAction
import io.agora.conversational.api.TranscriptType
import kotlinx.coroutines.launch

/**
 * Activity for agent chat interface
 * Layout: log, agent status, transcript, start/control buttons
 */
class AgentChatActivity : BaseActivity<ActivityAgentChatBinding>() {

    private lateinit var viewModel: AgentChatViewModel
    private lateinit var mPermissionHelp: PermissionHelp
    private val transcriptAdapter: TranscriptAdapter = TranscriptAdapter()

    // Track whether to automatically scroll to bottom
    private var autoScrollToBottom = true
    private var isScrollBottom = false
    private var chatMessageMode = ChatMessageMode.TEXT

    private enum class ChatMessageMode {
        TEXT,
        IMAGE,
        SPEAK,
        THINK
    }

    private data class MessageSheetOptions(
        val priorities: List<Priority>,
        val listeningActions: List<ThinkListeningAction>,
        val thinkingActions: List<ThinkThinkingAction>,
        val speakingActions: List<ThinkSpeakingAction>
    )

    override fun getViewBinding(): ActivityAgentChatBinding {
        return ActivityAgentChatBinding.inflate(layoutInflater)
    }

    override fun initData() {
        super.initData()
        viewModel = ViewModelProvider(this)[AgentChatViewModel::class.java]
        mPermissionHelp = PermissionHelp(this)

        // Observe UI state changes
        observeUiState()

        // Observe transcript list changes
        observeTranscriptList()

        // Observe debug log changes
        observeDebugLogs()
    }

    override fun initView() {
        mBinding?.apply {
            applyChatInsets(root)

            // Setup RecyclerView for transcript list
            setupRecyclerView()

            switchRealtimeData.isChecked = true
            transcriptAdapter.setLatencyMetricsVisible(switchRealtimeData.isChecked)
            llRealtimeDataToggle.setOnClickListener {
                switchRealtimeData.isChecked = !switchRealtimeData.isChecked
            }
            switchRealtimeData.setOnCheckedChangeListener { _, isChecked ->
                transcriptAdapter.setLatencyMetricsVisible(isChecked)
            }

            // Start button click listener
            btnStart.setOnClickListener {
                // Generate random channel name each time joining channel
                val channelName = AgentChatViewModel.generateRandomChannelName()
                startAgent(channelName)
            }

            btnSettings.setOnClickListener {
                showSettingsSheet()
            }

            // Mute button click listener
            btnMute.setOnClickListener {
                viewModel.toggleMute()
            }

            btnManualSos.setOnClickListener {
                viewModel.manualSOS()
            }

            btnManualEos.setOnClickListener {
                viewModel.manualEOS()
            }

            btnChat.setOnClickListener {
                showChatMessageSheet()
            }

            btnInterrupt.setOnClickListener {
                viewModel.sendInterrupt()
            }

            // Stop button click listener
            btnStop.setOnClickListener {
                viewModel.hangup()
            }
        }
    }

    private fun copyAgentId(agentId: String) {
        if (agentId.isBlank()) {
            Toast.makeText(this, "Agent ID is not available", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Agent ID", agentId))
        Toast.makeText(this, "Agent ID copied", Toast.LENGTH_SHORT).show()
    }

    private fun applyChatInsets(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun checkMicrophonePermission(granted: (Boolean) -> Unit) {
        if (mPermissionHelp.hasMicPerm()) {
            granted.invoke(true)
        } else {
            mPermissionHelp.checkMicPerm(
                granted = { granted.invoke(true) },
                unGranted = {
                    showPermissionDialog(
                        "Permission Required",
                        "Microphone permission is required for voice chat. Please grant the permission to continue.",
                        onResult = {
                            if (it) {
                                mPermissionHelp.launchAppSettingForMic(
                                    granted = { granted.invoke(true) },
                                    unGranted = { granted.invoke(false) }
                                )
                            } else {
                                granted.invoke(false)
                            }
                        }
                    )
                }
            )
        }
    }

    private fun startAgent(channelName: String) {
        // Check microphone permission before joining channel
        checkMicrophonePermission { granted ->
            if (granted) {
                viewModel.joinChannelAndLogin(channelName)
            } else {
                Toast.makeText(
                    this@AgentChatActivity,
                    "Microphone permission is required to join channel",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showChatMessageSheet() {
        val sheetBinding = BottomSheetChatMessageBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        val options = MessageSheetOptions(
            priorities = Priority.entries,
            listeningActions = ThinkListeningAction.entries,
            thinkingActions = ThinkThinkingAction.entries,
            speakingActions = ThinkSpeakingAction.entries
        )

        chatMessageMode = ChatMessageMode.TEXT
        configureMessageSheetOptions(sheetBinding, options)
        renderChatMessageMode(sheetBinding)
        bindMessageSheetListeners(sheetBinding, dialog, options)
        configureMessageSheetDialog(sheetBinding, dialog)
        dialog.show()
    }

    private fun configureMessageSheetOptions(binding: BottomSheetChatMessageBinding, options: MessageSheetOptions) {
        setSpinnerItems(binding.spinnerMessagePriority, options.priorities.map { it.name })
        setSpinnerItems(binding.spinnerThinkListening, options.listeningActions.map { it.name })
        setSpinnerItems(binding.spinnerThinkThinking, options.thinkingActions.map { it.name })
        setSpinnerItems(binding.spinnerThinkSpeaking, options.speakingActions.map { it.name })

        binding.spinnerMessagePriority.setSelection(options.priorities.indexOf(Priority.INTERRUPT))
        binding.spinnerThinkListening.setSelection(
            options.listeningActions.indexOf(ThinkListeningAction.INTERRUPT)
        )
        binding.spinnerThinkThinking.setSelection(
            options.thinkingActions.indexOf(ThinkThinkingAction.IGNORE)
        )
        binding.spinnerThinkSpeaking.setSelection(
            options.speakingActions.indexOf(ThinkSpeakingAction.IGNORE)
        )
        binding.switchMessageInterruptable.isChecked = true
        binding.switchThinkMetadata.isChecked = false
    }

    private fun setSpinnerItems(spinner: AppCompatSpinner, labels: List<String>) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun renderChatMessageMode(binding: BottomSheetChatMessageBinding) {
        listOf(
            binding.btnChatModeText to ChatMessageMode.TEXT,
            binding.btnChatModeImage to ChatMessageMode.IMAGE,
            binding.btnChatModeSpeak to ChatMessageMode.SPEAK,
            binding.btnChatModeThink to ChatMessageMode.THINK
        ).forEach { (button, buttonMode) ->
            val selected = chatMessageMode == buttonMode
            button.setBackgroundResource(
                if (selected) R.drawable.selector_chat_mode_selected else R.drawable.selector_chat_mode_unselected
            )
            button.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.white else R.color.text_subtitle)
            )
            button.setIconTintResource(
                if (selected) R.color.white else R.color.text_subtitle
            )
        }

        binding.etChatMessage.hint = when (chatMessageMode) {
            ChatMessageMode.TEXT -> "Type a chat message"
            ChatMessageMode.IMAGE -> "Paste image URL"
            ChatMessageMode.SPEAK -> "Text for direct speech"
            ChatMessageMode.THINK -> "Instruction for the LLM"
        }
        binding.etChatMessage.inputType =
            if (chatMessageMode == ChatMessageMode.IMAGE) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
        binding.etChatMessage.setSingleLine(true)
        val isKeyboardVisible = ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        renderMessageSheetCompactMode(binding, isKeyboardVisible)
    }

    private fun renderMessageSheetCompactMode(
        binding: BottomSheetChatMessageBinding,
        isKeyboardVisible: Boolean
    ) {
        binding.rowMessagePriority.isVisible =
            !isKeyboardVisible &&
                    (chatMessageMode == ChatMessageMode.TEXT || chatMessageMode == ChatMessageMode.SPEAK)
        binding.llThinkOptions.isVisible =
            !isKeyboardVisible && chatMessageMode == ChatMessageMode.THINK
        binding.rowMessageInterruptable.isVisible =
            !isKeyboardVisible && chatMessageMode != ChatMessageMode.IMAGE
    }

    private fun bindMessageSheetListeners(
        binding: BottomSheetChatMessageBinding,
        dialog: BottomSheetDialog,
        options: MessageSheetOptions
    ) {
        listOf(
            binding.btnChatModeText to ChatMessageMode.TEXT,
            binding.btnChatModeImage to ChatMessageMode.IMAGE,
            binding.btnChatModeSpeak to ChatMessageMode.SPEAK,
            binding.btnChatModeThink to ChatMessageMode.THINK
        ).forEach { (button, mode) ->
            button.setOnClickListener {
                chatMessageMode = mode
                renderChatMessageMode(binding)
            }
        }
        binding.btnSendChatMessage.setOnClickListener {
            sendMessageFromSheet(binding, dialog, options)
        }
        binding.etChatMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessageFromSheet(binding, dialog, options)
                true
            } else {
                false
            }
        }
    }

    private fun sendMessageFromSheet(
        binding: BottomSheetChatMessageBinding,
        dialog: BottomSheetDialog,
        options: MessageSheetOptions
    ) {
        val input = binding.etChatMessage.text?.toString().orEmpty()
        val sent = when (chatMessageMode) {
            ChatMessageMode.TEXT -> viewModel.sendTextMessage(
                text = input,
                priority = options.priorities[binding.spinnerMessagePriority.selectedItemPosition],
                responseInterruptable = binding.switchMessageInterruptable.isChecked
            )

            ChatMessageMode.IMAGE -> viewModel.sendImageUrlMessage(input)
            ChatMessageMode.SPEAK -> viewModel.sendSpeakMessage(
                text = input,
                priority = options.priorities[binding.spinnerMessagePriority.selectedItemPosition],
                interruptable = binding.switchMessageInterruptable.isChecked
            )

            ChatMessageMode.THINK -> viewModel.sendThinkMessage(
                text = input,
                onListeningAction = options.listeningActions[binding.spinnerThinkListening.selectedItemPosition],
                onThinkingAction = options.thinkingActions[binding.spinnerThinkThinking.selectedItemPosition],
                onSpeakingAction = options.speakingActions[binding.spinnerThinkSpeaking.selectedItemPosition],
                interruptable = binding.switchMessageInterruptable.isChecked,
                includeMetadata = binding.switchThinkMetadata.isChecked
            )
        }
        if (sent) dialog.dismiss()
    }

    private fun configureMessageSheetDialog(
        binding: BottomSheetChatMessageBinding,
        dialog: BottomSheetDialog
    ) {
        dialog.setContentView(binding.root)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            renderMessageSheetCompactMode(
                binding,
                insets.isVisible(WindowInsetsCompat.Type.ime())
            )
            insets
        }
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundResource(R.drawable.bg_bottom_sheet)
            ViewCompat.requestApplyInsets(binding.root)
        }
    }

    private fun showSettingsSheet() {
        val state = viewModel.uiState.value
        val sheetBinding = BottomSheetSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundResource(R.drawable.bg_bottom_sheet)
        }

        val optionTextColor = ContextCompat.getColor(
            this,
            if (state.canChangeTurnDetectionMode) R.color.text_primary else R.color.text_weak
        )
        sheetBinding.tvTurnSosDetectionLabel.setTextColor(optionTextColor)
        sheetBinding.tvTurnEosDetectionLabel.setTextColor(optionTextColor)
        sheetBinding.tvVersionInfo.text =
            "Demo v${BuildConfig.VERSION_NAME}  |  Component v$ConversationalAIAPI_VERSION"
        val agentId = state.agentId.orEmpty()
        sheetBinding.rowAgentId.isVisible = agentId.isNotBlank()
        sheetBinding.dividerAgentId.isVisible = agentId.isNotBlank()
        sheetBinding.tvAgentId.text = agentId
        sheetBinding.btnCopyAgentId.setOnClickListener {
            copyAgentId(agentId)
        }

        setSosModeButtonsChecked(sheetBinding, state.sosDetectionMode)
        setEosModeButtonsChecked(sheetBinding, state.eosDetectionMode)
        sheetBinding.groupTurnSosDetection.isEnabled = state.canChangeTurnDetectionMode
        sheetBinding.groupTurnEosDetection.isEnabled = state.canChangeTurnDetectionMode
        setChildButtonsEnabled(
            sheetBinding.groupTurnSosDetection,
            state.canChangeTurnDetectionMode
        )
        setChildButtonsEnabled(
            sheetBinding.groupTurnEosDetection,
            state.canChangeTurnDetectionMode
        )

        if (state.canChangeTurnDetectionMode) {
            sheetBinding.btnSosVad.setOnClickListener {
                updateSosDetectionMode(sheetBinding, TurnDetectionMode.VAD)
            }
            sheetBinding.btnSosSemantic.setOnClickListener {
                updateSosDetectionMode(sheetBinding, TurnDetectionMode.SEMANTIC)
            }
            sheetBinding.btnSosManual.setOnClickListener {
                updateSosDetectionMode(sheetBinding, TurnDetectionMode.MANUAL)
            }
            sheetBinding.btnEosVad.setOnClickListener {
                updateEosDetectionMode(sheetBinding, TurnDetectionMode.VAD)
            }
            sheetBinding.btnEosSemantic.setOnClickListener {
                updateEosDetectionMode(sheetBinding, TurnDetectionMode.SEMANTIC)
            }
            sheetBinding.btnEosManual.setOnClickListener {
                updateEosDetectionMode(sheetBinding, TurnDetectionMode.MANUAL)
            }
        }

        dialog.show()
    }

    private fun setChildButtonsEnabled(
        group: ViewGroup,
        enabled: Boolean
    ) {
        for (index in 0 until group.childCount) {
            group.getChildAt(index).isEnabled = enabled
        }
    }

    private fun updateSosDetectionMode(
        sheetBinding: BottomSheetSettingsBinding,
        mode: TurnDetectionMode
    ) {
        viewModel.setSosDetectionMode(mode)
        setSosModeButtonsChecked(sheetBinding, mode)
    }

    private fun updateEosDetectionMode(
        sheetBinding: BottomSheetSettingsBinding,
        mode: TurnDetectionMode
    ) {
        viewModel.setEosDetectionMode(mode)
        setEosModeButtonsChecked(sheetBinding, mode)
    }

    private fun setSosModeButtonsChecked(
        sheetBinding: BottomSheetSettingsBinding,
        mode: TurnDetectionMode
    ) {
        setModeButtonsChecked(
            mode = mode,
            vadButton = sheetBinding.btnSosVad,
            semanticButton = sheetBinding.btnSosSemantic,
            manualButton = sheetBinding.btnSosManual
        )
    }

    private fun setEosModeButtonsChecked(
        sheetBinding: BottomSheetSettingsBinding,
        mode: TurnDetectionMode
    ) {
        setModeButtonsChecked(
            mode = mode,
            vadButton = sheetBinding.btnEosVad,
            semanticButton = sheetBinding.btnEosSemantic,
            manualButton = sheetBinding.btnEosManual
        )
    }

    private fun setModeButtonsChecked(
        mode: TurnDetectionMode,
        vadButton: MaterialButton,
        semanticButton: MaterialButton,
        manualButton: MaterialButton
    ) {
        vadButton.isChecked = mode == TurnDetectionMode.VAD
        semanticButton.isChecked = mode == TurnDetectionMode.SEMANTIC
        manualButton.isChecked = mode == TurnDetectionMode.MANUAL
    }

    private fun showPermissionDialog(title: String, content: String, onResult: (Boolean) -> Unit) {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return

        CommonDialog.Builder()
            .setTitle(title)
            .setContent(content)
            .setPositiveButton("Retry") {
                onResult.invoke(true)
            }
            .setNegativeButton("Exit") {
                onResult.invoke(false)
            }
            .setCancelable(false)
            .build()
            .show(supportFragmentManager, "permission_dialog")
    }

    /**
     * Setup RecyclerView for transcript list
     */
    private fun setupRecyclerView() {
        mBinding?.rvTranscript?.apply {
            layoutManager = LinearLayoutManager(this@AgentChatActivity).apply {
                reverseLayout = false
            }
            adapter = transcriptAdapter
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            // Check if at bottom when scrolling stops
                            isScrollBottom = !recyclerView.canScrollVertically(1)
                            if (isScrollBottom) {
                                autoScrollToBottom = true
                            }
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            // When user actively drags
                            autoScrollToBottom = false
                        }
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    // Show button when scrolling up a significant distance
                    if (dy < -50) {
                        if (recyclerView.canScrollVertically(1)) {
                            autoScrollToBottom = false
                        }
                    }
                }
            })
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                mBinding?.apply {
                    // Update button visibility based on connection state
                    val isConnected = state.connectionState == AgentChatViewModel.ConnectionState.Connected
                    val isConnecting = state.connectionState == AgentChatViewModel.ConnectionState.Connecting

                    // Show/hide buttons
                    llStart.isVisible = !isConnected
                    llControls.isVisible = isConnected
                    llInterruptPanel.isVisible = isConnected
                    updateTranscriptBottomPadding(isConnected)
                    val messageControlsEnabled = isConnected
                    btnChat.isEnabled = messageControlsEnabled
                    btnInterrupt.isEnabled = messageControlsEnabled
                    tvTurnDetectionMode.text =
                        "SOS: ${state.sosDetectionMode.displayName}  |  " +
                                "EOS: ${state.eosDetectionMode.displayName}"
                    val settingsTint = if (state.canChangeTurnDetectionMode) {
                        R.color.mic_normal_icon
                    } else {
                        R.color.text_weak
                    }
                    btnSettings.setColorFilter(ContextCompat.getColor(this@AgentChatActivity, settingsTint))
                    val showCapabilityPanel = isConnected && state.isManualTurnDetectionEnabled
                    llCapabilityPanel.isVisible = showCapabilityPanel
                    btnManualSos.isVisible = state.isManualSosEnabled
                    manualActionDivider.isVisible =
                        state.isManualSosEnabled && state.isManualEosEnabled
                    btnManualEos.isVisible = state.isManualEosEnabled

                    // Update button style based on connection state
                    when {
                        isConnecting -> {
                            btnStart.text = "Connecting..."
                            btnStart.isEnabled = false
                            btnStart.setBackgroundResource(R.drawable.bg_start_button_disabled)
                            btnStart.setTextColor(
                                ContextCompat.getColor(
                                    this@AgentChatActivity,
                                    R.color.btn_disabled_text
                                )
                            )
                        }

                        else -> {
                            btnStart.text = "Start Agent"
                            btnStart.isEnabled = true
                            btnStart.setBackgroundResource(R.drawable.selector_gradient_button)
                            btnStart.setTextColor(ContextCompat.getColor(this@AgentChatActivity, R.color.white))
                        }
                    }

                    // Update mute button UI with semantic colors
                    if (state.isMuted) {
                        btnMute.setImageResource(R.drawable.ic_mic_off)
                        btnMute.setBackgroundResource(R.drawable.bg_button_mute_muted)
                        btnMute.setColorFilter(ContextCompat.getColor(this@AgentChatActivity, R.color.mic_muted_icon))
                    } else {
                        btnMute.setImageResource(R.drawable.ic_mic)
                        btnMute.setBackgroundResource(R.drawable.bg_button_mute_selector)
                        btnMute.setColorFilter(ContextCompat.getColor(this@AgentChatActivity, R.color.mic_normal_icon))
                    }
                }
            }
        }

        // Observe agent state with semantic colors
        lifecycleScope.launch {
            viewModel.agentState.collect { agentState ->
                mBinding?.apply {
                    val state = agentState ?: AgentState.IDLE
                    tvAgentStatus.text = state.value.replaceFirstChar { it.uppercase() }

                    // Map agent state to semantic color
                    val stateColorRes = when (state) {
                        AgentState.IDLE -> R.color.state_idle
                        AgentState.LISTENING -> R.color.state_listening
                        AgentState.THINKING -> R.color.state_thinking
                        AgentState.SPEAKING -> R.color.state_speaking
                        AgentState.SILENT -> R.color.state_silent
                        AgentState.UNKNOWN -> R.color.text_tertiary
                    }
                    val stateColor = ContextCompat.getColor(this@AgentChatActivity, stateColorRes)

                    // Update status text color
                    tvAgentStatus.setTextColor(stateColor)

                    // Update status dot color
                    val dotDrawable = viewStatusDot.background
                    if (dotDrawable is GradientDrawable) {
                        dotDrawable.setColor(stateColor)
                    }
                }
            }
        }
    }

    private fun updateTranscriptBottomPadding(showInterrupt: Boolean) {
        mBinding?.rvTranscript?.apply {
            val bottomPadding = if (showInterrupt) 58.dpToPx() else 0
            if (paddingBottom != bottomPadding) {
                setPadding(paddingLeft, paddingTop, paddingRight, bottomPadding)
            }
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun observeTranscriptList() {
        lifecycleScope.launch {
            viewModel.transcriptList.collect { transcriptList ->
                // Update transcript list
                transcriptAdapter.submitList(transcriptList)
                if (autoScrollToBottom) {
                    scrollToBottom()
                }
            }
        }
    }

    private fun observeDebugLogs() {
        lifecycleScope.launch {
            viewModel.debugLogList.collect { logList ->
                mBinding?.apply {
                    if (logList.isEmpty()) {
                        tvLog.text = "log"
                        return@apply
                    }
                    // Build colored log text using semantic log-level colors
                    val spannable = SpannableStringBuilder()
                    logList.forEachIndexed { index, log ->
                        val start = spannable.length
                        spannable.append(log)
                        val end = spannable.length

                        // Determine log level color based on content keywords
                        val colorRes = when {
                            log.contains("failed", ignoreCase = true) ||
                                    log.contains("error", ignoreCase = true) -> R.color.error_red_light

                            log.contains("successfully", ignoreCase = true) ||
                                    log.contains("success", ignoreCase = true) -> R.color.success_green_light

                            log.contains("connecting", ignoreCase = true) ||
                                    log.contains("starting", ignoreCase = true) -> R.color.warning_amber_light

                            else -> R.color.text_secondary
                        }
                        spannable.setSpan(
                            ForegroundColorSpan(ContextCompat.getColor(this@AgentChatActivity, colorRes)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        if (index < logList.size - 1) spannable.append("\n")
                    }
                    tvLog.text = spannable
                    // Auto scroll to bottom
                    tvLog.post {
                        val scrollView = tvLog.parent as? ScrollView
                        scrollView?.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    /**
     * Scroll RecyclerView to the bottom to show latest transcript
     */
    private fun scrollToBottom() {
        mBinding?.rvTranscript?.apply {
            val lastPosition = transcriptAdapter.itemCount - 1
            if (lastPosition < 0) return

            stopScroll()
            val layoutManager = layoutManager as? LinearLayoutManager ?: return

            // Use single post call to handle all scrolling logic
            post {
                layoutManager.scrollToPosition(lastPosition)

                // Handle extra-long messages that exceed viewport height
                val lastView = layoutManager.findViewByPosition(lastPosition)
                if (lastView != null && lastView.height > height) {
                    val offset = height - lastView.height
                    layoutManager.scrollToPositionWithOffset(lastPosition, offset)
                }

                isScrollBottom = true
            }
        }
    }
}

/**
 * Adapter for displaying transcript list with different view types for USER and AGENT
 */
class TranscriptAdapter :
    ListAdapter<AgentChatViewModel.TranscriptItem, RecyclerView.ViewHolder>(TranscriptDiffCallback()) {
    private var isLatencyMetricsVisible = true

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_AGENT = 1
    }

    fun setLatencyMetricsVisible(visible: Boolean) {
        if (isLatencyMetricsVisible == visible) return
        isLatencyMetricsVisible = visible
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).transcript.type) {
            TranscriptType.USER -> VIEW_TYPE_USER
            TranscriptType.AGENT -> VIEW_TYPE_AGENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_USER -> {
                UserViewHolder(ItemTranscriptUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

            VIEW_TYPE_AGENT -> {
                AgentViewHolder(ItemTranscriptAgentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(item)
            is AgentViewHolder -> holder.bind(item, isLatencyMetricsVisible)
        }
    }

    /**
     * ViewHolder for USER transcript items (right-aligned with "Me" avatar)
     */
    class UserViewHolder(private val binding: ItemTranscriptUserBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AgentChatViewModel.TranscriptItem) {
            binding.tvTranscriptText.text = item.transcript.text.ifEmpty { "..." }
        }
    }

    /**
     * ViewHolder for AGENT transcript items (left-aligned with "AI" avatar)
     */
    class AgentViewHolder(private val binding: ItemTranscriptAgentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AgentChatViewModel.TranscriptItem, isLatencyMetricsVisible: Boolean) {
            binding.tvTranscriptText.text = item.transcript.text.ifEmpty { "..." }
            bindLatencyMetrics(item.latencyMetrics, isLatencyMetricsVisible)
        }

        private fun bindLatencyMetrics(
            metrics: AgentChatViewModel.TurnLatencyMetrics?,
            isLatencyMetricsVisible: Boolean
        ) {
            val shouldShow = isLatencyMetricsVisible && metrics != null
            binding.llLatencyMetrics.isVisible = shouldShow
            if (metrics == null) {
                binding.tvLatencyTurn.text = ""
                binding.tvLatencySummary.text = ""
                return
            }

            binding.tvLatencyTurn.text = "#${metrics.turnId}"
            binding.tvLatencySummary.text = buildLatencySummary(metrics)
        }

        private fun buildLatencySummary(metrics: AgentChatViewModel.TurnLatencyMetrics): String {
            return listOf(
                "E2E:${metrics.e2eLatencyMs.toLatencyText()}",
                "RTC:${metrics.transportLatencyMs.toLatencyText()}",
                "AI:${metrics.algorithmProcessingLatencyMs.toLatencyText()}",
                "ASR:${metrics.asrLatencyMs.toLatencyText()}",
                "LLM:${metrics.llmLatencyMs.toLatencyText()}",
                "TTS:${metrics.ttsLatencyMs.toLatencyText()}"
            ).joinToString(separator = "  ")
        }

        private fun Int?.toLatencyText(): String {
            return this?.let { "${it}ms" } ?: "--"
        }
    }

    private class TranscriptDiffCallback : DiffUtil.ItemCallback<AgentChatViewModel.TranscriptItem>() {
        override fun areItemsTheSame(
            oldItem: AgentChatViewModel.TranscriptItem,
            newItem: AgentChatViewModel.TranscriptItem
        ): Boolean {
            return oldItem.transcript.turnId == newItem.transcript.turnId &&
                    oldItem.transcript.type == newItem.transcript.type
        }

        override fun areContentsTheSame(
            oldItem: AgentChatViewModel.TranscriptItem,
            newItem: AgentChatViewModel.TranscriptItem
        ): Boolean {
            return oldItem == newItem
        }
    }
}

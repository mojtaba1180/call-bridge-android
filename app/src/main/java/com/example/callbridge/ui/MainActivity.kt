package com.example.callbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.callbridge.audio.AudioBridgeMode
import com.example.callbridge.audio.AudioCapabilityState
import com.example.callbridge.bluetooth.AdvertisingStatus
import com.example.callbridge.core.App
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.network.ServerRunState
import com.example.callbridge.permissions.GrantState
import com.example.callbridge.permissions.PermissionCategory
import com.example.callbridge.security.PairingState
import com.example.callbridge.service.BridgeServiceState
import com.example.callbridge.telephony.CallControlCapability
import com.example.callbridge.telephony.CallDirection
import com.example.callbridge.telephony.CallState
import com.example.callbridge.ui.dialogs.BridgeActionDialogs
import com.example.callbridge.ui.dialogs.PairingApprovalDialog
import com.example.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Main RTL-ready Native XML + ViewBinding Dashboard for CallBridge Android Companion.
 *
 * Features on-demand permission requests (never prompting all permissions at launch),
 * real hardware/service controls, live diagnostics, explicit pairing approval, and
 * transparent cellular-audio limitation reporting.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DashboardViewModel by viewModels()
    private val logAdapter = EventLogAdapter()

    private var pendingAfterPermissionAction: (() -> Unit)? = null
    private var activeApprovalDialog: AlertDialog? = null
    private var lastPromptedRequestId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.refreshAllStatuses()
        val allGranted = results.values.all { it }
        val action = pendingAfterPermissionAction
        pendingAfterPermissionAction = null
        if (allGranted && action != null) {
            action.invoke()
        } else if (!allGranted && results.isNotEmpty()) {
            showMessage(getString(R.string.status_error) + ": " + results.filterValues { !it }.keys.joinToString())
        }
    }

    private val roleOrSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAllStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdgeInsets()
        setupEventLogList()
        setupButtons()
        observeState()
        handleDialIntentIfPresent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDialIntentIfPresent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAllStatuses()
    }

    override fun onDestroy() {
        activeApprovalDialog?.dismiss()
        activeApprovalDialog = null
        super.onDestroy()
    }

    private fun applyEdgeToEdgeInsets() {
        val headerBaseTop = binding.heroHeader.paddingTop
        val contentBaseBottom = binding.contentContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.heroHeader.setPadding(
                binding.heroHeader.paddingLeft,
                headerBaseTop + sysBars.top,
                binding.heroHeader.paddingRight,
                binding.heroHeader.paddingBottom
            )
            binding.contentContainer.setPadding(
                binding.contentContainer.paddingLeft,
                binding.contentContainer.paddingTop,
                binding.contentContainer.paddingRight,
                contentBaseBottom + sysBars.bottom
            )
            insets
        }
    }

    private fun setupEventLogList() {
        binding.rvEventLogs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
            itemAnimator = null
        }
    }

    private fun setupButtons() {
        val container = (application as App).container

        binding.btnToggleLanguage.setOnClickListener { toggleLanguage() }

        // Service Controls (Section H)
        binding.btnStartBridge.setOnClickListener {
            val perms = container.permissionManager.refresh()
            if (!perms.canStartConnectedDeviceFgs() && !perms.canStartPhoneCallFgs()) {
                val btPerms = container.permissionManager.requiredPermissionsFor(PermissionCategory.BLUETOOTH)
                if (btPerms.isNotEmpty()) {
                    showMessage(getString(R.string.msg_bt_perm_required_for_service))
                    requestPermissionsFor(PermissionCategory.BLUETOOTH) {
                        viewModel.startBridge()
                    }
                } else {
                    viewModel.startBridge()
                }
            } else {
                viewModel.startBridge()
            }
        }

        binding.btnStopBridge.setOnClickListener {
            viewModel.stopBridge()
        }

        binding.btnPairIphone.setOnClickListener {
            val pin = viewModel.openPairingWindow()
            PairingApprovalDialog.showPairingWindowDialog(
                context = this,
                pinCode = pin,
                pairedDevice = viewModel.uiState.value.pairedDevice,
                onUnpair = { viewModel.unpairDevice() },
                onDismiss = { }
            )
        }

        binding.btnTestBle.setOnClickListener {
            viewModel.runBleTest()
        }

        binding.btnTestNetwork.setOnClickListener {
            viewModel.runNetworkTest()
        }

        binding.btnTestMicrophone.setOnClickListener {
            val perms = container.permissionManager.refresh()
            if (perms.microphone != GrantState.GRANTED) {
                showMessage(getString(R.string.msg_mic_perm_required))
                requestPermissionsFor(PermissionCategory.MICROPHONE) {
                    viewModel.toggleMicrophoneTest()
                }
            } else {
                viewModel.toggleMicrophoneTest()
            }
        }

        // Permission Checklist (Section B) — on-demand per feature
        binding.btnPermBluetooth.setOnClickListener {
            requestPermissionsFor(PermissionCategory.BLUETOOTH)
        }
        binding.btnPermPhoneState.setOnClickListener {
            requestPermissionsFor(PermissionCategory.PHONE_STATE)
        }
        binding.btnPermNotifications.setOnClickListener {
            requestPermissionsFor(PermissionCategory.NOTIFICATIONS)
        }
        binding.btnPermMicrophone.setOnClickListener {
            requestPermissionsFor(PermissionCategory.MICROPHONE)
        }
        binding.btnPermCallPhone.setOnClickListener {
            requestPermissionsFor(PermissionCategory.CALL_PHONE)
        }
        binding.btnPermDefaultDialer.setOnClickListener {
            val intent = container.defaultDialerManager.createRequestDefaultDialerIntent()
            if (intent != null) {
                runCatching { roleOrSettingsLauncher.launch(intent) }
                    .onFailure { showMessage(getString(R.string.msg_dialer_unavailable)) }
            } else {
                showMessage(getString(R.string.msg_dialer_unavailable))
            }
        }
        binding.btnPermBattery.setOnClickListener {
            BridgeActionDialogs.showBatteryOptimizationDialog(this) {
                val intent = container.permissionManager.createBatteryOptimizationIntent()
                if (intent != null) {
                    runCatching { roleOrSettingsLauncher.launch(intent) }
                        .onFailure {
                            runCatching { startActivity(container.permissionManager.createAppSettingsIntent()) }
                        }
                }
            }
        }

        // Call Controls (Section E)
        binding.btnAnswerCall.setOnClickListener { viewModel.answerCall() }
        binding.btnRejectCall.setOnClickListener { viewModel.rejectCall() }
        binding.btnEndCall.setOnClickListener { viewModel.endCall() }
        binding.btnTestDial.setOnClickListener {
            BridgeActionDialogs.showDialTestDialog(this) { number ->
                val perms = container.permissionManager.refresh()
                if (perms.callPhone != GrantState.GRANTED) {
                    requestPermissionsFor(PermissionCategory.CALL_PHONE) {
                        viewModel.dialNumber(number)
                    }
                } else {
                    viewModel.dialNumber(number)
                }
            }
        }

        binding.btnClearLogs.setOnClickListener { viewModel.clearLogs() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { render(it) }
                }
                launch {
                    viewModel.toastMessages.collect { msg -> showMessage(msg) }
                }
            }
        }
    }

    private fun render(state: DashboardUiState) {
        binding.tvDeviceApiBadge.text = state.apiLabel

        // Section A: Application Status
        binding.tvAppServiceStatus.apply {
            text = mapServiceState(state.serviceState)
            setStatusColor(state.serviceState == BridgeServiceState.RUNNING)
        }
        binding.tvAppBleStatus.apply {
            text = mapAdvertisingStatus(state.bleStatus.advertisingStatus)
            setStatusColor(state.bleStatus.advertisingStatus == AdvertisingStatus.ACTIVE)
        }
        val iphoneConnected = state.bleStatus.connectedDeviceAddress != null
        binding.tvAppIphoneStatus.apply {
            text = if (iphoneConnected) {
                state.bleStatus.connectedDeviceName ?: getString(R.string.status_connected)
            } else {
                getString(R.string.status_disconnected)
            }
            setStatusColor(iphoneConnected)
        }
        binding.tvAppCallState.text = mapCallState(state.callInfo.state)
        binding.tvAppNetworkStatus.apply {
            text = state.networkStatus.localIp?.let { "$it:${state.networkStatus.controlPort}" }
                ?: getString(R.string.status_disconnected)
            setStatusColor(state.networkStatus.serverState == ServerRunState.LISTENING)
        }
        binding.tvAppAudioCapability.apply {
            text = state.audioReport.cellularAudioCapability.name
            val supported = state.audioReport.cellularAudioCapability == AudioCapabilityState.CELLULAR_AUDIO_SUPPORTED
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (supported) R.color.status_ok else R.color.status_warn
                )
            )
        }

        // Control button enabled states
        binding.btnStartBridge.isEnabled = state.serviceState != BridgeServiceState.RUNNING &&
            state.serviceState != BridgeServiceState.STARTING
        binding.btnStopBridge.isEnabled = state.serviceState == BridgeServiceState.RUNNING ||
            state.serviceState == BridgeServiceState.STARTING
        binding.btnTestMicrophone.text = if (state.audioMetrics.isRunning) {
            getString(R.string.action_stop_mic_test)
        } else {
            getString(R.string.action_test_mic)
        }

        // Section B: Permission Checklist
        bindPermissionButton(binding.btnPermBluetooth, state.permissions.bluetooth, isRole = false)
        bindPermissionButton(binding.btnPermPhoneState, state.permissions.phoneState, isRole = false)
        bindPermissionButton(binding.btnPermNotifications, state.permissions.notifications, isRole = false)
        bindPermissionButton(binding.btnPermMicrophone, state.permissions.microphone, isRole = false)
        bindPermissionButton(binding.btnPermCallPhone, state.permissions.callPhone, isRole = false)
        bindPermissionButton(binding.btnPermDefaultDialer, state.permissions.defaultDialerRole, isRole = true)
        bindPermissionButton(binding.btnPermBattery, state.permissions.batteryOptimizationIgnored, isRole = true)

        // Section C: BLE Status
        binding.tvBleAdapterEnabled.apply {
            text = getString(if (state.bleStatus.bluetoothEnabled) R.string.status_enabled else R.string.status_disabled)
            setStatusColor(state.bleStatus.bluetoothEnabled)
        }
        binding.tvBlePeripheralSupport.apply {
            text = getString(if (state.bleStatus.peripheralSupported) R.string.status_supported else R.string.status_not_supported)
            setStatusColor(state.bleStatus.peripheralSupported)
        }
        binding.tvBleAdvertising.apply {
            text = mapAdvertisingStatus(state.bleStatus.advertisingStatus)
            setStatusColor(state.bleStatus.advertisingStatus == AdvertisingStatus.ACTIVE)
        }
        binding.tvBleGattServer.apply {
            text = getString(if (state.bleStatus.gattServerInitialized) R.string.status_initialized else R.string.status_not_initialized)
            setStatusColor(state.bleStatus.gattServerInitialized)
        }
        binding.tvBleClient.apply {
            text = state.bleStatus.connectedDeviceAddress?.let {
                "${state.bleStatus.connectedDeviceName ?: "iPhone"} ($it)"
            } ?: getString(R.string.status_disconnected)
            setStatusColor(iphoneConnected)
        }
        val authLabel = getString(
            if (state.bleStatus.isClientAuthenticated) R.string.status_authenticated else R.string.status_unauthenticated
        )
        binding.tvBleMtuAuth.text = "MTU ${state.bleStatus.negotiatedMtu} • $authLabel"

        // Section D: Network Status
        binding.tvNetTransport.text = buildString {
            append(state.networkStatus.transportType.name)
            state.networkStatus.interfaceName?.let { append(" ($it)") }
        }
        binding.tvNetIp.text = state.networkStatus.localIp ?: "--"
        binding.tvNetPorts.text = "TCP ${state.networkStatus.controlPort} / UDP ${state.networkStatus.devAudioUdpPort}"
        binding.tvNetServerState.apply {
            text = mapServerState(state.networkStatus.serverState)
            setStatusColor(state.networkStatus.serverState == ServerRunState.LISTENING)
        }
        binding.tvNetClient.text = state.networkStatus.connectedClient?.let {
            "${it.remoteAddress}:${it.remotePort}"
        } ?: getString(R.string.status_none)

        // Section E: Call Status
        binding.tvCallDetailState.text = mapCallState(state.callInfo.state)
        binding.tvCallDirection.text = mapCallDirection(state.callInfo.direction)
        binding.tvCallerNumber.text = state.callInfo.phoneNumber?.let { Logger.maskPhoneNumber(it) }
            ?: getString(R.string.status_none)
        binding.tvCallControlCapability.text = mapCallCapability(state.callCapability)

        val fullControl = state.callCapability == CallControlCapability.FULL_INCALL_SERVICE
        binding.btnAnswerCall.isEnabled = fullControl && state.callInfo.state == CallState.RINGING
        binding.btnRejectCall.isEnabled = fullControl && state.callInfo.state == CallState.RINGING
        binding.btnEndCall.isEnabled = fullControl &&
            (state.callInfo.state == CallState.OFFHOOK || state.callInfo.state == CallState.RINGING)

        // Section F: Audio Status
        binding.tvAudioMicPerm.apply {
            text = getString(if (state.audioReport.microphonePermissionGranted) R.string.btn_granted else R.string.status_unavailable)
            setStatusColor(state.audioReport.microphonePermissionGranted)
        }
        binding.tvAudioRecord.apply {
            text = getString(if (state.audioReport.audioRecordAvailable) R.string.status_available else R.string.status_unavailable)
            setStatusColor(state.audioReport.audioRecordAvailable)
        }
        binding.tvAudioTrack.apply {
            text = getString(if (state.audioReport.audioTrackAvailable) R.string.status_available else R.string.status_unavailable)
            setStatusColor(state.audioReport.audioTrackAvailable)
        }
        val fx = state.audioReport.effectsAvailability
        binding.tvAudioEffects.text =
            "AEC:${yn(fx.acousticEchoCancelerAvailable)} NS:${yn(fx.noiseSuppressorAvailable)} AGC:${yn(fx.automaticGainControlAvailable)}"
        binding.tvCellularAudioCap.apply {
            text = state.audioReport.cellularAudioCapability.name
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
        }
        binding.tvAudioMetrics.text = if (state.audioMetrics.mode == AudioBridgeMode.IDLE) {
            "IDLE • 16kHz PCM16 Mono"
        } else {
            "${state.audioMetrics.mode.name} • ${state.audioMetrics.framesCaptured}f • ${state.audioMetrics.estimatedLatencyMs}ms"
        }

        // Section G: Live Event Log
        logAdapter.submitList(state.logs)
        binding.tvEmptyLogs.visibility = if (state.logs.isEmpty()) View.VISIBLE else View.GONE

        // Pairing explicit approval prompt when a remote iPhone requests pairing
        val pairingState = state.pairingState
        if (pairingState is PairingState.AwaitingUserApproval) {
            val req = pairingState.request
            val reqKey = "${req.deviceId}_${req.createdAtMs}"
            if (lastPromptedRequestId != reqKey) {
                lastPromptedRequestId = reqKey
                activeApprovalDialog?.dismiss()
                activeApprovalDialog = PairingApprovalDialog.showExplicitApprovalDialog(
                    context = this,
                    request = req,
                    onApprove = { viewModel.approvePendingPairing() },
                    onReject = { viewModel.rejectPendingPairing() }
                )
            }
        }
    }

    private fun requestPermissionsFor(category: PermissionCategory, onGranted: (() -> Unit)? = null) {
        val container = (application as App).container
        val perms = container.permissionManager.requiredPermissionsFor(category)
        if (perms.isEmpty()) {
            viewModel.refreshAllStatuses()
            onGranted?.invoke()
            return
        }
        pendingAfterPermissionAction = onGranted
        permissionLauncher.launch(perms)
    }

    private fun bindPermissionButton(button: MaterialButton, state: GrantState, isRole: Boolean) {
        when (state) {
            GrantState.GRANTED -> {
                button.text = getString(R.string.btn_granted)
                button.isEnabled = false
            }
            GrantState.NOT_REQUIRED_ON_API -> {
                button.text = getString(R.string.btn_not_needed)
                button.isEnabled = false
            }
            GrantState.UNSUPPORTED_ON_API -> {
                button.text = getString(R.string.btn_unsupported)
                button.isEnabled = false
            }
            GrantState.DENIED -> {
                button.text = getString(if (isRole) R.string.btn_configure else R.string.btn_grant)
                button.isEnabled = true
            }
        }
    }

    private fun toggleLanguage() {
        val prefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(App.KEY_LANGUAGE_TAG, App.DEFAULT_LANGUAGE_TAG) ?: App.DEFAULT_LANGUAGE_TAG
        val next = if (current == "fa") "en" else "fa"
        prefs.edit().putString(App.KEY_LANGUAGE_TAG, next).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
    }

    private fun handleDialIntentIfPresent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_DIAL || action == Intent.ACTION_VIEW) {
            val data = intent.data
            if (data?.scheme == "tel" && !data.schemeSpecificPart.isNullOrBlank()) {
                Logger.i("Telephony", "Received system DIAL intent (${Logger.maskPhoneNumber(data.schemeSpecificPart)})")
            }
        }
    }

    private fun mapServiceState(state: BridgeServiceState): String = when (state) {
        BridgeServiceState.RUNNING -> getString(R.string.status_running)
        BridgeServiceState.STARTING -> getString(R.string.status_starting)
        BridgeServiceState.STOPPED -> getString(R.string.status_stopped)
        BridgeServiceState.ERROR -> getString(R.string.status_error)
    }

    private fun mapAdvertisingStatus(status: AdvertisingStatus): String = when (status) {
        AdvertisingStatus.ACTIVE -> getString(R.string.status_active)
        AdvertisingStatus.STARTING -> getString(R.string.status_starting)
        AdvertisingStatus.INACTIVE -> getString(R.string.status_inactive)
        AdvertisingStatus.UNSUPPORTED -> getString(R.string.status_not_supported)
        AdvertisingStatus.PERMISSION_MISSING -> getString(R.string.status_error)
        AdvertisingStatus.BLUETOOTH_DISABLED -> getString(R.string.status_disabled)
        AdvertisingStatus.FAILED -> getString(R.string.status_error)
    }

    private fun mapServerState(state: ServerRunState): String = when (state) {
        ServerRunState.LISTENING -> getString(R.string.status_listening)
        ServerRunState.STARTING -> getString(R.string.status_starting)
        ServerRunState.STOPPED -> getString(R.string.status_stopped)
        ServerRunState.ERROR -> getString(R.string.status_error)
    }

    private fun mapCallState(state: CallState): String = when (state) {
        CallState.IDLE -> getString(R.string.call_state_idle)
        CallState.RINGING -> getString(R.string.call_state_ringing)
        CallState.OFFHOOK -> getString(R.string.call_state_offhook)
        CallState.DISCONNECTED -> getString(R.string.call_state_disconnected)
        CallState.UNKNOWN -> getString(R.string.call_state_unknown)
    }

    private fun mapCallDirection(dir: CallDirection?): String = when (dir) {
        CallDirection.INCOMING -> getString(R.string.call_dir_incoming)
        CallDirection.OUTGOING -> getString(R.string.call_dir_outgoing)
        CallDirection.UNKNOWN, null -> getString(R.string.call_dir_unknown)
    }

    private fun mapCallCapability(cap: CallControlCapability): String = when (cap) {
        CallControlCapability.FULL_INCALL_SERVICE -> getString(R.string.call_cap_full)
        CallControlCapability.MONITOR_ONLY_NOT_DIALER -> getString(R.string.call_cap_not_dialer)
        CallControlCapability.MONITOR_ONLY_LEGACY_API -> getString(R.string.call_cap_legacy)
        CallControlCapability.PERMISSION_MISSING -> getString(R.string.call_cap_no_perm)
    }

    private fun TextView.setStatusColor(positive: Boolean) {
        val colorRes = if (positive) R.color.status_ok else R.color.status_neutral
        setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
    }

    private fun yn(flag: Boolean): String = if (flag) "Y" else "N"

    private fun showMessage(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }
}

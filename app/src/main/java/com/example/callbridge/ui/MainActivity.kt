package com.example.callbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import com.example.callbridge.bluetooth.AdvertisingStatus
import com.example.callbridge.core.App
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.network.ServerRunState
import com.example.callbridge.permissions.GrantState
import com.example.callbridge.permissions.PermissionCategory
import com.example.callbridge.security.PairingState
import com.example.callbridge.service.BridgeServiceState
import com.example.callbridge.ui.dialogs.BridgeActionDialogs
import com.example.callbridge.ui.dialogs.PairingApprovalDialog
import com.example.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * BridgeLink Android Companion Activity (Native XML + ViewBinding, Persian RTL default + English toggle).
 * Hosts 6 functional screens: Dashboard, Messages, Contacts, Calls & Audio, Pairing, and Diagnostics.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DashboardViewModel by viewModels()
    private val logAdapter = EventLogAdapter()

    private enum class ScreenTab {
        DASHBOARD, MESSAGES, CONTACTS, CALLS, PAIRING, DIAGNOSTICS
    }

    private var activeTab = ScreenTab.DASHBOARD
    private var lastPromptedPairingRequestAt: Long = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val grantedCount = results.values.count { it }
        Logger.i("Permissions", "Runtime permission prompt finished ($grantedCount/${results.size} granted)")
        viewModel.refreshAllStates()
    }

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Logger.i("Telecom", "Returned from Default Dialer role request")
        viewModel.refreshAllStates()
    }

    private val batterySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAllStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdgeInsets()
        setupBackNavigation()
        setupRecyclerView()
        setupNavigation()
        setupButtonHandlers()
        observeState()
        handleIncomingDialIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingDialIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAllStates()
    }

    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topHeaderBar.setPadding(
                binding.topHeaderBar.paddingLeft,
                sysBars.top + dp(10),
                binding.topHeaderBar.paddingRight,
                dp(12)
            )
            binding.bottomNav.setPadding(0, 0, 0, sysBars.bottom)
            insets
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeTab != ScreenTab.DASHBOARD) {
                    selectTab(ScreenTab.DASHBOARD, syncBottomNav = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupRecyclerView() {
        binding.tabDiagnostics.rvEventLogs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = false
            }
            adapter = logAdapter
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val next = when (item.itemId) {
                R.id.nav_dashboard -> ScreenTab.DASHBOARD
                R.id.nav_messages -> ScreenTab.MESSAGES
                R.id.nav_contacts -> ScreenTab.CONTACTS
                R.id.nav_calls -> ScreenTab.CALLS
                R.id.nav_pairing -> ScreenTab.PAIRING
                else -> ScreenTab.DASHBOARD
            }
            selectTab(next, syncBottomNav = false)
            true
        }

        binding.btnOpenDiagnostics.setOnClickListener {
            selectTab(ScreenTab.DIAGNOSTICS, syncBottomNav = false)
        }

        binding.btnToggleLanguage.setOnClickListener {
            toggleLanguage()
        }
    }

    private fun selectTab(tab: ScreenTab, syncBottomNav: Boolean) {
        activeTab = tab
        binding.tabDashboard.root.visibility = if (tab == ScreenTab.DASHBOARD) View.VISIBLE else View.GONE
        binding.tabMessages.root.visibility = if (tab == ScreenTab.MESSAGES) View.VISIBLE else View.GONE
        binding.tabContacts.root.visibility = if (tab == ScreenTab.CONTACTS) View.VISIBLE else View.GONE
        binding.tabCalls.root.visibility = if (tab == ScreenTab.CALLS) View.VISIBLE else View.GONE
        binding.tabPairing.root.visibility = if (tab == ScreenTab.PAIRING) View.VISIBLE else View.GONE
        binding.tabDiagnostics.root.visibility = if (tab == ScreenTab.DIAGNOSTICS) View.VISIBLE else View.GONE

        if (syncBottomNav) {
            val navId = when (tab) {
                ScreenTab.DASHBOARD -> R.id.nav_dashboard
                ScreenTab.MESSAGES -> R.id.nav_messages
                ScreenTab.CONTACTS -> R.id.nav_contacts
                ScreenTab.CALLS -> R.id.nav_calls
                ScreenTab.PAIRING -> R.id.nav_pairing
                ScreenTab.DIAGNOSTICS -> null
            }
            if (navId != null && binding.bottomNav.selectedItemId != navId) {
                binding.bottomNav.selectedItemId = navId
            }
        }
    }

    private fun setupButtonHandlers() {
        // Dashboard tab
        binding.tabDashboard.btnStartBridge.setOnClickListener { viewModel.startBridgeService() }
        binding.tabDashboard.btnStopBridge.setOnClickListener { viewModel.stopBridgeService() }
        binding.tabDashboard.btnSyncAndroidToIphone.setOnClickListener { viewModel.syncAndroidDataToIphone() }

        binding.tabDashboard.btnGrantBluetooth.setOnClickListener { requestCategory(PermissionCategory.BLUETOOTH) }
        binding.tabDashboard.btnGrantPhoneState.setOnClickListener { requestCategory(PermissionCategory.PHONE_STATE) }
        binding.tabDashboard.btnGrantContacts.setOnClickListener { requestCategory(PermissionCategory.CONTACTS) }
        binding.tabDashboard.btnGrantNotifications.setOnClickListener { requestCategory(PermissionCategory.NOTIFICATIONS) }
        binding.tabDashboard.btnGrantMicrophone.setOnClickListener { requestCategory(PermissionCategory.MICROPHONE) }
        binding.tabDashboard.btnGrantCallPhone.setOnClickListener { requestCategory(PermissionCategory.CALL_PHONE) }
        binding.tabDashboard.btnGrantDialerRole.setOnClickListener { requestDefaultDialerRole() }
        binding.tabDashboard.btnGrantBattery.setOnClickListener { promptBatteryOptimization() }

        // Messages tab (Play Creator compliant)
        binding.tabMessages.btnComposeSmsHandoff.setOnClickListener {
            BridgeActionDialogs.showSmsHandoffDialog(this) { destination, body ->
                viewModel.queueSmsForSystemHandoff(destination, body) { smsResult ->
                    val handoffIntent = viewModel.container.smsBridgeManager
                        .createSystemSmsHandoffIntent(destination, body)
                    runCatching {
                        startActivity(handoffIntent)
                        viewModel.markSmsDispatchedToSystem(smsResult.outboxId)
                    }.onFailure {
                        showToast("No SMS app available to handle ACTION_SENDTO")
                    }
                }
            }
        }
        binding.tabMessages.btnSelectSim.setOnClickListener {
            val sims = viewModel.simSubscriptions.value
            if (sims.isEmpty()) {
                showToast("Grant Phone State permission to inspect active SIM cards")
            } else {
                BridgeActionDialogs.showSimPickerDialog(
                    context = this,
                    subscriptions = sims,
                    currentSelectedId = viewModel.selectedSimId.value
                ) { subId -> viewModel.selectSim(subId) }
            }
        }

        // Contacts tab
        binding.tabContacts.btnSyncContactsNow.setOnClickListener { viewModel.syncAndroidDataToIphone() }
        binding.tabContacts.btnAddContact.setOnClickListener {
            BridgeActionDialogs.showAddContactDialog(this) { name, phone ->
                viewModel.addAndroidContact(name, phone)
            }
        }
        binding.tabContacts.btnRequestContactsPermInTab.setOnClickListener {
            requestCategory(PermissionCategory.CONTACTS)
        }

        // Calls & Audio tab
        binding.tabCalls.btnAnswerCall.setOnClickListener { viewModel.answerCall() }
        binding.tabCalls.btnRejectCall.setOnClickListener { viewModel.rejectCall() }
        binding.tabCalls.btnHangupCall.setOnClickListener { viewModel.hangupCall() }
        binding.tabCalls.btnDialNumber.setOnClickListener {
            BridgeActionDialogs.showDialDialog(this) { number -> viewModel.dialNumber(number) }
        }
        binding.tabCalls.btnSetDefaultDialer.setOnClickListener { requestDefaultDialerRole() }
        binding.tabCalls.btnStartMicTest.setOnClickListener { viewModel.startLocalMicTest() }
        binding.tabCalls.btnStopMicTest.setOnClickListener { viewModel.stopLocalMicTest() }

        // Pairing tab
        binding.tabPairing.btnRegenerateQr.setOnClickListener { viewModel.regeneratePairingQr() }
        binding.tabPairing.btnDisconnectIphone.setOnClickListener { viewModel.disconnectIphone() }
        binding.tabPairing.btnRevokePairing.setOnClickListener { viewModel.revokePairing() }

        // Diagnostics tab
        binding.tabDiagnostics.btnTestBle.setOnClickListener { viewModel.runBleSelfTest() }
        binding.tabDiagnostics.btnTestWss.setOnClickListener { viewModel.runWssSelfTest() }
        binding.tabDiagnostics.btnTestMicDiag.setOnClickListener { viewModel.runMicSelfTest() }
        binding.tabDiagnostics.btnClearLogs.setOnClickListener { viewModel.clearLogs() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.serviceState.collect { state ->
                        val label = when (state) {
                            BridgeServiceState.RUNNING -> getString(R.string.service_running)
                            BridgeServiceState.STARTING -> getString(R.string.service_starting)
                            BridgeServiceState.ERROR -> getString(R.string.service_error)
                            BridgeServiceState.STOPPED -> getString(R.string.service_stopped)
                        }
                        binding.tvHeaderServiceBadge.text = label
                        binding.tabDashboard.tvServiceStateDetail.text = label
                        binding.tabDashboard.btnStartBridge.isEnabled = state != BridgeServiceState.RUNNING
                        binding.tabDashboard.btnStopBridge.isEnabled = state == BridgeServiceState.RUNNING
                    }
                }

                launch {
                    viewModel.deviceCapabilities.collect { cap ->
                        binding.tabDashboard.tvDeviceApiSummary.text =
                            "${cap.androidVersionLabel} • BLE=${cap.hasBleHardware} • SIMs=${cap.activeSimCount}"
                        binding.tabDashboard.tvSimSummary.text = "${cap.activeSimCount}"
                    }
                }

                launch {
                    viewModel.bleStatus.collect { ble ->
                        val connectedText = if (ble.connectedDeviceAddress != null) {
                            "${getString(R.string.status_connected)} (${ble.connectedDeviceName ?: ble.connectedDeviceAddress}, MTU ${ble.negotiatedMtu})"
                        } else {
                            getString(R.string.status_disconnected)
                        }
                        binding.tabDashboard.tvIphoneStatus.text = connectedText

                        binding.tabDashboard.tvBleStatus.text = when (ble.advertisingStatus) {
                            AdvertisingStatus.ACTIVE -> "${getString(R.string.status_advertising)} (GATT=${ble.gattServerInitialized})"
                            AdvertisingStatus.UNSUPPORTED -> "${getString(R.string.status_unsupported)} (${ble.lastError.orEmpty()})"
                            AdvertisingStatus.PERMISSION_MISSING -> getString(R.string.status_denied)
                            else -> "${getString(R.string.status_inactive)} (${ble.advertisingStatus.name})"
                        }
                    }
                }

                launch {
                    viewModel.networkStatus.collect { net ->
                        binding.tabDashboard.tvWssEndpoint.text = net.wssEndpointUrl ?: "—"
                        val serverLabel = when (net.serverState) {
                            ServerRunState.LISTENING -> getString(R.string.status_listening)
                            ServerRunState.ERROR -> net.lastError ?: getString(R.string.service_error)
                            else -> getString(R.string.status_stopped)
                        }
                        binding.tabDashboard.tvNsdStatus.text =
                            "${if (net.nsdRegistered) "_bridgelink._tcp. Active" else getString(R.string.status_inactive)} • $serverLabel"
                        binding.tabPairing.tvTlsFingerprint.text = net.certSha256Fingerprint ?: "—"
                    }
                }

                launch {
                    viewModel.syncStatus.collect { sync ->
                        binding.tabDashboard.tvSyncStatus.text =
                            "${sync.state.name} • ${sync.activePageProgress}"
                    }
                }

                launch {
                    viewModel.permissions.collect { snap ->
                        renderGrantState(binding.tabDashboard.tvPermBluetooth, snap.bluetooth)
                        renderGrantState(binding.tabDashboard.tvPermPhoneState, snap.phoneState)
                        val contactsState = if (snap.canReadContacts() && snap.canWriteContacts()) GrantState.GRANTED else GrantState.DENIED
                        renderGrantState(binding.tabDashboard.tvPermContacts, contactsState)
                        renderGrantState(binding.tabDashboard.tvPermNotifications, snap.notifications)
                        renderGrantState(binding.tabDashboard.tvPermMicrophone, snap.microphone)
                        renderGrantState(binding.tabDashboard.tvPermCallPhone, snap.callPhone)
                        renderGrantState(binding.tabDashboard.tvPermDialerRole, snap.defaultDialerRole)
                        renderGrantState(binding.tabDashboard.tvPermBattery, snap.batteryOptimizationIgnored)
                    }
                }

                launch {
                    viewModel.smsStatus.collect { sms ->
                        binding.tabMessages.tvSmsModeValue.text = sms.mode.name
                        binding.tabMessages.tvDefaultSmsRole.text = if (sms.defaultSmsRoleHeld) {
                            getString(R.string.status_granted)
                        } else {
                            "Companion Handoff (No Restricted SMS Permissions)"
                        }
                        binding.tabMessages.tvSelectedSim.text = sms.selectedSimLabel
                        binding.tabMessages.tvSmsOutboxSummary.text =
                            "${sms.outboxTotalCount} (${sms.lastOutboxState ?: "IDLE"})"
                    }
                }

                launch {
                    viewModel.contactsStatus.collect { cs ->
                        binding.tabContacts.tvContactsCount.text = "${cs.totalContacts}"
                        binding.tabContacts.tvContactsObserver.text =
                            if (cs.observerActive) getString(R.string.status_connected) else getString(R.string.status_inactive)
                        binding.tabContacts.tvContactsConflicts.text = "${cs.pendingConflicts}"
                    }
                }

                launch {
                    viewModel.callInfo.collect { call ->
                        binding.tabCalls.tvCallState.text = "${call.state.name} (${call.direction?.name ?: "—"})"
                        val masked = Logger.maskPhoneNumber(call.phoneNumber)
                        binding.tabCalls.tvCallerInfo.text = listOfNotNull(call.displayName, masked)
                            .joinToString(" • ").ifBlank { "—" }
                    }
                }

                launch {
                    viewModel.callCapability.collect { cap ->
                        binding.tabCalls.tvCallCapability.text = cap.name
                    }
                }

                launch {
                    viewModel.observedCallsCount.collect { count ->
                        binding.tabCalls.tvObservedCallsCount.text = "$count"
                    }
                }

                launch {
                    viewModel.audioCapability.collect { report ->
                        binding.tabCalls.tvCellularAudioCap.text =
                            "${report.cellularAudioCapability.name} (Mic: ${report.microphoneTestCapability.name})"
                    }
                }

                launch {
                    viewModel.audioMetrics.collect { audio ->
                        binding.tabCalls.tvMicRmsLevel.text =
                            if (audio.isRunning) "${audio.framesCaptured} frames • ${audio.estimatedLatencyMs}ms (${audio.activeAudioSourceLabel})" else "0 (Stopped)"
                    }
                }

                launch {
                    viewModel.pairingState.collect { state ->
                        binding.tabPairing.tvAndroidDeviceId.text = viewModel.container.pairingManager.localDeviceId
                        when (state) {
                            is PairingState.PairingWindowOpen -> {
                                binding.tabPairing.tvPairingPin.text = state.pinCode
                            }
                            is PairingState.AwaitingUserApproval -> {
                                binding.tabPairing.tvPairingPin.text = state.request.pinCode
                                if (state.request.createdAtMs != lastPromptedPairingRequestAt) {
                                    lastPromptedPairingRequestAt = state.request.createdAtMs
                                    PairingApprovalDialog.show(
                                        context = this@MainActivity,
                                        request = state.request,
                                        onApprove = { viewModel.approvePendingPairing() },
                                        onReject = { viewModel.rejectPendingPairing() }
                                    )
                                }
                            }
                            is PairingState.Paired -> {
                                binding.tabPairing.tvPairedDevice.text = state.device.deviceName
                            }
                            PairingState.Idle -> {
                                binding.tabPairing.tvPairedDevice.text = getString(R.string.status_not_paired)
                            }
                        }
                    }
                }

                launch {
                    viewModel.pairedDevice.collect { device ->
                        binding.tabPairing.tvPairedDevice.text =
                            device?.deviceName ?: getString(R.string.status_not_paired)
                    }
                }

                launch {
                    viewModel.qrBitmap.collect { bmp ->
                        if (bmp != null) {
                            binding.tabPairing.ivPairingQrCode.setImageBitmap(bmp)
                        }
                    }
                }

                launch {
                    viewModel.lastDiagnosticResult.collect { text ->
                        binding.tabDiagnostics.tvLastDiagnosticResult.text = text
                    }
                }

                launch {
                    viewModel.logs.collect { list ->
                        logAdapter.submitList(list)
                    }
                }

                launch {
                    viewModel.toastEvents.collect { msg ->
                        showToast(msg)
                    }
                }
            }
        }
    }

    private fun renderGrantState(tv: TextView, state: GrantState) {
        val (textRes, colorRes) = when (state) {
            GrantState.GRANTED -> R.string.status_granted to R.color.status_ok
            GrantState.DENIED -> R.string.status_denied to R.color.status_warn
            GrantState.NOT_REQUIRED_ON_API -> R.string.status_not_required to R.color.status_neutral
            GrantState.UNSUPPORTED_ON_API -> R.string.status_unsupported to R.color.status_error
            GrantState.RESTRICTED_PLAY_CREATOR_MODE -> R.string.status_creator_safe to R.color.status_ok
        }
        tv.setText(textRes)
        tv.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun requestCategory(category: PermissionCategory) {
        val perms = viewModel.container.permissionManager.requiredPermissionsFor(category)
        if (perms.isEmpty()) {
            showToast(getString(R.string.status_not_required))
            viewModel.refreshAllStates()
            return
        }
        permissionLauncher.launch(perms)
    }

    private fun requestDefaultDialerRole() {
        val intent = viewModel.container.defaultDialerManager.createRequestDefaultDialerIntent()
        if (intent == null) {
            showToast(getString(R.string.status_unsupported))
            return
        }
        runCatching { defaultDialerLauncher.launch(intent) }
            .onFailure { showToast("Unable to open Default Dialer role picker: ${it.message}") }
    }

    private fun promptBatteryOptimization() {
        val intent = viewModel.container.permissionManager.createBatteryOptimizationIntent()
        if (intent == null) {
            showToast(getString(R.string.status_not_required))
            return
        }
        BridgeActionDialogs.showBatteryOptimizationDialog(this) {
            runCatching { batterySettingsLauncher.launch(intent) }
                .onFailure { startActivity(viewModel.container.permissionManager.createAppSettingsIntent()) }
        }
    }

    private fun toggleLanguage() {
        val prefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(App.KEY_LANGUAGE_TAG, App.DEFAULT_LANGUAGE_TAG) ?: App.DEFAULT_LANGUAGE_TAG
        val next = if (current == "fa") "en" else "fa"
        prefs.edit().putString(App.KEY_LANGUAGE_TAG, next).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
    }

    private fun handleIncomingDialIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tel" && !data.schemeSpecificPart.isNullOrBlank()) {
            selectTab(ScreenTab.CALLS, syncBottomNav = true)
            BridgeActionDialogs.showDialDialog(this) { number -> viewModel.dialNumber(number) }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

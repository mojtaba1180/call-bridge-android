package com.example.callbridge.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Advertises the BridgeLink local TLS WebSocket endpoint over mDNS/Bonjour (`_bridgelink._tcp.`)
 * using Android's [NsdManager] so the paired iOS client can discover dynamic LAN/Hotspot IPs.
 */
class NetworkDiscoveryManager(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _registeredServiceName = MutableStateFlow<String?>(null)
    val registeredServiceName: StateFlow<String?> = _registeredServiceName.asStateFlow()

    private var activeListener: NsdManager.RegistrationListener? = null

    fun registerService(port: Int, deviceId: String, certFingerprintShort: String?) {
        val manager = nsdManager ?: run {
            Logger.w("NSD", "NsdManager unavailable on this device")
            return
        }
        unregisterService()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${AppConfig.NSD_SERVICE_NAME_PREFIX}-${deviceId.takeLast(6)}"
            serviceType = AppConfig.NSD_SERVICE_TYPE
            setPort(port)
            runCatching {
                setAttribute("v", AppConfig.PROTOCOL_VERSION.toString())
                setAttribute("deviceId", deviceId)
                setAttribute("path", AppConfig.DEFAULT_WSS_PATH)
                if (!certFingerprintShort.isNullOrBlank()) {
                    setAttribute("fp", certFingerprintShort.take(32))
                }
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                _registeredServiceName.value = info.serviceName
                _isRegistered.value = true
                Logger.i("NSD", "mDNS service registered: ${info.serviceName} (${AppConfig.NSD_SERVICE_TYPE}:$port)")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _isRegistered.value = false
                Logger.w("NSD", "mDNS service registration failed (code=$errorCode)")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                _isRegistered.value = false
                _registeredServiceName.value = null
                Logger.i("NSD", "mDNS service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _isRegistered.value = false
                Logger.w("NSD", "mDNS unregistration error (code=$errorCode)")
            }
        }

        activeListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { t ->
            activeListener = null
            _isRegistered.value = false
            Logger.w("NSD", "Failed to register NSD service: ${t.message}")
        }
    }

    fun unregisterService() {
        val manager = nsdManager ?: return
        val listener = activeListener ?: return
        activeListener = null
        runCatching {
            manager.unregisterService(listener)
        }.onFailure { t ->
            Logger.w("NSD", "Error unregistering NSD service: ${t.message}")
        }
        _isRegistered.value = false
        _registeredServiceName.value = null
    }
}

package com.example.callbridge.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.callbridge.core.Logger
import com.example.callbridge.utils.NetworkUtils
import com.example.callbridge.utils.VersionUtils
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Dynamically resolves the active local IPv4 address across Wi-Fi, Android Wi-Fi Hotspot (SoftAP),
 * and Ethernet interfaces without ever hardcoding gateway/hotspot IP assumptions.
 */
class LocalIpResolver(context: Context) {

    private val appContext = context.applicationContext

    fun resolveActiveLocalAddress(): ResolvedLocalAddress? {
        val candidates = mutableListOf<ResolvedLocalAddress>()

        // 1. Inspect ConnectivityManager LinkProperties on API 23+ for active Wi-Fi/Ethernet
        if (VersionUtils.isAtLeastM() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(activeNetwork)
                val linkProps = cm?.getLinkProperties(activeNetwork)
                val isWifiOrEth = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                if (isWifiOrEth && linkProps != null) {
                    val ifaceName = linkProps.interfaceName ?: "wlan0"
                    val transport = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                        NetworkTransportType.ETHERNET
                    } else {
                        NetworkTransportType.WIFI
                    }
                    linkProps.linkAddresses.forEach { linkAddr ->
                        val addr = linkAddr.address
                        if (NetworkUtils.isUsableLocalIpv4(addr)) {
                            addr.hostAddress?.let { ip ->
                                candidates.add(ResolvedLocalAddress(ip, ifaceName, transport))
                            }
                        }
                    }
                }
            }
        }

        // 2. Enumerate active NetworkInterfaces (essential for Wi-Fi Hotspot / SoftAP interfaces
        // which do not always appear as ConnectivityManager.activeNetwork)
        candidates.addAll(scanNetworkInterfaces())

        return selectBestCandidate(candidates)
    }

    internal fun scanNetworkInterfaces(): List<ResolvedLocalAddress> {
        val results = mutableListOf<ResolvedLocalAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name ?: continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && NetworkUtils.isUsableLocalIpv4(addr)) {
                        val ip = addr.hostAddress ?: continue
                        val type = classifyInterface(name)
                        results.add(ResolvedLocalAddress(ip, name, type))
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.w("Network", "Failed to enumerate network interfaces: ${t.message}")
        }
        return results
    }

    companion object {
        fun classifyInterface(interfaceName: String): NetworkTransportType {
            val lower = interfaceName.lowercase()
            return when {
                NetworkUtils.isLikelyHotspotInterface(lower) -> NetworkTransportType.HOTSPOT
                lower.startsWith("wlan") -> NetworkTransportType.WIFI
                lower.startsWith("eth") -> NetworkTransportType.ETHERNET
                else -> NetworkTransportType.OTHER_LOCAL
            }
        }

        /**
         * Pure selection function preferring Hotspot/Wi-Fi local interfaces over cellular/VPN interfaces.
         */
        fun selectBestCandidate(candidates: List<ResolvedLocalAddress>): ResolvedLocalAddress? {
            if (candidates.isEmpty()) return null
            return candidates
                .distinctBy { "${it.hostIp}@${it.interfaceName}" }
                .filter { NetworkUtils.interfacePriority(it.interfaceName) >= 40 }
                .maxByOrNull { NetworkUtils.interfacePriority(it.interfaceName) }
        }
    }
}

package com.example.callbridge.utils

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Pure networking utility helpers for local IPv4 address filtering and interface classification.
 */
object NetworkUtils {

    private val hotspotInterfacePrefixes = listOf(
        "ap", "swlan", "wlan1", "wlan2", "softap", "tether", "rndis"
    )

    private val wifiInterfacePrefixes = listOf(
        "wlan0", "wlan", "eth"
    )

    /**
     * Returns true when [address] is a usable local IPv4 address (non-loopback, non-link-local,
     * site-local private RFC 1918 range or CGNAT range used by some OEM hotspots).
     */
    fun isUsableLocalIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
            return false
        }
        val host = address.hostAddress ?: return false
        return isPrivateIpv4String(host)
    }

    fun isPrivateIpv4String(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            // Carrier-grade NAT space occasionally assigned to tethered AP interfaces
            first == 100 && second in 64..127 -> true
            else -> false
        }
    }

    fun isLikelyHotspotInterface(interfaceName: String): Boolean {
        val normalized = interfaceName.lowercase()
        return hotspotInterfacePrefixes.any { normalized.startsWith(it) }
    }

    fun isLikelyWifiInterface(interfaceName: String): Boolean {
        val normalized = interfaceName.lowercase()
        return wifiInterfacePrefixes.any { normalized.startsWith(it) } &&
            !isLikelyHotspotInterface(normalized)
    }

    /**
     * Scores candidate local network interfaces so active Hotspot/Wi-Fi addresses are preferred
     * over cellular data interfaces (rmnet/ccmni/pdp).
     */
    fun interfacePriority(interfaceName: String): Int {
        val lower = interfaceName.lowercase()
        return when {
            isLikelyHotspotInterface(lower) -> 100
            lower.startsWith("wlan") -> 90
            lower.startsWith("eth") -> 80
            lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("pdp") -> 5
            lower.startsWith("tun") || lower.startsWith("ppp") -> 10
            else -> 40
        }
    }
}

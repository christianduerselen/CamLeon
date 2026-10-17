package de.camleon.common

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** Best-effort list of this device's non-loopback IPv4 addresses (Wi-Fi first when identifiable). */
    fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun controlUrls(port: Int): List<String> = localIpv4().map { "http://$it:$port" }
}

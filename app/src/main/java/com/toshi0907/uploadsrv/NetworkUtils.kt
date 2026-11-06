package com.toshi0907.uploadsrv

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * 端末のLAN内IPv4アドレス(例: 192.168.1.10)を返す。Wi-Fi(wlan*)を優先する。
     */
    fun localIpv4Addresses(): List<String> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull()
            .orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        return interfaces
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .mapNotNull { it.hostAddress }
            }
            .distinct()
    }
}

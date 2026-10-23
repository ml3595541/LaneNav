package com.lobster.lanenav.phone

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** 取第一个非回环 IPv4 地址(热点/WiFi)。 */
    fun localIpv4(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress ?: "未知"
        } catch (e: Exception) {
            "未知"
        }
    }
}

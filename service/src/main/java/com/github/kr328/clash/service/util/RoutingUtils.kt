package com.github.kr328.clash.service.util

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.regex.Pattern

object RoutingUtils {
    private val IPV4_PATTERN = Pattern.compile(
        "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
    )

    fun isIpv4Address(address: String): Boolean {
        return IPV4_PATTERN.matcher(address).matches()
    }

    /**
     * Mendapatkan semua IP v4 dari sebuah host secara aman.
     * Jika host sudah berupa IP, langsung dikembalikan tanpa lookup DNS.
     */
    fun resolveAllIpv4(host: String): List<String> {
        if (isIpv4Address(host)) {
            return listOf(host)
        }
        
        return try {
            InetAddress.getAllByName(host)
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Memecah 0.0.0.0/0 menjadi daftar CIDR yang mengecualikan satu IP spesifik.
     */
    fun calculateDynamicRoutes(excludeIp: String): List<Pair<String, Int>> {
        val routes = mutableListOf<Pair<String, Int>>()
        val target = try {
            val addr = InetAddress.getByName(excludeIp)
            if (addr !is Inet4Address) return listOf("0.0.0.0" to 0)
            ByteBuffer.wrap(addr.address).int
        } catch (e: Exception) {
            return listOf("0.0.0.0" to 0)
        }

        var currentAddr = 0
        var currentPrefix = 0

        for (i in 31 downTo 0) {
            val bit = (target ushr i) and 1
            val mask = if (i == 31) 0 else (-1 shl (i + 1))
            val base = target and mask
            
            val nextRouteAddr = base or ((1 xor bit) shl i)
            val nextPrefix = 32 - i
            
            routes.add(intToIp(nextRouteAddr) to nextPrefix)
        }

        return routes
    }

    private fun intToIp(ip: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            (ip ushr 24) and 0xff,
            (ip ushr 16) and 0xff,
            (ip ushr 8) and 0xff,
            ip and 0xff
        )
    }
}

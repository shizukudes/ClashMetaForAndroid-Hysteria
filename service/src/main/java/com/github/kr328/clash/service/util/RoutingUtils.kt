package com.github.kr328.clash.service.util

import java.net.InetAddress
import java.nio.ByteBuffer

object RoutingUtils {
    /**
     * Memecah 0.0.0.0/0 menjadi daftar CIDR yang mengecualikan satu IP spesifik.
     * Ini digunakan untuk mencegah loop trafik pada VPN tanpa menggunakan metode excludeRoute API 33+.
     */
    fun calculateDynamicRoutes(excludeIp: String): List<Pair<String, Int>> {
        val routes = mutableListOf<Pair<String, Int>>()
        val target = try {
            val bytes = InetAddress.getByName(excludeIp).address
            if (bytes.size != 4) return listOf("0.0.0.0" to 0) // Hanya dukung IPv4 untuk saat ini
            ByteBuffer.wrap(bytes).int
        } catch (e: Exception) {
            return listOf("0.0.0.0" to 0)
        }

        var currentAddr = 0
        var currentPrefix = 0

        // Algoritma pemecahan rute untuk mengecualikan satu IP (/32)
        for (i in 31 downTo 0) {
            val bit = (target ushr i) and 1
            val mask = (-1 shl (i + 1))
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

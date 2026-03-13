package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.HysteriaAccount
import com.github.kr328.clash.service.model.HysteriaConfig
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class HysteriaModule(service: Service) : Module<Unit>(service) {
    private val serviceStore = ServiceStore(service)
    private val processes = mutableListOf<Process>()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        var useTun2Socks: Boolean = false
        var socksPort: Int = 0
        var udpgwServer: String = ""
        var dnsGateway: String = "127.0.0.1:1053"

        fun requestStop() {
            useTun2Socks = false
            socksPort = 0
            udpgwServer = ""
            dnsGateway = "127.0.0.1:1053"
        }
    }

    override suspend fun run() {
        val activeUuid = serviceStore.activeProfile ?: return
        val configFile = service.importedDir.resolve(activeUuid.toString()).resolve("hysteria.json")
        
        // Reset state
        requestStop()

        if (!configFile.exists()) {
            Log.i("HysteriaModule: No hysteria.json found for profile $activeUuid")
            return
        }

        val config = try {
            json.decodeFromString(HysteriaConfig.serializer(), configFile.readText())
        } catch (e: Exception) {
            Log.e("HysteriaModule: Failed to parse hysteria.json", e)
            return
        }

        val enabledAccounts = config.accounts.filter { it.enabled }
        if (!config.enabled || enabledAccounts.isEmpty()) return

        val runtimeAccounts = config.activeAccountId
            ?.let { activeId -> enabledAccounts.firstOrNull { it.id == activeId } }
            ?.let { listOf(it) }
            ?: enabledAccounts

        if (runtimeAccounts.isNotEmpty() && runtimeAccounts[0].tunCore == "Tun2Socks") {
            useTun2Socks = true
            socksPort = config.localPort
            udpgwServer = if (config.udpForwarding) runtimeAccounts[0].udpgwServer.trim() else ""
            if (udpgwServer.isNotBlank() && !isValidHostPort(udpgwServer)) {
                Log.w("HysteriaModule: Invalid udpgw server '$udpgwServer', disabling UDPGW")
                udpgwServer = ""
            }

            dnsGateway = parseDnsGateway(config.yamlTemplate)
            Log.i("HysteriaModule: Tun2Socks Core C enabled (SOCKS 127.0.0.1:$socksPort, UDPGW: ${if (udpgwServer.isBlank()) "disabled" else udpgwServer}, DNSGW: $dnsGateway)")
        }

        try {
            startCores(config, runtimeAccounts)

            suspendCancellableCoroutine<Unit> {
                it.invokeOnCancellation {
                    stopCores()
                }
            }
        } catch (e: Exception) {
            Log.e("HysteriaModule: ${e.message}", e)
        } finally {
            stopCores()
        }
    }

    private suspend fun startCores(config: HysteriaConfig, enabledAccounts: List<HysteriaAccount>) {
        val libDir = service.applicationInfo.nativeLibraryDir
        val libUz = File(libDir, "libuz.so").absolutePath
        val libLoad = File(libDir, "libload.so").absolutePath
        val filesDir = service.filesDir

        val tunnelTargets = mutableListOf<String>()
        val hyLogLevel = when (config.logLevel.lowercase()) {
            "silent" -> "disable"
            "error" -> "error"
            "debug" -> "debug"
            else -> "info"
        }

        val usedPorts = mutableSetOf(config.localPort)
        enabledAccounts.forEach { account ->
            // Spawn 3 instances per account for better load balancing
            repeat(3) { i ->
                val port = reserveFreePort(usedPorts)

                val hyConfig = JSONObject().apply {
                    put("server", "${account.serverIp}:${account.serverPortRange}")
                    put("obfs", account.obfs)
                    put("auth", account.password)
                    put("loglevel", hyLogLevel)
                    put("socks5", JSONObject().put("listen", "127.0.0.1:$port"))
                    put("insecure", true)
                    put("recvwindowconn", config.recvWindowConn)
                    put("recvwindow", config.recvWindow)
                }

                val hyCmd = arrayListOf(libUz, "-s", account.obfs, "--config", hyConfig.toString())
                val hyPb = ProcessBuilder(hyCmd)
                hyPb.directory(filesDir)
                hyPb.environment()["LD_LIBRARY_PATH"] = libDir
                hyPb.redirectErrorStream(true)

                Log.i("HysteriaModule: Starting Hysteria instance ${i + 1} [${account.name}] on port $port")
                val process = hyPb.start()
                processes.add(process)
                tunnelTargets.add("127.0.0.1:$port")
            }
        }

        if (tunnelTargets.isEmpty()) return

        delay(1500)

        val lbCmd = mutableListOf(libLoad, "-lport", config.localPort.toString(), "-tunnel").apply {
            addAll(tunnelTargets)
        }

        val lbPb = ProcessBuilder(lbCmd)
        lbPb.directory(filesDir)
        lbPb.environment()["LD_LIBRARY_PATH"] = libDir
        lbPb.redirectErrorStream(true)

        Log.i("HysteriaModule: Starting LoadBalancer on port ${config.localPort}")
        val lbProcess = lbPb.start()
        processes.add(lbProcess)
    }

    private fun parseDnsGateway(yamlTemplate: String): String {
        val lines = yamlTemplate.lines()
        var inDnsBlock = false
        var dnsIndent = 0

        for (raw in lines) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) continue

            val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val trimmed = line.trimStart()

            if (!inDnsBlock) {
                if (trimmed == "dns:") {
                    inDnsBlock = true
                    dnsIndent = indent
                }
                continue
            }

            if (indent <= dnsIndent) {
                break
            }

            if (trimmed.startsWith("listen:")) {
                val value = trimmed.removePrefix("listen:").trim().trim('"', '\'')
                if (isValidTun2SocksDnsGateway(value)) {
                    return value
                }

                if (value.isNotBlank()) {
                    Log.w("HysteriaModule: Unsupported dns.listen for Tun2Socks ($value), fallback to 127.0.0.1:1053")
                }
            }
        }

        return "127.0.0.1:1053"
    }

    private fun isValidTun2SocksDnsGateway(value: String): Boolean {
        val host = value.substringBefore(':', "")
        val port = value.substringAfter(':', "")

        if (host.isBlank() || port.isBlank()) return false

        val portInt = port.toIntOrNull() ?: return false
        if (portInt !in 1..65535) return false

        val ip = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return ip is Inet4Address
    }


    private fun isValidHostPort(value: String): Boolean {
        val host = value.substringBefore(':', "")
        val port = value.substringAfter(':', "")

        if (host.isBlank() || port.isBlank()) return false
        val portInt = port.toIntOrNull() ?: return false
        return portInt in 1..65535
    }

    private fun reserveFreePort(usedPorts: MutableSet<Int>): Int {
        repeat(64) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (candidate !in usedPorts) {
                usedPorts.add(candidate)
                return candidate
            }
        }

        var fallback = 20000
        while (fallback in usedPorts) fallback++
        usedPorts.add(fallback)
        return fallback
    }

    private fun stopCores() {
        Log.i("HysteriaModule: Stopping cores")

        val snapshot = processes.toList()
        processes.clear()

        snapshot.forEach {
            it.destroy()
        }

        snapshot.forEach {
            it.waitFor(2, TimeUnit.SECONDS)
        }

        try {
            val killCommand = "pkill -9 libuz; pkill -9 libload; pkill -f libuz.so; pkill -f libload.so"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", killCommand)).waitFor()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

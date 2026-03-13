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
        var usePdnsd: Boolean = false
        var runClashTun: Boolean = true

        fun requestStop() {
            useTun2Socks = false
            socksPort = 0
            udpgwServer = ""
            dnsGateway = "127.0.0.1:1053"
            usePdnsd = false
            runClashTun = true
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
            runClashTun = false
            socksPort = config.localPort
            udpgwServer = if (config.udpForwarding) runtimeAccounts[0].udpgwServer.trim() else ""
            if (config.udpForwarding && (udpgwServer.isBlank() || isLoopbackHostPort(udpgwServer))) {
                val derivedPort = udpgwServer.substringAfter(':', "").toIntOrNull() ?: config.udpgwPort
                udpgwServer = "${runtimeAccounts[0].serverIp}:$derivedPort"
                Log.i("HysteriaModule: Using derived remote UDPGW endpoint $udpgwServer")
            }

            if (udpgwServer.isNotBlank() && !isValidHostPort(udpgwServer)) {
                Log.w("HysteriaModule: Invalid udpgw server '$udpgwServer', disabling UDPGW")
                udpgwServer = ""
            }

            dnsGateway = config.tun2SocksDnsGateway.trim().ifBlank { parseDnsGateway(config.yamlTemplate) }
            usePdnsd = config.tun2SocksUsePdnsd && startPdnsdIfAvailable(config)
            if (usePdnsd) {
                dnsGateway = "127.0.0.1:${config.pdnsdListenPort}"
            }

            Log.i("HysteriaModule: Tun2Socks Core C enabled (SOCKS 127.0.0.1:$socksPort, UDPGW: ${if (udpgwServer.isBlank()) "disabled" else udpgwServer}, DNSGW: $dnsGateway, PDNSD: ${if (usePdnsd) "enabled" else "disabled"})")
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




    private fun startPdnsdIfAvailable(config: HysteriaConfig): Boolean {
        val pdnsd = File(service.applicationInfo.nativeLibraryDir, "pdnsd")
        if (!pdnsd.exists()) {
            Log.w("HysteriaModule: pdnsd executable not found, fallback to Clash DNS")
            return false
        }

        val upstreams = parseConfiguredDnsUpstreams(config)
            .ifEmpty { listOf(DnsEndpoint("208.67.222.222", 443), DnsEndpoint("208.67.220.220", 443)) }

        val conf = File(service.filesDir, "pdnsd.conf")
        val cacheDir = File(service.filesDir, "pdnsd-cache").apply { mkdirs() }
        val serverBlocks = upstreams.mapIndexed { idx, endpoint ->
            """
            server {
              label="ns$idx";
              ip=${endpoint.host};
              port=${endpoint.port};
              timeout=6;
              uptest=none;
              interval=10m;
              purge_cache=off;
            }
            """.trimIndent()
        }.joinToString("\n\n")

        conf.writeText(
            """
            global {
              perm_cache=2048;
              cache_dir="${cacheDir.absolutePath}";
              server_ip=127.0.0.1;
              server_port=${config.pdnsdListenPort};
              query_method=tcp_only;
              timeout=10;
              min_ttl=15m;
              max_ttl=1w;
              neg_domain_pol=on;
              daemon=off;
              status_ctl=off;
            }

            $serverBlocks

            rr {
              name=localhost;
              reverse=on;
              a=127.0.0.1;
              owner=localhost;
              soa=localhost,root.localhost,42,86400,900,86400,86400;
            }
            """.trimIndent()
        )

        return runCatching {
            val pb = ProcessBuilder(pdnsd.absolutePath, "-c", conf.absolutePath, "-d")
            pb.directory(service.filesDir)
            pb.redirectErrorStream(true)
            val p = pb.start()
            processes.add(p)
            Log.i("HysteriaModule: Started pdnsd for Tun2Socks DNS at 127.0.0.1:${config.pdnsdListenPort} with upstreams=$upstreams")
            true
        }.getOrElse {
            Log.w("HysteriaModule: Failed to start pdnsd (${it.message}), fallback to Clash DNS")
            false
        }
    }

    private data class DnsEndpoint(val host: String, val port: Int)

    private fun parseConfiguredDnsUpstreams(config: HysteriaConfig): List<DnsEndpoint> {
        val fromUi = config.pdnsdUpstreams
            .split(',')
            .mapNotNull { parseDnsEndpoint(it.trim()) }

        if (fromUi.isNotEmpty()) {
            return fromUi.distinct()
        }

        return parseUpstreamDnsServers(config.yamlTemplate)
    }

    private fun parseUpstreamDnsServers(yamlTemplate: String): List<DnsEndpoint> {
        val lines = yamlTemplate.lines()
        val servers = mutableListOf<DnsEndpoint>()
        var inDnsBlock = false
        var dnsIndent = 0
        var inNameServer = false
        var nameServerIndent = 0

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

            if (!inNameServer) {
                if (trimmed == "nameserver:" || trimmed == "default-nameserver:") {
                    inNameServer = true
                    nameServerIndent = indent
                }
                continue
            }

            if (indent <= nameServerIndent) {
                inNameServer = false
                if (trimmed == "nameserver:" || trimmed == "default-nameserver:") {
                    inNameServer = true
                    nameServerIndent = indent
                }
                continue
            }

            if (trimmed.startsWith("-")) {
                val value = trimmed.removePrefix("-").trim().trim('"', '\'')
                parseDnsEndpoint(value)?.let { servers.add(it) }
            }
        }

        return servers.distinct()
    }

    private fun parseDnsEndpoint(raw: String): DnsEndpoint? {
        if (raw.isBlank()) return null

        val withoutScheme = raw
            .substringAfter("https://", raw)
            .substringAfter("tls://", raw)
            .substringAfter("tcp://", raw)
            .substringBefore('/')
            .trim()

        if (withoutScheme.isBlank()) return null

        val host = withoutScheme.substringBefore(':').trim()
        val portText = withoutScheme.substringAfter(':', "")
        val port = portText.toIntOrNull() ?: if (raw.startsWith("https://") || raw.startsWith("tls://") || raw.startsWith("tcp://")) 443 else 53

        if (host.isBlank() || port !in 1..65535) return null

        val ip = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        if (ip !is Inet4Address) return null

        return DnsEndpoint(ip.hostAddress ?: host, port)
    }


    private fun isLoopbackHostPort(value: String): Boolean {
        val host = value.substringBefore(':', "").lowercase()
        return host == "127.0.0.1" || host == "localhost"
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
            val killCommand = "pkill -9 libuz; pkill -9 libload; pkill -9 pdnsd; pkill -f libuz.so; pkill -f libload.so; pkill -f pdnsd"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", killCommand)).waitFor()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

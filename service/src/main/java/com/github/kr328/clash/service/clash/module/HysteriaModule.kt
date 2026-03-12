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
import java.util.concurrent.TimeUnit

class HysteriaModule(service: Service) : Module<Unit>(service) {
    private val serviceStore = ServiceStore(service)
    private val processes = mutableListOf<Process>()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        var useTun2Socks: Boolean = false
        var socksPort: Int = 0
        var udpgwServer: String = ""
        private var pdnsdProcess: Process? = null

        fun requestStop() {
            useTun2Socks = false
            pdnsdProcess?.destroy()
            pdnsdProcess = null
        }
    }

    private fun startPdnsd(libDir: String, filesDir: File) {
        val pdnsdBat = File(libDir, "libpdnsd.so") // Built as executable but named as .so by NDK
        if (!pdnsdBat.exists()) {
            Log.e("HysteriaModule: pdnsd binary not found in $libDir")
            return
        }

        val confFile = File(filesDir, "pdnsd.conf")
        val cacheFile = File(filesDir, "pdnsd.cache")
        
        if (!cacheFile.exists()) cacheFile.createNewFile()

        val confContent = """
            global {
                perm_cache=1024;
                cache_dir="${filesDir.absolutePath}";
                server_ip=127.0.0.1;
                server_port=10535;
                query_method=tcp_only;
                run_as="root";
                status_ctl=on;
            }
            server {
                label="clash";
                ip=127.0.0.1;
                port=1053;
                timeout=4;
                uptest=none;
            }
        """.trimIndent()
        
        confFile.writeText(confContent)

        try {
            pdnsdProcess?.destroy()
            val cmd = listOf(pdnsdBat.absolutePath, "-c", confFile.absolutePath, "-g")
            pdnsdProcess = ProcessBuilder(cmd)
                .directory(filesDir)
                .redirectErrorStream(true)
                .start()
            Log.i("HysteriaModule: pdnsd started on port 10535")
        } catch (e: Exception) {
            Log.e("HysteriaModule: Failed to start pdnsd: ${e.message}")
        }
    }

    override suspend fun run() {
        val activeUuid = serviceStore.activeProfile ?: return
        val configFile = service.importedDir.resolve(activeUuid.toString()).resolve("hysteria.json")
        
        // Reset state
        useTun2Socks = false

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
            socksPort = 20080
            udpgwServer = runtimeAccounts[0].udpgwServer
            Log.i("HysteriaModule: Tun2Socks Core enabled (SOCKS 127.0.0.1:$socksPort, UDPGW: $udpgwServer)")
            
            val libDir = service.applicationInfo.nativeLibraryDir
            startPdnsd(libDir, service.filesDir)
        }

        try {
            if (runtimeAccounts.size == 1) {
                Log.i("HysteriaModule: Using selected account ${runtimeAccounts[0].name}")
            } else {
                Log.i("HysteriaModule: Using load-balance mode with ${runtimeAccounts.size} accounts")
            }

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

        enabledAccounts.forEachIndexed { index, account ->
            val port = 20080 + index

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

            Log.i("HysteriaModule: Starting Hysteria [${account.name}] on port $port")
            val process = hyPb.start()
            processes.add(process)
            tunnelTargets.add("127.0.0.1:$port")
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

    private fun stopCores() {
        Log.i("HysteriaModule: Stopping cores")

        val snapshot = processes.toList()

        snapshot.forEach { process ->
            runCatching {
                process.destroy()
            }.onFailure {
                Log.w("HysteriaModule: Failed to send destroy: ${it.message}")
            }
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)
        while (System.nanoTime() < deadline && snapshot.any { it.isAlive }) {
            Thread.sleep(50)
        }

        snapshot.forEach { process ->
            if (process.isAlive) {
                runCatching {
                    process.destroyForcibly()
                }.onFailure {
                    Log.w("HysteriaModule: Failed to force destroy: ${it.message}")
                }
            }
        }

        val hasAliveProcesses = snapshot.any { it.isAlive }

        if (hasAliveProcesses) {
            val killCommand = "pkill -9 libuz; pkill -9 libload; pkill -f libuz.so; pkill -f libload.so"
            runCatching {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", killCommand)).waitFor(1, TimeUnit.SECONDS)
                Log.w("HysteriaModule: Fallback force-kill with pkill")
            }.onFailure {
                Log.w("HysteriaModule: Fallback force-kill failed: ${it.message}")
            }
        }

        processes.clear()
    }
}

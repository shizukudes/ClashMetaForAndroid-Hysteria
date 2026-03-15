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

    override suspend fun run() {
        val activeUuid = serviceStore.activeProfile ?: return
        val configFile = service.importedDir.resolve(activeUuid.toString()).resolve("hysteria.json")

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

        if (enabledAccounts.size == 1) {
            val account = enabledAccounts[0]
            val port = config.localPort

            val hyConfig = JSONObject().apply {
                put("server", "${account.serverIp}:${account.serverPortRange}")
                put("obfs", account.obfs)
                put("auth", account.password)
                put("loglevel", hyLogLevel)
                put("socks5", JSONObject().apply {
                    put("listen", "127.0.0.1:$port")
                    put("udp", true)
                })
                put("insecure", true)
                put("recvwindowconn", config.recvWindowConn)
                put("recvwindow", config.recvWindow)
            }

            val hyCmd = arrayListOf(libUz, "-s", account.obfs, "--config", hyConfig.toString())
            val hyPb = ProcessBuilder(hyCmd)
            hyPb.directory(filesDir)
            hyPb.environment()["LD_LIBRARY_PATH"] = libDir
            hyPb.redirectErrorStream(true)

            Log.i("HysteriaModule: Starting Hysteria directly on port $port (UDP Optimized)")
            val process = hyPb.start()
            processes.add(process)
            return
        }

        enabledAccounts.forEachIndexed { index, account ->
            val port = 20080 + index

            val hyConfig = JSONObject().apply {
                put("server", "${account.serverIp}:${account.serverPortRange}")
                put("obfs", account.obfs)
                put("auth", account.password)
                put("loglevel", hyLogLevel)
                put("socks5", JSONObject().apply {
                    put("listen", "127.0.0.1:$port")
                    put("udp", true)
                })
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

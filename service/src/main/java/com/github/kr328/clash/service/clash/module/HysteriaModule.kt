package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.HysteriaStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class HysteriaModule(service: Service) : Module<Unit>(service) {
    private val store = HysteriaStore(service)
    private val processes = mutableListOf<Process>()

    override suspend fun run() {
        if (!store.enabled) return

        try {
            startCores()
            
            // Wait for completion or cancellation
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

    private fun startCores() {
        val libDir = service.applicationInfo.nativeLibraryDir
        val libUz = File(libDir, "libuz.so").absolutePath
        val libLoad = File(libDir, "libload.so").absolutePath
        val filesDir = service.filesDir

        val ip = store.serverIp
        val range = store.serverPortRange
        val pass = store.password
        val obfs = store.obfs
        val recvWin = store.recvWindow
        val recvConn = store.recvWindowConn
        val coreCount = store.coreCount
        val logLevel = store.logLevel

        if (ip.isBlank() || range.isBlank()) {
            Log.e("HysteriaModule: IP or Range is blank")
            return
        }

        val ports = (0 until coreCount).map { 20080 + it }
        val tunnelTargets = mutableListOf<String>()

        val hyLogLevel = when(logLevel) {
            "silent" -> "disable"
            "error" -> "error"
            "debug" -> "debug"
            else -> "info"
        }

        for (port in ports) {
            val hyConfig = JSONObject()
            hyConfig.put("server", "$ip:$range")
            hyConfig.put("obfs", obfs)
            hyConfig.put("auth", pass)
            hyConfig.put("loglevel", hyLogLevel)
            
            val socks5Json = JSONObject()
            socks5Json.put("listen", "127.0.0.1:$port")
            hyConfig.put("socks5", socks5Json)
            
            hyConfig.put("insecure", true)
            hyConfig.put("recvwindowconn", recvConn)
            hyConfig.put("recvwindow", recvWin)
            
            val hyCmd = arrayListOf(libUz, "-s", obfs, "--config", hyConfig.toString())
            val hyPb = ProcessBuilder(hyCmd)
            hyPb.directory(filesDir)
            hyPb.environment()["LD_LIBRARY_PATH"] = libDir
            hyPb.redirectErrorStream(true)
            
            Log.i("HysteriaModule: Starting Hysteria on port $port")
            val p = hyPb.start()
            processes.add(p)
            tunnelTargets.add("127.0.0.1:$port")
        }
        
        logToApp("Waiting for cores to warm up...")
        Thread.sleep(1500)

        val lbCmd = mutableListOf(libLoad, "-lport", store.localPort.toString(), "-tunnel")
        lbCmd.addAll(tunnelTargets)
        
        val lbPb = ProcessBuilder(lbCmd)
        lbPb.directory(filesDir)
        lbPb.environment()["LD_LIBRARY_PATH"] = libDir
        lbPb.redirectErrorStream(true)
        
        Log.i("HysteriaModule: Starting LoadBalancer on port ${store.localPort}")
        val lbProcess = lbPb.start()
        processes.add(lbProcess)
    }

    private fun logToApp(msg: String) {
        Log.i("HysteriaModule: $msg")
    }

    private fun stopCores() {
        Log.i("HysteriaModule: Stopping cores")
        processes.forEach { 
            it.destroy()
        }
        processes.clear()
        
        // Kill processes by name just in case
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 libuz; pkill -9 libload; pkill -f libuz.so; pkill -f libload.so"))
        } catch (e: Exception) {
            // Ignore
        }
    }
}

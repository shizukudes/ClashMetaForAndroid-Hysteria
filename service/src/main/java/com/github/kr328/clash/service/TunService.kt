package com.github.kr328.clash.service

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.HysteriaConfig
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.parseCIDR
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json

class TunService : VpnService(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    private val self: TunService
        get() = this

    private var reason: String? = null

    private val runtime = clashRuntime {
        val store = ServiceStore(self)
        val tun2SocksMode = detectTun2SocksMode(store)

        if (tun2SocksMode) {
            HysteriaModule.runClashTun = false
            Log.i("TunService: Tun2Socks mode detected, skipping Clash configuration module")
        }

        val close = install(CloseModule(self))
        val tun = install(TunModule(self))
        val config = if (tun2SocksMode) null else install(ConfigurationModule(self))
        val network = install(NetworkObserveModule(self))
        install(HysteriaModule(self))

        if (store.dynamicNotification)
            install(DynamicNotificationModule(self))
        else
            install(StaticNotificationModule(self))

        install(AppListCacheModule(self))
        install(TimeZoneModule(self))
        install(SuspendModule(self))

        try {
            tun.open()

            while (isActive) {
                val quit = select<Boolean> {
                    close.onEvent {
                        true
                    }
                    config?.onEvent {
                        reason = it.message

                        true
                    }
                    network.onEvent { n ->
                        if (Build.VERSION.SDK_INT in 22..28) @TargetApi(22) {
                            setUnderlyingNetworks(n?.let { arrayOf(it) })
                        }

                        false
                    }
                }

                if (quit) break
            }
        } catch (e: Exception) {
            Log.e("Create clash runtime: ${e.message}", e)

            reason = e.message
        } finally {
            withContext(NonCancellable) {
                tun.close()

                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (StatusProvider.serviceRunning)
            return stopSelf()

        StatusProvider.serviceRunning = true

        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)

        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendClashStarted()

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        TunModule.requestStop()

        StatusProvider.serviceRunning = false

        sendClashStopped(reason)

        cancelAndJoinBlocking()

        Log.i("TunService destroyed: ${reason ?: "successfully"}")

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }

    private fun TunModule.open() {
        val store = ServiceStore(self)

        val device = with(Builder()) {
            // Interface address
            addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
            if (store.allowIpv6) {
                addAddress(TUN_GATEWAY6, TUN_SUBNET_PREFIX6)
            }

            // Route
            if (store.bypassPrivateNetwork) {
                resources.getStringArray(R.array.bypass_private_route).map(::parseCIDR).forEach {
                    addRoute(it.ip, it.prefix)
                }
                if (store.allowIpv6) {
                    resources.getStringArray(R.array.bypass_private_route6).map(::parseCIDR).forEach {
                        addRoute(it.ip, it.prefix)
                    }
                }

                // Route of virtual DNS
                addRoute(TUN_DNS, 32)
                if (store.allowIpv6) {
                    addRoute(TUN_DNS6, 128)
                }
            } else {
                addRoute(NET_ANY, 0)
                if (store.allowIpv6) {
                    addRoute(NET_ANY6, 0)
                }
            }

            // Access Control
            val usingTun2Socks = HysteriaModule.useTun2Socks
            when (store.accessControlMode) {
                AccessControlMode.AcceptAll -> {
                    // Clash mode: include app UID by default (legacy behavior).
                    // Tun2Socks mode: app UID must bypass VPN to avoid self-capture routing loop.
                    if (usingTun2Socks) {
                        runCatching { addDisallowedApplication(packageName) }
                    }
                }
                AccessControlMode.AcceptSelected -> {
                    // In allow-list mode, only selected apps are routed to VPN.
                    // For Tun2Socks, do NOT add app package to allowed set (it should bypass VPN).
                    val allowed = if (usingTun2Socks) {
                        store.accessControlPackages
                    } else {
                        store.accessControlPackages + packageName
                    }

                    allowed.forEach {
                        runCatching { addAllowedApplication(it) }
                    }
                }
                AccessControlMode.DenySelected -> {
                    // In deny-list mode, all apps except listed ones are routed to VPN.
                    // For Tun2Socks, force app package to bypass VPN.
                    val disallowed = if (usingTun2Socks) {
                        store.accessControlPackages + packageName
                    } else {
                        store.accessControlPackages - packageName
                    }

                    disallowed.forEach {
                        runCatching { addDisallowedApplication(it) }
                    }
                }
            }

            // Blocking
            setBlocking(false)

            // Mtu
            setMtu(TUN_MTU)

            // Session Name
            setSession("Clash")

            // Virtual Dns Server
            addDnsServer(TUN_DNS)
            if (store.allowIpv6) {
                addDnsServer(TUN_DNS6)
            }

            // Open MainActivity
            setConfigureIntent(
                PendingIntent.getActivity(
                    self,
                    R.id.nf_vpn_status,
                    Intent().setComponent(Components.MAIN_ACTIVITY),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )

            // Metered
            if (Build.VERSION.SDK_INT >= 29) {
                setMetered(false)
            }

            // System Proxy
            if (Build.VERSION.SDK_INT >= 29 && store.systemProxy) {
                listenHttp()?.let {
                    setHttpProxy(
                        ProxyInfo.buildDirectProxy(
                            it.address.hostAddress,
                            it.port,
                            HTTP_PROXY_BLACK_LIST + if (store.bypassPrivateNetwork) HTTP_PROXY_LOCAL_LIST else emptyList()
                        )
                    )
                }
            }

            if (store.allowBypass) {
                allowBypass()
            }

            TunModule.TunDevice(
                fd = establish()?.detachFd()
                    ?: throw NullPointerException("Establish VPN rejected by system"),
                stack = store.tunStackMode,
                gateway = "$TUN_GATEWAY/$TUN_SUBNET_PREFIX" + if (store.allowIpv6) ",$TUN_GATEWAY6/$TUN_SUBNET_PREFIX6" else "",
                portal = TUN_PORTAL + if (store.allowIpv6) ",$TUN_PORTAL6" else "",
                dns = if (store.dnsHijacking) NET_ANY else (TUN_DNS + if (store.allowIpv6) ",$TUN_DNS6" else ""),
            )
        }

        if (HysteriaModule.useTun2Socks) {
            val socks = "127.0.0.1:${HysteriaModule.socksPort}"
            val udpgw = HysteriaModule.udpgwServer
            // Tun2Socks C handles DNS via --dnsgw (independent from Clash tun DNS hijack path).
            // DNS gateway is configurable from Hysteria settings (fallback to YAML dns.listen / 127.0.0.1:1053).
            val dnsGateway = HysteriaModule.dnsGateway
            attachTun2Socks(device.fd, TUN_MTU, socks, udpgw, dnsGateway)
        } else if (HysteriaModule.runClashTun) {
            // Clash tun core handles DNS from TunDevice.dns and dns-hijack settings.
            attach(device)
        } else {
            throw IllegalStateException("No tunnel core selected")
        }
    }

    private fun detectTun2SocksMode(store: ServiceStore): Boolean {
        val active = store.activeProfile ?: return false
        val configFile = importedDir.resolve(active.toString()).resolve("hysteria.json")
        if (!configFile.exists()) return false

        return runCatching {
            val config = Json { ignoreUnknownKeys = true }
                .decodeFromString(HysteriaConfig.serializer(), configFile.readText())
            val enabledAccounts = config.accounts.filter { it.enabled }
            if (!config.enabled || enabledAccounts.isEmpty()) {
                false
            } else {
                val activeAccount = config.activeAccountId
                    ?.let { activeId -> enabledAccounts.firstOrNull { it.id == activeId } }
                    ?: enabledAccounts.firstOrNull()
                activeAccount?.tunCore == "Tun2Socks"
            }
        }.getOrElse {
            Log.w("TunService: Failed to detect Tun2Socks mode (${it.message})")
            false
        }
    }

    companion object {
        private const val TUN_MTU = 1500
        private const val TUN_SUBNET_PREFIX = 30
        private const val TUN_GATEWAY = "172.19.0.1"
        private const val TUN_SUBNET_PREFIX6 = 126
        private const val TUN_GATEWAY6 = "fdfe:dcba:9876::1"
        private const val TUN_PORTAL = "172.19.0.2"
        private const val TUN_PORTAL6 = "fdfe:dcba:9876::2"
        private const val TUN_DNS = TUN_PORTAL
        private const val TUN_DNS6 = TUN_PORTAL6
        private const val NET_ANY = "0.0.0.0"
        private const val NET_ANY6 = "::"

        private val HTTP_PROXY_LOCAL_LIST: List<String> = listOf(
            "localhost",
            "*.local",
            "127.*",
            "10.*",
            "172.16.*",
            "172.17.*",
            "172.18.*",
            "172.19.*",
            "172.2*",
            "172.30.*",
            "172.31.*",
            "192.168.*"
        )
        private val HTTP_PROXY_BLACK_LIST: List<String> = listOf(
            "*zhihu.com",
            "*zhimg.com",
            "*jd.com",
            "100ime-iat-api.xfyun.cn",
            "*360buyimg.com",
        )
    }
}

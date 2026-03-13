package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

@Serializable
data class HysteriaAccount(
    val id: String,
    var name: String,
    var serverIp: String,
    var serverPortRange: String,
    var password: String,
    var obfs: String,
    var enabled: Boolean = true,
    var tunCore: String = "Clash", // "Clash" or "Tun2Socks"
    var udpgwServer: String = "",
)

@Serializable
data class HysteriaConfig(
    var enabled: Boolean = false,
    var localPort: Int = 7777,
    var recvWindowConn: Int = 131072,
    var recvWindow: Int = 327680,
    var logLevel: String = "info",
    var yamlTemplate: String = "",
    var udpForwarding: Boolean = true,
    var udpgwPort: Int = 7300,
    var tun2SocksDnsGateway: String = "127.0.0.1:1053",
    var tun2SocksUsePdnsd: Boolean = true,
    var pdnsdListenPort: Int = 1053,
    var pdnsdUpstreams: String = "208.67.222.222:443,208.67.220.220:443",
    var accounts: List<HysteriaAccount> = emptyList(),
    var activeAccountId: String? = null,
)

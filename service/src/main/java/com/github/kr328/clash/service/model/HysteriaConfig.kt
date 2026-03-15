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
)

@Serializable
data class HysteriaConfig(
    var enabled: Boolean = false,
    var localPort: Int = 7777,
    var recvWindowConn: Int = 131072,
    var recvWindow: Int = 327680,
    var logLevel: String = "info",
    var up: String = "100 Mbps",
    var down: String = "100 Mbps",
    var hop: Int = 1,
    var mtu: Int = 1200,
    var yamlTemplate: String = "proxies:\n  - name: Hysteria-Proxy\n    type: socks5\n    server: 127.0.0.1\n    port: 7777\n    udp: true\n    udp-over-tcp: \"127.0.0.1:7300\"\n",
    var accounts: List<HysteriaAccount> = emptyList(),
    var activeAccountId: String? = null,
)

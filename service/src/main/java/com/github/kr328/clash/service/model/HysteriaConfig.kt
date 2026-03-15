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
    var yamlTemplate: String = "",
    var accounts: List<HysteriaAccount> = emptyList(),
    var activeAccountId: String? = null,
)

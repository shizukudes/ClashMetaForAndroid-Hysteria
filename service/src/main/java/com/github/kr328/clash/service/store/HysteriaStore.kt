package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider

class HysteriaStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var enabled: Boolean by store.boolean(
        key = "hysteria_enabled",
        defaultValue = false
    )

    var serverIp: String by store.string(
        key = "hysteria_server_ip",
        defaultValue = ""
    )

    var serverPortRange: String by store.string(
        key = "hysteria_server_port_range",
        defaultValue = "6000-19999"
    )

    var password: String by store.string(
        key = "hysteria_password",
        defaultValue = ""
    )

    var obfs: String by store.string(
        key = "hysteria_obfs",
        defaultValue = "hu``hqb`c"
    )

    var localPort: Int by store.int(
        key = "hysteria_local_port",
        defaultValue = 7777
    )

    var recvWindowConn: Int by store.int(
        key = "hysteria_recv_window_conn",
        defaultValue = 131072
    )

    var recvWindow: Int by store.int(
        key = "hysteria_recv_window",
        defaultValue = 327680
    )

    var coreCount: Int by store.int(
        key = "hysteria_core_count",
        defaultValue = 4
    )

    var logLevel: String by store.string(
        key = "hysteria_log_level",
        defaultValue = "info"
    )
}

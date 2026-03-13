package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.HysteriaAccount
import com.github.kr328.clash.service.model.HysteriaConfig

class HysteriaSettingsDesign(
    context: Context,
    val config: HysteriaConfig,
) : Design<HysteriaSettingsDesign.Request>(context) {
    sealed class Request {
        object SaveAndGenerate : Request()
        object AddAccount : Request()
        object EditTemplate : Request()
        data class EditAccount(val account: HysteriaAccount) : Request()
        data class DeleteAccount(val account: HysteriaAccount) : Request()
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        update()
    }

    fun update() {
        binding.content.removeAllViews()

        val screen = preferenceScreen(context) {
            category(R.string.hysteria_settings)

            switch(
                value = config::enabled,
                icon = R.drawable.ic_baseline_dns,
                title = R.string.hysteria_enabled,
                summary = R.string.hysteria_enabled_summary,
            )

            category(R.string.settings)

            config.accounts.forEach { account ->
                clickable(
                    title = R.string.name,
                    icon = R.drawable.ic_baseline_dns,
                ) {
                    title = account.name
                    val activePrefix = if (config.activeAccountId == account.id) "ACTIVE • " else ""
                    summary = "$activePrefix${if (account.enabled) "ON" else "OFF"} • ${account.serverIp}:${account.serverPortRange}"

                    clicked {
                        requests.trySend(Request.EditAccount(account))
                    }
                }
            }

            clickable(
                title = R.string._new,
                icon = R.drawable.ic_baseline_add
            ) {
                clicked {
                    requests.trySend(Request.AddAccount)
                }
            }

            category(R.string.settings)

            editableText(
                value = config::localPort,
                adapter = NullableTextAdapter.Int,
                title = R.string.hysteria_local_port,
                icon = R.drawable.ic_baseline_edit,
            )

            switch(
                value = config::udpForwarding,
                icon = R.drawable.ic_baseline_dns,
                title = R.string.udp_forwarding,
                summary = R.string.udp_forwarding_summary,
            )

            editableText(
                value = config::udpgwPort,
                adapter = NullableTextAdapter.Int,
                title = R.string.udpgw_port,
                icon = R.drawable.ic_baseline_edit,
            )

            editableText(
                value = config::tun2SocksDnsGateway,
                adapter = TextAdapter.String,
                title = R.string.hysteria_tun2socks_dns_gateway,
                icon = R.drawable.ic_baseline_dns,
            )

            switch(
                value = config::tun2SocksUsePdnsd,
                icon = R.drawable.ic_baseline_dns,
                title = R.string.hysteria_tun2socks_use_pdnsd,
                summary = R.string.hysteria_tun2socks_use_pdnsd_summary,
            )

            editableText(
                value = config::pdnsdListenPort,
                adapter = NullableTextAdapter.Int,
                title = R.string.hysteria_pdnsd_listen_port,
                icon = R.drawable.ic_baseline_edit,
            )

            editableText(
                value = config::pdnsdUpstreams,
                adapter = TextAdapter.String,
                title = R.string.hysteria_pdnsd_upstreams,
                icon = R.drawable.ic_baseline_edit,
            )

            editableText(
                value = config::recvWindowConn,
                adapter = NullableTextAdapter.Int,
                title = R.string.hysteria_recv_window_conn,
                icon = R.drawable.ic_baseline_edit,
            )

            editableText(
                value = config::recvWindow,
                adapter = NullableTextAdapter.Int,
                title = R.string.hysteria_recv_window,
                icon = R.drawable.ic_baseline_edit,
            )

            editableText(
                value = config::logLevel,
                adapter = TextAdapter.String,
                title = R.string.hysteria_log_level,
                icon = R.drawable.ic_baseline_assignment,
            )

            clickable(
                title = R.string.edit_template,
                icon = R.drawable.ic_baseline_edit,
            ) {
                summary = context.getString(R.string.edit_template_summary)

                clicked {
                    requests.trySend(Request.EditTemplate)
                }
            }

            category(R.string.action)

            clickable(
                title = R.string.generate_config,
                icon = R.drawable.ic_baseline_save,
            ) {
                summary = context.getString(R.string.generate_config_summary)

                clicked {
                    requests.trySend(Request.SaveAndGenerate)
                }
            }
        }

        binding.content.addView(screen.root)
    }
}

package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.HysteriaConfig
import com.github.kr328.clash.service.model.HysteriaAccount

class HysteriaSettingsDesign(
    context: Context,
    val config: HysteriaConfig,
) : Design<HysteriaSettingsDesign.Request>(context) {
    sealed class Request {
        object SaveAndGenerate : Request()
        object AddAccount : Request()
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
                    title = TextAdapter.String.from(account.name),
                    summary = TextAdapter.String.from("${account.serverIp}:${account.serverPortRange}"),
                    icon = R.drawable.ic_baseline_edit
                ) {
                    clicked {
                        requests.trySend(Request.EditAccount(account))
                    }
                }
            }

            clickable(
                title = R.string.add,
                icon = R.drawable.ic_baseline_add
            ) {
                clicked {
                    requests.trySend(Request.AddAccount)
                }
            }

            category(R.string.clash)

            editableText(
                value = config::localPort,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_local_port,
                summary = R.string.hysteria_local_port_summary,
            )

            editableText(
                value = config::recvWindowConn,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_recv_window_conn,
                summary = R.string.hysteria_recv_window_conn_summary,
            )

            editableText(
                value = config::recvWindow,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_recv_window,
                summary = R.string.hysteria_recv_window_summary,
            )

            editableText(
                value = config::logLevel,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_assignment,
                title = R.string.hysteria_log_level,
                summary = R.string.hysteria_log_level_summary,
            )

            category(R.string.action)

            clickable(
                title = R.string.generate_config,
                summary = R.string.generate_config_summary,
                icon = R.drawable.ic_baseline_add,
            ) {
                clicked {
                    requests.trySend(Request.SaveAndGenerate)
                }
            }
        }

        binding.content.addView(screen.root)
    }
}

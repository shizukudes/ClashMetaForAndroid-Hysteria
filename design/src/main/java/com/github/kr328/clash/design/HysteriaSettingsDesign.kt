package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.HysteriaStore

class HysteriaSettingsDesign(
    context: Context,
    store: HysteriaStore,
) : Design<HysteriaSettingsDesign.Request>(context) {
    enum class Request {
        GenerateConfig
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.hysteria_settings)

            switch(
                value = store::enabled,
                icon = R.drawable.ic_baseline_dns,
                title = R.string.hysteria_enabled,
                summary = R.string.hysteria_enabled_summary,
            )

            category(R.string.settings)

            editableText(
                value = store::serverIp,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.hysteria_server_ip,
                summary = R.string.hysteria_server_ip_summary,
            )

            editableText(
                value = store::serverPortRange,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_server_port_range,
                summary = R.string.hysteria_server_port_range_summary,
            )

            editableText(
                value = store::password,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_password,
                summary = R.string.hysteria_password_summary,
            )

            editableText(
                value = store::obfs,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_obfs,
                summary = R.string.hysteria_obfs_summary,
            )

            editableText(
                value = store::localPort,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_local_port,
                summary = R.string.hysteria_local_port_summary,
            )

            category(R.string.settings)

            editableText(
                value = store::recvWindowConn,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_recv_window_conn,
                summary = R.string.hysteria_recv_window_conn_summary,
            )

            editableText(
                value = store::recvWindow,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_recv_window,
                summary = R.string.hysteria_recv_window_summary,
            )

            editableText(
                value = store::coreCount,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_core_count,
                summary = R.string.hysteria_core_count_summary,
            )

            editableText(
                value = store::logLevel,
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
                    requests.trySend(Request.GenerateConfig)
                }
            }
        }

        binding.content.addView(screen.root)
    }
}

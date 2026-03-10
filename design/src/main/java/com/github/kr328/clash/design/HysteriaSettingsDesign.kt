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
            )

            editableText(
                value = store::serverIp,
                adapter = NullableTextAdapter.String,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.hysteria_server_ip,
            )

            editableText(
                value = store::serverPortRange,
                adapter = NullableTextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_server_port_range,
            )

            editableText(
                value = store::password,
                adapter = NullableTextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_password,
            )

            editableText(
                value = store::obfs,
                adapter = NullableTextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_obfs,
            )

            editableText(
                value = store::localPort,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_local_port,
            )

            category(R.string.settings)

            editableText(
                value = store::recvWindowConn,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_recv_window_conn,
            )

            editableText(
                value = store::recvWindow,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_recv_window,
            )

            editableText(
                value = store::coreCount,
                adapter = NullableTextAdapter.Int,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_core_count,
            )

            editableText(
                value = store::logLevel,
                adapter = NullableTextAdapter.String,
                icon = R.drawable.ic_baseline_assignment,
                title = R.string.hysteria_log_level,
            )

            category(R.string.action)

            clickable(
                title = R.string.generate_config,
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

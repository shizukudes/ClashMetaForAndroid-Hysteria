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

class HysteriaAccountDesign(
    context: Context,
    val account: HysteriaAccount,
) : Design<HysteriaAccountDesign.Request>(context) {
    sealed class Request {
        object Delete : Request()
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
            category(R.string.settings)

            switch(
                value = account::enabled,
                icon = R.drawable.ic_baseline_dns,
                title = R.string.enabled,
            )

            editableText(
                value = account::name,
                adapter = TextAdapter.String,
                title = R.string.name,
                icon = R.drawable.ic_baseline_edit,
            )

            editableText(
                value = account::serverIp,
                adapter = TextAdapter.String,
                title = R.string.hysteria_server_ip,
                icon = R.drawable.ic_baseline_domain,
            )

            editableText(
                value = account::serverPortRange,
                adapter = TextAdapter.String,
                title = R.string.hysteria_server_port_range,
                icon = R.drawable.ic_baseline_edit,
            )

            editableText(
                value = account::password,
                adapter = TextAdapter.String,
                title = R.string.hysteria_password,
                icon = R.drawable.ic_baseline_edit,
            )

            editableText(
                value = account::obfs,
                adapter = TextAdapter.String,
                title = R.string.hysteria_obfs,
                icon = R.drawable.ic_baseline_edit,
            )

            selectable(
                value = account::tunCore,
                options = listOf("Clash", "Tun2Socks"),
                title = "Tunnel Core",
                icon = R.drawable.ic_baseline_settings,
                adapter = TextAdapter.String,
            )

            editableText(
                value = account::udpgwServer,
                adapter = TextAdapter.String,
                title = "UDPGW Server",
                icon = R.drawable.ic_baseline_edit,
            )

            category(R.string.action)

            clickable(
                title = R.string.delete,
                icon = R.drawable.ic_baseline_delete
            ) {
                clicked {
                    requests.trySend(Request.Delete)
                }
            }
        }

        binding.content.addView(screen.root)
    }
}

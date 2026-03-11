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
        object EditTemplate : Request()
        object OpenDonate : Request()
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
                    summary = "${if (account.enabled) "ON" else "OFF"} • ${account.serverIp}:${account.serverPortRange}"

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

            clickable(
                title = R.string.traktir_kopi,
                icon = R.drawable.ic_baseline_volunteer_activism,
            ) {
                summary = context.getString(R.string.donate_saweria_url)

                clicked {
                    requests.trySend(Request.OpenDonate)
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

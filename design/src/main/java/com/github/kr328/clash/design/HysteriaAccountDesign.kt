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

            editableText(
                value = account::name,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.edit,
            )

            editableText(
                value = account::serverIp,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.hysteria_server_ip,
                summary = R.string.hysteria_server_ip_summary,
            )

            editableText(
                value = account::serverPortRange,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_server_port_range,
                summary = R.string.hysteria_server_port_range_summary,
            )

            editableText(
                value = account::password,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_password,
                summary = R.string.hysteria_password_summary,
            )

            editableText(
                value = account::obfs,
                adapter = TextAdapter.String,
                icon = R.drawable.ic_baseline_edit,
                title = R.string.hysteria_obfs,
                summary = R.string.hysteria_obfs_summary,
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

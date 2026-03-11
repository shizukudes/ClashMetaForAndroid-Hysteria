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

class HysteriaTemplateDesign(
    context: Context,
    val config: HysteriaConfig,
) : Design<HysteriaTemplateDesign.Request>(context) {
    sealed class Request

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.edit_template)

            editableText(
                value = config::yamlTemplate,
                adapter = TextAdapter.String,
                title = R.string.edit_template,
                icon = R.drawable.ic_baseline_edit,
            )
        }

        binding.content.addView(screen.root)
    }
}

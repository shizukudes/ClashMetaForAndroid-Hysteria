package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import kotlinx.coroutines.channels.Channel

class SuspendModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        while (true) {
            when (screenToggle.receive().action) {
                Intent.ACTION_SCREEN_ON -> {
                    // Keep core active during screen-off period to avoid long reconnect/buffering
                    // when device wakes up.
                    // no-op
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // no-op
                }
            }
        }
    }
}

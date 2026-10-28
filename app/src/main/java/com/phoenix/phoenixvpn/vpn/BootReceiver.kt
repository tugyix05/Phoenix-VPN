package com.phoenix.phoenixvpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a device reboot: if Auto VPN was on, bring the foreground service
 * back and re-arm auto-run. setAutoRun(true) runs its immediate rule check,
 * which connects as soon as internet is available.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val appContext = context.applicationContext
            val store = EndpointStore(appContext)
            if (store.autoRun) {
                VpnForegroundService.start(appContext)
                WireGuardManager.getInstance(appContext).setAutoRun(true)
            }
        } catch (_: Exception) {
            // Direct-boot / locked-device edge cases: stay silent.
        }
    }
}

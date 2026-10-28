package com.phoenix.phoenixvpn.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Thin Firebase Analytics wrapper.
 *
 * Auto-collected (no code needed): user count, country, device, sessions,
 * first_open / app_open. Custom events below track real VPN usage.
 * All calls are safe no-ops when Firebase isn't available (e.g. no Play
 * Services): Analytics must never crash or block the VPN path.
 */
object Analytics {
    @Volatile
    private var fa: FirebaseAnalytics? = null

    fun init(context: Context) {
        try {
            fa = FirebaseAnalytics.getInstance(context.applicationContext)
        } catch (_: Exception) {
            fa = null
        }
    }

    fun logConnect() = log("vpn_connect")

    fun logDisconnect() = log("vpn_disconnect")

    private fun log(name: String) {
        try {
            fa?.logEvent(name, Bundle())
        } catch (_: Exception) {
            // Analytics is best-effort; never disturb the VPN.
        }
    }
}

package com.phoenix.phoenixvpn.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.TimeZone

/**
 * Thin Firebase Analytics wrapper.
 *
 * Auto-collected (no code needed): user count, country, device, sessions,
 * first_open / app_open. Custom events below track real VPN usage.
 * All calls are safe no-ops when Firebase isn't available (e.g. no Play
 * Services): Analytics must never crash or block the VPN path.
 *
 * Real country (2026-11-07): GA4's IP-based country shows the VPN exit
 * country, not the user's real country. So vpn_connect / vpn_disconnect
 * also carry the device timezone ("tz", e.g. "Asia/Yangon") as an event
 * parameter — no permission needed, timezone never changes under VPN.
 * The GA4 "Timezone" custom dimension (event parameter "tz") must be
 * registered in the GA4 console for the Worker to query it; the Worker
 * maps timezone -> country and shows it as the real country.
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
            val params = Bundle()
            try {
                params.putString("tz", TimeZone.getDefault().id)
            } catch (_: Exception) {
                // Timezone read is best-effort; the event still goes out.
            }
            fa?.logEvent(name, params)
        } catch (_: Exception) {
            // Analytics is best-effort; never disturb the VPN.
        }
    }
}

package com.phoenix.phoenixvpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phoenix.phoenixvpn.network.UpdateService

/**
 * v1.53: delete the kept update APK the moment its update installs.
 *
 * When a new version installs over this app, Android delivers
 * MY_PACKAGE_REPLACED to the NEW version — even before the user
 * opens it — so this receiver runs at install time, not at next
 * launch. Install failure or cancel sends no such broadcast, so
 * the kept file survives for a no-redownload retry: deletion
 * happens ONLY on successful install, exactly the user rule.
 */
class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            val appContext = context.applicationContext
            val kept = UpdateService.keptRecord(appContext)
            UpdateService.deleteKeptApk(appContext)
            Log.i(
                "PhoenixUpdate",
                "Kept APK deleted after install: " + (kept?.name ?: "none")
            )
        } catch (_: Exception) {
            // Deletion is best-effort; the start-up fallback covers a miss.
        }
    }
}

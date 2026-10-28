package com.phoenix.phoenixvpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phoenix.phoenixvpn.analytics.Analytics
import com.phoenix.phoenixvpn.ui.HomeScreen
import com.phoenix.phoenixvpn.ui.VpnViewModel
import com.phoenix.phoenixvpn.ui.theme.PhoenixVpnTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: VpnViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    /**
     * Battery-exemption screen. The exemption setting can take a moment to
     * propagate after the user grants it, so we wait ~1s before telling the
     * strip to re-check — this beats the ON_RESUME race the old flow had.
     */
    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        lifecycleScope.launch {
            delay(1000)
            viewModel.onBatteryExemptionReturned()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Analytics.init(this)
        checkNotificationPermission()

        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        lifecycleScope.launch {
            viewModel.permissionRequest.collect { intent: Intent? ->
                if (intent != null) {
                    try {
                        vpnPermissionLauncher.launch(intent)
                    } catch (_: Exception) {
                        viewModel.onVpnPermissionResult(false)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.batteryExemptionRequest.collect {
                try {
                    batteryExemptionLauncher.launch(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    // Fall through: the ON_RESUME check in the strip remains.
                }
            }
        }

        // In-app update: the VM hands over a downloaded APK; this
        // activity owns the installer / install-permission intents.
        lifecycleScope.launch {
            viewModel.installRequest.collect { file ->
                if (file != null) {
                    viewModel.consumeInstallRequest()
                    launchInstaller(file)
                }
            }
        }
        viewModel.checkForUpdate()

        setContent {
            PhoenixVpnTheme {
                HomeScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the one-time "allow installs" settings screen:
        // if an APK is waiting and the grant now exists, install it.
        viewModel.retryPendingInstall()
    }

    /**
     * Hand a downloaded update APK to the system installer — one tap
     * on UPDATE in the banner leads straight here. The only detour
     * Android forces is its one-time "allow installs from this app"
     * grant; after that, every update goes directly.
     */
    private fun launchInstaller(file: java.io.File) {
        if (packageManager.canRequestPackageInstalls()) {
            try {
                val uri = FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", file
                )
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                            uri,
                            "application/vnd.android.package-archive"
                        )
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                )
                viewModel.onInstallerLaunched()
            } catch (_: Exception) {
                // Installer could not open; the dialog already closed —
                // the next app open re-checks and offers it again.
            }
        } else {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
}

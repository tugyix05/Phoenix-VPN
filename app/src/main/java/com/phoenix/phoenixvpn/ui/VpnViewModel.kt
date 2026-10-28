package com.phoenix.phoenixvpn.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.phoenixvpn.analytics.Analytics
import com.phoenix.phoenixvpn.network.ConfigService
import com.phoenix.phoenixvpn.network.EndpointInfo
import com.phoenix.phoenixvpn.network.EndpointService
import com.phoenix.phoenixvpn.network.FeedSignalListener
import com.phoenix.phoenixvpn.network.UpdateService
import java.io.File
import com.phoenix.phoenixvpn.vpn.EndpointStore
import com.phoenix.phoenixvpn.vpn.TunnelConnectionState
import com.phoenix.phoenixvpn.vpn.VpnForegroundService
import com.phoenix.phoenixvpn.vpn.WireGuardManager
import com.wireguard.android.backend.BackendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Micro-progress phases of the tap-to-connect flow (honest UI). */
enum class SetupPhase { IDLE, FETCHING_CONFIG, FETCHING_ENDPOINTS }

data class EndpointRow(
    val id: String,          // "ip:port"
    val ms: Long?,           // publisher ms
    val isBest: Boolean,
    val isCurrent: Boolean,
    val isManual: Boolean
)

data class PhoenixUiState(
    val phase: SetupPhase = SetupPhase.IDLE,
    val statusLine: String? = null,   // micro-progress / "new #1 available — tap to switch"
    val rows: List<EndpointRow> = emptyList(),
    val currentId: String? = null,
    val currentMs: Long? = null,
    val updatedAgo: String = "never",
    val stale: Boolean = false,
    val autoRun: Boolean = false,
    val newBestAvailable: Boolean = false,
    val sheetOpen: Boolean = false,
    val manualDialogOpen: Boolean = false,
    /** null = ADD mode; non-null = EDIT mode for this endpoint id. */
    val editTargetId: String? = null,
    val aboutOpen: Boolean = false,
    val message: String? = null,
    val connectedAt: Long = 0L,
    val manualId: String? = null,
    val manualMs: Long? = null,
    /** Rows currently being live-measured by Refresh ("…" in the sheet). */
    val measuringIds: Set<String> = emptySet(),
    /** Draw-2 live-measured ms by endpoint id (shown with ● LIVE). */
    val liveMs: Map<String, Long> = emptyMap()
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val manager = WireGuardManager.getInstance(application)
    private val store = EndpointStore(application)

    val vpnState: StateFlow<com.phoenix.phoenixvpn.vpn.VpnUiState> = manager.vpnState

    private val _ui = MutableStateFlow(PhoenixUiState())
    val ui: StateFlow<PhoenixUiState> = _ui.asStateFlow()

    /** Fired when the flow needs the OS VPN-permission dialog; Activity launches it. */
    private val _permissionRequest = MutableStateFlow<Intent?>(null)
    val permissionRequest: StateFlow<Intent?> = _permissionRequest.asStateFlow()
    private var permissionDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Battery-exemption flow: HomeScreen's strip asks, MainActivity launches the
     * system screen via ActivityResultLauncher. On return (after a short delay
     * for the setting to propagate), [batteryCheckTick] bumps and the strip
     * re-checks — no reliance on winning a resume race.
     */
    private val _batteryExemptionRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val batteryExemptionRequest: SharedFlow<Unit> = _batteryExemptionRequest
    private val _batteryCheckTick = MutableStateFlow(0)
    val batteryCheckTick: StateFlow<Int> = _batteryCheckTick.asStateFlow()

    fun requestBatteryExemption() {
        _batteryExemptionRequest.tryEmit(Unit)
    }

    fun onBatteryExemptionReturned() {
        _batteryCheckTick.value += 1
    }

    /**
     * Battery-exemption grant persistence. On HyperOS/Xiaomi
     * PowerManager.isIgnoringBatteryOptimizations() is unreliable — it can
     * return false even after the user granted the exemption, so a config
     * change (e.g. dark/light mode switch) recreates the activity, the fresh
     * check reads false, and the strip wrongly reappears. Once ANY check
     * observes the exemption as granted, we persist it locally; the strip
     * then stays hidden for this install (survives config changes and
     * process death). Clearing app data resets it.
     */
    private val uiPrefs =
        application.getSharedPreferences("phoenix_vpn_ui", Context.MODE_PRIVATE)
    private val _batteryExemptionConfirmed =
        MutableStateFlow(uiPrefs.getBoolean("battery_exemption_confirmed", false))
    val batteryExemptionConfirmed: StateFlow<Boolean> =
        _batteryExemptionConfirmed.asStateFlow()

    fun confirmBatteryExemption() {
        if (!_batteryExemptionConfirmed.value) {
            _batteryExemptionConfirmed.value = true
            uiPrefs.edit().putBoolean("battery_exemption_confirmed", true).apply()
        }
    }

    // ============================================================
    //  In-app update (v1.46) — check GitHub releases, download the
    //  APK inside the app (never the browser), hand the file to
    //  the system installer. One tap on UPDATE does everything.
    // ============================================================

    enum class UpdatePhase { IDLE, AVAILABLE, DOWNLOADING, READY, ERROR }

    data class UpdateUiState(
        val phase: UpdatePhase = UpdatePhase.IDLE,
        val version: String? = null,
        val progress: Int = 0,
        val message: String? = null
    )

    private val _update = MutableStateFlow(UpdateUiState())
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    /** One-shot hand-off to MainActivity, which owns the intents. */
    private val _installRequest = MutableStateFlow<File?>(null)
    val installRequest: StateFlow<File?> = _installRequest.asStateFlow()

    private var updateJob: Job? = null
    private var latestRelease: UpdateService.ReleaseInfo? = null
    private var pendingApk: File? = null

    /** Release check on every app open (v1.47: no throttle — show immediately). */
    fun checkForUpdate() {
        viewModelScope.launch {
            val info = UpdateService.fetchLatest(getApplication()).getOrNull()
                ?: return@launch
            val installed = installedVersionName() ?: return@launch
            if (!UpdateService.isNewer(info.version, installed)) return@launch
            // v1.47: no dismissal — the banner stays until the update is
            // installed, on every app open.
            latestRelease = info
            _update.value = UpdateUiState(UpdatePhase.AVAILABLE, info.version)
        }
    }

    private fun installedVersionName(): String? = try {
        @Suppress("DEPRECATION")
        getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0)
            .versionName
    } catch (_: Exception) {
        null
    }

    /**
     * UPDATE tap: install a kept download when it matches the latest
     * release (v1.53 — no re-download after an install error, a
     * cancel, or an app close); otherwise download with progress,
     * keep the completed APK, then hand off to the installer.
     */
    fun startUpdate() {
        val info = latestRelease ?: return
        if (_update.value.phase == UpdatePhase.DOWNLOADING) return
        _update.value = UpdateUiState(UpdatePhase.DOWNLOADING, info.version, 0)
        updateJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val kept = withContext(Dispatchers.IO) {
                UpdateService.keptRecord(app)
            }
            if (kept != null && kept.version != info.version) {
                // A kept APK for an older release is dead weight.
                withContext(Dispatchers.IO) {
                    UpdateService.deleteKeptApk(app)
                }
            } else if (kept != null) {
                val valid = withContext(Dispatchers.IO) {
                    val f = File(UpdateService.updatesDir(app), kept.name)
                    f.exists() && f.length() == kept.size &&
                        UpdateService.sha256(f) == kept.sha256
                }
                if (valid) {
                    val f = File(UpdateService.updatesDir(app), kept.name)
                    pendingApk = f
                    _update.value =
                        UpdateUiState(UpdatePhase.READY, info.version, 100)
                    _installRequest.value = f
                    return@launch
                }
                // Corrupt kept file: drop it, fall through to download.
                withContext(Dispatchers.IO) {
                    UpdateService.deleteKeptApk(app)
                }
            }
            val result =
                UpdateService.download(app, info, UpdateService.updatesDir(app)) { pct ->
                    _update.update {
                        if (it.phase == UpdatePhase.DOWNLOADING) {
                            it.copy(progress = pct)
                        } else {
                            it
                        }
                    }
                }
            val file = result.getOrNull()
            if (file != null) {
                pendingApk = file
                val sha = withContext(Dispatchers.IO) {
                    UpdateService.sha256(file)
                }
                if (sha != null) {
                    UpdateService.saveKeptRecord(
                        app,
                        UpdateService.KeptApk(
                            info.version, file.name, file.length(), sha
                        )
                    )
                }
                _update.value =
                    UpdateUiState(UpdatePhase.READY, info.version, 100)
                _installRequest.value = file
            } else {
                val e = result.exceptionOrNull()
                _update.value = UpdateUiState(
                    UpdatePhase.ERROR, info.version, 0,
                    e?.message ?: "Download failed"
                )
            }
        }
    }

    fun cancelUpdate() {
        updateJob?.cancel()
        // A cancelled download leaves only a .tmp partial — drop it.
        // A completed kept APK is never touched by a cancel.
        val dir = UpdateService.updatesDir(getApplication<Application>())
        dir.listFiles()?.forEach {
            if (it.isFile && it.name.endsWith(".tmp")) it.delete()
        }
        _update.value =
            UpdateUiState(UpdatePhase.AVAILABLE, latestRelease?.version)
    }

    /** Close without recording a dismissal (e.g. after an error). */
    fun closeUpdate() {
        _update.value = UpdateUiState()
    }

    fun consumeInstallRequest() {
        _installRequest.value = null
    }

    /**
     * MainActivity.onResume: an APK is downloaded and waiting, but the
     * one-time "allow installs" system grant was missing — re-emit so
     * the installer can open as soon as the grant exists.
     */
    fun retryPendingInstall() {
        val f = pendingApk ?: return
        if (_update.value.phase != UpdatePhase.READY) return
        _installRequest.value = null
        _installRequest.value = f
    }

    fun onInstallerLaunched() {
        pendingApk = null
        _update.value = UpdateUiState()
    }

    private var connectJob: Job? = null
    private var tickerJob: Job? = null

    /** Firebase signal listener (null until started in init). */
    private var feedSignalListener: FeedSignalListener? = null

    /** "v" stamp of the last signal that produced a successful fetch. */
    private var lastSignalValue: Long? = null

    /**
     * Endpoint id the LIVE tunnel is actually on. This differs from
     * the selected endpoint after an auto-select while connected
     * (selection moves to new #1, tunnel stays on the old endpoint),
     * so failover must exclude THIS id — not the UI's current id —
     * or it would wrongly skip the healthy new #1.
     */
    private var tunnelEndpointId: String? = null

    init {
        // Restore persisted state into the manager (auto-run rules need it).
        val conf = store.baseConf
        val cur = store.currentEndpoint ?: store.effectiveList().firstOrNull()?.id
            ?: EndpointStore.BUNDLED.first().id
        if (conf != null) {
            pushDefaultTunnel(conf, cur)
        }
        _ui.update {
            it.copy(
                autoRun = store.autoRun,
                currentId = cur,
                manualId = store.manualEndpoint,
                manualMs = store.manualMs
            )
        }
        manager.setAutoRun(store.autoRun)
        refreshUiRows()
        refreshUpdatedAgo()

        // v1.53 fallback: a kept APK whose version is no longer newer
        // than the installed version means its update already happened
        // (the MY_PACKAGE_REPLACED receiver missed it, e.g. the app
        // was force-stopped at install time) — delete it + the record.
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val kept = UpdateService.keptRecord(app) ?: return@launch
            val installed = installedVersionName() ?: return@launch
            if (!UpdateService.isNewer(kept.version, installed)) {
                UpdateService.deleteKeptApk(app)
                Log.i("PhoenixUpdate", "Kept APK cleaned up on start: " + kept.name)
            }
        }

        // Uptime ticker + derived UI while connected.
        var lastConnState = TunnelConnectionState.DISCONNECTED
        viewModelScope.launch {
            vpnState.collect { st ->
                val cur = st.connectionState
                if (cur == TunnelConnectionState.CONNECTED &&
                    lastConnState != TunnelConnectionState.CONNECTED
                ) {
                    // Fresh connect: drop the transient micro-progress text.
                    // (newBestAvailable survives — it clears when the user
                    // switches to the new #1.)
                    _ui.update { it.copy(statusLine = null) }
                    Analytics.logConnect()
                }
                if (lastConnState == TunnelConnectionState.CONNECTED &&
                    cur == TunnelConnectionState.DISCONNECTED
                ) {
                    Analytics.logDisconnect()
                }
                lastConnState = cur
                if (cur == TunnelConnectionState.CONNECTED) {
                    if (_ui.value.connectedAt == 0L) {
                        _ui.update { it.copy(connectedAt = SystemClock.elapsedRealtime()) }
                    }
                    startTicker()
                } else {
                    stopTicker()
                    if (cur == TunnelConnectionState.DISCONNECTED) {
                        _ui.update { it.copy(connectedAt = 0L) }
                    }
                }
                // Keep currentMs in sync with the selected endpoint.
                val curId = _ui.value.currentId
                _ui.update { it.copy(currentMs = msFor(curId)) }
            }
        }

        // Endpoint-dead failover: watchdog exhausted the current endpoint.
        viewModelScope.launch {
            manager.endpointDeadEvent.collect {
                failover("Endpoint stopped responding — switching…")
            }
        }

        // Feed watch: Firebase signal (Scanner publish) + on-open fetch.
        // No 6-hour gate anymore (user spec 2026-10-28): a new feed is
        // picked up the moment it arrives, never on a timer.
        viewModelScope.launch {
            delay(4000)
            startFeedWatch()
        }

        // Pre-fetch the WireGuard .conf on app start if missing, so the
        // first connect doesn't stall on "Fetching config…" (pguard is
        // slow from Myanmar). Silent: no phase UI, no toast — connectFlow
        // retries with UI feedback if this fails or is skipped.
        if (store.baseConf.isNullOrBlank()) {
            viewModelScope.launch {
                if (!hasValidatedInternet()) return@launch
                try {
                    val fetched = ConfigService.fetchWireGuardConfig().getOrNull()
                    if (fetched != null) {
                        store.baseConf = fetched.content
                    }
                } catch (_: Exception) {
                    // Silent — connectFlow fetches with UI if still missing.
                }
            }
        }
    }

    // ============================================================
    //  Power button
    // ============================================================

    fun onPowerTap() {
        // Live measurement in progress and a test draw is holding the
        // CONNECTING state: the tap only cancels the measurement. Acting
        // on it as a disconnect would poison userOverride for a connection
        // that never existed (test draws are not real connections).
        if (_ui.value.measuringIds.isNotEmpty() &&
            vpnState.value.connectionState == TunnelConnectionState.CONNECTING
        ) {
            liveMeasureCancelled = true
            return
        }
        // Tapping during the setup flow cancels it (fetch/connect stages).
        if (_ui.value.phase != SetupPhase.IDLE) {
            cancelConnectFlow()
            return
        }
        when (vpnState.value.connectionState) {
            TunnelConnectionState.CONNECTED,
            TunnelConnectionState.CONNECTING -> manualDisconnect()
            else -> connectFlow()
        }
    }

    private fun cancelConnectFlow() {
        connectJob?.cancel()
        _ui.update { it.copy(phase = SetupPhase.IDLE, statusLine = null) }
    }

    private fun manualDisconnect() {
        connectJob?.cancel()
        _ui.update { it.copy(phase = SetupPhase.IDLE, statusLine = null) }
        // Manual-off sticks: network events must NOT clear this.
        manager.setUserOverride(true)
        viewModelScope.launch {
            manager.stopTunnel()
        }
    }

    /**
     * Tap-to-connect flow:
     *   internet → VPN permission → fetch .conf (if missing) →
     *   connect immediately on the card's top-3 (cached list, or bundled
     *   on fresh install). Endpoint freshness is handled separately: the
     *   Firebase signal watch (new feed → #1 auto-selected, tunnel
     *   never switched) and the manual Refresh pill (update → replace,
     *   no update → leave as-is). Tap never waits on — or triggers —
     *   an endpoints fetch.
     *   Then connect with failover (explicit → top-3 → last-working →
     *   rest → bundled → manual; first candidate fire-and-forget).
     * Any step may abort back to disconnected with a toast reason.
     */
    fun connectFlow() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            try {
                // 1. Internet first — no point asking for anything without it.
                if (!hasValidatedInternet()) {
                    message("No internet connection")
                    return@launch
                }

                // 2. VPN permission — ask before any fetching, so the user
                // isn't left staring at a fetch phase on a fresh install.
                if (manager.checkVpnPermission() != null) {
                    val granted = awaitPermission()
                    if (!granted) {
                        message("VPN permission denied")
                        return@launch
                    }
                }

                // 3. .conf
                var conf = store.baseConf
                if (conf.isNullOrBlank()) {
                    setPhase(SetupPhase.FETCHING_CONFIG, "Fetching config…")
                    conf = ConfigService.fetchWireGuardConfig()
                        .getOrElse {
                            message("Config fetch failed — check connection")
                            return@launch
                        }.content
                    store.baseConf = conf
                }

                // 3. Endpoints: tap never fetches. Connect immediately on the
                // card's top-3 (cached list, or bundled on fresh install).
                // Freshness comes only from the 6-hour background check
                // (update → replace, no update → leave as-is) and the manual
                // Refresh pill — never from a tap-time fetch.
                refreshUpdatedAgo()

                // 5. Connect with failover: fresh top-3 first, then cached,
                // top-10 → bundled → manual. First handshake (~5s each)
                // wins and stays connected.
                setPhase(SetupPhase.IDLE, "Connecting…")
                manager.setUserOverride(false)
                refreshUiRows()
                if (!connectWithFailover(conf, excludeId = null)) {
                    // All attempts failed: quiet cleanup (no manual-off
                    // semantics — the user did NOT ask to stop).
                    VpnForegroundService.stopQuiet(app)
                    message("No working endpoint found")
                }
            } finally {
                if (vpnState.value.connectionState != TunnelConnectionState.CONNECTED &&
                    vpnState.value.connectionState != TunnelConnectionState.CONNECTING
                ) {
                    _ui.update { it.copy(phase = SetupPhase.IDLE, statusLine = null) }
                } else {
                    _ui.update { it.copy(phase = SetupPhase.IDLE) }
                }
            }
        }
    }

    private suspend fun connectTo(endpointId: String, conf: String) {
        val cfg = manager.swapEndpoint(conf, endpointId)
        manager.setUserOverride(false)
        store.currentEndpoint = endpointId
        tunnelEndpointId = endpointId
        pushDefaultTunnel(conf, endpointId)
        refreshUiRows()
        _ui.update { it.copy(currentMs = msFor(endpointId)) }
        // Fire-and-forget, as the user wants it: the switch shows
        // connected immediately. The handshake is NOT awaited here —
        // since v1.39 the manager's birth wake owns persistence: it
        // keeps re-drawing flows behind the "connected" UI until the
        // first handshake lands on the endpoint the user picked. No
        // failover away from an explicit choice, no fake-idle wait.
        manager.startTunnel(manager.tunnelNameFor(endpointId), cfg)
        if (vpnState.value.connectionState == TunnelConnectionState.ERROR) {
            message(vpnState.value.errorMessage ?: "Connection failed")
            _ui.update { it.copy(statusLine = null) }
        }
    }

    /**
     * Try each candidate endpoint in order, waiting up to ~5s for the first
     * handshake rx. The first handshake wins and stays connected.
     * Order: stored current endpoint (explicit user choice / last winner)
     * first, then published list (publisher order) → bundled → manual,
     * deduped. Returns true on the first working endpoint, false if all
     * failed.
     */
    private suspend fun connectWithFailover(conf: String, excludeId: String?): Boolean {
        val seen = LinkedHashSet<String>()
        val candidates = mutableListOf<EndpointInfo>()
        fun add(e: EndpointInfo) {
            if (seen.add(e.id)) candidates.add(e)
        }
        // Order: explicit user selection first, then fresh top-3
        // (sequential), then the cached last-working endpoint, then the
        // rest. First success wins.
        val effective = store.effectiveList()
        val explicit = store.explicitEndpoint
        if (explicit != null && explicit != excludeId) {
            // User tapped/added this — their direct command wins.
            val found = (effective + EndpointStore.BUNDLED).firstOrNull { it.id == explicit }
            if (found != null) {
                add(found)
            } else {
                add(EndpointInfo(explicit.substringBefore(":"), explicit.substringAfter(":").toInt(), null, null))
            }
        }
        effective.take(3).forEach { add(it) }
        val cur = store.currentEndpoint
        if (cur != null && cur != excludeId) {
            // Cached endpoint after top-3 (add() dedupes if in top-3).
            val found = (effective + EndpointStore.BUNDLED).firstOrNull { it.id == cur }
            if (found != null) {
                add(found)
            } else {
                // Not in any list — synthesize from the id.
                val i = cur.lastIndexOf(':')
                if (i > 0) {
                    add(EndpointInfo(cur.substring(0, i), cur.substring(i + 1).toIntOrNull() ?: 500, null, null))
                }
            }
        }
        effective.drop(3).forEach { add(it) }
        EndpointStore.BUNDLED.forEach { add(it) }
        store.manualEndpoint?.let { raw ->
            val i = raw.lastIndexOf(':')
            if (i > 0) {
                val port = raw.substring(i + 1).toIntOrNull() ?: 500
                add(EndpointInfo(raw.substring(0, i), port, null, null))
            }
        }
        var first = true
        for (e in candidates) {
            currentCoroutineContext().job.ensureActive()
            if (e.id == excludeId) continue
            _ui.update { it.copy(statusLine = "Connecting… ${e.id}") }
            val cfg = manager.swapEndpoint(conf, e.id)
            if (first) {
                // Tugyi-style: fire-and-forget for the first candidate.
                // No handshake wait — matches Auto VPN path. The 12s
                // watchdog catches stalls; ERROR state triggers failover.
                first = false
                try {
                    manager.setUserOverride(false)
                    store.currentEndpoint = e.id
                    tunnelEndpointId = e.id
                    pushDefaultTunnel(conf, e.id)
                    refreshUiRows()
                    _ui.update { it.copy(currentMs = e.ms) }
                    manager.startTunnel(manager.tunnelNameFor(e.id), cfg)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    continue
                }
                // Brief pause for fast-fail (bad config, permission).
                delay(500)
                if (vpnState.value.connectionState == TunnelConnectionState.ERROR) {
                    continue
                }
                return true
            }
            val ok = awaitHandshakeWithReroll(manager.tunnelNameFor(e.id), cfg)
            if (ok) {
                store.currentEndpoint = e.id
                tunnelEndpointId = e.id
                pushDefaultTunnel(conf, e.id)
                refreshUiRows()
                _ui.update { it.copy(currentMs = e.ms) }
                return true
            }
        }
        return false
    }

    /**
     * One endpoint, up to 3 rapid consecutive attempts (user spec
     * 2026-10-23): on MPT a failed handshake is usually a dead NAT flow,
     * and retransmits on the same flow never recover — only a full tunnel
     * restart draws a new flow (the manual off/on toggle, automated).
     * Each startTunnelAndAwaitHandshake call tears the tunnel fully DOWN
     * on failure, so every attempt is a fresh socket/flow. The re-rolls
     * get short 1.5s windows (a healthy line handshakes in ~250ms — no
     * long waits); the last attempt keeps the full 5s before the caller
     * moves on to the next endpoint.
     */
    private suspend fun awaitHandshakeWithReroll(name: String, cfg: String): Boolean {
        val windows = longArrayOf(1500L, 1500L, 5000L)
        for (w in windows) {
            val ok = try {
                manager.startTunnelAndAwaitHandshake(name, cfg, w)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                false
            }
            if (ok) return true
        }
        return false
    }

    /** Fail over to the next endpoint when the current one dies. */
    private fun failover(reason: String) {
        if (manager.isUserOverride()) return
        val st = vpnState.value.connectionState
        if (st != TunnelConnectionState.CONNECTED && st != TunnelConnectionState.CONNECTING) return
        // Exclude the LIVE tunnel's endpoint, not the UI selection:
        // after an auto-select while connected those differ, and
        // excluding the selection would wrongly skip the healthy new #1.
        val cur = tunnelEndpointId ?: _ui.value.currentId
        val conf = store.baseConf ?: return
        message(reason)
        viewModelScope.launch {
            try {
                manager.stopTunnel()
            } catch (_: Exception) {
            }
            if (!connectWithFailover(conf, excludeId = cur)) {
                // Quiet cleanup: the user did NOT manually stop.
                VpnForegroundService.stopQuiet(app)
                message("No working endpoint found")
            }
        }
    }

    // ============================================================
    //  Endpoint sheet / manual / auto-run
    // ============================================================

    fun openSheet() = _ui.update { it.copy(sheetOpen = true) }
    fun closeSheet() = _ui.update { it.copy(sheetOpen = false) }

    fun selectEndpoint(id: String) {
        closeSheet()
        store.currentEndpoint = id
        store.explicitEndpoint = id
        _ui.update { it.copy(newBestAvailable = false) }
        val conf = store.baseConf
        if (conf == null) {
            // No conf yet — the full flow picks up the stored selection.
            connectFlow()
            return
        }
        if (id == _ui.value.currentId &&
            vpnState.value.connectionState == TunnelConnectionState.CONNECTED
        ) return
        pushDefaultTunnel(conf, id)
        refreshUiRows()
        if (vpnState.value.connectionState == TunnelConnectionState.CONNECTED ||
            vpnState.value.connectionState == TunnelConnectionState.CONNECTING
        ) {
            viewModelScope.launch {
                try { manager.stopTunnel() } catch (_: Exception) {}
                connectTo(id, conf)
            }
        } else {
            _ui.update { it.copy(currentMs = msFor(id)) }
        }
    }

    fun openManualDialog() = _ui.update { it.copy(manualDialogOpen = true, editTargetId = null) }
    fun openEditDialog(id: String) = _ui.update { it.copy(manualDialogOpen = true, editTargetId = id) }
    fun closeManualDialog() = _ui.update { it.copy(manualDialogOpen = false, editTargetId = null) }

    fun saveManualEndpoint(raw: String) {
        val v = raw.trim()
        if (!isValidEndpoint(v)) {
            message("Use host:port (e.g. 8.34.70.118:500)")
            return
        }
        val target = _ui.value.editTargetId
        if (target == null) {
            // ADD mode — single manual slot. Auto-select: the user just typed it.
            store.manualEndpoint = v
            store.currentEndpoint = v
            store.explicitEndpoint = v
            store.manualMs = null
            _ui.update { it.copy(manualId = v, manualMs = null) }
            message("Manual endpoint saved — testing…")
            testManualEndpoint(v)
        } else if (target == _ui.value.manualId) {
            // EDIT mode on the manual endpoint.
            store.manualEndpoint = v
            store.currentEndpoint = v
            store.explicitEndpoint = v
            store.manualMs = null
            _ui.update { it.copy(manualId = v, manualMs = null) }
            message("Manual endpoint updated — testing…")
            testManualEndpoint(v)
        } else {
            // EDIT mode on an auto-fetched row — persisted display override.
            store.editedEndpoints = store.editedEndpoints + (target to v)
            message("Endpoint updated")
        }
        closeSheet()
        closeManualDialog()
        refreshUiRows()
    }

    /** Test a manual endpoint with a 5s handshake; saves ms or null. */
    private fun testManualEndpoint(id: String) {
        // Don't disrupt an active connection.
        val st = vpnState.value.connectionState
        if (st == TunnelConnectionState.CONNECTED || st == TunnelConnectionState.CONNECTING) {
            message("Manual endpoint saved (test skipped — VPN active)")
            return
        }
        val conf = store.baseConf
        if (conf == null) {
            message("Manual endpoint saved (no config — test skipped)")
            return
        }
        viewModelScope.launch {
            try {
                val cfg = manager.swapEndpoint(conf, id)
                val ok = manager.startTunnelAndAwaitHandshake("manual-test", cfg, 5000L)
                // Pure handshake RTT (excludes tunnel setup), or null if
                // the slow rx-path was used.
                val ms = manager.lastHandshakeMs
                try { manager.stopTunnel() } catch (_: Exception) {}
                if (ok && ms != null) {
                    store.manualMs = ms
                    message("Manual endpoint: ${ms}ms ✓")
                } else if (ok) {
                    // Reachable via data path, but no pure handshake ms captured.
                    store.manualMs = null
                    message("Manual endpoint reachable ✓")
                } else {
                    store.manualMs = null
                    message("Manual endpoint unreachable")
                }
            } catch (e: Exception) {
                try { manager.stopTunnel() } catch (_: Exception) {}
                store.manualMs = null
                message("Manual endpoint test failed")
            }
            refreshUiRows()
        }
    }

    fun deleteEndpoint(id: String) {
        if (id == _ui.value.manualId) {
            store.manualEndpoint = null
            store.manualMs = null
            if (store.explicitEndpoint == id) store.explicitEndpoint = null
            _ui.update { it.copy(manualId = null, manualMs = null) }
        } else {
            val d = store.deletedEndpoints
            d.add(id)
            store.deletedEndpoints = d
            // Drop any pending edit for the deleted row.
            store.editedEndpoints = store.editedEndpoints - id
        }
        refreshUiRows()
        message("Endpoint deleted")
    }

    fun setAutoRun(enabled: Boolean) {
        // The master switch is purely user-controlled; code never flips it.
        store.autoRun = enabled
        _ui.update { it.copy(autoRun = enabled) }
        manager.setAutoRun(enabled)
    }

    fun openAbout() = _ui.update { it.copy(aboutOpen = true) }
    fun closeAbout() = _ui.update { it.copy(aboutOpen = false) }

    /**
     * Main-screen Refresh (feed only, split from live measurement): fetches
     * endpoints.json immediately, bypassing the 6-hour gate. New feed = new
     * baseline, so live values clear. The fetch is bound to the underlying
     * network, so it works with the tunnel up or down. Live measurement
     * lives in the sheet (refreshLiveMs) and still needs the tunnel down.
     */
    fun refreshEndpointsNow() {
        viewModelScope.launch {
            message("Refreshing endpoints…")
            if (fetchAndApplyFeed()) {
                _ui.update { it.copy(liveMs = emptyMap(), measuringIds = emptySet()) }
            } else {
                message("Feed refresh failed — keeping current list")
            }
        }
    }

    /** Single-flight guard: re-taps during a run are ignored. */
    private var liveMeasureRunning = false

    /** Per-draw suffix for test-tunnel names (v1.55 experiment). */
    private var liveMeasureDrawCounter = 0

    /** True for any test-draw tunnel name ("live-measure" + suffix). */
    private fun isTestTunnelName(name: String?): Boolean =
        name?.startsWith("live-measure") == true

    /** Sheet Refresh (live ms only): measures every sheet row, no feed fetch. */
    fun refreshLiveMs() {
        if (liveMeasureRunning) return
        viewModelScope.launch {
            liveMeasureRunning = true
            try {
                measureLiveMs()
            } finally {
                liveMeasureRunning = false
            }
        }
    }

    /** Set when the hero tap cancels a live measurement (see onPowerTap). */
    private var liveMeasureCancelled = false

    /** Tunnel-down states: no live tunnel to disturb. */
    private fun isVpnOff(): Boolean {
        val st = vpnState.value.connectionState
        return st == TunnelConnectionState.DISCONNECTED || st == TunnelConnectionState.ERROR
    }

    /**
     * True when a REAL tunnel is up/connecting. A test draw itself sets
     * CONNECTING with a "live-measure*" name — that is NOT a real
     * tunnel, so the prefix check keeps the post-draw stopTunnel() from
     * ever killing a user's real connection.
     */
    private fun realTunnelActive(): Boolean {
        val st = vpnState.value
        return st.connectionState == TunnelConnectionState.CONNECTED ||
            (st.connectionState == TunnelConnectionState.CONNECTING &&
                !isTestTunnelName(st.activeTunnelName))
    }

    /** Sheet row ids in display order: published top-3, then manual. */
    private fun sheetTargetIds(): List<String> {
        val deleted = store.deletedEndpoints
        val edited = store.editedEndpoints
        val top3 = store.effectiveList()
            .filter { it.id !in deleted }
            .map { edited[it.id] ?: it.id }
            .take(3)
        val manual = store.manualEndpoint
        return if (manual != null) top3 + manual else top3
    }

    /**
     * Fresh client key for one test draw (v1.54): the conf's PrivateKey
     * line is replaced with a fresh random key. v1.57 CORRECTION: the
     * v1.54 premise was wrong — a fresh LOCAL key gets NO server
     * response at all (probe 18:33: stored key LANDED, fresh key
     * FAILED 3/3 on the same name/endpoint/window; pguard keys work
     * because they are registered, not merely fresh). Kept ONLY for
     * the probe's fresh-key variants; measurement must use the
     * stored key. If the conf has no PrivateKey line, it is returned
     * unchanged.
     */
    private fun freshPrivateKeyConf(confText: String): String {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.encodeToString(key, Base64.NO_WRAP)
        val replaced = confText.replace(
            Regex("(?im)^\\s*PrivateKey\\s*=.*"),
            "PrivateKey = $b64"
        )
        return if (replaced != confText) replaced else confText
    }

    /**
     * One test draw: test tunnel + 3s handshake await. Returns the pure
     * handshake ms, or null (timeout / rx-path only). 3s is generous: a
     * good flow lands in ~1s, and a dead flow never rescues on
     * same-flow retransmits, so waiting 5s only burns time.
     * v1.54: the draw runs as a pure test (no CONNECTED state, no
     * stats loop — see [isTestDraw] in the manager), and the test
     * tunnel is ALWAYS stopped afterwards while it is still current.
     * A real tunnel is never touched: if another tunnel took over
     * mid-draw, its name is not a live-measure name and no stop runs.
     * v1.55: each draw gets a UNIQUE tunnel name ("live-measure-N").
     * If the backend keys devices by name, a fresh name means a fresh
     * device + peer and a fresh handshake; if it keys by peer, names
     * change nothing. Every draw logs one PhoenixUpdate line (name,
     * row, whether the key actually rotated, outcome, duration) so
     * the next field log pins the mechanism instead of guessing it.
     * The key itself is never logged.
     * v1.57: STORED key (no rotation). The v1.56 probe proved the
     * key is the discriminator: stored LANDED, fresh FAILED 3/3
     * (same name/endpoint/window), and the 6s window probe failed
     * too — a fresh local key is silently ignored by the server.
     */
    private suspend fun testDrawMs(conf: String, id: String): Long? {
        val swapped = manager.swapEndpoint(conf, id)
        // Stored key on purpose (v1.57): fresh local keys get no
        // handshake response from the server (probe 18:33).
        val cfg = swapped
        val rotated = cfg != swapped
        val drawName = "live-measure-" + (++liveMeasureDrawCounter)
        val t0 = SystemClock.elapsedRealtime()
        try {
            val ok = manager.startTunnelAndAwaitHandshake(
                drawName, cfg, 3000L, isTestDraw = true
            )
            val ms = if (ok) manager.lastHandshakeMs else null
            val outcome = when {
                ms != null -> "LANDED:$ms"
                ok -> "RX_NULL"
                else -> "FAIL"
            }
            Log.i(
                "PhoenixUpdate",
                "live-measure draw " + drawName + " id=" + id +
                    " rotated=" + rotated + " outcome=" + outcome +
                    " dt=" + (SystemClock.elapsedRealtime() - t0) + "ms"
            )
            return ms
        } catch (_: Exception) {
            Log.i(
                "PhoenixUpdate",
                "live-measure draw " + drawName + " id=" + id +
                    " rotated=" + rotated + " outcome=EXCEPTION" +
                    " dt=" + (SystemClock.elapsedRealtime() - t0) + "ms"
            )
            return null
        } finally {
            if (isTestTunnelName(vpnState.value.activeTunnelName)) {
                try { manager.stopTunnel() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Diagnostic probe (v1.56, temporary): factorial isolation of the
     * handshake-stage failure. The authorized runs (17:33/17:37) proved
     * backend.setState(UP) now succeeds and the failure moved to the
     * handshake (initiation sent, no response in 3s) — so the probe
     * varies the remaining draw-specific factors one at a time, all
     * through the REAL test-draw path (isTestDraw = true):
     *   P0  hero name + stored key + hero endpoint (path control)
     *   P1  hero name + fresh key + hero endpoint (key factor)
     *   P2  unique name + stored key + hero endpoint (name factor)
     *   P2b hero name + stored key + test endpoint (endpoint factor)
     *   P3  unique name + fresh key + hero endpoint (current behavior)
     *   P4  unique name + fresh key + hero endpoint, 6s window
     *       (window factor: a handshake landing at 3-6s proves the
     *       3s window — not key/name — is the cause, since the
     *       handshake retransmit lands around 5s)
     * Each probe runs up to 3 attempts (P4: 2) — a single dead flow
     * must not masquerade as a factor failure. Every attempt logs a
     * Log.e PhoenixUpdate line (reason + cause chain + frames when a
     * draw throws, handshake ms when it lands) so the in-app Log
     * dialog shows the verdict with no PC needed. Runs only with the
     * tunnel down; aborts the moment a real connection starts or the
     * manual-off flips mid-run. The probe never touches a real
     * tunnel: each draw's tunnel is stopped in the finally while it
     * is still the current one (exact-name match covers hero-name
     * probes; the backend mutex serializes the stop against any
     * hero connect racing it). Trigger: long-press the sheet
     * Refresh pill. Shares liveMeasureRunning with refreshLiveMs so
     * the two can never run concurrently.
     */
    fun runProbe() {
        if (liveMeasureRunning) return
        viewModelScope.launch {
            liveMeasureRunning = true
            _ui.update { it.copy(statusLine = "Probing…") }
            try {
                probeLiveMs()
            } finally {
                liveMeasureRunning = false
                _ui.update {
                    if (it.statusLine == "Probing…") it.copy(statusLine = null) else it
                }
            }
        }
    }

    private data class ProbeSpec(
        val tag: String,
        val heroName: Boolean,
        val freshKey: Boolean,
        val endpoint: String,
        val timeoutMs: Long,
        val maxAttempts: Int
    )

    private data class ProbeAttempt(
        val ms: Long?,
        val outcome: String
    )

    /** One-line error summary: cause chain + reason name/ordinal + frames. */
    private fun describeError(e: Throwable?): String {
        if (e == null) return "none"
        val be = e as? BackendException
        val chain = generateSequence(e) { it.cause }
            .take(4)
            .joinToString(" <- ") { it.javaClass.name + ":" + it.message }
        val frames = e.stackTrace.take(3)
            .joinToString("|") { it.methodName + ":" + it.lineNumber }
        val reason = if (be != null) {
            " reason=" + be.reason?.name + "/" + be.reason?.ordinal
        } else {
            ""
        }
        return chain + reason + " @ " + frames
    }

    /** One probe draw: testDrawMs parameterized by name/key/endpoint/window. */
    private suspend fun probeDraw(
        tag: String,
        conf: String,
        endpoint: String,
        heroName: Boolean,
        freshKey: Boolean,
        timeoutMs: Long
    ): ProbeAttempt {
        val swapped = manager.swapEndpoint(conf, endpoint)
        val cfg = if (freshKey) freshPrivateKeyConf(swapped) else swapped
        val rotated = cfg != swapped
        val drawName = if (heroName) {
            manager.tunnelNameFor(endpoint)
        } else {
            "live-measure-" + (++liveMeasureDrawCounter)
        }
        // prepare() returns null when authorized; non-null = the grant
        // dialog the app would have to show. No side effects.
        val prepareOk = VpnService.prepare(getApplication<Application>()) == null
        val t0 = SystemClock.elapsedRealtime()
        try {
            val ok = manager.startTunnelAndAwaitHandshake(
                drawName, cfg, timeoutMs, isTestDraw = true
            )
            val ms = if (ok) manager.lastHandshakeMs else null
            val outcome = when {
                ms != null -> "LANDED:$ms"
                ok -> "RX_NULL"
                else -> "FAIL"
            }
            Log.e(
                "PhoenixUpdate",
                "probe " + tag + " draw " + drawName +
                    " id=" + endpoint +
                    " rotated=" + rotated +
                    " outcome=" + outcome +
                    " dt=" + (SystemClock.elapsedRealtime() - t0) + "ms" +
                    " prepareOk=" + prepareOk +
                    " err=" + describeError(manager.lastDrawError)
            )
            return ProbeAttempt(ms, outcome)
        } catch (e: Exception) {
            Log.e(
                "PhoenixUpdate",
                "probe " + tag + " draw " + drawName +
                    " id=" + endpoint +
                    " rotated=" + rotated +
                    " outcome=EXCEPTION" +
                    " dt=" + (SystemClock.elapsedRealtime() - t0) + "ms" +
                    " prepareOk=" + prepareOk +
                    " err=" + describeError(e)
            )
            return ProbeAttempt(null, "EXCEPTION")
        } finally {
            val active = vpnState.value.activeTunnelName
            if (isTestTunnelName(active) || active == drawName) {
                try { manager.stopTunnel() } catch (_: Exception) {}
            }
        }
    }

    /** Runs P0-P3, P2b, P4 in order; logs one summary line per probe. */
    private suspend fun probeLiveMs() {
        if (!isVpnOff()) {
            Log.e("PhoenixUpdate", "probe aborted: vpn not off")
            return
        }
        val conf = store.baseConf ?: run {
            Log.e("PhoenixUpdate", "probe aborted: no config")
            return
        }
        val ids = sheetTargetIds()
        if (ids.isEmpty()) {
            Log.e("PhoenixUpdate", "probe aborted: no rows")
            return
        }
        val heroEndpoint = ids[0]
        val testEndpoint = if (ids.size > 1) ids[1] else ids[0]
        val specs = listOf(
            ProbeSpec("P0", true, false, heroEndpoint, 3000L, 3),
            ProbeSpec("P1", true, true, heroEndpoint, 3000L, 3),
            ProbeSpec("P2", false, false, heroEndpoint, 3000L, 3),
            ProbeSpec("P2b", true, false, testEndpoint, 3000L, 3),
            ProbeSpec("P3", false, true, heroEndpoint, 3000L, 3),
            ProbeSpec("P4", false, true, heroEndpoint, 6000L, 2)
        )
        val overrideAtStart = manager.isUserOverride()
        for (spec in specs) {
            if (!isVpnOff() || realTunnelActive() ||
                (manager.isUserOverride() && !overrideAtStart)
            ) {
                Log.e("PhoenixUpdate", "probe aborted mid-run at " + spec.tag)
                return
            }
            var landed: Long? = null
            var attempts = 0
            while (attempts < spec.maxAttempts && landed == null) {
                if (!isVpnOff() || realTunnelActive() ||
                    (manager.isUserOverride() && !overrideAtStart)
                ) {
                    Log.e("PhoenixUpdate", "probe aborted mid-run at " + spec.tag)
                    return
                }
                attempts++
                val attempt = probeDraw(
                    spec.tag, conf, spec.endpoint,
                    spec.heroName, spec.freshKey, spec.timeoutMs
                )
                landed = attempt.ms
            }
            Log.e(
                "PhoenixUpdate",
                "probe " + spec.tag + " result=" +
                    (landed?.let { "LANDED:$it" } ?: "FAIL") +
                    " attempts=" + attempts
            )
        }
        Log.e("PhoenixUpdate", "probe done")
    }

    /**
     * Sheet live-ms (adaptive, real-ms rule): each row is drawn until two
     * CONSECUTIVE LANDED handshakes agree within 30ms — that stability is
     * the proof the path is warm, so the later value is the shown value
     * (● LIVE). Failed draws never count: a blackholed flow never reaches
     * the backend, so it warms nothing (v1.51 counted attempts — draw 1
     * failing made draw 2 the first landed, cold handshake shown as LIVE).
     * A warm path stops at 2 draws; a cold path draws more on its own,
     * capped at 6 attempts per row. At the cap with 2+ landed, the last
     * landed value is shown; with fewer than 2 landed, the stored ms is
     * kept (no LIVE tag — an unwarmed value is never passed off as real).
     * The row shows "…" while measuring. Runs only with the tunnel down;
     * aborts the moment a real connection starts, the user cancels via
     * the hero tap, or a manual-off flips userOverride mid-run — without
     * ever touching the real tunnel.
     */
    private suspend fun measureLiveMs() {
        if (!isVpnOff()) {
            message("Live measurement skipped — VPN active")
            return
        }
        val conf = store.baseConf ?: run {
            message("No config — live measurement skipped")
            return
        }
        val targets = sheetTargetIds()
        if (targets.isEmpty()) return
        liveMeasureCancelled = false
        val overrideAtStart = manager.isUserOverride()
        _ui.update { it.copy(statusLine = "Measuring live ms…") }
        var measured = 0
        for (id in targets) {
            if (liveMeasureCancelled || !isVpnOff()) break
            if (manager.isUserOverride() && !overrideAtStart) break
            _ui.update { it.copy(measuringIds = it.measuringIds + id) }
            var prevLanded: Long? = null
            var landedCount = 0
            var shown: Long? = null
            var aborted = false
            var attempts = 0
            while (attempts < 6) {
                if (liveMeasureCancelled || !isVpnOff() ||
                    (manager.isUserOverride() && !overrideAtStart)
                ) {
                    aborted = true
                    break
                }
                attempts++
                val ms = testDrawMs(conf, id) ?: continue
                landedCount++
                val prev = prevLanded
                if (prev != null && kotlin.math.abs(ms - prev) <= 30L) {
                    shown = ms
                    break
                }
                prevLanded = ms
            }
            // Cap reached without stability: best available, but only if
            // at least one warm-up landed before it.
            if (!aborted && shown == null && landedCount >= 2) shown = prevLanded
            _ui.update { cur ->
                val measuring = cur.measuringIds - id
                if (!aborted && shown != null) {
                    if (id == store.manualEndpoint) store.manualMs = shown
                    cur.copy(measuringIds = measuring, liveMs = cur.liveMs + (id to shown))
                } else {
                    cur.copy(measuringIds = measuring)
                }
            }
            if (!aborted && shown != null) measured++
        }
        _ui.update {
            it.copy(
                measuringIds = emptySet(),
                statusLine = if (it.statusLine == "Measuring live ms…") null else it.statusLine
            )
        }
        if (!liveMeasureCancelled && measured > 0) message("Live ms updated")
        else if (!liveMeasureCancelled) message("Live ms unavailable — keeping stored ms")
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    // ============================================================
    //  Permission handshake with the Activity
    // ============================================================

    private suspend fun awaitPermission(): Boolean {
        val intent = manager.checkVpnPermission() ?: return true
        permissionDeferred = CompletableDeferred()
        _permissionRequest.value = intent
        return try {
            permissionDeferred!!.await()
        } catch (_: Exception) {
            false
        } finally {
            permissionDeferred = null
            _permissionRequest.value = null
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        permissionDeferred?.complete(granted)
    }

    // ============================================================
    //  Feed watch (Firebase signal, user spec 2026-10-28)
    // ============================================================

    /**
     * Starts the feed signal listener. Its first fire IS the on-open
     * fetch (listener fires immediately with the current value), so a
     * fresh install gets the feed at once, with no time gate at all.
     * If no signal channel exists yet (RTDB URL not set), falls back
     * to a single on-open fetch so the feed still arrives.
     */
    private fun startFeedWatch() {
        val listener = FeedSignalListener { v ->
            viewModelScope.launch { onFeedSignal(v) }
        }
        feedSignalListener = listener
        if (!listener.start()) {
            viewModelScope.launch { fetchAndApplyFeed() }
        }
    }

    /**
     * One signal fire = one fetch, deduplicated by the signal value:
     * a reconnect re-fire carries the SAME "v" and is skipped, while
     * a genuinely new publish (new "v") always fetches, even within
     * the same minute. A failed fetch does not consume the guard, so
     * a retry on the next fire still works.
     */
    private suspend fun onFeedSignal(v: Long?) {
        if (v != null && v == lastSignalValue) return
        if (fetchAndApplyFeed()) {
            lastSignalValue = v
        }
    }

    /**
     * Fetches the published list and applies it. Returns true only on
     * a successful fetch. Silent on the auto path (same as before).
     *
     * New feed (different updated_at, or a different endpoint set) =
     * stored list becomes the new list AND #1 is auto-SELECTED —
     * even while connected, even after a manual off (user spec
     * 2026-10-28). The live tunnel is NEVER touched here — no
     * stop, no restart, no default-tunnel change — so the tunnel
     * keeps running on the old endpoint and the internet is never
     * cut (user constraint 2026-10-28 18:25). Only the stored
     * selection + UI change. A manual tap still overrides the
     * selection until the next new feed. An unchanged feed touches
     * nothing but the stored list.
     */
    private suspend fun fetchAndApplyFeed(): Boolean {
        if (!hasValidatedInternet()) return false
        val res = try {
            EndpointService.fetch(app)
        } catch (_: Exception) {
            return false
        }
        if (!res.isSuccess) return false
        val f = res.getOrThrow()
        val oldUpdated = store.publishedUpdatedAt
        val oldIds = store.cachedEndpoints.map { it.id }
        val isNew = f.updatedAt != oldUpdated ||
            f.endpoints.map { it.id } != oldIds
        store.saveFetched(f, now())
        refreshUpdatedAgo()
        // v1.48: a live tunnel is never disturbed and never switched:
        // a degraded line is woken in place by the stall wake.
        if (isNew) {
            f.endpoints.firstOrNull()?.let { first ->
                store.currentEndpoint = first.id
                store.explicitEndpoint = null
            }
        }
        refreshUiRows()
        return true
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private fun pushDefaultTunnel(conf: String, endpointId: String) {
        manager.setDefaultTunnel(
            manager.tunnelNameFor(endpointId),
            manager.swapEndpoint(conf, endpointId)
        )
    }

    private fun msFor(id: String?): Long? {
        if (id == null) return null
        if (id == store.manualEndpoint) return store.manualMs
        return store.effectiveList().firstOrNull { it.id == id }?.ms
    }

    private fun refreshUiRows() {
        val deleted = store.deletedEndpoints
        val edited = store.editedEndpoints
        val top3 = store.effectiveList()
            .filter { it.id !in deleted }
            .map { e ->
                val newId = edited[e.id]
                if (newId != null) {
                    val parts = newId.split(":")
                    e.copy(ip = parts[0], port = parts.getOrNull(1)?.toIntOrNull() ?: e.port)
                } else e
            }
            .take(3)
        val cur = store.currentEndpoint ?: top3.firstOrNull()?.id
        val manual = store.manualEndpoint
        val rows = top3.mapIndexed { i, e ->
            EndpointRow(
                id = e.id,
                ms = e.ms,
                isBest = i == 0,
                isCurrent = e.id == cur,
                isManual = false
            )
        }
        _ui.update {
            it.copy(
                rows = rows,
                currentId = cur,
                currentMs = msFor(cur),
                manualId = manual,
                manualMs = store.manualMs
            )
        }
    }

    private fun refreshUpdatedAgo() {
        val ts = store.publishedUpdatedAt
        val now = now()
        _ui.update {
            it.copy(
                updatedAgo = agoString(ts, now),
                stale = ts > 0 && now - ts > EndpointStore.STALE_MS
            )
        }
    }

    private fun setPhase(phase: SetupPhase, statusLine: String?) {
        _ui.update { it.copy(phase = phase, statusLine = statusLine) }
    }

    private fun message(m: String) {
        _ui.update { it.copy(message = m) }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun hasValidatedInternet(): Boolean {
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidEndpoint(v: String): Boolean {
        // host:port — host may be IPv4, hostname, or [IPv6] (bracketed).
        val i = v.lastIndexOf(':')
        if (i <= 0 || i == v.length - 1) return false
        val port = v.substring(i + 1).toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        val host = v.substring(0, i).trim().trim('[', ']')
        return host.isNotEmpty() && !host.contains(' ')
    }

    private fun agoString(ts: Long, now: Long): String {
        if (ts <= 0L) return "not yet"
        val m = (now - ts) / 60000
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            m < 60 * 24 -> "${m / 60}h ago"
            else -> "${m / (60 * 24)}d ago"
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                // Re-emit to refresh the uptime label.
                _ui.update { it.copy() }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}

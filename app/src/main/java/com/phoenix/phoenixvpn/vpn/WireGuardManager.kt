package com.phoenix.phoenixvpn.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.phoenix.phoenixvpn.network.EndpointInfo
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class TunnelConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class VpnUiState(
    val connectionState: TunnelConnectionState = TunnelConnectionState.DISCONNECTED,
    val activeTunnelName: String? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val errorMessage: String? = null,
    /** True once a REAL handshake has landed for the current session (v1.40). */
    val handshakeLive: Boolean = false,
    /** Live handshake latency (ms from flow UP to handshake), not the stored list ms. */
    val livePingMs: Long? = null
)

class WireGuardManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var statsJob: Job? = null
    private var autoTunnelJob: Job? = null
    private var periodicCheckJob: Job? = null

    private val backend: Backend by lazy {
        GoBackend(context.applicationContext)
    }

    private val _vpnState = MutableStateFlow(VpnUiState())
    val vpnState: StateFlow<VpnUiState> = _vpnState.asStateFlow()

    // Current walk candidate, for the UI mirror: walkEndpoints sets this
    // on every attempt (manual AND auto) and clears it when the walk
    // ends, so the hero can show "Connecting… <id>" during manager-scope
    // auto walks that carry no onCandidate callback.
    private val _walkCandidate = MutableStateFlow<EndpointInfo?>(null)
    val walkCandidate: StateFlow<EndpointInfo?> = _walkCandidate.asStateFlow()

    /**
     * v1.64 (P-Tunnel flow): fires when the watchdog confirms the current
     * endpoint is unusable — rx stalled through the grace period, or the
     * handshake went stale after having landed. The UI layer fails over
     * with a walk from the successor (never a same-endpoint re-draw:
     * single-endpoint on/off looping is gone). Never fires while the
     * user is driving (manual off).
     */
    private val _endpointDeadEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val endpointDeadEvent: SharedFlow<Unit> = _endpointDeadEvent.asSharedFlow()

    /**
     * v1.62: fires on every underlying-network loss (NetworkCallback
     * onLost), before the debounced auto-tunnel rules run. The UI
     * layer uses it as the P-Tunnel-style feed rule's network trigger:
     * if a new feed is pending, the next walk starts from the new
     * feed's #1. Firing here (not in the rules) keeps the trigger
     * independent of whether auto-run is enabled.
     */
    private val _networkLostEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val networkLostEvent: SharedFlow<Unit> = _networkLostEvent.asSharedFlow()

    private var currentTunnel: PhoenixTunnel? = null

    /** Pure handshake RTT (ms) from the last startTunnelAndAwaitHandshake —
     *  measured from tunnel-UP to handshake-complete, excluding setup. */
    @Volatile
    var lastHandshakeMs: Long? = null
        private set
    /** Last exception a startTunnelAndAwaitHandshake attempt threw (v1.56
     *  probe): null on clean timeout/landed attempts. Read by the probe
     *  so a thrown reason is visible in Log.e instead of the stripped
     *  Log.d line. */
    @Volatile
    var lastDrawError: Throwable? = null
        private set
    private var currentConfig: Config? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Serializes every backend.setState() call. GoBackend keeps its tunnel
     * state (currentTunnel / currentTunnelHandle / currentConfig) in plain
     * unsynchronized fields, so concurrent setState() calls from the
     * failover loop, the network callback, the watchdog and the auto-run
     * rules can interleave and wedge the backend (stuck "connecting",
     * occasional native crash).
     */
    private val backendMutex = Mutex()
    /**
     * elapsedRealtime() of the last completed setState(DOWN). Before the
     * next setState(UP) we wait out [DOWN_UP_SETTLE_MS]: GoBackend's DOWN
     * tears the old tunnel down via VpnService.stopSelf(), whose onDestroy()
     * only runs later on the main thread — and that delayed onDestroy()
     * nulls the backend's current tunnel fields. Bringing a new tunnel UP
     * before it lands lets the stale onDestroy() kill the fresh tunnel
     * (TUN_CREATION_ERROR / dead handshake).
     */
    private var lastDownAt = 0L

    // Auto-tunnel state — the master switch is purely user-controlled and is
    // NEVER changed programmatically.
    private var isAutoRunEnabled = false
    private var userOverride = false

    // SharedPreferences-backed store (same file the UI layer uses — every
    // property reads prefs fresh, so this instance never goes stale).
    // The auto walk reads the base .conf and the cached list from here.
    private val endpointStore: EndpointStore by lazy { EndpointStore(context) }

    // Manager-scope auto walk (v1.64 P-Tunnel flow): runs the same
    // two-speed walk as manual, so auto works with the app closed too.
    private var autoWalkJob: Job? = null

    // P-Tunnel rule: a walk that exhausted every candidate stays stopped
    // until a manual ON or a new feed. Without this, the 15s periodic
    // check would re-walk forever on a dead list.
    @Volatile
    private var autoWalkExhausted = false

    fun isAutoWalkExhausted(): Boolean = autoWalkExhausted
    fun setAutoWalkExhausted() { autoWalkExhausted = true }
    fun clearAutoWalkExhausted() { autoWalkExhausted = false }
    fun cancelAutoWalk() {
        autoWalkJob?.cancel()
        autoWalkJob = null
    }

    // Current underlying network (not the VPN network).
    private var currentUnderlyingNetwork: Network? = null
    /** Last-seen VALIDATED state of the underlying network (for edge detection). */
    private var wasValidated = false

    init {
        registerNetworkCallback()
    }

    class PhoenixTunnel(
        private val name: String,
        private val onStateChanged: (Tunnel.State) -> Unit
    ) : Tunnel {
        private val safeInterfaceName = sanitizeInterfaceName(name)
        override fun getName(): String = safeInterfaceName
        override fun onStateChange(newState: Tunnel.State) {
            onStateChanged(newState)
        }

        companion object {
            fun sanitizeInterfaceName(rawName: String): String {
                val filtered = rawName.filter {
                    it in 'a'..'z' || it in 'A'..'Z' || it == '_' || it == '-'
                        || it in '0'..'9'
                }
                val truncated = filtered.take(15).trimEnd('_', '-')
                return if (truncated.isEmpty() || Tunnel.isNameInvalid(truncated)) "wg0" else truncated
            }
        }
    }

    /** Tunnel interface name for an endpoint id ("1.2.3.4:500" -> "1234500"-ish). */
    fun tunnelNameFor(endpointId: String): String =
        PhoenixTunnel.sanitizeInterfaceName(endpointId)

    // ============================================================
    //  Public API
    // ============================================================

    fun setAutoRun(enabled: Boolean) {
        isAutoRunEnabled = enabled
        if (enabled) {
            userOverride = false
            startPeriodicCheck()
            scope.launch {
                checkAndApplyAutoTunnelRules(immediate = true)
            }
        } else {
            autoTunnelJob?.cancel()
            autoTunnelJob = null
            stopPeriodicCheck()
        }
    }

    fun setUserOverride(override: Boolean) {
        userOverride = override
    }

    fun isUserOverride(): Boolean = userOverride

    fun isAutoRunEnabled(): Boolean = isAutoRunEnabled

    /** Notification "ရပ်ရန်": manual-off semantics — override sticks, tunnel down. */
    fun handleNotificationStop() {
        userOverride = true
        scope.launch(Dispatchers.IO) {
            stopTunnel()
        }
    }

    fun checkVpnPermission(): Intent? {
        return VpnService.prepare(context)
    }

    suspend fun startTunnel(name: String, configContent: String) = withContext(Dispatchers.IO) {
        try {
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.CONNECTING,
                    activeTunnelName = name,
                    errorMessage = null
                )
            }

            VpnForegroundService.start(context)

            val parsedConfig = Config.parse(
                ByteArrayInputStream(configContent.toByteArray(Charsets.UTF_8))
            )
            currentConfig = parsedConfig

            val tunnel = PhoenixTunnel(name) { newState ->
                handleBackendState(name, newState)
            }
            currentTunnel = tunnel

            val resultState = setBackendState(tunnel, Tunnel.State.UP, parsedConfig)
            handleBackendState(name, resultState)
            // Service is already in the foreground (or was just re-delivered
            // via startForegroundService on the running service): mirror the
            // connected state into its notification.
            VpnForegroundService.updateTunnelState(context, connected = true)
            startStatsPolling(tunnel)
            acquireLocks()
        } catch (e: Exception) {
            // Failure cleanup is quiet: it must NOT set the user override
            // (that is reserved for an explicit manual-off). Auto-run stays
            // armed so a later retry can still fire.
            VpnForegroundService.stopQuiet(context)
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.ERROR,
                    errorMessage = e.localizedMessage ?: "Failed to connect tunnel"
                )
            }
        }
    }

    /**
     * Bring the tunnel DOWN.
     *
     * @param keepService when true, the foreground service (and its
     * notification) is kept alive while the tunnel goes down. Used for the
     * automatic no-internet stop: on internet return, [startTunnel] can then
     * re-establish the tunnel without a background startForegroundService()
     * call, which Android 12+ would reject with
     * ForegroundServiceStartNotAllowedException. The notification is updated
     * to the honest idle state. Manual paths keep the default false: a real
     * manual-off stops the service entirely.
     */
    suspend fun stopTunnel(keepService: Boolean = false) = withContext(Dispatchers.IO) {
        // A stop always wins over a manager auto walk — including the
        // between-attempts gap, where currentTunnel is already null.
        // (The manual walk runs in the caller's scope, not autoWalkJob,
        // so it is unaffected.)
        autoWalkJob?.cancel()
        autoWalkJob = null
        val tunnel = currentTunnel ?: return@withContext
        try {
            _vpnState.update { it.copy(connectionState = TunnelConnectionState.DISCONNECTING) }
            stopStatsPolling()
            releaseLocks()
            val resultState = setBackendState(tunnel, Tunnel.State.DOWN, null)
            handleBackendState(tunnel.name, resultState)
            currentTunnel = null
            currentConfig = null
            if (keepService) {
                VpnForegroundService.updateTunnelState(context, connected = false)
            } else {
                // Quiet stop: manual-off callers (notification "ရပ်ရန်",
                // power-button off) already set the user override
                // explicitly before calling. Internal callers (failover,
                // endpoint switch) must NOT poison it.
                VpnForegroundService.stopQuiet(context)
            }
        } catch (e: Exception) {
            if (!keepService) {
                VpnForegroundService.stopQuiet(context)
            }
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.ERROR,
                    errorMessage = e.localizedMessage ?: "Failed to stop tunnel"
                )
            }
        }
    }

    // ============================================================
    //  Connect with handshake wait (failover primitive)
    // ============================================================
    // Bring the tunnel UP for one endpoint and wait for the first handshake
    // response (rx > 0) up to [timeoutMs]. True = handshake completed: the
    // tunnel stays UP, stats polling runs, locks are held (same connected
    // state as [startTunnel]). False = timeout/failure: the tunnel is
    // brought back DOWN and the foreground service stopped, so the caller
    // can try the next endpoint. Never touches the auto-run rules.
    // [isTestDraw] (v1.54, live-measure only): a successful draw must
    // NEVER look like a real connection — no CONNECTED state, no
    // notification flip, no stats loop, no locks. The tunnel is UP at
    // the backend level and the caller stops it; the UI state stays
    // CONNECTING with this name until that stop lands.
    suspend fun startTunnelAndAwaitHandshake(
        name: String,
        configContent: String,
        timeoutMs: Long = 5000L,
        isTestDraw: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        var tunnel: PhoenixTunnel? = null
        // Reset at the start: early returns (e.g. setState timeout) must not
        // leave a stale value from a previous attempt.
        lastHandshakeMs = null
        lastDrawError = null
        // Stuck watchdog (v1.26): a single attempt can never legitimately
        // exceed the 15s setState timeout + 5s handshake poll + slack. Still
        // inside after 30s → wedged at the native level → non-fatal to
        // Crashlytics so it auto-reports with zero user action.
        val attemptJob = coroutineContext.job
        val attemptDone = AtomicBoolean(false)
        val watchdog = scope.launch {
            delay(STUCK_WATCHDOG_MS)
            if (!attemptJob.isCancelled && attemptDone.compareAndSet(false, true)) {
                withContext(Dispatchers.IO) { reportStuckAttempt(name) }
            }
        }
        attemptJob.invokeOnCompletion {
            attemptDone.set(true)
            watchdog.cancel()
        }
        try {
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.CONNECTING,
                    activeTunnelName = name,
                    errorMessage = null
                )
            }

            // The foreground service stays up across failover attempts
            // (started once here, stopped quietly only when every attempt
            // fails). Per-attempt stop/start churn used to poison the user
            // override via ACTION_STOP's manual-off semantics.
            VpnForegroundService.start(context)

            val parsedConfig = Config.parse(
                ByteArrayInputStream(configContent.toByteArray(Charsets.UTF_8))
            )
            currentConfig = parsedConfig

            // Empty state callback: _vpnState is driven manually below, so a
            // racy backend UP event can't mark us CONNECTED before the
            // handshake is confirmed (timestamp) or the first data byte
            // arrives.
            tunnel = PhoenixTunnel(name) { }
            currentTunnel = tunnel

            // Wall-clock baseline for handshake-timestamp detection: a
            // handshake timestamp at/after this proves the handshake
            // completed during THIS attempt (a stale value from an
            // earlier session can't pass). Captured before setState(UP)
            // so a handshake racing the call still counts.
            val attemptStartWallMs = System.currentTimeMillis()

            try {
                setBackendState(tunnel, Tunnel.State.UP, parsedConfig)
            } catch (te: TimeoutCancellationException) {
                // Wedged backend: fail THIS attempt cleanly so the caller
                // tries the next endpoint instead of hanging "connecting".
                Log.e(TAG, "setState UP timed out for $name")
                lastDrawError = te
                tunnel?.let { t ->
                    try {
                        setBackendState(t, Tunnel.State.DOWN, null)
                    } catch (_: Exception) {
                    }
                }
                handleBackendState(name, Tunnel.State.DOWN)
                currentTunnel = null
                currentConfig = null
                return@withContext false
            }

            var handshakeOk = false
            // Pure handshake baseline (wall-clock): tunnel is UP, handshake
            // init sent. Using the peer's handshake timestamp avoids the
            // 250ms poll quantization — this is the true network RTT.
            val tunnelUpWallMs = System.currentTimeMillis()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isActive) break
                val stats = try {
                    backend.getStatistics(tunnel)
                } catch (_: Exception) {
                    null
                }
                val rx = stats?.totalRx() ?: 0L
                // Fast path: handshake completed during THIS attempt — works
                // on idle lines with zero traffic (no need to wait for data).
                // Slow path: first data bytes arrived. Either proves the
                // endpoint is alive.
                var hsWallMs: Long? = null
                val hsDone = stats?.peers()?.any { k ->
                    val t = stats.peer(k)?.latestHandshakeEpochMillis() ?: 0L
                    if (t >= attemptStartWallMs) hsWallMs = t
                    t >= attemptStartWallMs
                } == true
                if (hsDone) {
                    // Pure handshake RTT, excluding tunnel setup.
                    hsWallMs?.let { lastHandshakeMs = it - tunnelUpWallMs }
                    handshakeOk = true
                    break
                }
                if (rx > 0) {
                    handshakeOk = true
                    break
                }
                delay(250)
            }

            if (!handshakeOk) {
                try {
                    setBackendState(tunnel, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                }
                handleBackendState(name, Tunnel.State.DOWN)
                currentTunnel = null
                currentConfig = null
                return@withContext false
            }

            if (isTestDraw) {
                // Test draw only: stay out of the real-connection
                // machinery entirely (see the [isTestDraw] note above).
                // The backend tunnel is UP and the handshake is
                // confirmed; the caller stops it right after.
                true
            } else {
                handleBackendState(name, Tunnel.State.UP)
                VpnForegroundService.updateTunnelState(context, connected = true)
                startStatsPolling(tunnel)
                acquireLocks()
                true
            }
        } catch (e: Exception) {
            if (e is TimeoutCancellationException) {
                // A DOWN-path timeout (UP timeouts are handled above):
                // attempt failure, not a user cancellation.
                Log.e(TAG, "setState DOWN timed out for $name")
                currentTunnel = null
                currentConfig = null
                _vpnState.update {
                    it.copy(
                        connectionState = TunnelConnectionState.DISCONNECTED,
                        activeTunnelName = null,
                        errorMessage = null
                    )
                }
                return@withContext false
            }
            if (e is kotlinx.coroutines.CancellationException) {
                // Cancelled mid-attempt (user tapped again): don't leak a
                // half-up tunnel behind a stuck CONNECTING. NonCancellable
                // so the DOWN actually runs despite the cancellation.
                withContext(NonCancellable) {
                    try {
                        tunnel?.let { setBackendState(it, Tunnel.State.DOWN, null) }
                    } catch (_: Exception) {
                    }
                }
                currentTunnel = null
                currentConfig = null
                _vpnState.update {
                    it.copy(
                        connectionState = TunnelConnectionState.DISCONNECTED,
                        activeTunnelName = null,
                        errorMessage = null
                    )
                }
                throw e
            }
            lastDrawError = e
            Log.d(TAG, "connect $name failed: ${e.message}")
            try {
                currentTunnel?.let { setBackendState(it, Tunnel.State.DOWN, null) }
            } catch (_: Exception) {
            }
            currentTunnel = null
            currentConfig = null
            _vpnState.update {
                it.copy(
                    connectionState = TunnelConnectionState.DISCONNECTED,
                    activeTunnelName = null,
                    errorMessage = null
                )
            }
            false
        }
    }

    /**
     * v1.64 two-speed walk (P-Tunnel flow, shared by manual and auto):
     * candidates = the full cached list in publisher order (bundled trio
     * only if no feed), minus user-deleted, edits resolved. Pass 1 runs
     * from [startId] (else the stored last-connected, else #1) to the
     * end with the QUICK timeout; pass 2 runs the full list once more
     * with the FULL timeout, so a slow-landing endpoint cut short in
     * pass 1 still gets its full window. Per candidate exactly 1
     * attempt; the first handshake wins and becomes the new
     * last-connected. A pending new feed starts the walk at the feed's
     * #1 (flag cleared here) unless the caller already consumed it.
     * Returns the winner, or null when both passes found nothing.
     * Never loops on one endpoint: a failed candidate is left behind
     * at once — no same-endpoint DOWN→UP re-rolls anywhere in this
     * file anymore. [onCandidate] reports each attempt to the caller
     * (the manual walk uses it for its "Connecting… <id>" line).
     * [walkCandidate] mirrors the same attempt for the UI, so auto
     * walks (no onCandidate) show their current candidate too.
     */
    suspend fun walkEndpoints(
        conf: String,
        startId: String?,
        excludeId: String?,
        onCandidate: ((EndpointInfo) -> Unit)? = null
    ): EndpointInfo? {
        val published = endpointStore.walkList()
        if (published.isEmpty()) return null
        val start = when {
            endpointStore.feedRestartPending -> {
                endpointStore.feedRestartPending = false
                published.first().id
            }
            startId != null && published.any { it.id == startId } -> startId
            else -> endpointStore.currentEndpoint?.takeIf { id ->
                published.any { it.id == id }
            } ?: published.first().id
        }
        val startIdx = published.indexOfFirst { it.id == start }
        val pass1 = if (startIdx >= 0) published.subList(startIdx, published.size) else published
        val passes = listOf(pass1 to WALK_TIMEOUT_QUICK_MS, published to WALK_TIMEOUT_MS)
        try {
            for ((pass, timeoutMs) in passes) {
                for (e in pass) {
                    currentCoroutineContext().ensureActive()
                    if (e.id == excludeId) continue
                    onCandidate?.invoke(e)
                    _walkCandidate.value = e
                    val cfg = swapEndpoint(conf, e.id)
                    val ok = try {
                        startTunnelAndAwaitHandshake(tunnelNameFor(e.id), cfg, timeoutMs)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) {
                        endpointStore.currentEndpoint = e.id
                        if (endpointStore.explicitEndpoint != null &&
                            endpointStore.explicitEndpoint != endpointStore.manualEndpoint
                        ) {
                            endpointStore.explicitEndpoint = null
                        }
                        return e
                    }
                }
            }
            return null
        } finally {
            _walkCandidate.value = null
        }
    }

    /**
     * v1.64 auto-connect entry point (P-Tunnel flow): the auto-run rules
     * no longer start one default endpoint and re-roll it forever — they
     * run the same two-speed walk as manual, in manager scope, so auto
     * works with the app closed too. Guards: auto must be on, no user
     * override, no walk already running, nothing connected/connecting,
     * VPN permission granted (without it every attempt would fail and
     * burn the walk into exhaustion — skip silently and retry later),
     * and a base .conf must exist. On exhaustion the flag sticks until
     * a manual ON or a new feed clears it.
     */
    fun requestAutoWalk() {
        if (!isAutoRunEnabled || userOverride) return
        if (autoWalkExhausted) return
        if (autoWalkJob?.isActive == true) return
        val state = _vpnState.value.connectionState
        if (state == TunnelConnectionState.CONNECTED ||
            state == TunnelConnectionState.CONNECTING
        ) return
        if (checkVpnPermission() != null) return
        val conf = endpointStore.baseConf ?: return
        // No-internet pre-check (user 2026-11-03): never start an auto
        // walk without a LIVE validated underlying network. Skip
        // silently WITHOUT setting exhausted — the 15s periodic check
        // or the next validated event retries. Rule 1's caps check can
        // pass on a stale cache; this fresh scan does not trust it, so
        // a walk cancelled mid-cycle by a flap does not restart while
        // the real networks are still down.
        if (!hasLiveUsableNetwork()) return
        autoWalkJob = scope.launch(Dispatchers.IO) {
            try {
                if (walkEndpoints(conf, startId = null, excludeId = null) != null) {
                    autoWalkExhausted = false
                } else {
                    autoWalkExhausted = true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Auto walk failed", e)
                autoWalkExhausted = true
            }
        }
    }

    /**
     * Live usable-network scan: a FRESH pass over all underlying
     * networks for NOT_VPN + INTERNET + VALIDATED. Unlike
     * getUnderlyingNetworkCapabilities(), this never trusts the cached
     * currentUnderlyingNetwork — a stale-green cache must not
     * green-light a walk while the real networks are down.
     */
    private fun hasLiveUsableNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Stuck watchdog reporter (v1.26): a connect attempt still inside after
     * [STUCK_WATCHDOG_MS] is wedged at the native level (no Kotlin timeout
     * can interrupt it). Records a non-fatal to Crashlytics with the recent
     * log lines attached, so the failure auto-reports with zero user action.
     * Never throws — diagnostics must not break the VPN path.
     */
    private fun reportStuckAttempt(name: String) {
        try {
            val c = FirebaseCrashlytics.getInstance()
            c.setCustomKey("stuck_endpoint", name)
            c.setCustomKey("stuck_phase", "connect_attempt")
            c.log("Stuck watchdog fired for $name — recent log:")
            readRecentLogLines().forEach { c.log(it) }
            c.recordException(RuntimeException("VPN connect stuck >30s: $name"))
            Log.e(TAG, "Stuck watchdog: recorded non-fatal for $name")
        } catch (_: Exception) {
        }
    }

    private fun readRecentLogLines(): List<String> {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.lines()
                .filter { it.contains("PhoenixVpnManager") || it.contains("GoBackend") || it.contains("WireGuard") }
                .takeLast(60)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Replace the Endpoint line in the [Peer] section with the given value. */
    fun swapEndpoint(confText: String, endpoint: String): String {
        // The fetched .confs carry a single peer; replacing every Endpoint
        // line keeps multi-peer files consistent too. (No $ anchor: `.`
        // already stops at end-of-line, and `$` would clash with Kotlin
        // string templates.)
        return confText.replace(
            Regex("(?im)^\\s*Endpoint\\s*=.*"),
            "Endpoint = $endpoint"
        )
    }

    /**
     * The ONLY way to call backend.setState(). Serializes callers, enforces
     * the DOWN→UP settle, and hard-bounds setState(UP) with a timeout so a
     * wedged backend can never leave the UI stuck at "connecting" forever —
     * it becomes a clean attempt failure instead.
     */
    private suspend fun setBackendState(
        tunnel: PhoenixTunnel,
        state: Tunnel.State,
        config: Config?,
        settleMs: Long = DOWN_UP_SETTLE_MS
    ): Tunnel.State = backendMutex.withLock {
        if (state == Tunnel.State.UP) {
            val waitMs = settleMs - (SystemClock.elapsedRealtime() - lastDownAt)
            if (waitMs > 0) delay(waitMs)
        }
        try {
            withTimeout(SET_STATE_TIMEOUT_MS) {
                backend.setState(tunnel, state, config)
            }
        } finally {
            if (state == Tunnel.State.DOWN) lastDownAt = SystemClock.elapsedRealtime()
        }
    }

    // ============================================================
    //  Backend State
    // ============================================================

    private fun handleBackendState(tunnelName: String, state: Tunnel.State) {
        when (state) {
            Tunnel.State.UP -> {
                _vpnState.update {
                    it.copy(
                        connectionState = TunnelConnectionState.CONNECTED,
                        activeTunnelName = tunnelName,
                        errorMessage = null
                    )
                }
            }
            Tunnel.State.DOWN -> {
                _vpnState.update {
                    it.copy(
                        connectionState = TunnelConnectionState.DISCONNECTED,
                        activeTunnelName = null
                    )
                }
            }
            Tunnel.State.TOGGLE -> { /* intermediate */ }
        }
    }

    private fun startStatsPolling(tunnel: PhoenixTunnel) {
        stopStatsPolling()
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastRxProgressAt = System.currentTimeMillis()
        lastStaleRedrawAt = 0L
        // Live-PING fix (2026-11-01, v1.62 walk regression): the walk's
        // startTunnelAndAwaitHandshake already measured the real
        // handshake latency in lastHandshakeMs, but the old code below
        // recomputed it as (handshake epoch - polling start), which is
        // negative when the handshake landed during the walk — coerced
        // to a fake 0 ms for the whole session. Capture the walk value
        // here and clear it, so a later polling restart (wake) cannot
        // reuse a stale walk value.
        val walkHandshakeMs = lastHandshakeMs
        lastHandshakeMs = null
        tunnelUpWallMs = System.currentTimeMillis()
        _vpnState.update { it.copy(handshakeLive = false, livePingMs = null) }
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val stats: Statistics? = backend.getStatistics(tunnel)
                    if (stats != null) {
                        val rx = stats.totalRx()
                        val tx = stats.totalTx()
                        _vpnState.update {
                            val landed = handshakeLanded(stats)
                            it.copy(
                                rxBytes = rx, txBytes = tx,
                                handshakeLive = it.handshakeLive || landed,
                                livePingMs = if (landed && it.livePingMs == null) {
                                    // Prefer the walk's live handshake
                                    // latency (real, this session). Else
                                    // the epoch-minus-polling-start value,
                                    // but ONLY if positive — a stale
                                    // handshake must never become 0 ms;
                                    // staying null shows "–" honestly.
                                    val walkMs = walkHandshakeMs
                                    val calc =
                                        latestHandshakeEpoch(stats) - tunnelUpWallMs
                                    when {
                                        walkMs != null && walkMs > 0 -> walkMs
                                        calc > 0 -> calc
                                        else -> it.livePingMs
                                    }
                                } else {
                                    it.livePingMs
                                },
                                // v1.48 re-sync: the CURRENT flow is warm but
                                // the UI state was stranded by a backend DOWN
                                // event during wake restarts — heal it here.
                                // The stats loop only runs while the tunnel
                                // is intended UP, so this can't mark a down
                                // tunnel connected. `landed` (not the sticky
                                // handshakeLive) proves THIS flow is warm.
                                connectionState = if (landed &&
                                    it.connectionState != TunnelConnectionState.CONNECTED
                                ) {
                                    TunnelConnectionState.CONNECTED
                                } else {
                                    it.connectionState
                                }
                            )
                        }
                        watchdogCheck(rx, tx)
                        staleHandshakeCheck(stats)
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }

    /** Newest peer handshake time (epoch ms) in [stats]; 0 when none landed. */
    private fun latestHandshakeEpoch(stats: Statistics): Long =
        stats.peers().maxOfOrNull { k ->
            stats.peer(k)?.latestHandshakeEpochMillis() ?: 0L
        } ?: 0L

    /**
     * True when a REAL handshake has landed for the current flow: a
     * peer handshake timestamp at/after this flow's UP (1s grace for a
     * handshake that raced the UP stamp). A stale timestamp from an
     * earlier session can never pass — it predates the flow.
     */
    private fun handshakeLanded(stats: Statistics): Boolean {
        val hs = latestHandshakeEpoch(stats)
        return hs > 0L && hs >= tunnelUpWallMs - 1000L
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    /** Wall-clock stamp of the current flow's UP (set by stats polling). */
    private var tunnelUpWallMs: Long = 0L

    // v1.64 (P-Tunnel flow): the birth wake (v1.39) and the stall wake
    // (v1.48) are REMOVED — both looped DOWN→UP on a single endpoint
    // with no cap and never switched endpoints, which the user
    // explicitly rejected. A walk-verified connect lands its handshake
    // before CONNECTED, so no birth phase needs owning; a confirmed
    // mid-session stall or a stale handshake fires endpointDeadEvent
    // and the UI layer fails over with a walk from the successor.

    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastRxProgressAt: Long = 0L
    private var lastStaleRedrawAt: Long = 0L

    private suspend fun watchdogCheck(rx: Long, tx: Long) {
        val now = System.currentTimeMillis()
        if (rx != lastRxBytes) {
            lastRxBytes = rx
            lastTxBytes = tx
            lastRxProgressAt = now
            return
        }
        // Birth phase: a tunnel that has never received anything is not
        // "stalled" — walk-verified connects land their handshake before
        // CONNECTED, so rx == 0 here just means idle, not dead.
        if (rx == 0L) return
        // rx stalled. If tx is also flat the tunnel is simply idle — leave it.
        if (tx == lastTxBytes) return
        lastTxBytes = tx
        if (userOverride) return // user is driving — don't fight them
        if (now - lastRxProgressAt < WATCHDOG_STALL_MS) return
        // Confirmed stall (v1.64 P-Tunnel flow): NO same-endpoint
        // re-draw loop anymore — fire the dead-endpoint event and let
        // the UI layer fail over with a walk from the successor.
        Log.i(TAG, "Watchdog: confirmed stall — endpoint dead event")
        _endpointDeadEvent.tryEmit(Unit)
    }

    /**
     * Stale-handshake watchdog (v1.47, v1.64 P-Tunnel flow): the rx-stall
     * watchdog watches traffic counters, but WireGuard's own liveness
     * signal is the handshake clock — after RejectAfterTime (180s)
     * without a handshake the session keys are rejected by the protocol
     * itself. Once a real handshake has landed in this session, a
     * handshake older than STALE_HANDSHAKE_MS means the endpoint is
     * unusable — and v1.64 no longer re-draws it on the same endpoint.
     * It fires the dead-endpoint event instead, so the UI layer fails
     * over with a walk from the successor. The cooldown only spaces
     * repeat events. User override (manual-off) is never fought.
     */
    private suspend fun staleHandshakeCheck(stats: Statistics) {
        if (!_vpnState.value.handshakeLive) return
        // No UI-state gate (v1.48): stats decide — a stranded state must
        // not silently disable this watchdog.
        if (userOverride) return
        val hs = latestHandshakeEpoch(stats)
        if (hs <= 0L) return
        val now = System.currentTimeMillis()
        if (now - hs < STALE_HANDSHAKE_MS) return
        if (now - lastStaleRedrawAt < STALE_REDRAW_COOLDOWN_MS) return
        lastStaleRedrawAt = now
        Log.i(TAG, "Watchdog: handshake stale ${STALE_HANDSHAKE_MS}ms — endpoint dead event")
        _endpointDeadEvent.tryEmit(Unit)
    }

    // ============================================================
    //  Locks
    // ============================================================


    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PhoenixVPN:TunnelWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "PhoenixVPN:TunnelWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    // ============================================================
    //  Network Callback
    // ============================================================

    private fun registerNetworkCallback() {
        try {
            unregisterNetworkCallback()
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            connectivityManager = cm

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    currentUnderlyingNetwork = network
                    // No debounce here: onCapabilitiesChanged will signal
                    // VALIDATED, which is the prompt reconnect trigger.
                }

                override fun onLost(network: Network) {
                    if (currentUnderlyingNetwork == network) {
                        currentUnderlyingNetwork = null
                    }
                    wasValidated = false
                    onNetworkLost()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    currentUnderlyingNetwork = network
                    val validatedNow = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    val justValidated = validatedNow && !wasValidated
                    wasValidated = validatedNow
                    if (justValidated) {
                        onNetworkValidated()
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)

            // v1.68 revoke watch: a second callback that watches the VPN
            // network itself. The underlying-network callback above
            // explicitly excludes VPN (NET_CAPABILITY_NOT_VPN), so it can
            // never see a revoke. When another VPN takes over, Android
            // destroys our VPN network and this callback's onLost fires.
            val vpnRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            val vpnCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    onVpnNetworkLost()
                }
            }
            vpnNetworkCallback = vpnCallback
            cm.registerNetworkCallback(vpnRequest, vpnCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
            vpnNetworkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister", e)
        } finally {
            networkCallback = null
            vpnNetworkCallback = null
            connectivityManager = null
        }
    }

    /**
     * Internet-LOST path: short ~1s debounce (flap protection), then the
     * auto-tunnel rules are applied (they stop the tunnel: no usable net).
     *
     * NOTE: unlike the legacy build, this does NOT clear userOverride here.
     * A manual VPN-off must stick through network events; only an explicit
     * user action (tap connect / enable auto-run) clears it.
     */
    private fun onNetworkLost() {
        _networkLostEvent.tryEmit(Unit)
        scheduleRulesCheck(delayMs = 1000L)
    }

    /**
     * Revoke path (v1.68): the VPN network disappearing while we are
     * CONNECTED means Android revoked our tunnel — another VPN took over.
     * Why this callback, not onRevoke: the tunnel is owned by the
     * library's GoBackend$VpnService, which has NO onRevoke override
     * (wireguard-android 1.0.20230706, source-verified) — the Android
     * default just calls stopSelf(), and its onDestroy→onStateChange(DOWN)
     * propagation evidently does not reach the UI (field: stays
     * Connected). So observe the OS network directly instead of depending
     * on the library's service lifecycle. Treated like a manual off:
     * userOverride sticks, so Auto ON never re-walks and steals the
     * tunnel back from the other VPN. CONNECTED-only guard: our own
     * disconnect already moves the state to DISCONNECTING/DISCONNECTED
     * before the network teardown arrives, and a mid-connect revoke is
     * left to the connect flow (no race with startTunnel).
     */
    private fun onVpnNetworkLost() {
        if (_vpnState.value.connectionState != TunnelConnectionState.CONNECTED) return
        Log.i(TAG, "VPN network lost while connected — revoked by another VPN, disconnecting")
        userOverride = true
        scope.launch(Dispatchers.IO) {
            stopTunnel(keepService = false)
        }
    }

    /**
     * Internet-GAINED path: fired when NET_CAPABILITY_VALIDATED newly appears
     * on the underlying (non-VPN) network. No 3s debounce — the rules run
     * promptly (≤500ms) so auto-reconnect lands within 2–3s of the OS
     * declaring the network usable.
     */
    private fun onNetworkValidated() {
        scheduleRulesCheck(delayMs = 500L)
    }

    private fun scheduleRulesCheck(delayMs: Long) {
        autoTunnelJob?.cancel()
        autoTunnelJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)

            // Rebind if connected (Xiaomi-specific stalls). Serialized through
            // setBackendState; note this is a no-op when the tunnel and
            // config are unchanged (GoBackend short-circuits same refs).
            val tunnel = currentTunnel
            val config = currentConfig
            if (tunnel != null && config != null) {
                try {
                    setBackendState(tunnel, Tunnel.State.UP, config)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebind tunnel", e)
                }
            }

            checkAndApplyAutoTunnelRules()
        }
    }

    /**
     * Auto-tunnel rules:
     *   1. Stop on no usable internet (even Wi-Fi without internet).
     *   2. Respect user override (manual-off sticks).
     *   3. Auto-connect on Wi-Fi / Mobile data when enabled.
     * The master switch itself is NEVER changed here.
     */
    private suspend fun checkAndApplyAutoTunnelRules(immediate: Boolean = false) {
        if (!isAutoRunEnabled) return

        val caps = getUnderlyingNetworkCapabilities()

        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        Log.d(TAG, "AutoTunnel Check: internet=$hasInternet, validated=$isValidated, " +
                "wifi=$isWifi, cell=$isCellular, vpnState=${_vpnState.value.connectionState}")

        // ----- Rule 1: Stop on no usable internet -----
        if (!hasInternet || !isValidated) {
            if (_vpnState.value.connectionState == TunnelConnectionState.CONNECTED ||
                _vpnState.value.connectionState == TunnelConnectionState.CONNECTING
            ) {
                Log.d(TAG, "No usable network → stopping tunnel (service kept alive)")
                stopTunnel(keepService = true)
            }
            return
        }

        // ----- Rule 2: Respect user override -----
        if (userOverride && !immediate) {
            Log.d(TAG, "User override active → skip auto-connect")
            return
        }

        // ----- Rule 3: Auto-connect on Wi-Fi / Mobile data -----
        // v1.64 P-Tunnel flow: the SAME two-speed walk as manual, in
        // manager scope (requestAutoWalk carries the guards). No more
        // single-default-endpoint start + birth wake.
        if (isWifi || isCellular) {
            requestAutoWalk()
        }
    }

    /**
     * Capabilities of the non-VPN underlying network, so the real Wi-Fi/Data
     * state is visible even while the VPN is up.
     */
    private fun getUnderlyingNetworkCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null

        currentUnderlyingNetwork?.let { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return caps
            }
        }

        val allNetworks = cm.allNetworks
        for (network in allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                currentUnderlyingNetwork = network
                return caps
            }
        }

        val activeCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (activeCaps != null &&
            activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        ) {
            return activeCaps
        }

        return null
    }

    // ============================================================
    //  Periodic Check
    // ============================================================

    private fun startPeriodicCheck() {
        stopPeriodicCheck()
        periodicCheckJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15000)
                if (isAutoRunEnabled) {
                    try {
                        checkAndApplyAutoTunnelRules()
                    } catch (e: Exception) {
                        Log.e(TAG, "Periodic check error", e)
                    }
                }
            }
        }
    }

    private fun stopPeriodicCheck() {
        periodicCheckJob?.cancel()
        periodicCheckJob = null
    }

    companion object {
        private const val TAG = "PhoenixVpnManager"

        /** rx stall window before the watchdog forces a re-handshake. */
        private const val WATCHDOG_STALL_MS = 12_000L
        /** Max re-handshakes per stall episode (resets when rx moves again). */
        /**
         * Stale-handshake trigger (v1.47): WireGuard's RejectAfterTime — a
         * session with no handshake for this long is protocol-dead.
         */
        private const val STALE_HANDSHAKE_MS = 180_000L
        /** Minimum gap between stale re-draws (the new flow's proving time). */
        private const val STALE_REDRAW_COOLDOWN_MS = 30_000L
        /**
         * DOWN→UP settle: lets a torn-down VpnService's delayed onDestroy()
         * finish before the next establish(), so it can't kill the fresh
         * tunnel. Applies only right after a DOWN; a fresh UP waits nothing.
         */
        private const val DOWN_UP_SETTLE_MS = 500L
        /**
         * v1.64 two-speed walk timeouts (P-Tunnel flow): pass 1 steps
         * over failures fast (field evidence: connecting endpoints land
         * immediately, failing ones never land even in 5s); pass 2 keeps
         * the full window so a slow lander cut short in pass 1 still
         * gets its full chance before the walk gives up.
         */
        private const val WALK_TIMEOUT_QUICK_MS = 2_000L
        private const val WALK_TIMEOUT_MS = 5_000L
        /**
         * Hard backstop for backend.setState(): converts a wedged backend
         * into a clean attempt failure instead of a forever-"connecting" UI.
         * Normal UP completes in well under a second (local tun setup).
         */
        private const val SET_STATE_TIMEOUT_MS = 15_000L

        /**
         * Stuck watchdog for a single connect attempt (v1.26). One attempt
         * can never legitimately exceed the 15s setState timeout + 5s
         * handshake poll + slack — beyond this the backend is wedged at
         * the native level and the UI would sit at "connecting" forever.
         */
        private const val STUCK_WATCHDOG_MS = 30_000L

        @Volatile
        private var INSTANCE: WireGuardManager? = null

        fun getInstance(context: Context): WireGuardManager {
            return INSTANCE ?: synchronized(this) {
                val instance = WireGuardManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}

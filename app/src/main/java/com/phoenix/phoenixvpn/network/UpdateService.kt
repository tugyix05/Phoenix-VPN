package com.phoenix.phoenixvpn.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.DnsResolver
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * In-app update check + download (v1.46, user spec 2026-10-24).
 *
 * The browser is NEVER involved: some phones' browsers block GitHub
 * APK downloads, so the version check reads the GitHub "latest
 * release" redirect with the app's own HTTP client, the APK is
 * downloaded by the app itself, and the local file is handed to the
 * system installer. Both requests are bound to the underlying
 * physical network (the [EndpointService] rule) so a dead tunnel
 * can never strangle an update.
 */
object UpdateService {

    private const val LATEST_URL =
        "https://github.com/tugyix05/Phoenix-VPN/releases/latest"
    private const val DOWNLOAD_BASE =
        "https://github.com/tugyix05/Phoenix-VPN/releases/download"

    /**
     * v1.61: Lovable download proxy (user design 2026-10-29).
     * The updater downloads through the EXISTING Lovable route —
     * the same link the homepage button serves, no second link, no
     * re-upload. The version CHECK still uses GitHub (LATEST_URL):
     * only the download leg moves, Lovable-first with GitHub
     * fallback, because the WiFi path to GitHub's asset host takes
     * ~20s to start while Lovable starts immediately. The route
     * serves the CURRENT version, so a Lovable download is accepted
     * only when the APK's own versionName matches the expected
     * version; otherwise it falls back to the versioned GitHub URL.
     */
    private const val LOVABLE_DOWNLOAD_URL =
        "https://ipmh.lovable.app/api/public/apk/phoenix-vpn-v18"

    private const val TAG = "PhoenixUpdate"

    data class ReleaseInfo(val tag: String, val version: String) {
        val apkUrl: String get() = "$DOWNLOAD_BASE/$tag/PhoenixVPN-v$version.apk"
        val apkName: String get() = "PhoenixVPN-v$version.apk"
        val proxyUrl: String get() = LOVABLE_DOWNLOAD_URL
    }

    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .eventListener(UpdateEventListener())
        .build()

    /** The latest release published on GitHub. Never throws. */
    suspend fun fetchLatest(context: Context): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            val bound = boundClient(context, checkClient)
            val r = attemptLatest(bound ?: checkClient)
            if (r.isSuccess || bound == null) r else attemptLatest(checkClient)
        }

    private fun attemptLatest(client: OkHttpClient): Result<ReleaseInfo> {
        return try {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("User-Agent", "PhoenixVPN (Android update check)")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                    ?: return Result.failure(
                        IOException("HTTP ${resp.code}, no redirect")
                    )
                // Location: .../releases/tag/v1.46-202610240200
                val tag = loc.substringAfter("/releases/tag/", "")
                    .substringBefore("?")
                    .trimEnd('/')
                if (tag.isEmpty()) {
                    return Result.failure(IOException("Bad redirect"))
                }
                val version = tag.removePrefix("v").substringBefore("-")
                if (version.isEmpty() || version == tag) {
                    return Result.failure(IOException("Bad tag: $tag"))
                }
                Result.success(ReleaseInfo(tag, version))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    //  Kept downloaded APK (v1.53, user spec 2026-10-25).
    //
    //  A completed update download is KEPT in filesDir/updates until
    //  its update actually installs, so an install error, a cancel,
    //  or an app close never forces a re-download: the next UPDATE
    //  tap installs the kept file directly. The record (version +
    //  name + size + sha256 of the completed download) lives in the
    //  same prefs file the UI already uses, so it survives process
    //  death and app restarts. Deletion happens ONLY on successful
    //  install (UpdateCleanupReceiver, via MY_PACKAGE_REPLACED) —
    //  install failure/cancel sends no such broadcast, so the kept
    //  file stays for a no-redownload retry.
    // ============================================================

    data class KeptApk(
        val version: String,
        val name: String,
        val size: Long,
        val sha256: String
    )

    private const val KEPT_PREFS = "phoenix_vpn_ui"
    private const val KEY_KEPT_VERSION = "kept_apk_version"
    private const val KEY_KEPT_NAME = "kept_apk_name"
    private const val KEY_KEPT_SIZE = "kept_apk_size"
    private const val KEY_KEPT_SHA = "kept_apk_sha256"

    /** Durable home for kept update APKs (never cache-purged). */
    fun updatesDir(context: Context): File = File(context.filesDir, "updates")

    fun keptRecord(context: Context): KeptApk? {
        val p = context.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE)
        val version = p.getString(KEY_KEPT_VERSION, null) ?: return null
        val name = p.getString(KEY_KEPT_NAME, null) ?: return null
        val size = p.getLong(KEY_KEPT_SIZE, -1L)
        val sha = p.getString(KEY_KEPT_SHA, null) ?: return null
        if (size < 0) return null
        return KeptApk(version, name, size, sha)
    }

    fun saveKeptRecord(context: Context, kept: KeptApk) {
        context.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_KEPT_VERSION, kept.version)
            .putString(KEY_KEPT_NAME, kept.name)
            .putLong(KEY_KEPT_SIZE, kept.size)
            .putString(KEY_KEPT_SHA, kept.sha256)
            .apply()
    }

    fun clearKeptRecord(context: Context) {
        context.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_KEPT_VERSION)
            .remove(KEY_KEPT_NAME)
            .remove(KEY_KEPT_SIZE)
            .remove(KEY_KEPT_SHA)
            .apply()
    }

    /**
     * Delete the recorded kept APK + its record + legacy
     * cache/updates leftovers (v1.52 and earlier downloaded there
     * and never cleaned up). Never throws.
     */
    fun deleteKeptApk(context: Context) {
        try {
            val kept = keptRecord(context)
            if (kept != null) {
                val f = File(updatesDir(context), kept.name)
                if (f.exists()) f.delete()
            }
            clearKeptRecord(context)
            val legacy = File(context.cacheDir, "updates")
            if (legacy.isDirectory) {
                legacy.listFiles()?.forEach { if (it.isFile) it.delete() }
            }
        } catch (_: Exception) {
        }
    }

    /** SHA-256 hex of a file, or null on any error. */
    fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * v1.61: download with Lovable-first + GitHub fallback.
     * Attempt 1 is the existing Lovable route (same link as the
     * homepage button). It is accepted only when the APK's own
     * versionName matches the expected version — the route serves
     * the CURRENT version, so during the window between a GitHub
     * publish and the Lovable link update it would serve the OLD
     * file. On any Lovable failure, or a version mismatch, attempt
     * 2 is the versioned GitHub URL, which is always the right
     * version. The version CHECK is unchanged (GitHub latest).
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        destDir: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val proxy = attemptDownload(context, info.proxyUrl, true, info, destDir, onProgress)
        if (proxy.isSuccess) {
            val file = proxy.getOrNull()!!
            val got = downloadedVersion(context, file)
            if (got == info.version) return@withContext proxy
            Log.i(TAG, "Update download: Lovable served $got, expected ${info.version} — falling back to GitHub")
            file.delete()
        } else {
            Log.i(TAG, "Update download: Lovable failed — falling back to GitHub")
        }
        attemptDownload(context, info.apkUrl, false, info, destDir, onProgress)
    }

    /** versionName recorded inside an APK file, or null if unreadable. */
    private fun downloadedVersion(context: Context, file: File): String? = try {
        val pm = context.packageManager
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            legacyArchiveInfo(pm, file)
        }
        archiveInfo?.versionName
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun legacyArchiveInfo(pm: PackageManager, file: File) =
        pm.getPackageArchiveInfo(file.absolutePath, 0)

    /**
     * One download attempt from [url] into [destDir], reporting
     * progress 0..100 via [onProgress]. Returns the downloaded
     * file. Writes to a .tmp file and renames only on success, so
     * a killed download can never masquerade as a complete APK.
     */
    private suspend fun attemptDownload(
        context: Context,
        url: String,
        viaProxy: Boolean,
        info: ReleaseInfo,
        destDir: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            destDir.mkdirs()
            val dest = File(destDir, info.apkName)
            val tmp = File(destDir, info.apkName + ".tmp")
            if (tmp.exists()) tmp.delete()
            val client = boundClient(context, downloadClient) ?: downloadClient
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "PhoenixVPN (Android update)")
                .get()
                .build()
            Log.i(TAG, "Update download: start " + info.apkName + if (viaProxy) " via Lovable" else " via GitHub")
            val t0 = SystemClock.elapsedRealtime()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i(TAG, "Update download: HTTP " + resp.code)
                    return@withContext Result.failure(
                        IOException("HTTP ${resp.code}")
                    )
                }
                val body = resp.body
                    ?: return@withContext Result.failure(IOException("Empty body"))
                val total = body.contentLength()
                Log.i(TAG, "Update download: headers HTTP " + resp.code + " size=" + total + " after=" + (SystemClock.elapsedRealtime() - t0) + "ms")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                onProgress((read * 100 / total).toInt())
                            }
                        }
                    }
                }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                Result.failure(IOException("Empty APK"))
            } else {
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    Result.failure(IOException("Rename failed"))
                } else {
                    onProgress(100)
                    Log.i(TAG, "Update download: done " + dest.length() + " bytes")
                    Result.success(dest)
                }
            }
        } catch (e: Exception) {
            try {
                File(destDir, info.apkName + ".tmp").delete()
            } catch (_: Exception) {
            }
            Log.i(TAG, "Update download: failed " + e.javaClass.simpleName + ": " + e.message)
            Result.failure(e)
        }
    }

    /**
     * v1.50 diagnostic: a 1-byte Range probe through the updater's own
     * bound client (same path as a real download, no body cost).
     * The EventListener phase lines + the summary land in the log
     * under TAG PhoenixUpdate so the Log dialog shows them.
     */
    suspend fun diagnose(context: Context, info: ReleaseInfo): Result<String> =
        withContext(Dispatchers.IO) {
            val client = boundClient(context, downloadClient) ?: downloadClient
            var lastError: Exception? = null
            for (url in listOf(info.proxyUrl, info.apkUrl)) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "PhoenixVPN (Android update)")
                        .header("Range", "bytes=0-0")
                        .get()
                        .build()
                    val t0 = SystemClock.elapsedRealtime()
                    client.newCall(req).execute().use { resp ->
                        val finalUrl = resp.request.url.toString()
                        val summary = "Network diagnostic: HTTP " + resp.code +
                            " final=" + finalUrl +
                            " total=" + (SystemClock.elapsedRealtime() - t0) + "ms"
                        Log.i(TAG, summary)
                        return@withContext Result.success(summary)
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "Network diagnostic: failed " + e.javaClass.simpleName + ": " + e.message)
                    lastError = e
                }
            }
            Result.failure(lastError ?: IOException("Diagnostic failed"))
        }

    /** Dotted numeric compare: "1.46" > "1.45"; missing parts = 0. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * v1.50: split A/AAAA DNS on the bound network, IPv4-first.
     * A and AAAA are queried in parallel via DnsResolver (API 29+;
     * below that, or if both come back empty, the old getAllByName
     * path runs). A is waited up to 3000ms; AAAA gets 3000ms if A
     * came back empty, else only a 50ms grace — a slow/dead AAAA
     * can never stall an A answer. Per-type timing is logged.
     */
    private class SplitDns(private val net: Network) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return net.getAllByName(hostname).toList()
            }
            val t0 = SystemClock.elapsedRealtime()
            val aFuture = CompletableFuture<List<InetAddress>>()
            val aaaaFuture = CompletableFuture<List<InetAddress>>()
            queryType(hostname, DnsResolver.TYPE_A, aFuture)
            queryType(hostname, DnsResolver.TYPE_AAAA, aaaaFuture)
            val a = await(aFuture, 3000L)
            Log.i(TAG, "DNS A " + hostname + " " + (SystemClock.elapsedRealtime() - t0) + "ms n=" + a.size)
            val aaaaWait = if (a.isEmpty()) 3000L else 50L
            val aaaa = await(aaaaFuture, aaaaWait)
            Log.i(TAG, "DNS AAAA " + hostname + " " + (SystemClock.elapsedRealtime() - t0) + "ms n=" + aaaa.size)
            val combined = a + aaaa
            if (combined.isNotEmpty()) return combined
            Log.i(TAG, "DNS fallback getAllByName " + hostname)
            return net.getAllByName(hostname).toList()
        }

        private fun queryType(hostname: String, type: Int, future: CompletableFuture<List<InetAddress>>) {
            try {
                DnsResolver.getInstance().query(
                    net, hostname, type, DnsResolver.FLAG_EMPTY,
                    Executor { it.run() }, null,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            future.complete(answer)
                        }
                        override fun onError(error: DnsResolver.DnsException) {
                            future.complete(emptyList())
                        }
                    }
                )
            } catch (_: Exception) {
                future.complete(emptyList())
            }
        }

        private fun await(future: CompletableFuture<List<InetAddress>>, timeoutMs: Long): List<InetAddress> {
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** v1.50: per-phase timing for the download path (bound copies inherit it). */
    private class UpdateEventListener : EventListener() {
        override fun dnsStart(call: Call, domainName: String) {
            Log.i(TAG, "Event dnsStart " + domainName)
        }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            Log.i(TAG, "Event dnsEnd " + domainName + " n=" + inetAddressList.size)
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            Log.i(TAG, "Event connectStart " + inetSocketAddress)
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            Log.i(TAG, "Event connectEnd " + inetSocketAddress)
        }
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, e: IOException) {
            Log.i(TAG, "Event connectFailed " + inetSocketAddress + " " + e.javaClass.simpleName)
        }
        override fun secureConnectStart(call: Call) {
            Log.i(TAG, "Event secureConnectStart")
        }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            Log.i(TAG, "Event secureConnectEnd " + handshake?.tlsVersion)
        }
        override fun responseHeadersEnd(call: Call, response: Response) {
            Log.i(TAG, "Event responseHeadersEnd " + response.code)
        }
    }

    private fun boundClient(context: Context, base: OkHttpClient): OkHttpClient? {
        val net = underlyingNetwork(context) ?: return null
        return try {
            base.newBuilder()
                .socketFactory(net.socketFactory)
                .dns(SplitDns(net))
                .build()
        } catch (_: Exception) {
            null
        }
    }

    /** The physical network underneath any VPN, or null. */
    private fun underlyingNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }
}

package com.phoenix.phoenixvpn.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class EndpointInfo(
    val ip: String,
    val port: Int,
    val ms: Long?,
    val jitterMs: Long?
) {
    val id: String get() = "$ip:$port"
}

data class FetchedEndpoints(
    /** updated_at from the file, epoch millis. 0 if unparseable. */
    val updatedAt: Long,
    val endpoints: List<EndpointInfo>
)

/**
 * Fetches the published top-10 endpoint list.
 *
 * Schema: {"v":1,"updated_at":"<ISO8601>","isp":"MPT",
 *          "endpoints":[{"ip":..,"port":500,"ms":..,"jitter_ms":..}]}
 *
 * The file may 404 until the first publish — callers treat failure as
 * "keep cache / bundled", never as fatal.
 *
 * Relay-first (user spec 2026-10-28, no-VPN on all networks): the
 * Lovable relay is tried before GitHub raw, because the relay is
 * reachable on networks where GitHub is not (home WiFi) and where
 * Firebase is not (MPT data, home WiFi). Raw stays as the fallback
 * for when the relay itself is down. The relay serves the same file
 * byte-identical (verified 2026-10-28), so callers see no difference.
 *
 * Each URL is fetched bound to the underlying physical network
 * (cellular / Wi-Fi), never routed through the VPN tunnel: plain
 * internet is enough to receive new endpoints — no working tunnel
 * and no handshake are required, so a dead/blackholed tunnel can
 * never strangle the refresh.
 */
object EndpointService {
    /** Lovable feed relay (proven live 2026-10-28, no-VPN everywhere). */
    const val RELAY_URL = "https://ipmh.lovable.app/api/test-relay"

    const val URL = "https://raw.githubusercontent.com/tugyix05/Warp-IP-Scanner/main/endpoints.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(context: Context): Result<FetchedEndpoints> =
        withContext(Dispatchers.IO) {
            val relay = attemptUrl(context, RELAY_URL)
            if (relay.isSuccess) return@withContext relay
            attemptUrl(context, URL)
        }

    /** One URL, bound to the underlying network first, then unbound. */
    private fun attemptUrl(context: Context, url: String): Result<FetchedEndpoints> {
        val net = underlyingNetwork(context)
        if (net != null) {
            val bound = try {
                httpClient.newBuilder()
                    .socketFactory(net.socketFactory)
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            net.getAllByName(hostname).toList()
                    })
                    .build()
            } catch (_: Exception) {
                null
            }
            if (bound != null) {
                val r = attempt(bound, url)
                if (r.isSuccess) return r
            }
        }
        return attempt(httpClient, url)
    }

    private fun attempt(client: OkHttpClient, url: String): Result<FetchedEndpoints> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PhoenixVPN/1.0 (Android)")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return Result.failure(IOException("Empty response body"))
                try {
                    Result.success(parse(body))
                } catch (e: Exception) {
                    Result.failure(IOException("Bad endpoints.json: ${e.message}", e))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The physical network underneath any VPN (cellular / Wi-Fi /
     * ethernet), or null when none is available.
     */
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

    /** Visible for tests. */
    fun parse(body: String): FetchedEndpoints {
        val o = JSONObject(body)
        val updatedAt = parseIso8601(o.optString("updated_at", ""))
        val arr = o.optJSONArray("endpoints") ?: throw IOException("missing endpoints array")
        val list = mutableListOf<EndpointInfo>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val ip = e.optString("ip", "").trim()
            if (ip.isEmpty()) continue
            list.add(
                EndpointInfo(
                    ip = ip,
                    port = e.optInt("port", 500),
                    ms = if (e.isNull("ms")) null else e.optLong("ms"),
                    jitterMs = if (e.isNull("jitter_ms")) null else e.optLong("jitter_ms")
                )
            )
        }
        if (list.isEmpty()) throw IOException("empty endpoints array")
        return FetchedEndpoints(updatedAt = updatedAt, endpoints = list)
    }

    private fun parseIso8601(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            // Accept "2026-10-04T14:30:00Z" and with fractional seconds.
            val norm = s.trim().replace("Z", "+00:00")
            java.time.OffsetDateTime.parse(norm).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

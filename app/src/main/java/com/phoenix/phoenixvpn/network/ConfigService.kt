package com.phoenix.phoenixvpn.network

import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class FetchedConfig(
    val name: String,
    val content: String,
    val parsedConfig: Config
)

/** Fetches a fresh WireGuard .conf from the generator worker. */
object ConfigService {
    private const val GENERATE_URL = "https://pguard.val.run/"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchWireGuardConfig(): Result<FetchedConfig> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GENERATE_URL)
                .header("User-Agent", "PhoenixVPN/1.0 (Android)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP error code: ${response.code}")
                    )
                }

                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                val contentDisposition = response.header("Content-Disposition")
                var extractedName = extractFilename(contentDisposition)

                if (extractedName.isNullOrBlank()) {
                    extractedName = extractNameFromBody(body) ?: "Phoenix-Tunnel"
                }

                if (extractedName.endsWith(".conf", ignoreCase = true)) {
                    extractedName = extractedName.substring(0, extractedName.length - 5)
                }

                val parsed = try {
                    Config.parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid WireGuard configuration format: ${e.message}", e)
                    )
                }

                Result.success(FetchedConfig(name = extractedName, content = body, parsedConfig = parsed))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val pattern = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(header)
        return if (matcher.find()) {
            matcher.group(1)?.trim()
        } else null
    }

    private fun extractNameFromBody(body: String): String? {
        val endpointPattern = Pattern.compile("Endpoint\\s*=\\s*([0-9a-zA-Z.:]+)")
        val matcher = endpointPattern.matcher(body)
        if (matcher.find()) {
            val endpoint = matcher.group(1) ?: return null
            return endpoint.split(":").firstOrNull()
        }
        return null
    }
}

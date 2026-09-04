package com.usagehub.app

import org.json.JSONObject
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class FetchResult(
    val snapshot: DashboardSnapshot,
    val healthOk: Boolean,
    val displayName: String,
    val avatar: ByteArray?,
)

class DashboardClient {
    fun fetch(baseUrl: String, key: String, nowMillis: Long): FetchResult {
        requireSecureUrl(baseUrl)
        val dashboardRaw = request(
            url = "${baseUrl.trimEnd('/')}/v1/dashboard",
            authorization = "Bearer $key",
        )
        val snapshot = DashboardJson.parse(dashboardRaw, nowMillis)
        val profile = runCatching {
            val profileRaw = request("${baseUrl.trimEnd('/')}/v1/display/profile", authorization = "Bearer $key")
            val profileJson = JSONObject(profileRaw)
            val displayName = profileJson.optString("displayName").take(100).ifBlank { "UsageHub" }
            val avatar = if (profileJson.optString("avatarVersion").isNotBlank()) requestBytes("${baseUrl.trimEnd('/')}/v1/display/avatar", "Bearer $key") else null
            displayName to avatar
        }.getOrDefault("UsageHub" to null)
        val healthOk = runCatching {
            request("${baseUrl.trimEnd('/')}/v1/health", authorization = null)
            true
        }.getOrDefault(false)
        return FetchResult(snapshot, healthOk, profile.first, profile.second)
    }

    fun health(baseUrl: String): Boolean {
        requireSecureUrl(baseUrl)
        return runCatching {
            request("${baseUrl.trimEnd('/')}/v1/health", authorization = null)
            true
        }.getOrDefault(false)
    }

    fun resolveDisplayToken(baseUrl: String, tokenInput: String, deviceId: String): String {
        requireSecureUrl(baseUrl)
        val token = tokenInput.trim()
        if (token.startsWith("uh_display_") && token.length <= 256) return token
        require(token.startsWith("uh_enroll_") && token.length <= 256) { "请输入有效的 Display Token 或配对码" }
        val payload = JSONObject().put("token", token).put("deviceId", deviceId).toString()
        val raw = request(
            url = "${baseUrl.trimEnd('/')}/v1/device-enrollments/redeem",
            authorization = null,
            method = "POST",
            body = payload,
        )
        val displayToken = JSONObject(raw).optString("token")
        require(displayToken.startsWith("uh_display_") && displayToken.length <= 256) { "服务器未返回有效的 Display Token" }
        return displayToken
    }

    private fun request(url: String, authorization: String?, method: String = "GET", body: String? = null): String {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (authorization != null) setRequestProperty("Authorization", authorization)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.use { it.readLimited(4_096) }
                throw IllegalStateException("HTTP $status")
            }
            return connection.inputStream.use { it.readLimited(1_048_576) }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBytes(url: String, authorization: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "image/webp")
            setRequestProperty("Authorization", authorization)
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Avatar HTTP ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw IllegalStateException("Avatar is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    private fun requireSecureUrl(baseUrl: String) {
        val url = URL(baseUrl)
        require(url.protocol == "https") { "HTTPS is required" }
        require(url.host.isNotBlank()) { "Server host is required" }
    }

    private fun InputStream.readLimited(limit: Int): String {
        val output = StringBuilder()
        bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4_096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (output.length + count > limit) throw IllegalStateException("Response is too large")
                output.append(buffer, 0, count)
            }
        }
        return output.toString()
    }
}

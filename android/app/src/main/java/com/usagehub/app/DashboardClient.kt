package com.usagehub.app

import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class FetchResult(
    val snapshot: DashboardSnapshot,
    val healthOk: Boolean,
)

class DashboardClient {
    fun fetch(baseUrl: String, key: String, nowMillis: Long): FetchResult {
        requireSecureUrl(baseUrl)
        val dashboardRaw = request(
            url = "${baseUrl.trimEnd('/')}/v1/dashboard",
            authorization = "Bearer $key",
        )
        val snapshot = DashboardJson.parse(dashboardRaw, nowMillis)
        val healthOk = runCatching {
            request("${baseUrl.trimEnd('/')}/v1/health", authorization = null)
            true
        }.getOrDefault(false)
        return FetchResult(snapshot, healthOk)
    }

    fun health(baseUrl: String): Boolean {
        requireSecureUrl(baseUrl)
        return runCatching {
            request("${baseUrl.trimEnd('/')}/v1/health", authorization = null)
            true
        }.getOrDefault(false)
    }

    private fun request(url: String, authorization: String?): String {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (authorization != null) setRequestProperty("Authorization", authorization)
        }
        try {
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

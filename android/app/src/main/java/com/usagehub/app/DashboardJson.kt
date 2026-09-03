package com.usagehub.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object DashboardJson {
    fun parse(raw: String, fetchedAtMillis: Long): DashboardSnapshot {
        val root = JSONObject(raw)
        val serverTime = parseInstant(root.optString("serverTime")) ?: fetchedAtMillis
        val providersJson = root.optJSONArray("providers") ?: JSONArray()
        val providers = buildList {
            for (index in 0 until providersJson.length()) {
                val item = providersJson.optJSONObject(index) ?: continue
                val provider = item.optString("provider").trim().lowercase()
                if (provider.isBlank()) continue
                val windowsJson = item.optJSONArray("windows") ?: JSONArray()
                val windows = buildList {
                    for (windowIndex in 0 until windowsJson.length()) {
                        val window = windowsJson.optJSONObject(windowIndex) ?: continue
                        val usedPercent = nullableDouble(window, "usedPercent")
                        add(
                            UsageWindow(
                                limitId = window.optString("limitId").trim(),
                                kind = window.optString("kind", "other").trim(),
                                durationMinutes = nullableInt(window, "durationMinutes"),
                                usedPercent = usedPercent,
                                resetsAtEpochSeconds = nullableLong(window, "resetsAt"),
                            ),
                        )
                    }
                }
                val observedAt = parseInstant(item.optString("observedAt"))
                val tokenUsage = parseTokenUsage(item.optJSONObject("tokenUsage"))
                val incomplete = observedAt == null || windows.isEmpty() || windows.any { window ->
                    window.limitId.isBlank() ||
                        window.durationMinutes == null ||
                        window.durationMinutes <= 0 ||
                        window.usedPercent == null ||
                        window.resetsAtEpochSeconds == null
                }
                add(
                    ProviderUsage(
                        provider = provider,
                        status = item.optString("status", "missing"),
                        source = item.optString("source", "unknown"),
                        observedAtMillis = observedAt,
                        receivedAtMillis = parseInstant(item.optString("receivedAt")),
                        upstreamStale = item.optBoolean("stale", false) || incomplete,
                        windows = windows,
                        tokenUsage = tokenUsage,
                    ),
                )
            }
        }
        return DashboardSnapshot(
            serverTimeMillis = serverTime,
            fetchedAtMillis = fetchedAtMillis,
            providers = providers,
        )
    }

    fun encode(snapshot: DashboardSnapshot): String {
        val root = JSONObject()
        root.put("serverTime", Instant.ofEpochMilli(snapshot.serverTimeMillis).toString())
        root.put("fetchedAt", snapshot.fetchedAtMillis)
        root.put(
            "providers",
            JSONArray().apply {
                snapshot.providers.forEach { provider ->
                    put(
                        JSONObject().apply {
                            put("provider", provider.provider)
                            put("status", provider.status)
                            put("source", provider.source)
                            put("observedAt", instantOrNull(provider.observedAtMillis))
                            put("receivedAt", instantOrNull(provider.receivedAtMillis))
                            put("stale", provider.upstreamStale)
                            put("tokenUsage", encodeTokenUsage(provider.tokenUsage))
                            put(
                                "windows",
                                JSONArray().apply {
                                    provider.windows.forEach { window ->
                                        put(
                                            JSONObject().apply {
                                                put("limitId", window.limitId)
                                                put("kind", window.kind)
                                                put("durationMinutes", window.durationMinutes ?: JSONObject.NULL)
                                                put("usedPercent", window.usedPercent ?: JSONObject.NULL)
                                                put("resetsAt", window.resetsAtEpochSeconds ?: JSONObject.NULL)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        return root.toString()
    }

    fun parseCache(raw: String): DashboardSnapshot {
        val root = JSONObject(raw)
        val fetchedAt = nullableLong(root, "fetchedAt") ?: 0L
        return parse(raw, fetchedAt)
    }

    private fun parseInstant(raw: String?): Long? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private fun nullableDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        return runCatching { json.getDouble(key) }.getOrNull()?.takeIf { it.isFinite() }
    }

    private fun nullableInt(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        return runCatching { json.getInt(key) }.getOrNull()
    }

    private fun nullableLong(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        return runCatching { json.getLong(key) }.getOrNull()
    }

    private fun parseTokenUsage(json: JSONObject?): TokenUsage {
        if (json == null) return TokenUsage.missing()
        val status = json.optString("status", "missing")
        val source = json.optString("source").takeIf { it == "ccusage" || it == "ccusage_codex" }
        return TokenUsage(
            status = status,
            todayTokens = nonNegativeLong(json, "todayTokens"),
            totalTokens = nonNegativeLong(json, "totalTokens"),
            todayCostUsd = nonNegativeDouble(json, "todayCostUsd"),
            totalCostUsd = nonNegativeDouble(json, "totalCostUsd"),
            costLabel = json.optString("costLabel", "标准 API 等价费用"),
            source = source,
            observedAtMillis = parseInstant(json.optString("observedAt")),
            stale = json.optBoolean("stale", status != "ok"),
        )
    }

    private fun encodeTokenUsage(tokenUsage: TokenUsage): JSONObject = JSONObject().apply {
        put("status", tokenUsage.status)
        put("todayTokens", tokenUsage.todayTokens ?: JSONObject.NULL)
        put("totalTokens", tokenUsage.totalTokens ?: JSONObject.NULL)
        put("todayCostUsd", tokenUsage.todayCostUsd ?: JSONObject.NULL)
        put("totalCostUsd", tokenUsage.totalCostUsd ?: JSONObject.NULL)
        put("costLabel", tokenUsage.costLabel)
        put("source", tokenUsage.source ?: JSONObject.NULL)
        put("observedAt", instantOrNull(tokenUsage.observedAtMillis))
        put("stale", tokenUsage.stale)
    }

    private fun nonNegativeLong(json: JSONObject, key: String): Long? =
        nullableLong(json, key)?.takeIf { it >= 0L }

    private fun nonNegativeDouble(json: JSONObject, key: String): Double? =
        nullableDouble(json, key)?.takeIf { it >= 0.0 }

    private fun instantOrNull(millis: Long?): Any =
        millis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL
}

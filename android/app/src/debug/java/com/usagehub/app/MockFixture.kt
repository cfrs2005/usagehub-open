package com.usagehub.app

import android.content.Context
import com.usagehub.app.data.DashboardJson
import com.usagehub.app.data.FetchResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Debug-only fixture (`adb shell am start ... --ez mock true --es tab usage`). Same numbers as the web console's
 * localhost mock so both screens can be compared side by side. Served as JSON so the real parsers run on it.
 */
object MockFixture {
    private const val DAY = 86_400L

    @Suppress("UNUSED_PARAMETER")
    fun source(context: Context): Source = object : Source {
        override val persistent = false
        override fun dashboard(nowMillis: Long): FetchResult =
            FetchResult(DashboardJson.parse(dashboardJson(nowMillis / 1000), nowMillis), true, "Toy张", null)
        override fun get(pathAndQuery: String): String = route(pathAndQuery, System.currentTimeMillis() / 1000)
    }

    private fun route(pathAndQuery: String, now: Long): String {
        val query = pathAndQuery.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val from = query["from"]?.toLongOrNull() ?: (now - 6 * DAY)
        val to = query["to"]?.toLongOrNull() ?: now
        return when {
            pathAndQuery.startsWith("/v1/history") -> history(query["provider"] ?: "claude", now)
            to < now - 120 -> analytics(7, now, to)
            else -> analytics(((to - from) / DAY + 1).toInt(), now, null)
        }
    }

    private fun window(id: String, kind: String, minutes: Int, used: Double?, resets: Long?) = JSONObject()
        .put("limitId", id).put("kind", kind).put("durationMinutes", minutes)
        .put("usedPercent", used ?: JSONObject.NULL).put("resetsAt", resets ?: JSONObject.NULL)

    private fun dashboardJson(now: Long): String {
        val iso = Instant.ofEpochSecond(now).toString()
        // Claude's quota sample is two days old and flagged stale, so screenshots show the expired styling.
        val twoDaysAgo = Instant.ofEpochSecond(now - 2 * DAY).toString()
        fun provider(name: String, source: String, windows: JSONArray, today: Long, total: Long, todayCost: Double, totalCost: Double) = JSONObject()
            .put("provider", name).put("status", "ok").put("source", source)
            .put("observedAt", if (name == "claude") twoDaysAgo else iso).put("receivedAt", if (name == "claude") twoDaysAgo else iso)
            .put("stale", name == "claude").put("windows", windows)
            .put(
                "tokenUsage",
                JSONObject().put("status", "ok").put("todayTokens", today).put("totalTokens", total)
                    .put("todayCostUsd", todayCost).put("totalCostUsd", totalCost).put("costLabel", "标准 API 等价费用")
                    .put("source", if (name == "codex") "ccusage_codex" else "ccusage").put("observedAt", iso).put("stale", false),
            )
        val claude = JSONArray()
            .put(window("five_hour", "short", 300, 28.0, now + 3 * 3600))
            .put(window("seven_day", "weekly", 10_080, 24.0, now + 2 * DAY + 14 * 3600 + 120))
        val codex = JSONArray()
            .put(window("codex:primary", "short", 300, null, null))
            .put(window("codex:secondary", "weekly", 10_080, 24.0, now + 6 * DAY + 3 * 3600 + 120))
        return JSONObject().put("serverTime", iso).put(
            "providers",
            JSONArray()
                .put(provider("claude", "official_statusline", claude, 0, 17_340_000_000, 0.0, 14_396.95))
                .put(provider("codex", "official_app_server", codex, 3_915_165, 26_610_000_000, 1.41, 16_718.04)),
        ).toString()
    }

    private val models = mapOf(
        "claude" to listOf("claude-3.5-sonnet" to 9_800_000_000.0, "claude-3.5-haiku" to 5_300_000_000.0, "claude-3-opus" to 1_700_000_000.0, "claude-3-sonnet" to 540_000_000.0),
        "codex" to listOf("gpt-4o-codex" to 18_200_000_000.0, "gpt-4.1" to 7_400_000_000.0, "o3-mini" to 760_000_000.0, "o1-preview" to 250_000_000.0),
    )
    private val modelCost = mapOf(
        "claude" to listOf(8_100.2, 5_400.3, 800.0, 96.45),
        "codex" to listOf(11_200.0, 5_300.0, 160.0, 58.04),
    )

    /** [baselineTo] set = the snapshot at a window boundary (older cumulative model totals). */
    private fun analytics(days: Int, now: Long, baselineTo: Long?): String {
        val to = baselineTo ?: now
        val from = to - (days - 1) * DAY
        fun series(seed: Int, scale: Double) = JSONArray().apply {
            for (index in 0 until days) {
                val wave = abs(sin((index + seed) * 1.7)) * 0.62 + abs(cos((index + seed) * 0.6)) * 0.25 + 0.18
                val value = wave * scale
                put(JSONObject().put("start", from + index * DAY).put("tokens", value.roundToLong()).put("costUsd", (value / 3_600_000).roundToLong()).put("usedPercent", JSONObject.NULL))
            }
        }
        val old = baselineTo != null && to < now - 2 * DAY
        fun shrink(name: String): List<Double> = when {
            baselineTo == null -> emptyList()
            old -> listOf(0.72, 0.86, 0.94, 0.97)
            name == "codex" -> listOf(1 - 3_915_165 / 18_200_000_000.0)
            else -> emptyList()
        }
        fun modelArray(name: String) = JSONArray().apply {
            models.getValue(name).forEachIndexed { index, (model, tokens) ->
                put(JSONObject().put("model", model).put("tokens", (tokens * (shrink(name).getOrNull(index) ?: 1.0)).roundToLong()).put("costUsd", modelCost.getValue(name)[index]))
            }
        }
        fun provider(name: String, seed: Int, scale: Double, total: Long, cost: Double) = JSONObject()
            .put("provider", name).put("points", series(seed, scale)).put("models", modelArray(name))
            .put("latest", JSONObject().put("totalTokens", total).put("totalCostUsd", cost).put("observedAt", to))
        return JSONObject().put("from", from).put("to", to).put("bucket", "day").put(
            "providers",
            JSONArray()
                .put(provider("claude", 2, 4_600_000_000.0, 17_340_000_000, 14_396.95))
                .put(provider("codex", 5, 4_900_000_000.0, 26_610_000_000, 16_718.04)),
        ).toString()
    }

    private fun history(name: String, now: Long): String {
        val today = if (name == "claude") 0L else 3_915_165L
        val total = if (name == "claude") 17_340_000_000L else 26_610_000_000L
        val cost = if (name == "claude") 14_396.95 else 16_718.04
        val items = JSONArray()
        for (index in 0 until 12) {
            val back = index * 11 * 60 + (index % 3) * 97
            val t = (today - index * 312_401L).coerceAtLeast(0)
            items.put(
                JSONObject().put("provider", name).put("observedAt", now - back).put("todayTokens", t)
                    .put("totalTokens", total - index * 312_401L).put("todayCostUsd", Math.round(t / 2_780_000.0 * 100) / 100.0)
                    .put("totalCostUsd", cost - index * 0.11),
            )
        }
        return JSONObject().put("items", JSONArray()).put("nextCursor", JSONObject.NULL).put("tokenItems", items).toString()
    }
}

package com.usagehub.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.usagehub.app.data.DashboardJson
import com.usagehub.app.data.FetchResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Debug-only fixture (`adb shell am start ... --ez mock true --es tab models`). It is a small in-memory copy of the
 * cloud: a list of collector uploads shaped like the real ones (v0.1.8 field data, 10^8–10^10 tokens), and
 * `/v1/analytics` + `/v1/history` answered with the same rules as usagehub-cloud `server.ts`. The upload pattern is the
 * sparse one seen on real devices:
 * - uploads every 30 min while the Mac is awake, nothing 02:00–09:00;
 * - per-model totals only exist in uploads from 18 h before today's midnight on (the collector learnt model_usage then);
 * - no upload from 2 h before midnight until a few hours into today (laptop asleep).
 * So no snapshot sits within 12 h of the 7-day boundary or within 1 h of midnight — the condition that left every model
 * cell at — / 未知 in v0.1.8. Codex today is unpriced ($0 with tokens), as the offline price table produced.
 */
object MockFixture {
    private const val DAY = 86_400L
    private const val SLOT = 1_800L
    private val collectorZone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun source(context: Context): Source = object : Source {
        override val persistent = false
        private val avatar = avatarPng(context)
        override fun dashboard(nowMillis: Long): FetchResult =
            FetchResult(DashboardJson.parse(dashboardJson(nowMillis / 1000), nowMillis), true, "Toy张", avatar)
        override fun get(pathAndQuery: String): String = route(pathAndQuery, System.currentTimeMillis() / 1000)
    }

    private class Spec(val name: String, val baseTotal: Long, val perSlot: Double, val usdPerToken: Double, val models: List<Pair<String, Double>>)

    private val specs = listOf(
        Spec(
            "claude", 15_400_000_000L, 9_400_000.0, 0.585e-6,
            listOf("claude-opus-5-5" to 0.46, "claude-fable-5-1" to 0.22, "claude-opus-5" to 0.14, "claude-opus-4-8" to 0.08,
                "claude-sonnet-5" to 0.05, "claude-fable-5" to 0.03, "claude-haiku-4-5-20251001" to 0.02),
        ),
        Spec(
            "codex", 360_000_000L, 1_650_000.0, 0.9e-6,
            listOf("gpt-6-astra" to 0.55, "gpt-6.1-sol" to 0.35, "gpt-5.6-sol" to 0.08, "gpt-6-luna" to 0.02),
        ),
    )

    private class Upload(
        val at: Long, val today: Long, val total: Long, val todayCost: Double, val totalCost: Double,
        val models: List<Pair<String, Long>>,
    )

    private class Store(val uploads: List<Upload>, val daily: Map<LocalDate, Pair<Long, Double>>)

    /** Deterministic uploads for one vendor, oldest first. */
    private fun store(spec: Spec, now: Long): Store {
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
        val modelEra = midnight - 18 * 3600
        val wakeUp = midnight + minOf(7 * 3600L, (now - midnight) / 2)
        fun awake(t: Long): Boolean {
            if (t >= midnight - 2 * 3600 && t < wakeUp) return false
            val hour = Instant.ofEpochSecond(t).atZone(zone).hour
            return t >= midnight || hour !in 2..8
        }
        val daily = HashMap<LocalDate, Pair<Long, Double>>()
        val start = midnight - 8 * DAY
        // History before the generated week: one value per day so the 30/90-day charts have data.
        for (back in 1..90) {
            val day = Instant.ofEpochSecond(start).atZone(collectorZone).toLocalDate().minusDays(back.toLong())
            val tokens = (spec.perSlot * 30 * (0.35 + abs(sin(back * 0.9)) * 0.9)).roundToLong()
            daily[day] = tokens to tokens * spec.usdPerToken
        }
        val cumulative = DoubleArray(spec.models.size) { spec.baseTotal * spec.models[it].second }
        var total = spec.baseTotal
        var totalCost = spec.baseTotal * spec.usdPerToken
        val uploads = ArrayList<Upload>()
        var t = start
        while (t <= now) {
            val stamp = if (t + SLOT > now) now - 60 else t
            if (awake(stamp)) {
                val burst = (0.25 + abs(sin(stamp / 7_200.0)) * 1.5) * spec.perSlot
                val tokens = burst.roundToLong()
                spec.models.indices.forEach { cumulative[it] += tokens * spec.models[it].second }
                total += tokens
                val date = Instant.ofEpochSecond(stamp).atZone(collectorZone).toLocalDate()
                val unpriced = spec.name == "codex" && stamp >= midnight
                val cost = if (unpriced) 0.0 else tokens * spec.usdPerToken
                totalCost += cost
                val day = daily[date] ?: (0L to 0.0)
                daily[date] = (day.first + tokens) to (day.second + cost)
                val models = if (stamp >= modelEra) spec.models.indices.map { spec.models[it].first to cumulative[it].roundToLong() } else emptyList()
                uploads.add(Upload(stamp, daily.getValue(date).first, total, daily.getValue(date).second, totalCost, models))
            }
            t += SLOT
        }
        return Store(uploads, daily)
    }

    private fun route(pathAndQuery: String, now: Long): String {
        val query = pathAndQuery.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val from = query["from"]?.toLongOrNull() ?: (now - 6 * DAY)
        val to = query["to"]?.toLongOrNull() ?: now
        return if (pathAndQuery.startsWith("/v1/history")) {
            val spec = specs.first { it.name == (query["provider"] ?: "claude") }
            history(spec, from, to, query["limit"]?.toIntOrNull() ?: 50, now)
        } else {
            analytics(from, to, now)
        }
    }

    /** Uploads inside [from, to], newest first, at most [limit] (postgres.ts `tokenHistory`). */
    private fun window(store: Store, from: Long, to: Long, limit: Int) =
        store.uploads.filter { it.at in from..to }.sortedByDescending { it.at }.take(limit)

    private fun models(models: List<Pair<String, Long>>) = JSONArray().apply {
        models.forEach { (model, tokens) -> put(JSONObject().put("model", model).put("tokens", tokens).put("costUsd", 0)) }
    }

    private fun history(spec: Spec, from: Long, to: Long, limit: Int, now: Long): String {
        val items = JSONArray()
        window(store(spec, now), from, to, limit.coerceIn(1, 100)).forEach { upload ->
            items.put(
                JSONObject().put("provider", spec.name).put("observedAt", upload.at).put("todayTokens", upload.today)
                    .put("totalTokens", upload.total).put("todayCostUsd", round(upload.todayCost)).put("totalCostUsd", round(upload.totalCost))
                    .put("modelUsage", models(upload.models)),
            )
        }
        return JSONObject().put("items", JSONArray()).put("nextCursor", JSONObject.NULL).put("tokenItems", items).toString()
    }

    /** server.ts `/v1/analytics`: daily points keyed at the date's 00:00 UTC; models from the newest upload with models. */
    private fun analytics(from: Long, to: Long, now: Long): String {
        val providers = JSONArray()
        specs.forEach { spec ->
            val store = store(spec, now)
            val uploads = window(store, from, to, 100)
            val points = sortedMapOf<Long, JSONObject>()
            if (uploads.isNotEmpty()) {
                store.daily.forEach { (date, value) ->
                    val at = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                    if (at < from || at > to || at > now) return@forEach
                    val key = (at - from) / DAY * DAY + from
                    points[key] = JSONObject().put("start", key).put("tokenSnapshots", 1).put("quotaSnapshots", 0)
                        .put("tokens", value.first).put("costUsd", round(value.second)).put("usedPercent", JSONObject.NULL)
                }
            }
            val newest = uploads.firstOrNull()
            providers.put(
                JSONObject().put("provider", spec.name).put("points", JSONArray(points.values.toList()))
                    .put("models", models(uploads.firstOrNull { it.models.isNotEmpty() }?.models.orEmpty().sortedByDescending { it.second }))
                    .put("latest", newest?.let { JSONObject().put("totalTokens", it.total).put("totalCostUsd", round(it.totalCost)).put("observedAt", it.at) } ?: JSONObject.NULL),
            )
        }
        return JSONObject().put("from", from).put("to", to).put("bucket", "day").put("providers", providers).toString()
    }

    private fun windowJson(id: String, kind: String, minutes: Int, used: Double?, resets: Long?) = JSONObject()
        .put("limitId", id).put("kind", kind).put("durationMinutes", minutes)
        .put("usedPercent", used ?: JSONObject.NULL).put("resetsAt", resets ?: JSONObject.NULL)

    private fun dashboardJson(now: Long): String {
        val iso = Instant.ofEpochSecond(now).toString()
        val providers = JSONArray()
        specs.forEach { spec ->
            val latest = store(spec, now).uploads.last()
            val at = Instant.ofEpochSecond(latest.at).toString()
            // Real Codex app-server answers carry only the weekly bucket: no 5-hour window at all.
            val windows = if (spec.name == "claude") {
                JSONArray().put(windowJson("five_hour", "short", 300, 71.0, now + 3 * 3600))
                    .put(windowJson("seven_day", "weekly", 10_080, 51.0, now + 2 * DAY + 120))
            } else {
                JSONArray().put(windowJson("codex:secondary", "weekly", 10_080, 33.0, now + 5 * DAY + 12 * 3600))
            }
            providers.put(
                JSONObject().put("provider", spec.name).put("status", "ok")
                    .put("source", if (spec.name == "claude") "official_statusline" else "official_app_server")
                    .put("observedAt", at).put("receivedAt", at).put("stale", false).put("windows", windows)
                    .put(
                        "tokenUsage",
                        JSONObject().put("status", "ok").put("todayTokens", latest.today).put("totalTokens", latest.total)
                            .put("todayCostUsd", round(latest.todayCost)).put("totalCostUsd", round(latest.totalCost))
                            .put("costLabel", "标准 API 等价费用").put("source", if (spec.name == "codex") "ccusage_codex" else "ccusage")
                            .put("observedAt", at).put("stale", false),
                    ),
            )
        }
        return JSONObject().put("serverTime", iso).put("providers", providers).toString()
    }

    private fun round(value: Double) = Math.round(value * 100) / 100.0

    /** A real photo-like avatar (a crop of the bundled hero art) so the avatar drawing path is exercised, not the initial. */
    private fun avatarPng(context: Context): ByteArray? = runCatching {
        val art = BitmapFactory.decodeResource(context.resources, R.drawable.console_hero) ?: return null
        val side = minOf(art.width, art.height) / 2
        val crop = Bitmap.createBitmap(art, art.width - side - art.width / 8, art.height / 10, side, side)
        ByteArrayOutputStream().use { out ->
            Bitmap.createScaledBitmap(crop, 256, 256, true).compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()
}

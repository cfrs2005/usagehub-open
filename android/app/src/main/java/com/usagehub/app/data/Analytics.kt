package com.usagehub.app.data

import org.json.JSONArray
import org.json.JSONObject

/** One bucket of `GET /v1/analytics` (start is epoch seconds). */
data class AnalyticsPoint(val start: Long, val tokens: Double, val costUsd: Double, val usedPercent: Double?)

data class ModelUsage(val model: String, val tokens: Double, val costUsd: Double)

data class LatestTotals(val totalTokens: Double, val totalCostUsd: Double, val observedAt: Long)

data class AnalyticsProvider(
    val provider: String,
    val points: List<AnalyticsPoint>,
    val models: List<ModelUsage>,
    val latest: LatestTotals?,
)

data class AnalyticsData(val from: Long, val to: Long, val bucket: String, val providers: List<AnalyticsProvider>) {
    fun provider(name: String): AnalyticsProvider? = providers.firstOrNull { it.provider == name }
}

/** One collector upload from `GET /v1/history` (`tokenItems`). */
data class TokenItem(
    val provider: String,
    val observedAt: Long,
    val todayTokens: Double,
    val totalTokens: Double,
    val todayCostUsd: Double,
    val totalCostUsd: Double,
    /** Cumulative per-model totals carried by this upload (empty for uploads made before model_usage existed). */
    val models: List<ModelUsage> = emptyList(),
)

data class HistoryData(val tokenItems: List<TokenItem>)

/** A row of the models table. `null` means "not knowable from the data we have" and is drawn as —. */
data class ModelRow(val model: String, val today: Double?, val week: Double?, val share: Double?)

/**
 * Cumulative per-model totals at one upload time (epoch seconds). [full] = the models add up to the provider total.
 * Collectors before v0.1.9 counted only input+output per Claude model (~1% of the total); such a snapshot must never be
 * subtracted from a full one, or the delta would be the whole history.
 */
data class ModelSnap(val at: Long, val models: Map<String, Double>, val full: Boolean = true)

/**
 * The snapshot a window's delta is measured from. [exact] = it sits at the window boundary (within tolerance), so the
 * delta covers the whole window; otherwise the delta only covers [snap].at..now and the page says so.
 */
data class WindowStart(val snap: ModelSnap, val exact: Boolean)

/** One vendor's models table plus where its two delta columns actually start. */
data class ModelTable(val rows: List<ModelRow>, val today: WindowStart?, val week: WindowStart?)

object AnalyticsJson {
    fun parse(raw: String): AnalyticsData {
        val root = JSONObject(raw)
        val providers = root.optJSONArray("providers") ?: JSONArray()
        return AnalyticsData(
            from = root.optLong("from"),
            to = root.optLong("to"),
            bucket = root.optString("bucket", "day"),
            providers = buildList {
                for (index in 0 until providers.length()) {
                    val item = providers.optJSONObject(index) ?: continue
                    val name = item.optString("provider").trim().lowercase()
                    if (name.isBlank()) continue
                    add(
                        AnalyticsProvider(
                            provider = name,
                            points = objects(item.optJSONArray("points")).map { point ->
                                AnalyticsPoint(
                                    start = point.optLong("start"),
                                    tokens = number(point, "tokens"),
                                    costUsd = number(point, "costUsd"),
                                    usedPercent = nullable(point, "usedPercent"),
                                )
                            }.sortedBy { it.start },
                            models = objects(item.optJSONArray("models")).mapNotNull { model ->
                                val id = model.optString("model").trim()
                                if (id.isBlank()) null else ModelUsage(id, number(model, "tokens"), number(model, "costUsd"))
                            },
                            latest = item.optJSONObject("latest")?.let {
                                LatestTotals(number(it, "totalTokens"), number(it, "totalCostUsd"), it.optLong("observedAt"))
                            },
                        ),
                    )
                }
            },
        )
    }

    fun parseHistory(raw: String): HistoryData {
        val root = JSONObject(raw)
        return HistoryData(
            objects(root.optJSONArray("tokenItems")).map { item ->
                TokenItem(
                    provider = item.optString("provider").trim().lowercase(),
                    observedAt = item.optLong("observedAt"),
                    todayTokens = number(item, "todayTokens"),
                    totalTokens = number(item, "totalTokens"),
                    todayCostUsd = number(item, "todayCostUsd"),
                    totalCostUsd = number(item, "totalCostUsd"),
                    models = objects(item.optJSONArray("modelUsage")).mapNotNull { model ->
                        val id = model.optString("model").trim()
                        if (id.isBlank()) null else ModelUsage(id, number(model, "tokens"), number(model, "costUsd"))
                    },
                )
            }.sortedByDescending { it.observedAt },
        )
    }

    private fun objects(array: JSONArray?): List<JSONObject> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun nullable(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        return runCatching { json.getDouble(key) }.getOrNull()?.takeIf { it.isFinite() }
    }

    /** Negative / non-finite values never make it onto a chart: they are treated as 0. */
    private fun number(json: JSONObject, key: String): Double = nullable(json, key)?.coerceAtLeast(0.0) ?: 0.0
}

object ConsoleRules {
    const val DAY = 86_400L

    /** Ring rule: show the 5h window; when it is empty fall back to the 7d window and label it that way. */
    fun ringWindow(provider: ProviderUsage?): Pair<Double?, String> {
        val five = UsageLogic.quotaWindow(provider, 300)?.usedPercent
        if (five != null) return five to "5小时用量"
        val week = UsageLogic.quotaWindow(provider, 10_080)?.usedPercent
        if (week != null) return week to "7天用量"
        return null to "5小时用量"
    }

    enum class Status { OK, STALE, MISSING }

    /**
     * Freshness is judged on the device from `observedAt`, not from the server's `stale` flag: the server flags quota
     * stale after 30 minutes, which is exactly the collector's default upload interval and would make the screen
     * flicker. The quota threshold is a device setting; tokens use a fixed 6 hours.
     */
    val QUOTA_STALE_OPTIONS = listOf(60, 90, 120, 360, 1_440)
    const val DEFAULT_QUOTA_STALE_MINUTES = 90
    const val TOKEN_STALE_MILLIS = 6L * 60L * 60L * 1000L

    fun status(provider: ProviderUsage?, nowMillis: Long, quotaStaleMillis: Long): Status {
        val observed = provider?.observedAtMillis ?: return Status.MISSING
        return if (nowMillis - observed >= quotaStaleMillis) Status.STALE else Status.OK
    }

    /**
     * Quota and token sample times. [shownAt] is the quota time, or the token time when the token sample is past its
     * threshold and older (a stuck token collector must not hide behind a fresh quota). [old] = draw in amber.
     */
    data class Updated(val quotaAt: Long?, val tokenAt: Long?, val shownAt: Long?, val quotaOld: Boolean, val tokenOld: Boolean) {
        val old: Boolean get() = shownAt != null && (quotaOld || tokenOld)
    }

    fun updated(provider: ProviderUsage?, nowMillis: Long, quotaStaleMillis: Long, tokenStaleMillis: Long = TOKEN_STALE_MILLIS): Updated {
        val quota = provider?.observedAtMillis
        val tokens = provider?.tokenUsage?.observedAtMillis
        val quotaOld = quota == null || nowMillis - quota >= quotaStaleMillis
        val tokenOld = tokens != null && nowMillis - tokens >= tokenStaleMillis
        val shown = when {
            quota == null -> tokens
            tokens != null && tokenOld && tokens < quota -> tokens
            else -> quota
        }
        return Updated(quota, tokens, shown, quotaOld, tokenOld)
    }

    /**
     * Per-model totals are cumulative, so a window's usage is "newest snapshot minus the snapshot the window starts at".
     * [before] is the newest snapshot at or before the boundary (analytics with `to = boundary`); it is used when it sits
     * within [toleranceSeconds] of the boundary. Otherwise the earliest upload inside the window ([history], any order)
     * is used and marked inexact, so the page can say "since HH:mm" instead of pretending to cover the whole window.
     * A start that is the newest upload itself measures nothing: null (—), never 0.
     */
    fun windowStart(
        boundary: Long,
        before: ModelSnap?,
        history: List<TokenItem>,
        latestAt: Long?,
        toleranceSeconds: Long,
        full: Boolean = true,
    ): WindowStart? {
        if (before != null && before.models.isNotEmpty() && before.full == full && before.at <= boundary && boundary - before.at <= toleranceSeconds) {
            return WindowStart(before, true)
        }
        val first = history
            .filter { it.observedAt >= boundary && it.models.isNotEmpty() && complete(it.models, it.totalTokens) == full }
            .minByOrNull { it.observedAt } ?: return null
        if (latestAt != null && first.observedAt >= latestAt) return null
        return WindowStart(ModelSnap(first.observedAt, first.models.associate { it.model to it.tokens }, full), false)
    }

    /** True when per-model totals account for (≥90% of) the provider total; see [ModelSnap.full]. */
    fun complete(models: List<ModelUsage>, totalTokens: Double): Boolean =
        totalTokens <= 0.0 || models.sumOf { it.tokens } >= totalTokens * 0.9

    /** Newest snapshot with models at or before an analytics range's `to`, as the server reports it. */
    fun snapAt(data: AnalyticsData?, provider: String): ModelSnap? {
        val item = data?.provider(provider) ?: return null
        val latest = item.latest ?: return null
        if (item.models.isEmpty()) return null
        return ModelSnap(latest.observedAt, item.models.associate { it.model to it.tokens }, complete(item.models, latest.totalTokens))
    }

    fun modelRows(
        current: List<ModelUsage>,
        weekBase: Map<String, Double>?,
        todayBase: Map<String, Double>?,
        query: String = "",
    ): List<ModelRow> {
        val deltas = current.map { model ->
            Triple(
                model.model,
                todayBase?.let { (model.tokens - (it[model.model] ?: 0.0)).coerceAtLeast(0.0) },
                weekBase?.let { (model.tokens - (it[model.model] ?: 0.0)).coerceAtLeast(0.0) },
            )
        }
        val weekTotal = deltas.sumOf { it.third ?: 0.0 }
        val cumulative = current.associate { it.model to it.tokens }
        return deltas
            .filter { query.isBlank() || it.first.contains(query.trim(), ignoreCase = true) }
            .map { (model, today, week) ->
                ModelRow(model, today, week, week?.let { if (weekTotal > 0) it / weekTotal * 100.0 else 0.0 })
            }
            .sortedWith(byUsage(cumulative))
    }

    /**
     * Heaviest first, so the models page truncates the long tail rather than the models actually in use:
     * 7-day delta ↓, then today ↓, then cumulative tokens ↓, then name ↑. Unknown (null) sorts as the smallest.
     */
    private fun byUsage(cumulative: Map<String, Double>): Comparator<ModelRow> =
        compareByDescending<ModelRow> { it.week ?: Double.NEGATIVE_INFINITY }
            .thenByDescending { it.today ?: Double.NEGATIVE_INFINITY }
            .thenByDescending { cumulative[it.model] ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.model }

    fun modelTable(current: List<ModelUsage>, week: WindowStart?, today: WindowStart?, query: String = ""): ModelTable =
        ModelTable(modelRows(current, week?.snap?.models, today?.snap?.models, query), today, week)

    /**
     * Honest coverage note for a models table: `今日自 08:12 · 7天自 10/24 15:00` for inexact starts, `今日无快照` when a
     * column cannot be computed, nothing when both columns cover their full window.
     */
    fun coverageNote(table: ModelTable, zone: java.time.ZoneId): String? {
        fun part(label: String, start: WindowStart?, pattern: String): String? = when {
            start == null -> "${label}无可比快照"
            start.exact -> null
            else -> label + "自 " + java.time.Instant.ofEpochSecond(start.snap.at).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern(pattern))
        }
        val parts = listOfNotNull(part("今日", table.today, "HH:mm"), part("7天", table.week, "MM/dd HH:mm"))
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * Daily analytics points are keyed by the collector's calendar date at 00:00 UTC. Asking for a range that starts on
     * a UTC midnight and ends at the last day's 23:59:59 UTC gives every date its own bucket, today included; a range
     * starting at "now − 6 days" splits dates across buckets and drops today's point.
     */
    fun dayRange(days: Int, nowMillis: Long, zone: java.time.ZoneId): Pair<Long, Long> {
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val from = today.minusDays((days - 1).toLong()).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
        return from to from + days * DAY - 1
    }

    /** Day slots `from + k·DAY` for k in 0 until days, each with its point if the server sent one. */
    fun daySlots(data: AnalyticsData?, provider: String, days: Int, nowSeconds: Long): List<Pair<Long, AnalyticsPoint?>> {
        val from = data?.from ?: (nowSeconds - (days - 1) * DAY)
        val points = data?.provider(provider)?.points.orEmpty()
        return (0 until days).map { index ->
            val start = from + index * DAY
            start to points.firstOrNull { it.start in start until start + DAY }
        }
    }
}

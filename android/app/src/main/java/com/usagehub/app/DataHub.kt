package com.usagehub.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import com.usagehub.app.data.AnalyticsData
import com.usagehub.app.data.AnalyticsJson
import com.usagehub.app.data.ConsoleRules
import com.usagehub.app.data.DashboardClient
import com.usagehub.app.data.FetchResult
import com.usagehub.app.data.DashboardSnapshot
import com.usagehub.app.data.HistoryData
import com.usagehub.app.data.ModelRow
import java.util.concurrent.ExecutorService

/** Where requests go: the paired server, or (debug builds only) the fixture. */
interface Source {
    /** Mock data must never land in the on-disk cache the real app reads on its next start. */
    val persistent: Boolean
    fun dashboard(nowMillis: Long): FetchResult
    fun get(pathAndQuery: String): String
}

class ServerSource(private val client: DashboardClient, private val server: String, private val token: String) : Source {
    override val persistent = true
    override fun dashboard(nowMillis: Long): FetchResult = client.fetch(server, token, nowMillis)
    override fun get(pathAndQuery: String): String = client.get(server, token, pathAndQuery)
}

/**
 * Owns every piece of fetched data. Requests run on [executor]; answers are applied on [main]. Each request key keeps
 * a version number and only the newest request for a key may write it, so a slow 7-day answer can never overwrite a
 * newer one. Results are keyed by range, so switching 7/30/90 never shows another range's numbers either.
 */
class DataHub(
    private val storage: AppStorage,
    private val executor: ExecutorService,
    private val main: Handler,
    private val onChange: () -> Unit,
) {
    var dashboard: DashboardSnapshot? = storage.readCache()
        private set
    var lastSuccessMillis: Long? = dashboard?.fetchedAtMillis
        private set
    var offline = false
        private set
    var healthOk = false
        private set
    var statusMessage = "正在启动"
    var displayName: String = storage.readDisplayName()
        private set
    var avatar: Bitmap? = storage.readAvatar()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        private set

    val analytics = HashMap<Int, AnalyticsData>()
    private val histories = HashMap<String, HistoryData>()
    private val baselines = HashMap<String, AnalyticsData>()
    private val versions = HashMap<String, Int>()
    private var counter = 0

    /** Dashboard + profile + health. [done] receives success so the caller can schedule the next refresh. */
    fun refreshDashboard(source: Source, done: (Boolean) -> Unit) {
        val version = bump("dashboard")
        statusMessage = "正在同步"
        executor.execute {
            val result = runCatching { source.dashboard(System.currentTimeMillis()) }
            main.post {
                if (versions["dashboard"] != version) return@post
                result.onSuccess { fetched ->
                    dashboard = fetched.snapshot
                    healthOk = fetched.healthOk
                    displayName = fetched.displayName
                    avatar = fetched.avatar?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (source.persistent) {
                        storage.writeCache(fetched.snapshot)
                        storage.writeDisplayProfile(fetched.displayName, fetched.avatar)
                    }
                    lastSuccessMillis = fetched.snapshot.fetchedAtMillis
                    offline = false
                    statusMessage = if (healthOk) "服务正常" else "数据已更新"
                    done(true)
                }.onFailure { error ->
                    offline = true
                    healthOk = false
                    statusMessage = if (error.message == "HTTPS is required") "仅支持 HTTPS" else "离线，使用上次数据"
                    done(false)
                }
                onChange()
            }
        }
    }

    fun analytics(days: Int, source: Source, now: Long) {
        val to = now / 1000
        load("analytics-$days", "/v1/analytics?from=${to - (days - 1) * ConsoleRules.DAY}&to=$to&bucket=day", source, AnalyticsJson::parse) {
            analytics[days] = it
        }
    }

    fun history(provider: String, days: Int, limit: Int, source: Source, now: Long) {
        val to = now / 1000
        val key = historyKey(provider, days, limit)
        load(key, "/v1/history?provider=$provider&from=${to - (days - 1) * ConsoleRules.DAY}&to=$to&limit=$limit", source, AnalyticsJson::parseHistory) {
            histories[key] = it
        }
    }

    fun history(provider: String, days: Int, limit: Int): HistoryData? = histories[historyKey(provider, days, limit)]

    /** Snapshot ending at [boundary] (epoch seconds) used to turn cumulative model totals into window deltas. */
    fun baseline(kind: String, boundary: Long, source: Source) {
        val key = "baseline-$kind"
        load(key, "/v1/analytics?from=${boundary - 30 * ConsoleRules.DAY}&to=$boundary&bucket=day", source, AnalyticsJson::parse, accept = { it.to == boundary }) {
            baselines[key] = it
        }
    }

    fun modelRows(weekBoundary: Long, dayBoundary: Long, query: String): Map<String, List<ModelRow>> {
        val current = analytics[7] ?: return emptyMap()
        val week = baselines["baseline-week"]?.takeIf { it.to == weekBoundary }
        val day = baselines["baseline-day"]?.takeIf { it.to == dayBoundary }
        return listOf("claude", "codex").associateWith { name ->
            ConsoleRules.modelRows(
                current.provider(name)?.models.orEmpty(),
                ConsoleRules.baseline(week, name, weekBoundary, 12 * 3600L),
                ConsoleRules.baseline(day, name, dayBoundary, 3600L),
                query,
            )
        }
    }

    private fun historyKey(provider: String, days: Int, limit: Int) = "history-$provider-$days-$limit"

    private fun bump(key: String): Int {
        counter += 1
        versions[key] = counter
        return counter
    }

    private fun <T> load(
        key: String,
        path: String,
        source: Source,
        parse: (String) -> T,
        accept: (T) -> Boolean = { true },
        store: (T) -> Unit,
    ) {
        if (source.persistent && !versions.containsKey(key)) {
            storage.readJson(key)?.let { raw -> runCatching { parse(raw) }.getOrNull()?.takeIf(accept)?.let(store) }
        }
        val version = bump(key)
        executor.execute {
            val result = runCatching { source.get(path).let { raw -> raw to parse(raw) } }
            main.post {
                if (versions[key] != version) return@post
                result.onSuccess { (raw, value) ->
                    if (!accept(value)) return@onSuccess
                    store(value)
                    if (source.persistent) storage.writeJson(key, raw)
                    onChange()
                }
            }
        }
    }
}

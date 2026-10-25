package com.usagehub.app.ui

import android.graphics.Bitmap
import android.graphics.RectF
import com.usagehub.app.AppConfig
import com.usagehub.app.data.AnalyticsData
import com.usagehub.app.data.DashboardSnapshot
import com.usagehub.app.data.DeviceDiagnostics
import com.usagehub.app.data.HistoryData
import com.usagehub.app.data.ModelTable

enum class Tab(val label: String, val icon: Icon, val key: String) {
    HOME("首页", Icon.HOME, "home"),
    USAGE("用量", Icon.USAGE, "usage"),
    MODELS("模型", Icon.MODELS, "models"),
    STATS("统计", Icon.STATS, "stats"),
    SETTINGS("设置", Icon.SETTINGS, "settings"),
}

/** Where the user is. Owned by the activity (it drives which data gets requested). */
data class Nav(
    val tab: Tab = Tab.HOME,
    val detail: String? = null,
    val usageDays: Int = 7,
    val statsDays: Int = 7,
    val detailDays: Int = 7,
    val recordsExpanded: Boolean = false,
    val modelFilter: String = "all",
    val modelQuery: String = "",
    val settingsTab: Int = 0,
) {
    val recordLimit: Int get() = if (recordsExpanded) 20 else 4
}

sealed interface Action {
    data class GoTab(val tab: Tab) : Action
    data class OpenDetail(val provider: String) : Action
    object Back : Action
    data class UsageDays(val days: Int) : Action
    data class StatsDays(val days: Int) : Action
    data class DetailDays(val days: Int) : Action
    object ToggleRecords : Action
    data class ModelFilter(val filter: String) : Action
    object EditSearch : Action
    data class SettingsTab(val index: Int) : Action
    object Refresh : Action
    object EditServer : Action
    object Pair : Action
    object ToggleImmersive : Action
    object ToggleKeepOn : Action
    object ToggleLockScreen : Action
    data class Brightness(val delta: Int) : Action
    data class Interval(val minutes: Int) : Action
    data class QuotaStale(val minutes: Int) : Action
    object ClearCache : Action
    object SystemSettings : Action
    object SystemHome : Action
}

/** Everything one frame needs. Built by the activity; pages only read it. */
data class Screen(
    val now: Long,
    val nav: Nav,
    val displayName: String,
    val avatar: Bitmap?,
    val dashboard: DashboardSnapshot?,
    val analytics: Map<Int, AnalyticsData>,
    val history: HistoryData?,
    val modelTables: Map<String, ModelTable>,
    val offline: Boolean,
    val lastSuccessMillis: Long?,
    val paired: Boolean,
    val config: AppConfig,
    val diagnostics: DeviceDiagnostics?,
    val healthOk: Boolean,
    val statusMessage: String,
    val versionName: String,
    val mock: Boolean,
    val cacheBytes: Long,
)

/** Tap targets registered while drawing; the last registered target on top wins. */
class Hits {
    private val rects = ArrayList<RectF>()
    private val actions = ArrayList<Action>()

    fun clear() { rects.clear(); actions.clear() }

    fun add(rect: RectF, action: Action) { rects.add(RectF(rect)); actions.add(action) }

    fun find(x: Float, y: Float): Int {
        for (index in rects.indices.reversed()) if (rects[index].contains(x, y)) return index
        return -1
    }

    fun rect(index: Int): RectF? = rects.getOrNull(index)
    fun action(index: Int): Action? = actions.getOrNull(index)
}

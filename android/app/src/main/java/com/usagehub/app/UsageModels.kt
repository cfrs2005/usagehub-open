package com.usagehub.app

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

data class UsageWindow(
    val limitId: String,
    val kind: String,
    val durationMinutes: Int?,
    val usedPercent: Double?,
    val resetsAtEpochSeconds: Long?,
)

data class ProviderUsage(
    val provider: String,
    val status: String,
    val source: String,
    val observedAtMillis: Long?,
    val receivedAtMillis: Long?,
    val upstreamStale: Boolean,
    val windows: List<UsageWindow>,
    val tokenUsage: TokenUsage = TokenUsage.missing(),
)

data class TokenUsage(
    val status: String,
    val todayTokens: Long?,
    val totalTokens: Long?,
    val todayCostUsd: Double?,
    val totalCostUsd: Double?,
    val costLabel: String,
    val source: String?,
    val observedAtMillis: Long?,
    val stale: Boolean,
) {
    companion object {
        fun missing() = TokenUsage(
            status = "missing",
            todayTokens = null,
            totalTokens = null,
            todayCostUsd = null,
            totalCostUsd = null,
            costLabel = "标准 API 等价费用",
            source = null,
            observedAtMillis = null,
            stale = true,
        )
    }
}

data class DashboardSnapshot(
    val serverTimeMillis: Long,
    val fetchedAtMillis: Long,
    val providers: List<ProviderUsage>,
) {
    val serverOffsetMillis: Long get() = serverTimeMillis - fetchedAtMillis
}

enum class Freshness {
    OFFICIAL_SAMPLE,
    LAST_OFFICIAL_DATA,
    EXPIRED,
    MISSING,
}

enum class ResetState {
    COUNTING_DOWN,
    PENDING_OFFICIAL_REFRESH,
    UNKNOWN,
}

enum class DisplayMode {
    USED,
    REMAINING,
}

object UsageLogic {
    private const val FRESH_MILLIS = 30L * 60L * 1000L
    private const val EXPIRED_MILLIS = 24L * 60L * 60L * 1000L
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val clockMinuteFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateHeaderFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    private val weekdayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun freshness(provider: ProviderUsage, nowMillis: Long): Freshness {
        val observed = provider.observedAtMillis ?: return Freshness.MISSING
        val age = (nowMillis - observed).coerceAtLeast(0L)
        return when {
            provider.status != "ok" && provider.windows.isEmpty() -> Freshness.MISSING
            age < FRESH_MILLIS && provider.status == "ok" && !provider.upstreamStale -> Freshness.OFFICIAL_SAMPLE
            age < EXPIRED_MILLIS -> Freshness.LAST_OFFICIAL_DATA
            else -> Freshness.EXPIRED
        }
    }

    fun resetState(window: UsageWindow, effectiveNowMillis: Long): ResetState {
        val resetMillis = window.resetsAtEpochSeconds?.times(1000L) ?: return ResetState.UNKNOWN
        return if (effectiveNowMillis >= resetMillis) {
            ResetState.PENDING_OFFICIAL_REFRESH
        } else {
            ResetState.COUNTING_DOWN
        }
    }

    fun effectiveNow(localNowMillis: Long, snapshot: DashboardSnapshot?): Long {
        return localNowMillis + (snapshot?.serverOffsetMillis ?: 0L)
    }

    fun displayPercent(rawUsedPercent: Double?, mode: DisplayMode): Int? {
        val used = rawUsedPercent?.coerceIn(0.0, 100.0) ?: return null
        val value = if (mode == DisplayMode.USED) used else 100.0 - used
        return value.roundToInt()
    }

    fun windowLabel(provider: String, window: UsageWindow): String {
        val duration = window.durationMinutes
        if (provider == "claude") {
            if (duration == 300 || window.limitId == "five_hour") return "5h"
            if (duration == 10_080 || window.limitId == "seven_day") return "7d"
        }
        val durationLabel = when {
            duration == null -> "window"
            duration % 10_080 == 0 -> "${duration / 10_080}w"
            duration % 1_440 == 0 -> "${duration / 1_440}d"
            duration % 60 == 0 -> "${duration / 60}h"
            else -> "${duration}m"
        }
        val id = window.limitId.ifBlank { window.kind.ifBlank { "quota" } }
        return if (provider == "codex") "$id · $durationLabel" else durationLabel
    }

    fun quotaWindow(provider: ProviderUsage?, durationMinutes: Int): UsageWindow? {
        if (provider == null) return null
        val durationMatches = provider.windows.filter { it.durationMinutes == durationMinutes }
        if (provider.provider == "codex" && durationMatches.isNotEmpty()) {
            durationMatches.firstOrNull { it.limitId == "codex:primary" }?.let { return it }
            durationMatches.firstOrNull { it.limitId.startsWith("codex:") }?.let { return it }
            durationMatches.firstOrNull {
                it.limitId.startsWith("codex_") && it.limitId.endsWith(":primary")
            }?.let { return it }
        }
        durationMatches.firstOrNull()?.let { return it }
        val expectedId = if (durationMinutes == 300) "five_hour" else "seven_day"
        return provider.windows.firstOrNull { it.limitId == expectedId }
    }

    fun countdown(window: UsageWindow, effectiveNowMillis: Long): String {
        val resetMillis = window.resetsAtEpochSeconds?.times(1000L) ?: return "reset unknown"
        if (effectiveNowMillis >= resetMillis) return "await official refresh"
        val duration = Duration.ofMillis(resetMillis - effectiveNowMillis)
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes.coerceAtLeast(1)}m"
        }
    }

    fun countdownChinese(window: UsageWindow?, effectiveNowMillis: Long): String {
        val resetMillis = window?.resetsAtEpochSeconds?.times(1000L) ?: return "重置时间未知"
        val remaining = resetMillis - effectiveNowMillis
        if (remaining < 60_000L) return "等待刷新"
        val duration = Duration.ofMillis(remaining)
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        return when {
            days > 0 -> "还剩 ${days}天${hours.toString().padStart(2, '0')}小时"
            hours > 0 -> "还剩 ${hours}小时${minutes.toString().padStart(2, '0')}分"
            else -> "还剩 ${minutes}分"
        }
    }

    fun clockText(nowMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zoneId).format(clockFormatter)

    fun clockMinuteText(nowMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zoneId).format(clockMinuteFormatter)

    fun dateHeaderText(nowMillis: Long, zoneId: ZoneId): String {
        val dateTime = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        return "${weekdayNames[dateTime.dayOfWeek.value - 1]} ${dateTime.format(dateHeaderFormatter)}"
    }

    fun dateTimeText(nowMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zoneId).format(dateTimeFormatter)

    fun ageText(ageMillis: Long): String {
        val seconds = (ageMillis.coerceAtLeast(0L) / 1000L)
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    fun ageTextChinese(ageMillis: Long): String {
        val minutes = ageMillis.coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            minutes < 1_440 -> "${minutes / 60}小时前"
            else -> "${minutes / 1_440}天前"
        }
    }

    fun formatTokens(value: Long?): String {
        value ?: return "--"
        return when {
            value >= 100_000_000L -> String.format(Locale.US, "%.1f亿", value / 100_000_000.0)
            value >= 10_000L -> String.format(Locale.US, "%.1f万", value / 10_000.0)
            else -> NumberFormat.getIntegerInstance(Locale.US).format(value)
        }
    }

    fun formatUsd(value: Double?): String {
        value ?: return "--"
        if (!value.isFinite() || value < 0.0) return "--"
        if (value == 0.0) return "\$0.00"
        if (value < 0.01) return "<\$0.01"
        return "\$" + NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(value)
    }
}

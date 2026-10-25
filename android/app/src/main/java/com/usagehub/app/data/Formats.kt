package com.usagehub.app.data

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** Number / date formatting shared by every console page. Mirrors the web console so both read the same. */
object Fmt {
    const val NONE = "—"
    private val date = DateTimeFormatter.ofPattern("MM/dd/yyyy")
    private val clock = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
    private val stamp = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")
    private val day = DateTimeFormatter.ofPattern("MM/dd")
    private val short = DateTimeFormatter.ofPattern("MM/dd HH:mm")
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    fun compact(value: Double?, digits: Int = 2): String {
        value ?: return NONE
        return when {
            value >= 1e9 -> String.format(Locale.US, "%.${digits}fB", value / 1e9)
            value >= 1e6 -> String.format(Locale.US, "%.${digits}fM", value / 1e6)
            value >= 1e3 -> String.format(Locale.US, "%.1fK", value / 1e3)
            else -> value.roundToLong().toString()
        }
    }

    fun compact(value: Long?, digits: Int = 2): String = compact(value?.toDouble(), digits)

    fun full(value: Double?): String =
        value?.let { NumberFormat.getIntegerInstance(Locale.US).format(it.roundToLong()) } ?: NONE

    fun full(value: Long?): String = full(value?.toDouble())

    fun money(value: Double?): String {
        value ?: return NONE
        if (!value.isFinite()) return NONE
        return "$" + NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(value)
    }

    fun pct(value: Double?): String = value?.let { "${Math.round(it.coerceIn(0.0, 100.0))}%" } ?: "--"

    /** Time left until a quota resets: `2天14小时`, `3小时05分`, `等待重置` once passed, `--` when unknown. */
    fun remain(resetsAtSeconds: Long?, nowMillis: Long): String {
        resetsAtSeconds ?: return "--"
        val diff = resetsAtSeconds * 1000L - nowMillis
        if (diff <= 0) return "等待重置"
        val days = diff / 86_400_000L
        val hours = diff % 86_400_000L / 3_600_000L
        val minutes = diff % 3_600_000L / 60_000L
        return if (days > 0) "${days}天${pad(hours)}小时" else "${hours}小时${pad(minutes)}分"
    }

    fun date(millis: Long, zone: ZoneId): String = fmt(millis, zone, date)
    fun clock(millis: Long, zone: ZoneId): String = fmt(millis, zone, clock)
    fun stamp(millis: Long, zone: ZoneId): String = fmt(millis, zone, stamp)
    fun hm(millis: Long, zone: ZoneId): String = fmt(millis, zone, hm)
    fun day(seconds: Long, zone: ZoneId): String = fmt(seconds * 1000L, zone, day)
    fun shortStamp(seconds: Long, zone: ZoneId): String = fmt(seconds * 1000L, zone, short)

    /** `07:34` when [millis] is on today's local date, otherwise `10/22 07:34`. */
    fun moment(millis: Long, nowMillis: Long, zone: ZoneId): String {
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return if (day == today) hm(millis, zone) else fmt(millis, zone, short)
    }

    /** `10/18/2026 - 10/24/2026`: the server's range when we have one, otherwise the requested one. */
    fun range(days: Int, data: AnalyticsData?, nowMillis: Long, zone: ZoneId): String {
        val to = data?.let { it.to * 1000L } ?: nowMillis
        val from = data?.let { it.from * 1000L } ?: (to - (days - 1) * ConsoleRules.DAY * 1000L)
        return "${date(from, zone)} - ${date(to, zone)}"
    }

    /** 0..top split into [count] round steps (1 / 2 / 2.5 / 5 × 10^k), never finer than 1 (axes show integers). */
    fun niceTicks(max: Double, count: Int = 3): List<Double> {
        val raw = max.coerceAtLeast(1.0) / count
        val power = 10.0.pow(floor(log10(raw)))
        val step = (listOf(1.0, 2.0, 2.5, 5.0, 10.0).firstOrNull { it * power >= raw * (1 - 1e-9) } ?: 10.0) * power
        if (step < 1.0) return (0..count).map { it.toDouble() }
        return (0..count).map { it * step }
    }

    fun tokenTick(value: Double): String = when {
        value >= 1e9 -> trim(value / 1e9) + "B"
        value >= 1e6 -> trim(value / 1e6) + "M"
        value >= 1e3 -> trim(value / 1e3) + "K"
        else -> value.roundToLong().toString()
    }

    fun moneyTick(value: Double): String = "$" + NumberFormat.getIntegerInstance(Locale.US).format(value.roundToLong())

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    private fun pad(value: Long): String = value.toString().padStart(2, '0')

    private fun fmt(millis: Long, zone: ZoneId, formatter: DateTimeFormatter): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(formatter)
}

package com.usagehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ConsoleLogicTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun parsesAnalyticsProvidersPointsModelsAndLatest() {
        val raw = """
            {"from":1000,"to":1000000,"bucket":"day","providers":[
              {"provider":"claude","points":[{"start":87400,"tokens":20,"costUsd":2.5,"usedPercent":null},{"start":1000,"tokens":10,"costUsd":1,"usedPercent":40}],
               "models":[{"model":"claude-3.5-sonnet","tokens":900,"costUsd":9},{"model":"","tokens":1,"costUsd":0}],
               "latest":{"totalTokens":17340000000,"totalCostUsd":14396.95,"observedAt":999000}},
              {"provider":"codex","points":[],"models":[],"latest":null},
              {"provider":"","points":[]}
            ]}
        """.trimIndent()
        val data = AnalyticsJson.parse(raw)
        assertEquals(2, data.providers.size)
        val claude = data.provider("claude")!!
        assertEquals(listOf(1000L, 87400L), claude.points.map { it.start })
        assertEquals(40.0, claude.points[0].usedPercent!!, 0.0)
        assertNull(claude.points[1].usedPercent)
        assertEquals(listOf("claude-3.5-sonnet"), claude.models.map { it.model })
        assertEquals(14396.95, claude.latest!!.totalCostUsd, 1e-9)
        assertNull(data.provider("codex")!!.latest)
    }

    @Test
    fun parsesHistoryNewestFirstAndClampsNegativeNumbers() {
        val raw = """{"items":[],"nextCursor":null,"tokenItems":[
            {"provider":"codex","observedAt":10,"todayTokens":5,"totalTokens":50,"todayCostUsd":0.1,"totalCostUsd":1},
            {"provider":"codex","observedAt":30,"todayTokens":-4,"totalTokens":60,"todayCostUsd":0.2,"totalCostUsd":2,"dailyUsage":[]}]}"""
        val history = AnalyticsJson.parseHistory(raw)
        assertEquals(listOf(30L, 10L), history.tokenItems.map { it.observedAt })
        assertEquals(0.0, history.tokenItems[0].todayTokens, 0.0)
    }

    @Test
    fun numberFormattingMatchesTheWebConsole() {
        assertEquals("17.34B", Fmt.compact(17_340_000_000L))
        assertEquals("3.9M", Fmt.compact(3_915_165.0, 1))
        assertEquals("12.4K", Fmt.compact(12_431L))
        assertEquals("999", Fmt.compact(999L))
        assertEquals(Fmt.NONE, Fmt.compact(null as Long?))
        assertEquals("3,915,165", Fmt.full(3_915_165L))
        assertEquals("0", Fmt.full(0L))
        assertEquals("$14,396.95", Fmt.money(14_396.95))
        assertEquals("$0.00", Fmt.money(0.0))
        assertEquals(Fmt.NONE, Fmt.money(null))
        assertEquals("28%", Fmt.pct(28.4))
        assertEquals("100%", Fmt.pct(130.0))
        assertEquals("--", Fmt.pct(null))
    }

    @Test
    fun countdownUsesDaysHoursThenHoursMinutes() {
        val now = 1_000_000_000_000L
        val seconds = now / 1000
        assertEquals("2天14小时", Fmt.remain(seconds + 2 * 86_400 + 14 * 3_600 + 120, now))
        assertEquals("6天03小时", Fmt.remain(seconds + 6 * 86_400 + 3 * 3_600 + 120, now))
        assertEquals("2小时05分", Fmt.remain(seconds + 2 * 3_600 + 5 * 60 + 10, now))
        assertEquals("等待重置", Fmt.remain(seconds, now))
        assertEquals("--", Fmt.remain(null, now))
    }

    @Test
    fun datesUseTheCompsMonthDayYearFormat() {
        val millis = Instant.parse("2026-10-24T07:34:05Z").toEpochMilli()
        assertEquals("10/24/2026", Fmt.date(millis, utc))
        assertEquals("10/24/2026 07:34", Fmt.clock(millis, utc))
        assertEquals("10/24 07:34", Fmt.shortStamp(millis / 1000, utc))
        val data = AnalyticsData(millis / 1000 - 6 * 86_400, millis / 1000, "day", emptyList())
        assertEquals("10/18/2026 - 10/24/2026", Fmt.range(7, data, 0L, utc))
    }

    @Test
    fun yAxisTicksUseRoundSteps() {
        assertEquals(listOf(0.0, 2e9, 4e9, 6e9), Fmt.niceTicks(5.2e9))
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0), Fmt.niceTicks(1_380.0))
        assertEquals(listOf(0.0, 2e8, 4e8, 6e8), Fmt.niceTicks(5.3e8))
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), Fmt.niceTicks(0.0))
        assertEquals("6B", Fmt.tokenTick(6e9))
        assertEquals("2.5B", Fmt.tokenTick(2.5e9))
        assertEquals("200M", Fmt.tokenTick(2e8))
        assertEquals("$1,000", Fmt.moneyTick(1000.0))
    }

    private fun provider(five: Double?, week: Double?) = ProviderUsage(
        provider = "codex", status = "ok", source = "official_app_server", observedAtMillis = 0L, receivedAtMillis = 0L,
        upstreamStale = false,
        windows = listOf(UsageWindow("codex:primary", "short", 300, five, null), UsageWindow("codex:secondary", "weekly", 10_080, week, null)),
    )

    @Test
    fun ringFallsBackToTheSevenDayWindowWhenFiveHourIsEmpty() {
        assertEquals(28.0 to "5小时用量", ConsoleRules.ringWindow(provider(28.0, 24.0)))
        assertEquals(24.0 to "7天用量", ConsoleRules.ringWindow(provider(null, 24.0)))
        assertEquals(null to "5小时用量", ConsoleRules.ringWindow(provider(null, null)))
        assertEquals(null to "5小时用量", ConsoleRules.ringWindow(null))
    }

    private val q90 = 90 * 60_000L

    @Test
    fun quotaStatusIsJudgedOnDeviceAtTheConfiguredThreshold() {
        val observed = 1_000_000_000L
        val p = provider(28.0, 24.0).copy(observedAtMillis = observed)
        assertEquals(ConsoleRules.Status.OK, ConsoleRules.status(p, observed + q90 - 1, q90))
        assertEquals(ConsoleRules.Status.STALE, ConsoleRules.status(p, observed + q90, q90))
        assertEquals(ConsoleRules.Status.OK, ConsoleRules.status(p, observed + q90, 2 * 60 * 60_000L))
        assertEquals(ConsoleRules.Status.MISSING, ConsoleRules.status(p.copy(observedAtMillis = null), observed, q90))
        assertEquals(ConsoleRules.Status.MISSING, ConsoleRules.status(null, observed, q90))
    }

    @Test
    fun serverStaleFlagIsIgnoredInBothDirections() {
        val observed = 1_000_000_000L
        val p = provider(28.0, 24.0).copy(observedAtMillis = observed)
        // Server says stale (its 30-minute rule) but the sample is 40 minutes old: within 90 minutes → not stale.
        assertEquals(ConsoleRules.Status.OK, ConsoleRules.status(p.copy(serverStale = true, upstreamStale = true), observed + 40 * 60_000L, q90))
        // Server says fresh but the sample is 3 hours old → stale on the device.
        assertEquals(ConsoleRules.Status.STALE, ConsoleRules.status(p.copy(serverStale = false), observed + 3 * 3_600_000L, q90))
    }

    @Test
    fun modelRowsAreDeltasAgainstTheBoundarySnapshotNotCumulativeTotals() {
        val current = listOf(ModelUsage("a", 1_000.0, 0.0), ModelUsage("b", 500.0, 0.0), ModelUsage("new", 100.0, 0.0))
        val week = mapOf("a" to 400.0, "b" to 500.0)
        val rows = ConsoleRules.modelRows(current, week, null)
        assertEquals(listOf(600.0, 0.0, 100.0), rows.map { it.week })
        assertEquals(600.0 / 700.0 * 100, rows[0].share!!, 1e-9)
        assertTrue(rows.all { it.today == null })
        val unknown = ConsoleRules.modelRows(current, null, null)
        assertTrue(unknown.all { it.week == null && it.share == null })
        assertEquals(listOf("new"), ConsoleRules.modelRows(current, week, null, "NE").map { it.model })
    }

    private fun upload(at: Long, total: Double, vararg models: Pair<String, Double>) =
        TokenItem("claude", at, 0.0, total, 0.0, 0.0, models.map { ModelUsage(it.first, it.second, 0.0) })

    @Test
    fun sparseUploadsStillGiveHonestDeltasInsteadOfDashes() {
        // v0.1.8 field shape: no upload within 1 h before midnight, model totals only since yesterday 06:00.
        val midnight = Instant.parse("2026-10-24T16:00:00Z").epochSecond // 10/25 00:00 Asia/Shanghai
        val weekBoundary = midnight - 6 * 86_400
        val now = midnight + 13 * 3_600
        val history = listOf(
            upload(now - 60, 1_000.0, "opus" to 700.0, "fable" to 300.0),
            upload(midnight + 9 * 3_600, 900.0, "opus" to 620.0, "fable" to 280.0),
            upload(midnight - 18 * 3_600, 400.0, "opus" to 300.0, "fable" to 100.0),
            upload(midnight - 20 * 3_600, 380.0), // before model_usage existed
        )
        val beforeMidnight = ModelSnap(midnight - 2 * 3_600, mapOf("opus" to 610.0, "fable" to 270.0))
        val current = listOf(ModelUsage("opus", 700.0, 0.0), ModelUsage("fable", 300.0, 0.0))

        val today = ConsoleRules.windowStart(midnight, beforeMidnight, history, now - 60, 3_600)!!
        val week = ConsoleRules.windowStart(weekBoundary, null, history, now - 60, 12 * 3_600)!!
        assertEquals(false, today.exact)
        assertEquals(midnight + 9 * 3_600, today.snap.at)
        assertEquals(midnight - 18 * 3_600, week.snap.at)

        val table = ConsoleRules.modelTable(current, week, today)
        assertEquals(listOf(80.0, 20.0), table.rows.map { it.today })
        assertEquals(listOf(400.0, 200.0), table.rows.map { it.week })
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals("今日自 09:00 · 7天自 10/24 06:00", ConsoleRules.coverageNote(table, shanghai))
    }

    @Test
    fun aSnapshotAtTheBoundaryIsExactAndNeedsNoNote() {
        val boundary = 1_000_000L
        val before = ModelSnap(boundary - 3_000, mapOf("a" to 5.0))
        val exact = ConsoleRules.windowStart(boundary, before, emptyList(), boundary + 100, 3_600)!!
        assertEquals(true, exact.exact)
        // Too far before the boundary, and nothing after it: unknown, not a guess that spans the previous day.
        assertNull(ConsoleRules.windowStart(boundary, before.copy(at = boundary - 4_000), emptyList(), boundary + 100, 3_600))
        val table = ConsoleRules.modelTable(listOf(ModelUsage("a", 9.0, 0.0)), exact, exact)
        assertNull(ConsoleRules.coverageNote(table, utc))
        assertEquals(listOf(4.0), table.rows.map { it.week })
        assertEquals("今日无可比快照 · 7天无可比快照", ConsoleRules.coverageNote(ConsoleRules.modelTable(emptyList(), null, null), utc))
    }

    @Test
    fun theNewestUploadAloneMeasuresNothing() {
        val boundary = 1_000_000L
        val only = upload(boundary + 500, 10.0, "a" to 10.0)
        assertNull(ConsoleRules.windowStart(boundary, null, listOf(only), boundary + 500, 3_600))
    }

    @Test
    fun partialModelTotalsAreNeverSubtractedFromFullOnes() {
        // Pre-v0.1.9 Claude snapshots counted input+output only (~1% of the provider total).
        val boundary = 1_000_000L
        val history = listOf(
            upload(boundary + 100, 17_000.0, "opus" to 120.0),
            upload(boundary + 900, 17_100.0, "opus" to 17_100.0),
            upload(boundary + 1_800, 17_200.0, "opus" to 17_200.0),
        )
        val start = ConsoleRules.windowStart(boundary, ModelSnap(boundary - 10, mapOf("opus" to 110.0), full = false), history, boundary + 1_800, 3_600)!!
        assertEquals(boundary + 900, start.snap.at)
        assertEquals(false, start.exact)
        assertTrue(ConsoleRules.complete(listOf(ModelUsage("a", 95.0, 0.0)), 100.0))
        assertEquals(false, ConsoleRules.complete(listOf(ModelUsage("a", 1.0, 0.0)), 100.0))
    }

    @Test
    fun historyParsesModelUsage() {
        val raw = """{"items":[],"tokenItems":[{"provider":"codex","observedAt":5,"todayTokens":1,"totalTokens":2,"todayCostUsd":0,"totalCostUsd":3,
            "modelUsage":[{"model":"gpt-6-astra","tokens":499450672,"costUsd":0},{"model":"","tokens":1,"costUsd":0}]}]}"""
        val item = AnalyticsJson.parseHistory(raw).tokenItems.single()
        assertEquals(listOf(ModelUsage("gpt-6-astra", 499_450_672.0, 0.0)), item.models)
        assertTrue(AnalyticsJson.parseHistory("""{"tokenItems":[{"provider":"codex","observedAt":5}]}""").tokenItems.single().models.isEmpty())
    }

    @Test
    fun dayRangeStartsOnUtcMidnightSoTodayGetsItsOwnBucket() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-25T04:50:00Z").toEpochMilli() // 10/25 12:50 in Shanghai
        val (from, to) = ConsoleRules.dayRange(7, now, shanghai)
        assertEquals(Instant.parse("2026-10-19T00:00:00Z").epochSecond, from)
        assertEquals(Instant.parse("2026-10-25T23:59:59Z").epochSecond, to)
        assertEquals("10/19/2026 - 10/25/2026", Fmt.range(7, null, now, shanghai))
    }

    @Test
    fun zeroCostWithTokensIsUnpricedNotFree() {
        assertEquals(Fmt.NONE, Fmt.cost(0.0, 15_453_948L))
        assertEquals("$0.00", Fmt.cost(0.0, 0L))
        assertEquals("$3.10", Fmt.cost(3.095408, 15_453_948L))
        assertEquals(Fmt.NONE, Fmt.cost(null, 5L))
        assertTrue(Fmt.unpriced(0.0, 1.0))
        assertEquals(false, Fmt.unpriced(0.0, 0.0))
    }

    @Test
    fun recordTokenColumnsStayCompactAtRealMagnitudes() {
        assertEquals("307.86M", Fmt.compact(307_861_178.0))
        assertEquals("17.66B", Fmt.compact(17_661_187_881.0))
        assertEquals("123.46B", Fmt.compact(123_456_789_012.0))
    }

    @Test
    fun daySlotsKeepMissingDaysEmptyInsteadOfZero() {
        val from = 1_000_000L
        val data = AnalyticsData(
            from, from + 6 * 86_400, "day",
            listOf(AnalyticsProvider("claude", listOf(AnalyticsPoint(from + 2 * 86_400, 5.0, 1.0, null)), emptyList(), null)),
        )
        val slots = ConsoleRules.daySlots(data, "claude", 7, 0L)
        assertEquals(7, slots.size)
        assertEquals(from + 6 * 86_400, slots.last().first)
        assertEquals(listOf(2), slots.indices.filter { slots[it].second != null })
    }

    @Test
    fun updateTimeUsesDeviceThresholdsForQuotaAndTokens() {
        val now = Instant.parse("2026-10-25T04:00:00Z").toEpochMilli()
        fun p(quota: Long?, tokens: Long?, stale: Boolean = false) = provider(28.0, 24.0).copy(
            observedAtMillis = quota, serverStale = stale,
            tokenUsage = TokenUsage.missing().copy(status = "ok", observedAtMillis = tokens, stale = stale),
        )
        // 40-minute-old quota with the server's stale flag set: fresh on the device.
        val fresh = ConsoleRules.updated(p(now - 40 * 60_000L, now - 120_000, stale = true), now, q90)
        assertEquals(now - 40 * 60_000L, fresh.shownAt)
        assertEquals(false, fresh.old)
        // Quota exactly at the threshold → old.
        assertEquals(true, ConsoleRules.updated(p(now - q90, now), now, q90).quotaOld)
        // Token sample 6 h old (fixed threshold) while quota is fresh: show the older token time, amber.
        val tokenLag = ConsoleRules.updated(p(now - 60_000, now - ConsoleRules.TOKEN_STALE_MILLIS), now, q90)
        assertEquals(now - ConsoleRules.TOKEN_STALE_MILLIS, tokenLag.shownAt)
        assertEquals(true, tokenLag.old)
        // Token 5 h old is still within its threshold: quota time shown, not amber.
        val tokenOk = ConsoleRules.updated(p(now - 60_000, now - 5 * 3_600_000L), now, q90)
        assertEquals(now - 60_000, tokenOk.shownAt)
        assertEquals(false, tokenOk.old)
        assertNull(ConsoleRules.updated(null, now, q90).shownAt)
    }

    @Test
    fun momentDropsTheDateOnlyForToday() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-25T04:00:00Z").toEpochMilli()
        assertEquals("11:52", Fmt.moment(Instant.parse("2026-10-25T03:52:00Z").toEpochMilli(), now, shanghai))
        assertEquals("10/24 23:30", Fmt.moment(Instant.parse("2026-10-24T15:30:00Z").toEpochMilli(), now, shanghai))
    }
}

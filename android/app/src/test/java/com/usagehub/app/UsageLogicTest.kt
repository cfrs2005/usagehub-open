package com.usagehub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UsageLogicTest {
    private val observed = Instant.parse("2026-09-02T18:00:00Z").toEpochMilli()
    private val provider = ProviderUsage(
        provider = "claude",
        status = "ok",
        source = "official_statusline",
        observedAtMillis = observed,
        receivedAtMillis = observed,
        upstreamStale = false,
        windows = listOf(UsageWindow("five_hour", "short", 300, 50.0, null)),
    )

    @Test
    fun freshnessUsesExactThirtyMinuteAndTwentyFourHourBoundaries() {
        assertEquals(Freshness.OFFICIAL_SAMPLE, UsageLogic.freshness(provider, observed + 30 * 60_000L - 1L))
        assertEquals(Freshness.LAST_OFFICIAL_DATA, UsageLogic.freshness(provider, observed + 30 * 60_000L))
        assertEquals(Freshness.LAST_OFFICIAL_DATA, UsageLogic.freshness(provider, observed + 24 * 60 * 60_000L - 1L))
        assertEquals(Freshness.EXPIRED, UsageLogic.freshness(provider, observed + 24 * 60 * 60_000L))
    }

    @Test
    fun upstreamStaleImmediatelyUsesLastOfficialDataLabel() {
        val stale = provider.copy(upstreamStale = true)
        assertEquals(Freshness.LAST_OFFICIAL_DATA, UsageLogic.freshness(stale, observed + 1_000L))
    }

    @Test
    fun resetAtBoundaryWaitsForOfficialRefreshInsteadOfResettingUsage() {
        val resetSeconds = 1_788_356_400L
        val window = UsageWindow("five_hour", "short", 300, 63.0, resetSeconds)

        assertEquals(ResetState.COUNTING_DOWN, UsageLogic.resetState(window, resetSeconds * 1_000L - 1L))
        assertEquals(ResetState.PENDING_OFFICIAL_REFRESH, UsageLogic.resetState(window, resetSeconds * 1_000L))
        assertEquals("await official refresh", UsageLogic.countdown(window, resetSeconds * 1_000L))
        assertEquals(63, UsageLogic.displayPercent(window.usedPercent, DisplayMode.USED))
    }

    @Test
    fun displayPercentageClampsDefensiveInputAndDerivesRemaining() {
        assertEquals(0, UsageLogic.displayPercent(-7.0, DisplayMode.USED))
        assertEquals(100, UsageLogic.displayPercent(107.0, DisplayMode.USED))
        assertEquals(0, UsageLogic.displayPercent(107.0, DisplayMode.REMAINING))
        assertEquals(37, UsageLogic.displayPercent(63.0, DisplayMode.REMAINING))
        assertNull(UsageLogic.displayPercent(null, DisplayMode.USED))
    }

    @Test
    fun claudeAndCodexLabelsUseIdsAndDurationsNotArrayPosition() {
        assertEquals("5h", UsageLogic.windowLabel("claude", UsageWindow("five_hour", "short", 300, 1.0, null)))
        assertEquals("7d", UsageLogic.windowLabel("claude", UsageWindow("anything", "weekly", 10_080, 1.0, null)))
        assertEquals("team-pool · 1h", UsageLogic.windowLabel("codex", UsageWindow("team-pool", "other", 60, 1.0, null)))
        assertEquals("new-limit · 15m", UsageLogic.windowLabel("codex", UsageWindow("new-limit", "other", 15, 1.0, null)))
    }

    @Test
    fun timeFormattingUsesRequestedTimezoneIncludingDateBoundary() {
        val instant = Instant.parse("2026-09-02T00:30:00Z").toEpochMilli()
        assertEquals("00:30:00", UsageLogic.clockText(instant, ZoneId.of("UTC")))
        assertEquals("17:30:00", UsageLogic.clockText(instant, ZoneId.of("America/Los_Angeles")))
        assertEquals("2026-09-02 08:30:00", UsageLogic.dateTimeText(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals("周三 2026/09/02", UsageLogic.dateHeaderText(instant, ZoneId.of("UTC")))
        assertEquals("周二 2026/09/01", UsageLogic.dateHeaderText(instant, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun serverClockOffsetControlsCountdown() {
        val localFetch = Instant.parse("2026-09-02T18:00:00Z").toEpochMilli()
        val snapshot = DashboardSnapshot(
            serverTimeMillis = localFetch + 120_000L,
            fetchedAtMillis = localFetch,
            providers = emptyList(),
        )
        assertEquals(localFetch + 180_000L, UsageLogic.effectiveNow(localFetch + 60_000L, snapshot))
    }

    @Test
    fun quotaWindowsUseDurationInsteadOfInternalIdOrArrayPosition() {
        val mixed = provider.copy(
            provider = "codex",
            windows = listOf(
                UsageWindow("hidden-weekly-name", "weekly", 10_080, 71.0, null),
                UsageWindow("hidden-short-name", "short", 300, 28.0, null),
                UsageWindow("unused-bucket", "other", 60, 99.0, null),
            ),
        )

        assertEquals(28.0, UsageLogic.quotaWindow(mixed, 300)?.usedPercent!!, 0.0)
        assertEquals(71.0, UsageLogic.quotaWindow(mixed, 10_080)?.usedPercent!!, 0.0)
    }

    @Test
    fun codexWeeklyWindowPrefersTheMainCodexBucket() {
        val mixed = provider.copy(
            provider = "codex",
            windows = listOf(
                UsageWindow("base_model_inference:primary", "weekly", 10_080, 0.0, null),
                UsageWindow("codex:primary", "weekly", 10_080, 47.0, null),
                UsageWindow("codex_bengalfox:secondary", "weekly", 10_080, 0.0, null),
            ),
        )

        assertEquals(47.0, UsageLogic.quotaWindow(mixed, 10_080)?.usedPercent!!, 0.0)
    }

    @Test
    fun chineseCountdownUsesMinutePrecision() {
        val base = Instant.parse("2026-09-02T18:00:00Z").toEpochMilli()
        val short = UsageWindow("hidden", "short", 300, 12.0, (base + 3 * 3_600_000L + 8 * 60_000L) / 1_000L)
        val weekly = UsageWindow("hidden", "weekly", 10_080, 12.0, (base + 4 * 86_400_000L + 8 * 3_600_000L) / 1_000L)
        val due = UsageWindow("hidden", "short", 300, 12.0, (base + 59_000L) / 1_000L)

        assertEquals("还剩 3小时08分", UsageLogic.countdownChinese(short, base))
        assertEquals("还剩 4天08小时", UsageLogic.countdownChinese(weekly, base))
        assertEquals("等待刷新", UsageLogic.countdownChinese(due, base))
    }

    @Test
    fun compactTokenAndUsdFormattingIsReadable() {
        assertEquals("--", UsageLogic.formatTokens(null))
        assertEquals("9,876", UsageLogic.formatTokens(9_876))
        assertEquals("6.2万", UsageLogic.formatTokens(62_000))
        assertEquals("1.3亿", UsageLogic.formatTokens(128_600_000))
        assertEquals("\$0.00", UsageLogic.formatUsd(0.0))
        assertEquals("<\$0.01", UsageLogic.formatUsd(0.001))
        assertEquals("\$1,234.56", UsageLogic.formatUsd(1_234.56))
    }
}

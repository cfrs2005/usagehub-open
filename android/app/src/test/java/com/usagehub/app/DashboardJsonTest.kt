package com.usagehub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DashboardJsonTest {
    private val fetchedAt = 1_788_375_000_000L

    @Test
    fun parsesDynamicBucketsWithoutDependingOnArrayPosition() {
        val snapshot = DashboardJson.parse(
            """
            {
              "serverTime":"2026-09-02T19:00:00Z",
              "unknownRoot":"ignored",
              "providers":[
                {
                  "provider":"codex",
                  "status":"ok",
                  "source":"official_app_server",
                  "observedAt":"2026-09-02T18:59:30Z",
                  "receivedAt":"2026-09-02T18:59:31Z",
                  "stale":false,
                  "windows":[
                    {"limitId":"weekly-team","kind":"weekly","durationMinutes":10080,"usedPercent":42,"resetsAt":1788681600},
                    {"limitId":"new-bucket","kind":"other","durationMinutes":60,"usedPercent":11.5,"resetsAt":1788357000,"newField":true},
                    {"limitId":"short-main","kind":"short","durationMinutes":15,"usedPercent":25,"resetsAt":1788356400}
                  ]
                }
              ]
            }
            """.trimIndent(),
            fetchedAt,
        )

        val provider = snapshot.providers.single()
        assertEquals("codex", provider.provider)
        assertEquals(listOf("weekly-team", "new-bucket", "short-main"), provider.windows.map { it.limitId })
        assertEquals(60, provider.windows[1].durationMinutes)
        assertEquals(11.5, provider.windows[1].usedPercent!!, 0.0)
    }

    @Test
    fun handlesNullAndMissingOptionalWindowFields() {
        val snapshot = DashboardJson.parse(
            """
            {
              "serverTime":"2026-09-02T19:00:00Z",
              "providers":[{
                "provider":"codex",
                "status":"missing",
                "source":"official_app_server",
                "observedAt":null,
                "windows":[
                  {"limitId":"nullable","kind":"other","durationMinutes":null,"usedPercent":null,"resetsAt":null}
                ]
              }]
            }
            """.trimIndent(),
            fetchedAt,
        )

        val window = snapshot.providers.single().windows.single()
        assertNull(window.durationMinutes)
        assertNull(window.usedPercent)
        assertNull(window.resetsAtEpochSeconds)
        assertNull(snapshot.providers.single().observedAtMillis)
    }

    @Test
    fun cacheRoundTripPreservesServerAndFetchTimes() {
        val original = DashboardSnapshot(
            serverTimeMillis = fetchedAt + 31_000L,
            fetchedAtMillis = fetchedAt,
            providers = listOf(
                ProviderUsage(
                    provider = "claude",
                    status = "ok",
                    source = "official_statusline",
                    observedAtMillis = fetchedAt - 2_000L,
                    receivedAtMillis = fetchedAt - 1_000L,
                    upstreamStale = false,
                    windows = listOf(UsageWindow("five_hour", "short", 300, 63.0, 1_788_356_400L)),
                ),
            ),
        )

        val restored = DashboardJson.parseCache(DashboardJson.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun invalidProviderEntryIsIgnored() {
        val snapshot = DashboardJson.parse(
            """{"serverTime":"bad","providers":[{"status":"ok"},null,"text"]}""",
            fetchedAt,
        )

        assertTrue(snapshot.providers.isEmpty())
        assertEquals(fetchedAt, snapshot.serverTimeMillis)
    }

    @Test
    fun parsesTokenUsageAndPreservesRealZeroValues() {
        val snapshot = DashboardJson.parse(
            """
            {
              "serverTime":"2026-09-02T19:00:00Z",
              "providers":[{
                "provider":"claude",
                "status":"ok",
                "source":"official_statusline",
                "observedAt":"2026-09-02T18:59:30Z",
                "windows":[{"limitId":"five_hour","kind":"short","durationMinutes":300,"usedPercent":0,"resetsAt":1788357000}],
                "tokenUsage":{
                  "status":"ok",
                  "todayTokens":0,
                  "totalTokens":13894277721,
                  "todayCostUsd":0,
                  "totalCostUsd":13029.064177,
                  "costLabel":"标准 API 等价费用",
                  "source":"ccusage",
                  "observedAt":"2026-09-02T18:58:00Z",
                  "stale":false
                }
              }]
            }
            """.trimIndent(),
            fetchedAt,
        )

        val usage = snapshot.providers.single().tokenUsage
        assertEquals("ok", usage.status)
        assertEquals(0L, usage.todayTokens)
        assertEquals(13_894_277_721L, usage.totalTokens)
        assertEquals(0.0, usage.todayCostUsd!!, 0.0)
        assertEquals(13_029.064177, usage.totalCostUsd!!, 0.0)
        assertEquals("ccusage", usage.source)
        assertEquals(Instant.parse("2026-09-02T18:58:00Z").toEpochMilli(), usage.observedAtMillis)
    }

    @Test
    fun missingTokenUsageUsesExplicitMissingState() {
        val snapshot = DashboardJson.parse(
            """{"providers":[{"provider":"codex","status":"missing","source":"official_app_server","windows":[]}]}""",
            fetchedAt,
        )

        val usage = snapshot.providers.single().tokenUsage
        assertEquals("missing", usage.status)
        assertNull(usage.todayTokens)
        assertNull(usage.totalCostUsd)
        assertTrue(usage.stale)
    }
}

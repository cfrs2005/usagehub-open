package com.usagehub.app.pages

import android.graphics.Paint
import android.graphics.RectF
import com.usagehub.app.data.ConsoleRules
import com.usagehub.app.data.Fmt
import com.usagehub.app.data.UsageLogic
import com.usagehub.app.ui.Accent
import com.usagehub.app.ui.Action
import com.usagehub.app.ui.Charts
import com.usagehub.app.ui.Palette
import com.usagehub.app.ui.Widgets

/** Day-range selector + date pill on the right of the header. Returns nothing; registers its own taps. */
object RangeControls {
    fun draw(ctx: PageCtx, options: List<Int>, selected: Int, rightEdge: Float, action: (Int) -> Action) {
        val g = ctx.g
        val range = Fmt.range(selected, ctx.s.analytics[selected], ctx.s.now, ctx.zone)
        val pill = ctx.ctrlRect(rightEdge, Widgets.datePillWidth(g, range))
        Widgets.datePill(g, pill, range)
        val labels = options.map { "${it}天" }
        val segW = Widgets.segWidth(g, labels, minItem = 50f)
        val rects = Widgets.seg(g, pill.left - 10f - segW, Layout.CTRL_TOP, labels, options.indexOf(selected), minItem = 50f)
        rects.forEachIndexed { index, rect -> ctx.hits.add(rect, action(options[index])) }
    }
}

object UsagePage {
    fun draw(ctx: PageCtx) {
        val g = ctx.g
        val s = ctx.s
        val days = s.nav.usageDays
        ctx.header("用量趋势", "查看 Token、费用和时间趋势")
        RangeControls.draw(ctx, listOf(7, 30, 90), days, Layout.RIGHT) { Action.UsageDays(it) }
        val data = s.analytics[days]
        val gap = 10f
        val width = (Layout.RIGHT - Layout.LEFT - gap) / 2
        listOf("claude", "codex").forEachIndexed { index, name ->
            val left = Layout.LEFT + index * (width + gap)
            val c = RectF(left, 74f, left + width, 344f)
            val accent = Accent.of(name)
            Widgets.neonCard(g, c, accent)
            Widgets.logo(g, name, c.left + 14f, c.top + 13f, 24f)
            g.textMid(if (name == "claude") "Claude" else "Codex", c.left + 47f, c.top + 25f, 19f, 0xFFFFFFFF.toInt(), 800)

            val slots = ConsoleRules.daySlots(data, name, days, s.now / 1000)
            val rangeTokens = if (data == null) null else slots.sumOf { it.second?.tokens ?: 0.0 }
            g.textMid("Token 使用量", c.left + 14f, c.top + 55f, 13f, 0xFFE3E7EF.toInt(), 500)
            val total = Fmt.compact(rangeTokens)
            val totalW = g.textMid(total, c.right - 14f, c.top + 55f, 21f, accent.value, 800, Paint.Align.RIGHT, tnum = true, glow = Palette.alpha(accent.main, 0.55f))
            g.textMid("总计", c.right - 18f - totalW, c.top + 56f, 12.5f, Palette.SOFT, 400, Paint.Align.RIGHT)

            val chart = RectF(c.left + 12f, c.top + 72f, c.right - 12f, c.bottom - 62f)
            if (data == null) {
                g.textMid(if (s.paired || s.mock) "正在读取…" else "设备未配对", chart.centerX(), chart.centerY(), 12f, Palette.DIM, 400, Paint.Align.CENTER)
            } else {
                Charts.bars(g, chart, slots, name)
            }

            val current = s.dashboard?.providers?.firstOrNull { it.provider == name }
            val usage = current?.tokenUsage
            val week = UsageLogic.quotaWindow(current, 10_080)
            val stats = listOf(
                StatRow.cost("累计费用", usage?.totalCostUsd ?: data?.provider(name)?.latest?.totalCostUsd, usage?.totalTokens),
                Stat("今日 Token", Fmt.full(usage?.todayTokens), if (name == "codex") ValueTone.CYAN else ValueTone.WHITE, Fmt.compact(usage?.todayTokens)),
                StatRow.cost("今日费用", usage?.todayCostUsd, usage?.todayTokens),
                Stat("额度重置", Fmt.remain(week?.resetsAtEpochSeconds, UsageLogic.effectiveNow(s.now, s.dashboard))),
            )
            g.hline(c.left + 12f, c.right - 12f, c.bottom - 55f, 0x17FFFFFF)
            val fitted = StatRow.fit(g, stats, c.width() - 28f, 0f, 15.5f, 9f, 11f, 16f)
            var x = c.left + 14f
            stats.forEachIndexed { i, stat ->
                val pad = if (i == 0) 0f else 8f
                if (i > 0) g.vline(x, c.bottom - 44f, c.bottom - 10f, 0x1FFFFFFF)
                StatRow.text(g, stat, fitted.texts[i], x + pad, c.bottom - 27f, fitted.size)
                g.text(stat.label, x + pad, c.bottom - 11f, 11f, Palette.MUTED, 400)
                x += fitted.widths[i]
            }
        }
    }
}

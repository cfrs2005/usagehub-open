package com.usagehub.app.pages

import android.graphics.Paint
import android.graphics.RectF
import com.usagehub.app.data.ConsoleRules
import com.usagehub.app.data.Fmt
import com.usagehub.app.ui.Accent
import com.usagehub.app.ui.Action
import com.usagehub.app.ui.Charts
import com.usagehub.app.ui.Palette
import com.usagehub.app.ui.Widgets
import java.util.Locale

object StatsPage {
    private val vendors = listOf("claude" to "Claude", "codex" to "Codex")

    fun draw(ctx: PageCtx) {
        val g = ctx.g
        val s = ctx.s
        val days = s.nav.statsDays
        ctx.header("模型对比", "对比不同模型的用量和成本")
        RangeControls.draw(ctx, listOf(7, 30), days, Layout.RIGHT) { Action.StatsDays(it) }
        val data = s.analytics[days]
        val claude = ConsoleRules.daySlots(data, "claude", days, s.now / 1000)
        val codex = ConsoleRules.daySlots(data, "codex", days, s.now / 1000)

        val gap = 10f
        val unit = (Layout.RIGHT - Layout.LEFT - gap * 2) / 2.75f
        val top = 74f
        val bottom = 346f
        val tokenCard = RectF(Layout.LEFT, top, Layout.LEFT + unit, bottom)
        val costCard = RectF(tokenCard.right + gap, top, tokenCard.right + gap + unit, bottom)
        val shareCard = RectF(costCard.right + gap, top, Layout.RIGHT, bottom)

        listOf(tokenCard to "Token 使用量对比", costCard to "费用对比 (USD)").forEachIndexed { index, (card, title) ->
            Widgets.neutralCard(g, card)
            g.textMid(title, card.left + 12f, card.top + 20f, 14f, 0xFFFFFFFF.toInt(), 800)
            DetailPage.legend(g, card.right - 12f, card.top + 44f, vendors.map { it.second to Accent.of(it.first).main })
            val chart = RectF(card.left + 8f, card.top + 58f, card.right - 8f, card.bottom - 8f)
            if (data == null) g.textMid("正在读取…", chart.centerX(), chart.centerY(), 12f, Palette.DIM, 400, Paint.Align.CENTER)
            else Charts.groupedBars(g, chart, claude, codex, cost = index == 1)
        }

        Widgets.neutralCard(g, shareCard)
        g.textMid("总用量占比 (${days}天)", shareCard.left + 12f, shareCard.top + 20f, 14f, 0xFFFFFFFF.toInt(), 800)
        val totals = vendors.map { (name, _) -> name to (if (data == null) null else ConsoleRules.daySlots(data, name, days, s.now / 1000).sumOf { it.second?.tokens ?: 0.0 }) }
        val sum = totals.sumOf { it.second ?: 0.0 }
        val radius = minOf(shareCard.width() / 2 - 26f, 56f)
        Charts.donut(
            g, shareCard.centerX(), shareCard.top + 128f, radius, 13f,
            totals.map { (name, value) -> (value ?: 0.0) to Accent.of(name).main },
            if (data == null) Fmt.NONE else Fmt.compact(sum), "总 Token",
        )
        totals.forEachIndexed { index, (name, value) ->
            val right = index == 1
            val x = if (right) shareCard.right - 14f else shareCard.left + 14f
            val align = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            val label = vendors[index].second
            val color = Accent.of(name).main
            val labelW = g.width(label, 12f)
            val dotX = if (right) x - labelW - 10f else x + 5f
            g.circle(dotX, shareCard.bottom - 64f, 5f, color, glow = Palette.alpha(color, 0.7f), glowRadius = 4f)
            g.textMid(label, if (right) x else x + 15f, shareCard.bottom - 64f, 12f, 0xFFD0D6E2.toInt(), 400, align)
            val pct = if (sum > 0 && value != null) String.format(Locale.US, "%.1f%%", value / sum * 100) else "--"
            g.textMid(pct, x, shareCard.bottom - 42f, 14f, 0xFFFFFFFF.toInt(), 800, align, tnum = true)
            g.textMid(Fmt.compact(value), x, shareCard.bottom - 22f, 11f, Palette.DIM, 400, align, tnum = true)
        }
    }
}

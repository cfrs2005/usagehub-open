package com.usagehub.app.pages

import android.graphics.Paint
import android.graphics.RectF
import com.usagehub.app.data.ConsoleRules
import com.usagehub.app.data.Fmt
import com.usagehub.app.data.UsageLogic
import com.usagehub.app.ui.Accent
import com.usagehub.app.ui.Action
import com.usagehub.app.ui.Charts
import com.usagehub.app.ui.Gfx
import com.usagehub.app.ui.Palette
import com.usagehub.app.ui.Palette.alpha
import com.usagehub.app.ui.Tone
import com.usagehub.app.ui.Widgets

object DetailPage {
    fun draw(ctx: PageCtx, name: String) {
        val g = ctx.g
        val s = ctx.s
        val days = s.nav.detailDays
        val label = if (name == "claude") "Claude" else "Codex"
        val accent = Accent.of(name)
        ctx.headerNote = updateTimes(ctx, s.dashboard?.providers?.firstOrNull { it.provider == name })
        ctx.header("$label 详情", "详细的用量、费用和上报记录", Action.Back)
        RangeControls.draw(ctx, listOf(7, 30), days, Layout.RIGHT) { Action.DetailDays(it) }

        val current = s.dashboard?.providers?.firstOrNull { it.provider == name }
        val usage = current?.tokenUsage
        val now = UsageLogic.effectiveNow(s.now, s.dashboard)
        val (ringValue, ringLabel) = ConsoleRules.ringWindow(current)
        val top = 68f
        val bottom = 142f
        Widgets.ring(g, Layout.LEFT + 38f, (top + bottom) / 2, 76f, 10f, ringValue, ringLabel, accent, 20f, 9f)

        val stats = listOf(
            Stat("累计 Token", Fmt.compact(usage?.totalTokens)),
            Stat("今日 Token", Fmt.full(usage?.todayTokens), fallback = Fmt.compact(usage?.todayTokens)),
            StatRow.cost("累计费用", usage?.totalCostUsd, usage?.totalTokens, ValueTone.RED),
            StatRow.cost("今日费用", usage?.todayCostUsd, usage?.todayTokens),
            Stat("额度重置", Fmt.remain(UsageLogic.quotaWindow(current, 10_080)?.resetsAtEpochSeconds, now)),
        )
        val boxLeft = Layout.LEFT + 86f
        val fitted = StatRow.fit(g, stats, Layout.RIGHT - boxLeft, 8f, 19f, 10f, 11.5f, 26f)
        var x = boxLeft
        stats.forEachIndexed { index, stat ->
            val box = RectF(x, top, x + fitted.widths[index], bottom)
            if (index == 2) Widgets.neonCard(g, box, accent, soft = true, radius = 9f) else detailBox(g, box)
            if (index == 2) {
                cents(g, fitted.texts[index], box.left + 12f, box.top + 37f, fitted.size, stat.tone)
                StatRow.note(g, stat, fitted.texts[index], box.left + 12f, box.top + 37f, fitted.size)
            } else StatRow.text(g, stat, fitted.texts[index], box.left + 12f, box.top + 37f, fitted.size)
            g.text(stat.label, box.left + 12f, box.top + 58f, 11.5f, Palette.MUTED, 400)
            x += fitted.widths[index] + 8f
        }

        val gap = 10f
        val half = (Layout.RIGHT - Layout.LEFT - gap) / 2
        val chartCard = RectF(Layout.LEFT, 152f, Layout.LEFT + half, 346f)
        val recordCard = RectF(chartCard.right + gap, 152f, Layout.RIGHT, 346f)
        Widgets.neonCard(g, chartCard, accent, soft = true)
        g.textMid("Token 使用趋势", chartCard.left + 12f, chartCard.top + 19f, 14f, 0xFFFFFFFF.toInt(), 800)
        legend(g, chartCard.right - 12f, chartCard.top + 19f, listOf("Token" to accent.main, "费用" to Accent.other(name).main))
        val data = s.analytics[days]
        val chart = RectF(chartCard.left + 8f, chartCard.top + 34f, chartCard.right - 8f, chartCard.bottom - 6f)
        if (data == null) {
            g.textMid("正在读取…", chart.centerX(), chart.centerY(), 12f, Palette.DIM, 400, Paint.Align.CENTER)
        } else {
            Charts.lines(g, chart, ConsoleRules.daySlots(data, name, days, s.now / 1000), name)
        }

        records(ctx, recordCard, name, accent)
    }

    /** `配额更新 07:34 · Token 更新 07:33`, each part amber once it is older than the freshness threshold. */
    fun updateTimes(ctx: PageCtx, provider: com.usagehub.app.data.ProviderUsage?): List<Pair<String, Int>> {
        val now = UsageLogic.effectiveNow(ctx.s.now, ctx.s.dashboard)
        val info = ConsoleRules.updated(provider, now, ctx.quotaStaleMillis)
        fun part(label: String, at: Long?, old: Boolean): Pair<String, Int> {
            at ?: return "$label 从未" to Palette.AMBER
            return "$label ${Fmt.moment(at, ctx.s.now, ctx.zone)}" to if (old) Palette.AMBER else Palette.DIM
        }
        return listOf(
            part("配额更新", info.quotaAt, info.quotaOld),
            " · " to Palette.DIM,
            part("Token 更新", info.tokenAt, info.tokenOld),
        )
    }

    private fun detailBox(g: Gfx, rect: RectF) {
        g.roundV(rect, 9f, 0xDB0E162A.toInt(), 0xE6090D18.toInt())
        g.stroke(rect, 9f, alpha(0xFF5082E6.toInt(), 0.3f))
    }

    /** `$14,396` + smaller `.95`, as on the detail comp. */
    private fun cents(g: Gfx, value: String, x: Float, y: Float, size: Float, tone: ValueTone) {
        val dot = value.lastIndexOf('.')
        if (dot < 0) {
            g.text(value, x, y, size, tone.color, 800, tnum = true, glow = tone.glow)
            return
        }
        val w = g.text(value.substring(0, dot), x, y, size, tone.color, 800, tnum = true, glow = tone.glow, glowRadius = 6f)
        g.text(value.substring(dot), x + w, y, size * 0.72f, tone.color, 800, tnum = true, glow = tone.glow, glowRadius = 6f)
    }

    fun legend(g: Gfx, right: Float, cy: Float, items: List<Pair<String, Int>>) {
        var x = right
        items.reversed().forEach { (label, color) ->
            val w = g.width(label, 11.5f)
            g.textMid(label, x - w, cy, 11.5f, 0xFFD0D6E2.toInt())
            val box = RectF(x - w - 14f, cy - 4.5f, x - w - 5f, cy + 4.5f)
            g.round(box, 2f, color)
            x -= w + 26f
        }
    }

    private fun records(ctx: PageCtx, c: RectF, name: String, accent: Accent) {
        val g = ctx.g
        Widgets.neonCard(g, c, accent, soft = true)
        g.textMid("最近上报记录", c.left + 12f, c.top + 19f, 14f, 0xFFFFFFFF.toInt(), 800)
        val linkLabel = if (ctx.s.nav.recordsExpanded) "收起" else "查看全部"
        val linkW = g.textMid(linkLabel, c.right - 12f, c.top + 19f, 12.5f, Palette.LINK, 700, Paint.Align.RIGHT)
        ctx.hits.add(RectF(c.right - 20f - linkW, c.top + 4f, c.right - 4f, c.top + 34f), Action.ToggleRecords)

        val cols = floatArrayOf(0f, 0.25f, 0.46f, 0.66f, 0.84f)
        val inner = c.width() - 24f
        val colX = cols.map { c.left + 12f + it * inner }
        val head = RectF(c.left + 1f, c.top + 36f, c.right - 1f, c.top + 62f)
        g.canvas.drawRect(head, g.fill.apply { shader = null; color = 0x0AFFFFFF })
        listOf("时间", "今日Token", "累计Token", "今日费用", "状态").forEachIndexed { i, label ->
            g.textMid(label, colX[i], head.centerY(), 11f, Palette.MUTED)
        }
        val items = ctx.s.history?.tokenItems.orEmpty().filter { it.provider == name }.take(ctx.s.nav.recordLimit)
        val body = RectF(c.left + 1f, head.bottom, c.right - 1f, c.bottom - 3f)
        if (items.isEmpty()) {
            val text = if (ctx.s.history == null) "正在读取…" else "还没有上报记录。"
            g.textMid(text, body.centerX(), body.centerY(), 12f, Palette.DIM, 400, Paint.Align.CENTER)
            return
        }
        val rowH = 32f
        ctx.scrollArea = RectF(body)
        ctx.scrollMax = (items.size * rowH - body.height()).coerceAtLeast(0f)
        g.canvas.save()
        g.canvas.clipRect(body)
        items.forEachIndexed { index, item ->
            val top = body.top + index * rowH - ctx.scroll
            if (top > body.bottom || top + rowH < body.top) return@forEachIndexed
            val cy = top + rowH / 2
            g.hline(c.left + 1f, c.right - 1f, top, 0x12FFFFFF)
            // Token columns are compact (307.86M, 17.66B): full digits of 10^8–10^11 do not fit a quarter-width column.
            val cells = listOf(
                Fmt.shortStamp(item.observedAt, ctx.zone),
                Fmt.compact(item.todayTokens),
                Fmt.compact(item.totalTokens),
                if (Fmt.unpriced(item.todayCostUsd, item.todayTokens)) "${Fmt.NONE} ${Fmt.NO_PRICE}" else Fmt.money(item.todayCostUsd),
            )
            cells.forEachIndexed { i, text ->
                val maxW = colX[i + 1] - colX[i] - 6f
                val size = g.fit(text, maxW, 11.5f, 9.5f, 400) ?: 9.5f
                g.textMid(g.ellipsize(text, maxW, size), colX[i], cy, size, if (i == 0) 0xFFC7CEDE.toInt() else 0xFFE6EAF2.toInt(), 400, tnum = true)
            }
            Widgets.status(g, colX[4], cy, "成功", Tone.OK, 11f)
        }
        g.canvas.restore()
    }
}

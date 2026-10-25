package com.usagehub.app.pages

import android.graphics.Paint
import android.graphics.RectF
import com.usagehub.app.data.ConsoleRules
import com.usagehub.app.data.Fmt
import com.usagehub.app.ui.Accent
import com.usagehub.app.ui.Action
import com.usagehub.app.ui.ButtonStyle
import com.usagehub.app.ui.Icon
import com.usagehub.app.ui.Icons
import com.usagehub.app.ui.Palette
import com.usagehub.app.ui.Palette.alpha
import com.usagehub.app.ui.Tone
import com.usagehub.app.ui.Widgets

object ModelsPage {
    private val filters = listOf("all" to "全部模型", "claude" to "Claude 系列", "codex" to "Codex 系列")

    fun draw(ctx: PageCtx) {
        val g = ctx.g
        val s = ctx.s
        ctx.header("模型管理", "查看各模型的用量和状态")
        val refresh = ctx.ctrlRect(Layout.RIGHT, Widgets.buttonWidth(g, "刷新", Icon.REFRESH, 13f, 15f))
        Widgets.button(g, refresh, "刷新", ButtonStyle.RED_OUTLINE, Icon.REFRESH, 13f)
        ctx.hits.add(refresh, Action.Refresh)

        val barTop = 68f
        val rects = Widgets.seg(g, Layout.LEFT, barTop, filters.map { it.second }, filters.indexOfFirst { it.first == s.nav.modelFilter }, 32f, 22f, 12.5f, 86f)
        rects.forEachIndexed { index, rect -> ctx.hits.add(rect, Action.ModelFilter(filters[index].first)) }
        val search = RectF(Layout.RIGHT - 168f, barTop, Layout.RIGHT, barTop + 32f)
        g.round(search, 8f, 0xCC080B14.toInt())
        g.stroke(search, 8f, alpha(0xFFA0AFD2.toInt(), 0.2f))
        Icons.draw(g, Icon.SEARCH, search.left + 10f, search.centerY() - 7.5f, 15f, 0xFF9AA3B5.toInt(), 1.8f)
        val query = s.nav.modelQuery
        g.textMid(
            if (query.isBlank()) "搜索模型..." else g.ellipsize(query, search.width() - 40f, 12f),
            search.left + 32f, search.centerY(), 12f, if (query.isBlank()) 0xFF8A93A5.toInt() else 0xFFFFFFFF.toInt(),
        )
        ctx.hits.add(search, Action.EditSearch)

        val providers = listOf("claude", "codex").filter { s.nav.modelFilter == "all" || s.nav.modelFilter == it }
        val gap = 10f
        val width = (Layout.RIGHT - Layout.LEFT - gap * (providers.size - 1)) / providers.size
        providers.forEachIndexed { index, name ->
            val left = Layout.LEFT + index * (width + gap)
            table(ctx, RectF(left, 110f, left + width, 346f), name)
        }
    }

    private fun table(ctx: PageCtx, c: RectF, name: String) {
        val g = ctx.g
        val accent = Accent.of(name)
        Widgets.neonCard(g, c, accent)
        Widgets.logo(g, name, c.left + 14f, c.top + 9f, 24f)
        val titleW = g.textMid(if (name == "claude") "Claude" else "Codex", c.left + 47f, c.top + 21f, 19f, 0xFFFFFFFF.toInt(), 800)
        val table = ctx.s.modelTables[name]
        // Where the 今日 / 7天 deltas really start when no snapshot sits at the boundary (sparse uploads).
        table?.let { ConsoleRules.coverageNote(it, ctx.zone) }?.let { note ->
            val room = c.right - 14f - (c.left + 47f + titleW + 12f)
            val size = g.fit(note, room, 10f, 8.5f, 400, tnum = true) ?: 8.5f
            g.textMid(g.ellipsize(note, room, size), c.right - 14f, c.top + 21f, size, Palette.DIM, 400, Paint.Align.RIGHT, tnum = true)
        }

        val wide = c.width() > 400f
        val pad = 14f
        val moreW = 14f
        val statusW = 50f
        val shareW = if (wide) 150f else 70f
        val todayW = if (wide) 90f else 48f
        val more = c.right - 8f - moreW
        val status = more - 4f - statusW
        val share = status - 8f - shareW
        val today = share - 6f - todayW
        val nameRight = today - 4f

        val headTop = c.top + 40f
        val head = RectF(c.left + 1f, headTop, c.right - 1f, headTop + 27f)
        g.canvas.drawRect(head, g.fill.apply { shader = null; color = 0x0AFFFFFF })
        g.hline(c.left + 1f, c.right - 1f, headTop, 0x1AFFFFFF)
        val hy = head.centerY()
        g.textMid("模型名称", c.left + pad, hy, 11f, Palette.MUTED)
        g.textMid(if (wide) "今日 Token" else "今日Token", today, hy, 10.5f, Palette.MUTED)
        g.textMid("7天用量", share, hy, 10.5f, Palette.MUTED)
        g.vline(status - 5f, head.top + 5f, head.bottom - 5f, 0x1FFFFFFF)
        g.textMid("状态", status, hy, 11f, Palette.MUTED)

        val rows = table?.rows.orEmpty()
        val bodyTop = head.bottom
        val available = c.bottom - 4f - bodyTop
        if (rows.isEmpty()) {
            val empty = if (ctx.s.nav.modelQuery.isNotBlank()) "没有匹配的模型" else "暂无该厂商的模型明细"
            g.textMid(empty, c.centerX(), bodyTop + available / 2, 12f, Palette.DIM, 400, Paint.Align.CENTER)
            return
        }
        val rowH = (available / rows.size).coerceIn(28f, 39f)
        val fit = (available / rowH).toInt()
        val shown = if (rows.size > fit) rows.take(fit - 1) else rows
        val nameW = nameRight - c.left - pad
        val nameSize = shown.minOf { g.fit(it.model, nameW, 12.5f, 10.5f, 600, tnum = false) ?: 10.5f }
        shown.forEachIndexed { index, row ->
            val top = bodyTop + index * rowH
            val cy = top + rowH / 2
            g.hline(c.left + 1f, c.right - 1f, top, 0x12FFFFFF)
            g.textMid(g.ellipsize(row.model, nameW, nameSize, 600), c.left + pad, cy, nameSize, Palette.TEXT, 600)
            g.textMid(row.today?.let { Fmt.compact(it, 1) } ?: Fmt.NONE, today, cy, 12f, Palette.SOFT, 400, tnum = true)
            val pctW = 28f
            Widgets.meter(g, RectF(share, cy - 3.5f, share + shareW - pctW - 6f, cy + 3.5f), row.share, accent)
            g.textMid(row.share?.let { Fmt.pct(it) } ?: Fmt.NONE, share + shareW, cy, 11.5f, 0xFFFFFFFF.toInt(), 700, Paint.Align.RIGHT, tnum = true)
            when {
                row.week == null -> Widgets.status(g, status, cy, "未知", Tone.IDLE)
                row.week > 0 -> Widgets.status(g, status, cy, "已连接", Tone.OK)
                else -> Widgets.status(g, status, cy, "空闲", Tone.IDLE)
            }
            Icons.draw(g, Icon.MORE, more, cy - 8f, 16f, 0xFFAAB2C2.toInt(), 1.7f)
            ctx.hits.add(RectF(more - 8f, top, c.right - 2f, top + rowH), Action.OpenDetail(name))
        }
        if (shown.size < rows.size) {
            val top = bodyTop + shown.size * rowH
            g.hline(c.left + 1f, c.right - 1f, top, 0x12FFFFFF)
            g.textMid("另有 ${rows.size - shown.size} 个模型", c.left + pad, top + rowH / 2, 11.5f, Palette.DIM)
        }
    }
}

package com.usagehub.app.pages

import android.graphics.Paint
import android.graphics.RectF
import com.usagehub.app.data.ConsoleRules
import com.usagehub.app.data.Fmt
import com.usagehub.app.data.ProviderUsage
import com.usagehub.app.data.UsageLogic
import com.usagehub.app.ui.Accent
import com.usagehub.app.ui.Action
import com.usagehub.app.ui.ButtonStyle
import com.usagehub.app.ui.Gfx
import com.usagehub.app.ui.Palette
import com.usagehub.app.ui.Palette.alpha
import com.usagehub.app.ui.Tone
import com.usagehub.app.ui.Widgets

/** Value colours of the four stat tiles, per the comp. */
enum class ValueTone(val color: Int, val glow: Int) {
    RED(0xFFFF4652.toInt(), 0x73FF3B47),
    BLUE(0xFF4FA8FF.toInt(), 0x662F7BFF),
    CYAN(0xFF5CC8FF.toInt(), 0x662FA0FF),
    WHITE(0xFFF1F4FA.toInt(), 0),
}

/**
 * A label + value pair whose value shrinks to fit, then falls back to a compact form. [note] is a small dim remark
 * drawn right after the value (e.g. 无价格 next to —) so an empty value explains itself.
 */
class Stat(val label: String, val value: String, val tone: ValueTone = ValueTone.WHITE, val fallback: String? = null, val note: String? = null)

object StatRow {
    class Fitted(val widths: List<Float>, val size: Float, val texts: List<String>)

    /**
     * Lays out a row of tiles so that every label shows in full and all values share one font size — the largest
     * in [min]..[max] that fits once tile widths follow their content. Values with a compact fallback switch to it
     * only when even [min] would not fit. Leftover width is spread in proportion to each tile.
     */
    fun fit(g: Gfx, stats: List<Stat>, width: Float, gap: Float, max: Float, min: Float, labelSize: Float, pad: Float): Fitted {
        val free = width - gap * (stats.size - 1)
        val mins = stats.map { g.width(it.label, labelSize) + pad }
        val notes = stats.map { stat -> stat.note?.let { g.width(it, NOTE_SIZE) + NOTE_GAP } ?: 0f }
        fun need(texts: List<String>, size: Float) = texts.mapIndexed { i, t -> maxOf(mins[i], g.width(t, size, 800, true) + notes[i] + pad) }
        var texts = stats.map { it.value }
        var size = max
        while (size > min && need(texts, size).sum() > free) size -= 0.5f
        if (need(texts, size).sum() > free) {
            texts = stats.map { it.fallback ?: it.value }
            size = max
            while (size > min && need(texts, size).sum() > free) size -= 0.5f
        }
        val wants = need(texts, size)
        val total = wants.sum()
        return Fitted(wants.map { it / total * free }, size, texts)
    }

    const val NOTE_SIZE = 9.5f
    const val NOTE_GAP = 4f

    fun text(g: Gfx, stat: Stat, text: String, x: Float, y: Float, size: Float, align: Paint.Align = Paint.Align.LEFT) {
        g.text(text, x, y, size, stat.tone.color, 800, align, tnum = true, glow = stat.tone.glow, glowRadius = 6f, spacing = -0.01f)
        if (align == Paint.Align.LEFT) note(g, stat, text, x, y, size)
    }

    /** The stat's [Stat.note] right after a left-aligned value drawn at ([x], [y]). */
    fun note(g: Gfx, stat: Stat, text: String, x: Float, y: Float, size: Float) {
        val note = stat.note ?: return
        g.text(note, x + g.width(text, size, 800, true) + NOTE_GAP, y, NOTE_SIZE, Palette.DIM, 400)
    }

    /** 今日费用 / 累计费用 tile: — plus 无价格 when tokens are non-zero but the cost is 0 (no price for the model). */
    fun cost(label: String, cost: Double?, tokens: Long?, tone: ValueTone = ValueTone.WHITE): Stat =
        Stat(label, Fmt.cost(cost, tokens), tone, note = if (Fmt.unpriced(cost, tokens)) Fmt.NO_PRICE else null)
}

object HomePage {
    private val tones = mapOf(
        "claude" to listOf(ValueTone.RED, ValueTone.RED, ValueTone.BLUE, ValueTone.RED),
        "codex" to listOf(ValueTone.WHITE, ValueTone.CYAN, ValueTone.CYAN, ValueTone.CYAN),
    )

    fun draw(ctx: PageCtx) {
        val g = ctx.g
        val s = ctx.s
        ctx.header("AI 用量看板", "实时 · 简洁 · 高效")
        val buttonW = Widgets.buttonWidth(g, "刷新", null, 14f, 16f)
        val refresh = ctx.ctrlRect(Layout.RIGHT, buttonW)
        Widgets.button(g, refresh, "刷新", ButtonStyle.RED_SOLID, size = 14f)
        ctx.hits.add(refresh, Action.Refresh)
        val clock = Fmt.clock(s.now, ctx.zone)
        val chip = ctx.ctrlRect(refresh.left - 9f, g.width(clock, 12f, 400, true) + 26f)
        g.round(chip, 8f, 0xC7080B14.toInt())
        g.stroke(chip, 8f, alpha(0xFFA0AFD2.toInt(), 0.22f))
        g.textMid(clock, chip.centerX(), chip.centerY(), 12f, 0xFFE3E8F1.toInt(), 400, Paint.Align.CENTER, tnum = true)

        val gap = 10f
        val width = (Layout.RIGHT - Layout.LEFT - gap) / 2
        listOf("claude", "codex").forEachIndexed { index, name ->
            val left = Layout.LEFT + index * (width + gap)
            val rect = RectF(left, 76f, left + width, 341f)
            card(ctx, rect, name, s.dashboard?.providers?.firstOrNull { it.provider == name })
        }
        g.text("费用为 API 等价估算，非账单金额", Layout.RIGHT, 354f, 8.5f, 0xFF5D6576.toInt(), 400, Paint.Align.RIGHT)
    }

    fun pill(ctx: PageCtx, x: Float, cy: Float, provider: ProviderUsage?) {
        when (ConsoleRules.status(provider, UsageLogic.effectiveNow(ctx.s.now, ctx.s.dashboard), ctx.quotaStaleMillis)) {
            ConsoleRules.Status.OK -> Widgets.pill(ctx.g, x, cy, "已连接", Tone.OK)
            ConsoleRules.Status.STALE -> Widgets.pill(ctx.g, x, cy, "数据过期", Tone.WARN)
            ConsoleRules.Status.MISSING -> Widgets.pill(ctx.g, x, cy, "无数据", Tone.IDLE)
        }
    }

    /** Right-aligned `更新于 07:34`, or amber `上次 10/23 11:52` when the vendor's data is stale. */
    fun updatedNote(ctx: PageCtx, provider: ProviderUsage?, right: Float, cy: Float) {
        val now = UsageLogic.effectiveNow(ctx.s.now, ctx.s.dashboard)
        val info = ConsoleRules.updated(provider, now, ctx.quotaStaleMillis)
        val at = info.shownAt ?: return
        val stale = ConsoleRules.status(provider, now, ctx.quotaStaleMillis) == ConsoleRules.Status.STALE
        val text = if (stale) "上次 ${Fmt.shortStamp(at / 1000, ctx.zone)}" else "更新于 ${Fmt.moment(at, ctx.s.now, ctx.zone)}"
        ctx.g.textMid(text, right, cy, 9.5f, if (info.old) Palette.AMBER else Palette.DIM, if (info.old) 600 else 400, Paint.Align.RIGHT, tnum = true)
    }

    private fun card(ctx: PageCtx, c: RectF, name: String, provider: ProviderUsage?) {
        val g = ctx.g
        val accent = Accent.of(name)
        Widgets.neonCard(g, c, accent)
        ctx.hits.add(c, Action.OpenDetail(name))

        Widgets.logo(g, name, c.left + 14f, c.top + 15f, 25f)
        val title = if (name == "claude") "Claude" else "Codex"
        val titleW = g.textMid(title, c.left + 48f, c.top + 28f, 19f, 0xFFFFFFFF.toInt(), 800)
        pill(ctx, c.left + 48f + titleW + 12f, c.top + 28f, provider)
        updatedNote(ctx, provider, c.right - 14f, c.top + 28f)

        val (ringValue, ringLabel) = ConsoleRules.ringWindow(provider)
        val ringY = c.top + 121f
        Widgets.ring(g, c.left + 66f, ringY, 106f, 11f, ringValue, ringLabel, accent, 31f, 11.5f)

        val now = UsageLogic.effectiveNow(ctx.s.now, ctx.s.dashboard)
        val five = UsageLogic.quotaWindow(provider, 300)
        val week = UsageLogic.quotaWindow(provider, 10_080)
        val qx = c.left + 133f
        val qr = c.right - 14f
        quota(g, "5 小时用量", five?.usedPercent, qx, qr, c.top + 72f, accent, missing = windowMissing(name, 300))
        quota(g, "7 天用量", week?.usedPercent, qx, qr, c.top + 122f, accent, missing = windowMissing(name, 10_080))
        val left = if (week?.resetsAtEpochSeconds != null) "还剩 ${Fmt.remain(week.resetsAtEpochSeconds, now)}" else "重置时间未知"
        g.textMid(left, qr, c.top + 168f, 12.5f, Palette.SOFT, 400, Paint.Align.RIGHT)

        val usage = provider?.tokenUsage
        val tone = tones.getValue(name)
        val stats = listOf(
            Stat("今日 Token", Fmt.full(usage?.todayTokens), tone[0], Fmt.compact(usage?.todayTokens)),
            Stat("累计 Token", Fmt.compact(usage?.totalTokens), tone[1]),
            StatRow.cost("今日费用", usage?.todayCostUsd, usage?.todayTokens, tone[2]),
            StatRow.cost("累计费用", usage?.totalCostUsd, usage?.totalTokens, tone[3]),
        )
        val top = c.bottom - 69f
        val inner = c.width() - 22f
        val fitted = StatRow.fit(g, stats, inner, 5f, 17f, 10f, 10.5f, 13f)
        var x = c.left + 11f
        stats.forEachIndexed { index, stat ->
            val box = RectF(x, top, x + fitted.widths[index], c.bottom - 11f)
            Widgets.tile(g, box)
            g.text(stat.label, box.left + 7f, box.top + 21f, 10.5f, Palette.MUTED, 400)
            StatRow.text(g, stat, fitted.texts[index], box.left + 7f, box.top + 45f, fitted.size)
            x += fitted.widths[index] + 5f
        }
    }

    /**
     * Shown instead of an empty bar + `--` when the vendor sends no value for a window. Codex's app-server only has the
     * weekly bucket, so its 5-hour window is "not provided"; anything else is "not returned" this time.
     */
    fun windowMissing(name: String, minutes: Int): String {
        val label = if (minutes == 300) "5 小时窗口" else "7 天窗口"
        return if (name == "codex" && minutes == 300) "官方未提供 $label" else "官方未返回 $label"
    }

    private fun quota(g: Gfx, label: String, value: Double?, x: Float, right: Float, top: Float, accent: Accent, missing: String? = null) {
        g.textMid(label, x, top, 12.5f, Palette.SOFT, 400)
        val cy = top + 19f
        if (value == null && missing != null) {
            g.textMid(g.ellipsize(missing, right - x, 11.5f), x, cy, 11.5f, Palette.DIM, 400)
            return
        }
        Widgets.meter(g, RectF(x, cy - 4f, right - 50f, cy + 4f), value, accent)
        g.textMid(Fmt.pct(value), right, cy, 15.5f, 0xFFFFFFFF.toInt(), 800, Paint.Align.RIGHT, tnum = true)
    }
}

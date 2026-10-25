package com.usagehub.app.ui

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.usagehub.app.data.AnalyticsPoint
import com.usagehub.app.data.Fmt
import com.usagehub.app.ui.Palette.alpha
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

typealias Slot = Pair<Long, AnalyticsPoint?>

/** Chart colours per vendor (same as the web console charts). */
private class Ink(val top: Int, val bottom: Int, val cap: Int, val line: Int, val glow: Int)

private fun ink(provider: String): Ink = if (provider == "codex") {
    Ink(0xFF3D8BFF.toInt(), 0xD9123FAE.toInt(), 0xFF9CC6FF.toInt(), 0xFF2F7BFF.toInt(), 0x8C2F7BFF.toInt())
} else {
    Ink(0xFFFF4A55.toInt(), 0xD9A8121C.toInt(), 0xFFFF9AA0.toInt(), 0xFFFF3B47.toInt(), 0x8CFF3B47.toInt())
}

object Charts {
    private const val TICK_SIZE = 10.5f
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private class Frame(val left: Float, val right: Float, val top: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private fun axis(g: Gfx, ticks: List<Double>, f: Frame, format: (Double) -> String, right: Boolean = false) {
        val top = ticks.last().takeIf { it > 0 } ?: 1.0
        ticks.forEach { tick ->
            val y = f.bottom - (tick / top).toFloat() * f.height
            if (!right) g.hline(f.left, f.right, y, 0x1AFFFFFF, 1f, dashed = true)
            g.text(
                format(tick), if (right) f.right + 6f else f.left - 6f, y + 3.5f, TICK_SIZE, Palette.TICK, 400,
                if (right) Paint.Align.LEFT else Paint.Align.RIGHT, tnum = true,
            )
        }
    }

    private fun xLabels(g: Gfx, starts: List<Long>, f: Frame, slot: Float, y: Float) {
        val fit = max(2, floor(f.width / 36f).toInt())
        val step = max(1, ceil(starts.size / fit.toFloat()).toInt())
        starts.forEachIndexed { index, start ->
            if ((starts.size - 1 - index) % step == 0) {
                g.text(Fmt.day(start, zone), f.left + index * slot + slot / 2, y, TICK_SIZE, Palette.TICK, 400, Paint.Align.CENTER, tnum = true)
            }
        }
    }

    private fun bar(g: Gfx, x: Float, y: Float, w: Float, h: Float, ink: Ink, cap: Boolean, glow: Float) {
        val rect = RectF(x, y, x + w, y + h)
        val r = min(1.5f, w / 3f)
        g.fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, ink.top, ink.bottom, Shader.TileMode.CLAMP)
        g.fill.color = 0xFFFFFFFF.toInt()
        g.fill.setShadowLayer(glow, 0f, 0f, ink.glow)
        g.canvas.drawRoundRect(rect, r, r, g.fill)
        g.fill.clearShadowLayer()
        g.fill.shader = null
        if (cap && h > 6f) g.round(RectF(x, y, x + w, y + min(5f, h * 0.14f)), r, alpha(ink.cap, 0.55f))
    }

    /** One vendor's daily token bars with a Y axis and dashed grid. Missing days stay empty (never drawn as 0). */
    fun bars(g: Gfx, box: RectF, slots: List<Slot>, provider: String) {
        val ink = ink(provider)
        val ticks = Fmt.niceTicks(slots.maxOfOrNull { it.second?.tokens ?: 0.0 } ?: 0.0)
        val labelW = ticks.maxOf { g.width(Fmt.tokenTick(it), TICK_SIZE, 400, true) }
        val f = Frame(box.left + labelW + 8f, box.right - 4f, box.top + 6f, box.bottom - 18f)
        axis(g, ticks, f, Fmt::tokenTick)
        val top = ticks.last()
        val slot = f.width / max(1, slots.size)
        val barW = when {
            slots.size > 40 -> max(1.5f, slot * 0.62f)
            slots.size > 10 -> slot * 0.55f
            else -> min(18f, slot * 0.42f)
        }
        slots.forEachIndexed { index, (_, point) ->
            point ?: return@forEachIndexed
            val h = max(1f, (point.tokens / top).toFloat() * f.height)
            bar(g, f.left + index * slot + (slot - barW) / 2, f.bottom - h, barW, h, ink, true, 4f)
        }
        xLabels(g, slots.map { it.first }, f, slot, box.bottom - 3f)
    }

    /** Claude vs Codex side by side per day, for tokens or cost. */
    fun groupedBars(g: Gfx, box: RectF, claude: List<Slot>, codex: List<Slot>, cost: Boolean) {
        val value: (AnalyticsPoint?) -> Double = { if (it == null) 0.0 else if (cost) it.costUsd else it.tokens }
        val format: (Double) -> String = if (cost) Fmt::moneyTick else Fmt::tokenTick
        val ticks = Fmt.niceTicks(claude.indices.maxOfOrNull { max(value(claude[it].second), value(codex.getOrNull(it)?.second)) } ?: 0.0)
        val labelW = ticks.maxOf { g.width(format(it), TICK_SIZE, 400, true) }
        val f = Frame(box.left + labelW + 8f, box.right - 4f, box.top + 6f, box.bottom - 18f)
        axis(g, ticks, f, format)
        val top = ticks.last()
        val slot = f.width / max(1, claude.size)
        val barW = max(1.5f, min(11f, slot * 0.3f))
        claude.indices.forEach { index ->
            val center = f.left + index * slot + slot / 2
            listOf("claude" to claude[index].second, "codex" to codex.getOrNull(index)?.second).forEachIndexed { side, (name, point) ->
                point ?: return@forEachIndexed
                val h = max(1f, (value(point) / top).toFloat() * f.height)
                val x = if (side == 0) center - barW - 0.8f else center + 0.8f
                bar(g, x, f.bottom - h, barW, h, ink(name), false, 3f)
            }
        }
        xLabels(g, claude.map { it.first }, f, slot, box.bottom - 3f)
    }

    private fun smooth(points: List<Pair<Float, Float>>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].first, points[0].second)
        for (index in 1 until points.size) {
            val p0 = points.getOrNull(index - 2) ?: points[index - 1]
            val p1 = points[index - 1]
            val p2 = points[index]
            val p3 = points.getOrNull(index + 1) ?: p2
            path.cubicTo(
                p1.first + (p2.first - p0.first) / 6f, p1.second + (p2.second - p0.second) / 6f,
                p2.first - (p3.first - p1.first) / 6f, p2.second - (p3.second - p1.second) / 6f,
                p2.first, p2.second,
            )
        }
        return path
    }

    /**
     * Token line (vendor colour, left axis) plus cost line (other vendor's colour, its own right axis). We have no
     * input/output split, so the second series is cost — labelled as such in the legend.
     */
    fun lines(g: Gfx, box: RectF, slots: List<Slot>, provider: String) {
        val tokenInk = ink(provider)
        val costInk = ink(if (provider == "codex") "claude" else "codex")
        val tokenTicks = Fmt.niceTicks(slots.maxOfOrNull { it.second?.tokens ?: 0.0 } ?: 0.0)
        val costTicks = Fmt.niceTicks((slots.maxOfOrNull { it.second?.costUsd ?: 0.0 } ?: 0.0) * 1.5)
        val leftW = tokenTicks.maxOf { g.width(Fmt.tokenTick(it), TICK_SIZE, 400, true) }
        val rightW = costTicks.maxOf { g.width(Fmt.moneyTick(it), TICK_SIZE, 400, true) }
        val f = Frame(box.left + leftW + 8f, box.right - rightW - 8f, box.top + 8f, box.bottom - 18f)
        axis(g, tokenTicks, f, Fmt::tokenTick)
        axis(g, costTicks, f, Fmt::moneyTick, right = true)
        val slot = f.width / max(1, slots.size)
        val series = listOf(
            Triple(costInk, costTicks.last()) { p: AnalyticsPoint -> p.costUsd },
            Triple(tokenInk, tokenTicks.last()) { p: AnalyticsPoint -> p.tokens },
        )
        val dots = slots.size <= 31
        series.forEach { (ink, top, pick) ->
            val pts = slots.mapIndexedNotNull { index, (_, point) ->
                point?.let { (f.left + index * slot + slot / 2) to (f.bottom - (pick(it) / top).toFloat() * f.height) }
            }
            if (pts.isEmpty()) return@forEach
            val curve = smooth(pts)
            val area = Path(curve).apply { lineTo(pts.last().first, f.bottom); lineTo(pts.first().first, f.bottom); close() }
            g.fill.shader = LinearGradient(0f, f.top, 0f, f.bottom, alpha(ink.line, 0.42f), alpha(ink.line, 0f), Shader.TileMode.CLAMP)
            g.fill.color = 0xFFFFFFFF.toInt()
            g.canvas.drawPath(area, g.fill)
            g.fill.shader = null
            g.path(curve, ink.line, 2f, glow = ink.glow, glowRadius = 4f)
            if (dots) pts.forEach { (x, y) ->
                g.circle(x, y, 2.6f, ink.line)
                g.ring(x, y, 2.6f, 0xBFFFFFFF.toInt(), 0.8f)
            }
        }
        xLabels(g, slots.map { it.first }, f, slot, box.bottom - 3f)
    }

    /** Share donut with a centre figure. */
    fun donut(g: Gfx, cx: Float, cy: Float, radius: Float, stroke: Float, segments: List<Pair<Double, Int>>, centre: String, caption: String) {
        g.ring(cx, cy, radius, 0x12FFFFFF, stroke)
        val total = segments.sumOf { it.first }
        if (total > 0) {
            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            var start = -90f
            g.line.pathEffect = null
            g.line.shader = null
            g.line.strokeWidth = stroke
            g.line.strokeCap = Paint.Cap.BUTT
            segments.forEach { (value, color) ->
                val sweep = (value / total * 360.0).toFloat()
                g.line.color = color
                g.line.setShadowLayer(4f, 0f, 0f, alpha(color, 0.8f))
                g.canvas.drawArc(rect, start, sweep, false, g.line)
                start += sweep
            }
            g.line.clearShadowLayer()
        }
        g.text(centre, cx, cy + 4f, 21f, Palette.TEXT, 800, Paint.Align.CENTER, tnum = true)
        g.text(caption, cx, cy + 20f, 11f, 0xFFC7CEDE.toInt(), 400, Paint.Align.CENTER)
    }
}

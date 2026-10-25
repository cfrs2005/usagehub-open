package com.usagehub.app.ui

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.usagehub.app.ui.Palette.alpha

enum class Icon { HOME, USAGE, MODELS, STATS, SETTINGS, REFRESH, SYNC, CALENDAR, DOWNLOAD, SEARCH, PLUS, EYE, BACK, MORE }

enum class Tone { OK, WARN, IDLE, BAD }

enum class ButtonStyle { RED_SOLID, BLUE_SOLID, RED_OUTLINE, OUTLINE, DARK }

/** Hand-drawn line icons on a 20x20 grid (same shapes as the web console). */
object Icons {
    private val cache = HashMap<Icon, Path>()

    private fun build(icon: Icon): Path = Path().apply {
        when (icon) {
            Icon.HOME -> {
                moveTo(3.5f, 9.2f); lineTo(10f, 3.8f); lineTo(16.5f, 9.2f); lineTo(16.5f, 16.8f); lineTo(12.3f, 16.8f)
                lineTo(12.3f, 12.4f); lineTo(7.7f, 12.4f); lineTo(7.7f, 16.8f); lineTo(3.5f, 16.8f); close()
            }
            Icon.USAGE -> {
                moveTo(3.6f, 10.2f); lineTo(10f, 4.6f); lineTo(16.4f, 10.2f); lineTo(16.4f, 16.6f); lineTo(3.6f, 16.6f); close()
                addCircle(10f, 12.2f, 2.2f, Path.Direction.CW)
            }
            Icon.MODELS, Icon.STATS -> {
                moveTo(3.5f, 16.5f); lineTo(16.5f, 16.5f)
                moveTo(5f, 16.5f); lineTo(5f, 12.3f); moveTo(8.3f, 16.5f); lineTo(8.3f, 9.6f)
                moveTo(11.6f, 16.5f); lineTo(11.6f, 7.4f); moveTo(15f, 16.5f); lineTo(15f, 4.6f)
                moveTo(4.4f, 9.4f); lineTo(8f, 6.4f); lineTo(11.4f, 8f); lineTo(15.8f, 4.2f)
            }
            Icon.SETTINGS -> {
                addRoundRect(RectF(3.6f, 4.2f, 16.4f, 15.8f), 2.2f, 2.2f, Path.Direction.CW)
                moveTo(3.6f, 9.2f); lineTo(16.4f, 9.2f); moveTo(3.6f, 12.6f); lineTo(16.4f, 12.6f)
                moveTo(10f, 4.2f); lineTo(10f, 2.8f); moveTo(10f, 17.2f); lineTo(10f, 15.8f)
            }
            Icon.REFRESH -> {
                addArc(RectF(4f, 4f, 16f, 16f), 0f, 314f)
                moveTo(16f, 3.8f); lineTo(16f, 7.2f); lineTo(12.6f, 7.2f)
            }
            Icon.SYNC -> {
                addCircle(10f, 10f, 6.6f, Path.Direction.CW)
                addArc(RectF(6.8f, 6.8f, 13.2f, 13.2f), 190f, 120f)
                addArc(RectF(6.8f, 6.8f, 13.2f, 13.2f), 10f, 120f)
                moveTo(12.9f, 6.4f); lineTo(12.9f, 8.2f); lineTo(11.1f, 8.2f)
                moveTo(7.1f, 13.6f); lineTo(7.1f, 11.8f); lineTo(8.9f, 11.8f)
            }
            Icon.CALENDAR -> {
                addRoundRect(RectF(3.5f, 4.5f, 16.5f, 16.5f), 2f, 2f, Path.Direction.CW)
                moveTo(3.5f, 8.4f); lineTo(16.5f, 8.4f); moveTo(7f, 3f); lineTo(7f, 6f); moveTo(13f, 3f); lineTo(13f, 6f)
                addRect(RectF(8.6f, 11.4f, 11.4f, 14.2f), Path.Direction.CW)
            }
            Icon.DOWNLOAD -> {
                moveTo(10f, 3.6f); lineTo(10f, 12.2f); moveTo(6.6f, 9f); lineTo(10f, 12.4f); lineTo(13.4f, 9f)
                moveTo(4f, 15.8f); lineTo(16f, 15.8f)
            }
            Icon.SEARCH -> {
                addCircle(9f, 9f, 5f, Path.Direction.CW)
                moveTo(12.8f, 12.8f); lineTo(16.4f, 16.4f)
            }
            Icon.PLUS -> { moveTo(10f, 4f); lineTo(10f, 16f); moveTo(4f, 10f); lineTo(16f, 10f) }
            Icon.EYE -> {
                moveTo(2.6f, 10f); quadTo(10f, 0.5f, 17.4f, 10f); quadTo(10f, 19.5f, 2.6f, 10f); close()
                addCircle(10f, 10f, 2.3f, Path.Direction.CW)
            }
            Icon.BACK -> { moveTo(12f, 4f); lineTo(6f, 10f); lineTo(12f, 16f) }
            Icon.MORE -> {
                addCircle(5f, 10f, 0.9f, Path.Direction.CW)
                addCircle(10f, 10f, 0.9f, Path.Direction.CW)
                addCircle(15f, 10f, 0.9f, Path.Direction.CW)
            }
        }
    }

    fun draw(g: Gfx, icon: Icon, x: Float, y: Float, size: Float, color: Int, stroke: Float = 1.7f) {
        val path = cache.getOrPut(icon) { build(icon) }
        g.canvas.save()
        g.canvas.translate(x, y)
        g.canvas.scale(size / 20f, size / 20f)
        g.path(path, color, stroke)
        g.canvas.restore()
    }
}

object Widgets {
    private val logoOuter = Path().apply { moveTo(12f, 2.6f); lineTo(22.2f, 20.4f); lineTo(1.8f, 20.4f); close() }
    private val logoInner = Path().apply { moveTo(12f, 11.2f); lineTo(15.6f, 17.4f); lineTo(8.4f, 17.4f); close() }
    private val arcRect = RectF()
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Vendor mark: outlined triangle with a filled inner triangle, in the vendor gradient, glowing. */
    fun logo(g: Gfx, provider: String, x: Float, y: Float, size: Float) {
        val accent = Accent.of(provider)
        val c = g.canvas
        c.save()
        c.translate(x, y)
        c.scale(size / 24f, size / 24f)
        val shader = LinearGradient(0f, 0f, 0f, 24f, accent.logoFrom, accent.logoTo, Shader.TileMode.CLAMP)
        g.line.shader = shader
        g.line.strokeWidth = 3.1f
        g.line.strokeJoin = Paint.Join.ROUND
        g.line.pathEffect = null
        g.line.color = 0xFFFFFFFF.toInt()
        g.line.setShadowLayer(4f, 0f, 0f, alpha(accent.main, 0.7f))
        c.drawPath(logoOuter, g.line)
        g.line.clearShadowLayer()
        g.line.shader = null
        g.line.strokeJoin = Paint.Join.MITER
        g.fill.shader = shader
        g.fill.color = 0xFFFFFFFF.toInt()
        c.drawPath(logoInner, g.fill)
        g.fill.shader = null
        c.restore()
    }

    private fun toneColor(tone: Tone): Int = when (tone) {
        Tone.OK -> Palette.GREEN
        Tone.WARN -> Palette.AMBER
        Tone.BAD -> 0xFFFF6B74.toInt()
        Tone.IDLE -> 0xFF9AA3B5.toInt()
    }

    /** Status capsule `◯ 已连接`. Returns its width. */
    fun pill(g: Gfx, x: Float, cy: Float, label: String, tone: Tone, height: Float = 22f, size: Float = 11.5f): Float {
        val color = toneColor(tone)
        val w = g.width(label, size, 700) + 33f
        val rect = RectF(x, cy - height / 2, x + w, cy + height / 2)
        g.round(rect, height / 2, alpha(color, if (tone == Tone.IDLE) 0.06f else 0.13f))
        g.stroke(rect, height / 2, alpha(color, if (tone == Tone.IDLE) 0.18f else 0.5f))
        dot(g, x + 13f, cy, tone)
        g.textMid(label, x + 22f, cy, size, color, 700)
        return w
    }

    /** Dot + label without the capsule (table cells). */
    fun status(g: Gfx, x: Float, cy: Float, label: String, tone: Tone, size: Float = 11.5f) {
        dot(g, x + 4f, cy, tone)
        g.textMid(label, x + 12f, cy, size, toneColor(tone), 600)
    }

    private fun dot(g: Gfx, cx: Float, cy: Float, tone: Tone) {
        val color = toneColor(tone)
        if (tone == Tone.OK) g.circle(cx, cy, 4.2f, alpha(color, 0.25f), glow = alpha(color, 0.8f), glowRadius = 3f)
        g.ring(cx, cy, 3f, color, 1.8f)
    }

    /** Segmented control; returns one rect per item (for hit testing). */
    fun seg(g: Gfx, x: Float, y: Float, labels: List<String>, selected: Int, height: Float = 30f, pad: Float = 14f, size: Float = 12.5f, minItem: Float = 0f): List<RectF> {
        val widths = labels.map { maxOf(minItem, g.width(it, size, 700) + pad * 2) }
        val outer = RectF(x, y, x + widths.sum() + 4f + (labels.size - 1) * 2f, y + height)
        g.round(outer, 9f, 0xCC080B14.toInt())
        g.stroke(outer, 9f, alpha(0xFFA0AFD2.toInt(), 0.2f))
        var left = x + 2f
        return labels.mapIndexed { index, label ->
            val rect = RectF(left, y + 2f, left + widths[index], y + height - 2f)
            if (index == selected) {
                g.halo(rect, 7f, alpha(Palette.BLUE, 0.6f), 6f, 1f)
                g.roundV(rect, 7f, 0xFF3D8BFF.toInt(), 0xFF1D5FE0.toInt())
            }
            g.textMid(label, rect.centerX(), rect.centerY(), size, if (index == selected) 0xFFFFFFFF.toInt() else 0xFFD7DCE6.toInt(), if (index == selected) 700 else 600, Paint.Align.CENTER)
            left += widths[index] + 2f
            rect
        }
    }

    fun segWidth(g: Gfx, labels: List<String>, pad: Float = 14f, size: Float = 12.5f, minItem: Float = 0f): Float =
        labels.sumOf { maxOf(minItem, g.width(it, size, 700) + pad * 2).toDouble() }.toFloat() + 4f + (labels.size - 1) * 2f

    fun buttonWidth(g: Gfx, label: String, icon: Icon?, size: Float = 12.5f, pad: Float = 14f): Float =
        g.width(label, size, 700) + pad * 2 + if (icon != null) 19f else 0f

    fun button(g: Gfx, rect: RectF, label: String, style: ButtonStyle, icon: Icon? = null, size: Float = 12.5f, pressed: Boolean = false) {
        when (style) {
            ButtonStyle.RED_SOLID -> {
                g.halo(rect, 8f, alpha(Palette.RED, 0.55f), 7f, 1f)
                g.roundV(rect, 8f, 0xFFE8303C.toInt(), 0xFF9D0F19.toInt())
                g.stroke(rect, 8f, alpha(0xFFFF6E76.toInt(), 0.75f))
            }
            ButtonStyle.BLUE_SOLID -> {
                g.halo(rect, 8f, alpha(Palette.BLUE, 0.55f), 7f, 1f)
                g.roundV(rect, 8f, 0xFF3D8BFF.toInt(), 0xFF1652D6.toInt())
                g.stroke(rect, 8f, alpha(0xFF78AFFF.toInt(), 0.7f))
            }
            ButtonStyle.RED_OUTLINE -> {
                g.halo(rect, 8f, alpha(Palette.RED, 0.3f), 5f, 1f)
                g.round(rect, 8f, 0x99080B14.toInt())
                g.stroke(rect, 8f, alpha(Palette.RED, 0.65f))
            }
            ButtonStyle.OUTLINE -> {
                g.round(rect, 8f, 0x99080B14.toInt())
                g.stroke(rect, 8f, alpha(0xFFA0AFD2.toInt(), 0.25f))
            }
            ButtonStyle.DARK -> {
                g.round(rect, 7f, 0xE6222838.toInt())
                g.stroke(rect, 7f, alpha(0xFFFFFFFF.toInt(), 0.1f))
            }
        }
        if (pressed) g.round(rect, 8f, 0x33FFFFFF)
        val textW = g.width(label, size, 700)
        val total = textW + if (icon != null) 19f else 0f
        var left = rect.centerX() - total / 2
        if (icon != null) {
            Icons.draw(g, icon, left, rect.centerY() - 7.5f, 15f, 0xFFFFFFFF.toInt(), 2f)
            left += 19f
        }
        g.textMid(label, left, rect.centerY(), size, 0xFFFFFFFF.toInt(), 700)
    }

    fun datePill(g: Gfx, rect: RectF, value: String) {
        g.round(rect, 8f, 0xC7080B14.toInt())
        g.stroke(rect, 8f, alpha(0xFFA0AFD2.toInt(), 0.22f))
        g.textMid(value, rect.left + 12f, rect.centerY(), 12f, 0xFFE3E8F1.toInt(), 400, tnum = true)
        Icons.draw(g, Icon.CALENDAR, rect.right - 27f, rect.centerY() - 8f, 16f, 0xFFDFE4EE.toInt(), 1.7f)
    }

    fun datePillWidth(g: Gfx, value: String): Float = g.width(value, 12f, 400, true) + 24f + 25f

    /** Quota ring. [value] null draws `--` over an empty track. */
    fun ring(
        g: Gfx, cx: Float, cy: Float, diameter: Float, stroke: Float, value: Double?, label: String, accent: Accent,
        valueSize: Float, labelSize: Float,
    ) {
        val r = diameter / 2 - stroke / 2
        g.ring(cx, cy, r, alpha(accent.main, 0.17f), stroke)
        val safe = value?.coerceIn(0.0, 100.0) ?: 0.0
        if (value != null && safe > 0) {
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            arcPaint.strokeWidth = stroke
            arcPaint.strokeCap = Paint.Cap.BUTT
            arcPaint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, accent.light, accent.main, Shader.TileMode.CLAMP)
            arcPaint.setShadowLayer(5f, 0f, 0f, alpha(accent.main, 0.85f))
            g.canvas.drawArc(arcRect, -90f, (safe * 3.6).toFloat(), false, arcPaint)
            arcPaint.clearShadowLayer()
        }
        val numberY = cy + valueSize * 0.18f
        if (value == null) {
            g.text("--", cx, numberY, valueSize, Palette.TEXT, 800, Paint.Align.CENTER)
        } else {
            val number = Math.round(safe).toString()
            val small = valueSize * 0.55f
            val nw = g.width(number, valueSize, 800, true)
            val pw = g.width("%", small, 800)
            val left = cx - (nw + pw + 1f) / 2
            g.text(number, left, numberY, valueSize, Palette.TEXT, 800, tnum = true)
            g.text("%", left + nw + 1f, numberY, small, Palette.TEXT, 800)
        }
        g.text(label, cx, numberY + labelSize + 5f, labelSize, 0xFFD0D6E2.toInt(), 400, Paint.Align.CENTER)
    }

    /** Thin quota / share bar. null draws a faint 5% stub. */
    fun meter(g: Gfx, rect: RectF, value: Double?, accent: Accent) {
        val radius = rect.height() / 2
        g.round(rect, radius, 0x14FFFFFF)
        val safe = (value ?: 0.0).coerceIn(0.0, 100.0)
        val frac = if (value == null) 0.05f else maxOf(safe.toFloat(), 3f) / 100f
        val bar = RectF(rect.left, rect.top, rect.left + rect.width() * frac, rect.bottom)
        if (value != null) g.halo(bar, radius, alpha(accent.main, 0.75f), 5f, 1f)
        g.fill.shader = LinearGradient(bar.left, 0f, bar.right, 0f, accent.light, accent.main, Shader.TileMode.CLAMP)
        g.fill.alpha = if (value == null) 140 else 255
        g.canvas.drawRoundRect(bar, radius, radius, g.fill)
        g.fill.shader = null
        g.fill.alpha = 255
    }

    /** Neon vendor card: outer halo, translucent tinted body, inner glow, crisp border. */
    fun neonCard(g: Gfx, rect: RectF, accent: Accent, soft: Boolean = false, radius: Float = 10f) {
        g.halo(rect, radius, alpha(accent.main, if (soft) 0.3f else 0.45f), if (soft) 7f else 10f, 1.5f)
        g.roundV(rect, radius, accent.cardTop, accent.cardBottom, 0.7f)
        g.canvas.save()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        g.canvas.clipPath(clip)
        g.halo(rect, radius, alpha(accent.main, if (soft) 0.18f else 0.3f), if (soft) 9f else 12f, 3f)
        g.canvas.restore()
        g.stroke(rect, radius, alpha(accent.main, if (soft) 0.55f else 0.8f), 1.2f)
    }

    fun neutralCard(g: Gfx, rect: RectF, radius: Float = 10f) {
        g.roundV(rect, radius, 0xDB101626.toInt(), 0xE60A0E18.toInt())
        g.stroke(rect, radius, alpha(0xFF788CBE.toInt(), 0.22f))
        g.hline(rect.left + radius, rect.right - radius, rect.top + 1f, 0x0AFFFFFF)
    }

    /** Small dark tile used for stat boxes. */
    fun tile(g: Gfx, rect: RectF, radius: Float = 8f) {
        g.round(rect, radius, 0x0AFFFFFF)
        g.stroke(rect, radius, 0x17FFFFFF)
    }

    /**
     * Opaque, unfiltered paint for the avatar. Drawing the photo with the shared [Gfx.fill] inherited the 0x24 alpha of
     * the glow circle drawn just before, so the photo showed at ~14% opacity over the red glow (v0.1.8 bug).
     */
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun avatar(g: Gfx, bitmap: Bitmap?, name: String, cx: Float, cy: Float, diameter: Float) {
        val r = diameter / 2
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        // Glow ring behind the photo only; the photo is drawn opaque on top, so only the 1.5 px rim and the glow show.
        g.circle(cx, cy, r + 1.5f, 0x24FFFFFF, glow = alpha(Palette.RED, 0.45f), glowRadius = 8f)
        if (bitmap != null) {
            g.canvas.save()
            g.canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
            val side = minOf(bitmap.width, bitmap.height)
            val src = android.graphics.Rect((bitmap.width - side) / 2, (bitmap.height - side) / 2, (bitmap.width + side) / 2, (bitmap.height + side) / 2)
            g.canvas.drawBitmap(bitmap, src, rect, photo)
            g.canvas.restore()
        } else {
            g.fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFFFF4A55.toInt(), Palette.BLUE, Shader.TileMode.CLAMP)
            g.fill.color = 0xFFFFFFFF.toInt()
            g.canvas.drawCircle(cx, cy, r, g.fill)
            g.fill.shader = null
            val initial = name.trim().firstOrNull()?.uppercase() ?: "U"
            g.textMid(initial, cx, cy, diameter * 0.42f, 0xFFFFFFFF.toInt(), 800, Paint.Align.CENTER)
        }
        g.ring(cx, cy, r + 0.75f, 0x33FFFFFF, 1.5f)
    }
}

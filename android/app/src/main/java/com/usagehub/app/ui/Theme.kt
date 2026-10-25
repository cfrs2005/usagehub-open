package com.usagehub.app.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/** Colours of the neon console (same values as the web console's console.css). */
object Palette {
    const val BG = 0xFF05070D.toInt()
    const val TEXT = 0xFFEEF2F8.toInt()
    const val SOFT = 0xFFDCE1EA.toInt()
    const val MUTED = 0xFFB4BCCB.toInt()
    const val DIM = 0xFF7D8697.toInt()
    const val TICK = 0xFFAAB2C2.toInt()
    const val GREEN = 0xFF3DDC84.toInt()
    const val AMBER = 0xFFF5B942.toInt()
    const val RED = 0xFFFF3B47.toInt()
    const val BLUE = 0xFF2F7BFF.toInt()
    const val LINK = 0xFF3D9BFF.toInt()

    fun alpha(color: Int, alpha: Float): Int = (color and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
}

/** Per-vendor accent set. Claude is red, Codex is blue. */
class Accent(
    val main: Int,
    val light: Int,
    val dark: Int,
    val value: Int,
    val logoFrom: Int,
    val logoTo: Int,
    val cardTop: Int,
    val cardBottom: Int,
) {
    companion object {
        val CLAUDE = Accent(
            main = Palette.RED, light = 0xFFFF7A5A.toInt(), dark = 0xFFC4141F.toInt(), value = 0xFFFF4652.toInt(),
            logoFrom = 0xFFFF7A52.toInt(), logoTo = 0xFFFF2D3D.toInt(),
            cardTop = 0xB8300A12.toInt(), cardBottom = 0xE00E090F.toInt(),
        )
        val CODEX = Accent(
            main = Palette.BLUE, light = 0xFF47B6FF.toInt(), dark = 0xFF1546C8.toInt(), value = 0xFF5CC8FF.toInt(),
            logoFrom = 0xFF5FC8FF.toInt(), logoTo = 0xFF1F6BFF.toInt(),
            cardTop = 0xB80A1C40.toInt(), cardBottom = 0xE6070C1A.toInt(),
        )

        fun of(provider: String): Accent = if (provider == "codex") CODEX else CLAUDE
        fun other(provider: String): Accent = if (provider == "codex") CLAUDE else CODEX
    }
}

/**
 * Thin drawing helper over a Canvas in console units (dp of a 740x360 design). Paints are reused; every call sets
 * the fields it needs so callers never inherit state from the previous draw.
 */
class Gfx {
    lateinit var canvas: Canvas
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val faces = HashMap<Int, Typeface>()
    private val dash = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    private val scratch = RectF()

    private fun face(weight: Int): Typeface = faces.getOrPut(weight) { Typeface.create(Typeface.SANS_SERIF, weight, false) }

    private fun setText(size: Float, weight: Int, tnum: Boolean, spacing: Float) {
        text.textSize = size
        text.typeface = face(weight)
        text.fontFeatureSettings = if (tnum) "tnum" else null
        text.letterSpacing = spacing
    }

    fun width(value: String, size: Float, weight: Int = 400, tnum: Boolean = false, spacing: Float = 0f): Float {
        setText(size, weight, tnum, spacing)
        return text.measureText(value)
    }

    /**
     * Draws [value] with its baseline at [y]. [glow] adds a soft same-colour halo (0 = none). Returns the drawn width.
     */
    fun text(
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        weight: Int = 400,
        align: Paint.Align = Paint.Align.LEFT,
        tnum: Boolean = false,
        glow: Int = 0,
        glowRadius: Float = 5f,
        spacing: Float = 0f,
        shadow: Boolean = false,
    ): Float {
        setText(size, weight, tnum, spacing)
        text.color = color
        text.textAlign = align
        when {
            glow != 0 -> text.setShadowLayer(glowRadius, 0f, 0f, glow)
            shadow -> text.setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
            else -> text.clearShadowLayer()
        }
        canvas.drawText(value, x, y, text)
        text.clearShadowLayer()
        return text.measureText(value)
    }

    /** Text vertically centred on [cy] (cap-height based, good for both Latin and CJK). */
    fun textMid(
        value: String, x: Float, cy: Float, size: Float, color: Int, weight: Int = 400,
        align: Paint.Align = Paint.Align.LEFT, tnum: Boolean = false, glow: Int = 0, spacing: Float = 0f,
    ): Float = text(value, x, cy + size * 0.36f, size, color, weight, align, tnum, glow, spacing = spacing)

    /** Largest size in [min]..[max] (0.5 steps) at which [value] fits [maxWidth]; null when even [min] overflows. */
    fun fit(value: String, maxWidth: Float, max: Float, min: Float, weight: Int, tnum: Boolean = true): Float? {
        var size = max
        while (size >= min) {
            if (width(value, size, weight, tnum) <= maxWidth) return size
            size -= 0.5f
        }
        return null
    }

    fun ellipsize(value: String, maxWidth: Float, size: Float, weight: Int = 400): String {
        if (width(value, size, weight) <= maxWidth) return value
        var end = value.length
        while (end > 1 && width(value.substring(0, end) + "…", size, weight) > maxWidth) end--
        return value.substring(0, end) + "…"
    }

    fun round(rect: RectF, radius: Float, color: Int) {
        fill.shader = null
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    fun roundV(rect: RectF, radius: Float, top: Int, bottom: Int, split: Float = 1f) {
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * split, top, bottom, Shader.TileMode.CLAMP)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.shader = null
    }

    fun roundH(rect: RectF, radius: Float, left: Int, right: Int) {
        fill.shader = LinearGradient(rect.left, 0f, rect.right, 0f, left, right, Shader.TileMode.CLAMP)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.shader = null
    }

    fun stroke(rect: RectF, radius: Float, color: Int, width: Float = 1f) {
        line.shader = null
        line.pathEffect = null
        line.strokeWidth = width
        line.color = color
        line.strokeCap = Paint.Cap.BUTT
        scratch.set(rect.left + width / 2, rect.top + width / 2, rect.right - width / 2, rect.bottom - width / 2)
        canvas.drawRoundRect(scratch, radius, radius, line)
    }

    /** Neon halo around a rounded rect: a blurred stroke whose blur spills both outside and inside. */
    fun halo(rect: RectF, radius: Float, color: Int, blur: Float, width: Float = 1.5f) {
        line.shader = null
        line.pathEffect = null
        line.strokeWidth = width
        line.color = color
        line.setShadowLayer(blur, 0f, 0f, color)
        canvas.drawRoundRect(rect, radius, radius, line)
        line.clearShadowLayer()
    }

    fun hline(x1: Float, x2: Float, y: Float, color: Int, width: Float = 1f, dashed: Boolean = false) {
        line.shader = null
        line.strokeWidth = width
        line.color = color
        line.pathEffect = if (dashed) dash else null
        canvas.drawLine(x1, y, x2, y, line)
        line.pathEffect = null
    }

    fun vline(x: Float, y1: Float, y2: Float, color: Int, width: Float = 1f) {
        line.shader = null
        line.pathEffect = null
        line.strokeWidth = width
        line.color = color
        canvas.drawLine(x, y1, x, y2, line)
    }

    fun circle(cx: Float, cy: Float, r: Float, color: Int, glow: Int = 0, glowRadius: Float = 4f) {
        fill.shader = null
        fill.color = color
        if (glow != 0) fill.setShadowLayer(glowRadius, 0f, 0f, glow)
        canvas.drawCircle(cx, cy, r, fill)
        fill.clearShadowLayer()
    }

    fun ring(cx: Float, cy: Float, r: Float, color: Int, width: Float) {
        line.shader = null
        line.pathEffect = null
        line.strokeWidth = width
        line.color = color
        canvas.drawCircle(cx, cy, r, line)
    }

    fun path(path: Path, color: Int, width: Float, glow: Int = 0, glowRadius: Float = 4f) {
        line.shader = null
        line.pathEffect = null
        line.strokeWidth = width
        line.color = color
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        if (glow != 0) line.setShadowLayer(glowRadius, 0f, 0f, glow)
        canvas.drawPath(path, line)
        line.clearShadowLayer()
        line.strokeCap = Paint.Cap.BUTT
        line.strokeJoin = Paint.Join.MITER
    }
}

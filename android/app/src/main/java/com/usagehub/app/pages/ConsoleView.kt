package com.usagehub.app.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.usagehub.app.R
import com.usagehub.app.data.Fmt
import com.usagehub.app.ui.Action
import com.usagehub.app.ui.Gfx
import com.usagehub.app.ui.Hits
import com.usagehub.app.ui.Icon
import com.usagehub.app.ui.Icons
import com.usagehub.app.ui.Palette
import com.usagehub.app.ui.Palette.alpha
import com.usagehub.app.ui.Screen
import com.usagehub.app.ui.Tab
import com.usagehub.app.ui.Widgets
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shared layout of the 740x360 console (dp). */
object Layout {
    const val W = 740f
    const val H = 360f
    const val SIDE = 88f
    const val LEFT = 103f
    const val RIGHT = 726f
    const val BOTTOM = 350f
    const val CTRL_TOP = 14f
    const val CTRL_H = 30f
    const val HERO_H = 186f
}

/** What a page draws with: the canvas helper, the frame data and the tap registry. */
class PageCtx(val g: Gfx, val hits: Hits) {
    lateinit var s: Screen
    var scroll = 0f

    val zone: ZoneId = ZoneId.systemDefault()

    /** One vertically scrollable region per page (records list); set while drawing. */
    var scrollArea: RectF? = null
    var scrollMax = 0f

    val quotaStaleMillis: Long get() = s.config.quotaStaleMinutes * 60_000L

    /** Extra small text after the subtitle (e.g. update times); colour per segment. */
    var headerNote: List<Pair<String, Int>> = emptyList()

    fun header(title: String, subtitle: String, onBack: Action? = null) {
        var x = Layout.LEFT
        if (onBack != null) {
            Icons.draw(g, Icon.BACK, x - 6f, 19f, 20f, 0xFFC9CFDB.toInt(), 2.2f)
            hits.add(RectF(x - 12f, 8f, x + 18f, 46f), onBack)
            x += 18f
        }
        g.text(title, x, 37f, 22f, 0xFFFFFFFF.toInt(), 800, spacing = 0.02f, shadow = true)
        val subW = g.text(subtitle, Layout.LEFT, 58f, 11.5f, 0xFFC7CEDE.toInt(), 400, spacing = 0.08f, shadow = true)
        x = Layout.LEFT + subW + 14f
        if (s.offline) {
            val last = s.lastSuccessMillis?.let { "离线 · 上次更新 ${Fmt.hm(it, zone)}" } ?: "离线 · 暂无数据"
            g.circle(x, 54f, 3f, Palette.AMBER, glow = alpha(Palette.AMBER, 0.8f), glowRadius = 3f)
            x += 7f + g.text(last, x + 7f, 58f, 10.5f, Palette.AMBER, 600, shadow = true) + 12f
        }
        headerNote.forEach { (text, color) -> x += g.text(text, x, 58f, 10f, color, if (color == Palette.AMBER) 600 else 400, tnum = true, shadow = true) }
        headerNote = emptyList()
    }

    fun ctrlRect(right: Float, width: Float): RectF = RectF(right - width, Layout.CTRL_TOP, right, Layout.CTRL_TOP + Layout.CTRL_H)
}

class ConsoleView(context: Context) : View(context) {
    private val g = Gfx()
    private val hits = Hits()
    private val hero: Bitmap? = runCatching { BitmapFactory.decodeResource(resources, R.drawable.console_hero) }.getOrNull()
    private var backdrop: Bitmap? = null
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var pressed = -1
    private var downX = 0f
    private var downY = 0f
    private var scrollArea: RectF? = null
    private var scrollMax = 0f
    private var scroll = 0f
    private var scrollStart = 0f
    private var scrolling = false
    private var scrollKey: Any? = null

    var onAction: ((Action) -> Unit)? = null

    private val ctx = PageCtx(g, hits)

    var screen: Screen? = null
        set(value) {
            field = value
            val key = value?.let { Triple(it.nav.detail, it.nav.detailDays, it.nav.recordsExpanded) }
            if (key != scrollKey) { scrollKey = key; scroll = 0f }
            invalidate()
        }

    init {
        isFocusable = true
        contentDescription = "Claude 与 Codex 用量看板"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scale = min(w / Layout.W, h / Layout.H)
        offsetX = (w - Layout.W * scale) / 2f
        offsetY = (h - Layout.H * scale) / 2f
        backdrop?.recycle()
        backdrop = if (w > 0 && h > 0) renderBackdrop(w, h) else null
    }

    /** Static layers (base colour, ambient glows, hero art, sidebar body) rendered once per size. */
    private fun renderBackdrop(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Palette.BG)
        c.translate(offsetX, offsetY)
        c.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val main = RectF(Layout.SIDE, 0f, Layout.W, Layout.H)
        paint.shader = RadialGradient(Layout.SIDE, Layout.H, 460f, 0x38961E1E, 0x00961E1E, Shader.TileMode.CLAMP)
        c.drawRect(main, paint)
        paint.shader = RadialGradient(Layout.W, Layout.H, 460f, 0x38143CA0, 0x00143CA0, Shader.TileMode.CLAMP)
        c.drawRect(main, paint)
        paint.shader = null
        hero?.let { art ->
            val dst = RectF(Layout.SIDE, 0f, Layout.W, Layout.HERO_H)
            val srcH = art.width * dst.height() / dst.width()
            val top = ((art.height - srcH) * 0.32f).coerceAtLeast(0f)
            c.drawBitmap(art, Rect(0, top.toInt(), art.width, (top + srcH).toInt().coerceAtMost(art.height)), dst, paint)
            val bg = Palette.BG
            paint.shader = LinearGradient(
                dst.left, 0f, dst.right, 0f,
                intArrayOf(alpha(bg, 0.55f), alpha(bg, 0.2f), alpha(bg, 0.02f), alpha(bg, 0.2f)),
                floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(dst, paint)
            paint.shader = LinearGradient(
                0f, 0f, 0f, dst.bottom,
                intArrayOf(alpha(bg, 0f), alpha(bg, 0.1f), alpha(bg, 0.72f), bg),
                floatArrayOf(0f, 0.4f, 0.75f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(dst, paint)
            paint.shader = null
        }
        paint.shader = LinearGradient(0f, 0f, 0f, Layout.H, 0xFF0B0F1A.toInt(), 0xFF06080F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, Layout.SIDE, Layout.H, paint)
        paint.shader = null
        paint.color = alpha(0xFF788CBE.toInt(), 0.16f)
        c.drawRect(Layout.SIDE - 1f, 0f, Layout.SIDE, Layout.H, paint)
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = screen
        backdrop?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(Palette.BG)
        if (state == null) return
        g.canvas = canvas
        hits.clear()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        drawSide(state)
        ctx.s = state
        ctx.scroll = scroll
        ctx.scrollArea = null
        ctx.scrollMax = 0f
        canvas.save()
        canvas.clipRect(Layout.SIDE, 0f, Layout.W, Layout.H)
        when {
            state.nav.detail != null -> DetailPage.draw(ctx, state.nav.detail)
            state.nav.tab == Tab.HOME -> HomePage.draw(ctx)
            state.nav.tab == Tab.USAGE -> UsagePage.draw(ctx)
            state.nav.tab == Tab.MODELS -> ModelsPage.draw(ctx)
            state.nav.tab == Tab.STATS -> StatsPage.draw(ctx)
            else -> SettingsPage.draw(ctx)
        }
        canvas.restore()
        scrollArea = ctx.scrollArea
        scrollMax = ctx.scrollMax
        scroll = scroll.coerceIn(0f, scrollMax)
        hits.rect(pressed)?.let { g.round(it, 8f, 0x1FFFFFFF) }
        canvas.restore()
    }

    private fun drawSide(s: Screen) {
        Widgets.avatar(g, s.avatar, s.displayName, Layout.SIDE / 2, 36f, 46f)
        val name = g.ellipsize(s.displayName, Layout.SIDE - 12f, 13.5f, 800)
        g.text(name, Layout.SIDE / 2, 82f, 13.5f, 0xFFFFFFFF.toInt(), 800, Paint.Align.CENTER)
        Tab.values().forEachIndexed { index, tab ->
            val top = 98f + index * 43f
            val rect = RectF(6f, top, Layout.SIDE - 6f, top + 37f)
            val on = s.nav.tab == tab
            if (on) {
                g.halo(rect, 9f, alpha(Palette.RED, 0.55f), 9f, 1f)
                g.roundH(rect, 9f, Palette.RED, 0xFFB3121C.toInt())
                g.hline(rect.left + 8f, rect.right - 8f, rect.top + 0.8f, 0x33FFFFFF)
            }
            val color = if (on) 0xFFFFFFFF.toInt() else 0xFFC9CFDB.toInt()
            Icons.draw(g, tab.icon, 15f, rect.centerY() - 9f, 18f, color, if (on) 2f else 1.7f)
            g.textMid(tab.label, 41f, rect.centerY(), 13.5f, color, if (on) 800 else 600)
            hits.add(rect, Action.GoTab(tab))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x - offsetX) / scale
        val y = (event.y - offsetY) / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.find(x, y)
                downX = event.x
                downY = event.y
                scrolling = false
                scrollStart = scroll
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.y - downY) / scale
                val canScroll = scrollMax > 0f && scrollArea?.contains((downX - offsetX) / scale, (downY - offsetY) / scale) == true
                if (canScroll && (scrolling || abs(dy) > 8f)) {
                    scrolling = true
                    pressed = -1
                    scroll = (scrollStart - dy).coerceIn(0f, scrollMax)
                    invalidate()
                } else if (pressed >= 0 && max(abs(event.x - downX), abs(event.y - downY)) > 24f * scale) {
                    pressed = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val action = hits.action(pressed)?.takeIf { hits.rect(pressed)?.contains(x, y) == true }
                pressed = -1
                invalidate()
                if (action != null) {
                    performClick()
                    onAction?.invoke(action)
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

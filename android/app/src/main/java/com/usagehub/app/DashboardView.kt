package com.usagehub.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class DashboardScreenState(
    val nowMillis: Long,
    val snapshot: DashboardSnapshot?,
    val diagnostics: DeviceDiagnostics,
    val statusMessage: String,
    val refreshMinutes: Int,
)

class DashboardView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val settingsRect = RectF(1_374f, 24f, 1_448f, 88f)
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var pressStartedX = 0f
    private var pressStartedY = 0f
    private val holdPolicy = SettingsHoldPolicy()
    private val openSettings = Runnable {
        if (holdPolicy.onTimer(SystemClock.elapsedRealtime())) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onOpenSettings?.invoke()
        }
    }

    var onOpenSettings: (() -> Unit)? = null

    var state = DashboardScreenState(
        nowMillis = System.currentTimeMillis(),
        snapshot = null,
        diagnostics = DeviceDiagnostics(0, 0, 1f, 0, "离线", true, true, false),
        statusMessage = "等待配置",
        refreshMinutes = 2,
    )
        set(value) {
            field = value
            invalidate()
        }

    init {
        isFocusable = true
        contentDescription = "Claude 与 Codex 用量看板。长按右上角设置三秒可修改配置。"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(PAPER)
        scaleFactor = min(width / BASE_WIDTH, height / BASE_HEIGHT)
        offsetX = (width - BASE_WIDTH * scaleFactor) / 2f
        offsetY = (height - BASE_HEIGHT * scaleFactor) / 2f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor)
        drawPaperTexture(canvas)
        drawHeader(canvas)
        drawIdentity(canvas)
        drawProviderColumn(canvas, "claude", RectF(256f, 108f, 844f, 456f), CLAUDE_BLUE)
        drawProviderColumn(canvas, "codex", RectF(868f, 108f, 1_456f, 456f), CODEX_ORANGE)
        drawTokenCard(canvas, "claude", RectF(256f, 472f, 844f, 696f), CLAUDE_BLUE)
        drawTokenCard(canvas, "codex", RectF(868f, 472f, 1_456f, 696f), CODEX_ORANGE)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val baseX = (event.x - offsetX) / scaleFactor
        val baseY = (event.y - offsetY) / scaleFactor
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!settingsRect.contains(baseX, baseY)) return true
                pressStartedX = event.x
                pressStartedY = event.y
                holdPolicy.onDown(SystemClock.elapsedRealtime())
                handler.postDelayed(openSettings, SETTINGS_HOLD_MILLIS)
            }
            MotionEvent.ACTION_MOVE -> {
                val movement = max(abs(event.x - pressStartedX), abs(event.y - pressStartedY))
                holdPolicy.onMove(settingsRect.contains(baseX, baseY), movement)
                if (movement > TOUCH_SLOP || !settingsRect.contains(baseX, baseY)) cancelSettingsHold()
            }
            MotionEvent.ACTION_UP -> {
                cancelSettingsHold()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancelSettingsHold()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelSettingsHold()
        super.onDetachedFromWindow()
    }

    private fun cancelSettingsHold() {
        holdPolicy.cancel()
        handler.removeCallbacks(openSettings)
    }

    private fun drawPaperTexture(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = PAPER_FIBER
        for (index in 0 until 96) {
            val x = ((index * 173 + 41) % 1_432 + 24).toFloat()
            val y = ((index * 97 + 29) % 676 + 20).toFloat()
            canvas.drawRect(x, y, x + 2f, y + 2f, paint)
        }
    }

    private fun drawHeader(canvas: Canvas) {
        outlinedBox(canvas, RectF(24f, 20f, 1_456f, 92f), INK, 3f)
        paint.style = Paint.Style.FILL
        paint.color = CLAUDE_BLUE
        canvas.drawRect(24f, 20f, 32f, 92f, paint)
        paint.color = CODEX_ORANGE
        canvas.drawRect(1_448f, 20f, 1_456f, 92f, paint)
        text(canvas, "AI 用量看板", 52f, 67f, 34f, INK, true)
        val zone = ZoneId.systemDefault()
        val dateTime = "${UsageLogic.dateHeaderText(state.nowMillis, zone)}  ${UsageLogic.clockMinuteText(state.nowMillis, zone)}"
        text(canvas, dateTime, 1_370f, 52f, 22f, INK, true, condensed = true, align = Paint.Align.RIGHT)
        val updated = state.snapshot?.let {
            val time = UsageLogic.clockMinuteText(it.fetchedAtMillis, ZoneId.systemDefault())
            "数据更新 $time · ${UsageLogic.ageTextChinese(state.nowMillis - it.fetchedAtMillis)}"
        } ?: "等待第一份数据"
        text(canvas, updated, 1_370f, 80f, 14f, SECONDARY, false, align = Paint.Align.RIGHT)
        drawSettingsIcon(canvas)
    }

    private fun drawSettingsIcon(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = INK
        canvas.drawRect(1_397f, 40f, 1_425f, 68f, paint)
        canvas.drawRect(1_404f, 47f, 1_418f, 61f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(1_407f, 31f, 1_415f, 40f, paint)
        canvas.drawRect(1_407f, 68f, 1_415f, 77f, paint)
        canvas.drawRect(1_388f, 50f, 1_397f, 58f, paint)
        canvas.drawRect(1_425f, 50f, 1_434f, 58f, paint)
    }

    private fun drawIdentity(canvas: Canvas) {
        val bounds = RectF(24f, 108f, 240f, 696f)
        outlinedBox(canvas, bounds, INK, 3f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = INK
        canvas.drawRect(42f, 130f, 222f, 310f, paint)
        paint.style = Paint.Style.FILL
        paint.color = PAPER_FIBER
        canvas.drawCircle(132f, 220f, 72f, paint)
        text(canvas, "U", 132f, 242f, 72f, INK, true, align = Paint.Align.CENTER)
        paint.color = CLAUDE_BLUE
        canvas.drawRect(42f, 130f, 48f, 310f, paint)
        canvas.drawRect(42f, 130f, 132f, 136f, paint)
        paint.color = CODEX_ORANGE
        canvas.drawRect(216f, 130f, 222f, 310f, paint)
        canvas.drawRect(132f, 304f, 222f, 310f, paint)

        text(canvas, "UsageHub", 48f, 362f, 23f, INK, true)
        text(canvas, "AI 用量", 48f, 398f, 30f, INK, true)
        paint.color = CLAUDE_BLUE
        canvas.drawRect(48f, 430f, 76f, 438f, paint)
        text(canvas, "CLAUDE", 86f, 441f, 16f, SECONDARY, true, condensed = true)
        paint.color = CODEX_ORANGE
        canvas.drawRect(48f, 462f, 76f, 470f, paint)
        text(canvas, "CODEX", 86f, 473f, 16f, SECONDARY, true, condensed = true)

        text(canvas, "每 ${state.refreshMinutes} 分钟取数", 48f, 536f, 17f, SECONDARY, false)
        text(canvas, "倒计时每分钟更新", 48f, 566f, 15f, SECONDARY, false)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = INK_LIGHT
        canvas.drawLine(48f, 594f, 208f, 594f, paint)
        val health = state.statusMessage
        val healthColor = if (state.diagnostics.healthOk) NETWORK_BLUE else ALERT_RED
        text(canvas, health, 48f, 628f, 18f, healthColor, true)
        text(canvas, state.diagnostics.networkLabel, 48f, 660f, 16f, SECONDARY, false)
        text(canvas, "长按右上角设置", 48f, 684f, 13f, SECONDARY, false)
    }

    private fun drawProviderColumn(canvas: Canvas, providerName: String, bounds: RectF, accent: Int) {
        outlinedBox(canvas, bounds, INK, 3f)
        val provider = state.snapshot?.providers?.firstOrNull { it.provider == providerName }
        val title = if (providerName == "claude") "CLAUDE" else "CODEX"
        text(canvas, title, bounds.left + 24f, bounds.top + 44f, 28f, accent, true, condensed = true)
        text(canvas, freshnessLabel(provider), bounds.right - 176f, bounds.top + 39f, 15f, SECONDARY, true)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = INK_LIGHT
        canvas.drawLine(bounds.left + 24f, bounds.top + 60f, bounds.right - 24f, bounds.top + 60f, paint)

        drawQuotaRow(canvas, "5 小时用量", UsageLogic.quotaWindow(provider, 300), bounds.left + 24f, bounds.top + 76f, accent)
        drawQuotaRow(canvas, "7 天用量", UsageLogic.quotaWindow(provider, 10_080), bounds.left + 24f, bounds.top + 196f, accent)
        val source = if (providerName == "claude") "用量来源：官方 statusLine" else "用量来源：官方 app-server"
        text(canvas, source, bounds.left + 24f, bounds.bottom - 14f, 15f, SECONDARY, false)
    }

    private fun drawQuotaRow(canvas: Canvas, label: String, window: UsageWindow?, x: Float, y: Float, accent: Int) {
        text(canvas, label, x, y + 24f, 21f, INK, true)
        val percent = UsageLogic.displayPercent(window?.usedPercent, DisplayMode.USED)
        text(canvas, percent?.let { "$it%" } ?: "--", x, y + 82f, 54f, INK, true, condensed = true)
        drawSegmentBar(canvas, x + 132f, y + 43f, percent, accent)
        val now = UsageLogic.effectiveNow(state.nowMillis, state.snapshot)
        text(canvas, UsageLogic.countdownChinese(window, now), x + 132f, y + 89f, 22f, SECONDARY, true)
    }

    private fun drawSegmentBar(canvas: Canvas, x: Float, y: Float, percent: Int?, accent: Int) {
        val filled = percent?.let { ceil(it / 5.0).toInt().coerceIn(0, SEGMENT_COUNT) } ?: 0
        repeat(SEGMENT_COUNT) { index ->
            val left = x + index * (SEGMENT_WIDTH + SEGMENT_GAP)
            paint.style = Paint.Style.FILL
            paint.color = if (index < filled) accent else BAR_EMPTY
            canvas.drawRect(left, y, left + SEGMENT_WIDTH, y + SEGMENT_HEIGHT, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = PAPER
            canvas.drawRect(left, y, left + SEGMENT_WIDTH, y + SEGMENT_HEIGHT, paint)
        }
    }

    private fun drawTokenCard(canvas: Canvas, providerName: String, bounds: RectF, accent: Int) {
        outlinedBox(canvas, bounds, INK, 3f)
        val provider = state.snapshot?.providers?.firstOrNull { it.provider == providerName }
        val usage = provider?.tokenUsage ?: TokenUsage.missing()
        text(canvas, "令牌 / API 等价费用", bounds.left + 24f, bounds.top + 33f, 22f, accent, true)
        text(canvas, "今天", bounds.left + 250f, bounds.top + 32f, 17f, SECONDARY, true)
        text(canvas, "累计", bounds.left + 410f, bounds.top + 32f, 17f, SECONDARY, true)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = INK_LIGHT
        canvas.drawLine(bounds.left + 24f, bounds.top + 48f, bounds.right - 24f, bounds.top + 48f, paint)
        canvas.drawLine(bounds.left + 24f, bounds.top + 120f, bounds.right - 24f, bounds.top + 120f, paint)

        text(canvas, "令牌", bounds.left + 24f, bounds.top + 91f, 18f, INK, true)
        text(canvas, UsageLogic.formatTokens(usage.todayTokens), bounds.left + 232f, bounds.top + 101f, 35f, INK, true, condensed = true)
        text(canvas, UsageLogic.formatTokens(usage.totalTokens), bounds.left + 392f, bounds.top + 101f, 35f, INK, true, condensed = true)
        text(canvas, "标准 API", bounds.left + 24f, bounds.top + 158f, 17f, INK, true)
        text(canvas, "等价费用", bounds.left + 24f, bounds.top + 180f, 17f, INK, true)
        text(canvas, UsageLogic.formatUsd(usage.todayCostUsd), bounds.left + 232f, bounds.top + 174f, 28f, INK, true, condensed = true)
        text(canvas, UsageLogic.formatUsd(usage.totalCostUsd), bounds.left + 392f, bounds.top + 174f, 28f, INK, true, condensed = true)

        val source = when (usage.source) {
            "ccusage" -> "ccusage"
            "ccusage_codex" -> "ccusage-codex"
            else -> "等待 ccusage"
        }
        val stale = if (usage.stale && usage.status == "ok") " · 上次数据" else ""
        text(canvas, "令牌来源：$source$stale · 费用为 API 单价估算", bounds.left + 24f, bounds.bottom - 10f, 14f, SECONDARY, false)
    }

    private fun freshnessLabel(provider: ProviderUsage?): String {
        return when (provider?.let { UsageLogic.freshness(it, UsageLogic.effectiveNow(state.nowMillis, state.snapshot)) }) {
            Freshness.OFFICIAL_SAMPLE -> "官方数据"
            Freshness.LAST_OFFICIAL_DATA -> "上次数据"
            Freshness.EXPIRED -> "数据过期"
            else -> "等待数据"
        }
    }

    private fun outlinedBox(canvas: Canvas, bounds: RectF, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        canvas.drawRect(bounds, paint)
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        condensed: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.typeface = Typeface.create(
            if (condensed) "sans-serif-condensed" else "sans-serif",
            if (bold) Typeface.BOLD else Typeface.NORMAL,
        )
        paint.letterSpacing = if (condensed && bold) 0.015f else 0f
        paint.textAlign = align
        canvas.drawText(value, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    companion object {
        private const val BASE_WIDTH = 1_480f
        private const val BASE_HEIGHT = 720f
        private const val SETTINGS_HOLD_MILLIS = 3_000L
        private const val TOUCH_SLOP = 28f
        private const val SEGMENT_COUNT = 20
        private const val SEGMENT_WIDTH = 14f
        private const val SEGMENT_GAP = 4f
        private const val SEGMENT_HEIGHT = 28f
        private val PAPER = Color.rgb(250, 248, 243)
        private val PAPER_FIBER = Color.rgb(237, 233, 225)
        private val BAR_EMPTY = Color.rgb(228, 226, 220)
        private val INK = Color.rgb(12, 12, 12)
        private val SECONDARY = Color.rgb(78, 77, 72)
        private val INK_LIGHT = Color.rgb(190, 187, 179)
        private val CLAUDE_BLUE = Color.rgb(20, 73, 232)
        private val CODEX_ORANGE = Color.rgb(242, 86, 24)
        private val NETWORK_BLUE = Color.rgb(20, 73, 232)
        private val ALERT_RED = Color.rgb(184, 33, 45)
    }
}

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
import com.usagehub.app.ui.Icon
import com.usagehub.app.ui.Palette
import com.usagehub.app.ui.Palette.alpha
import com.usagehub.app.ui.Widgets
import java.util.Locale

/**
 * Device settings in the console skin. The display token cannot read `/v1/connections`, so "连接配置" shows the
 * read-only collector status per vendor instead of API keys; everything else is this device's own settings.
 */
object SettingsPage {
    val TABS = listOf("连接配置", "服务与配对", "显示偏好", "数据管理", "关于")
    private const val TOP = 108f
    private const val BOTTOM = 346f

    fun draw(ctx: PageCtx) {
        val g = ctx.g
        val s = ctx.s
        ctx.header("设置", "管理连接、配对和显示选项")
        val sync = ctx.ctrlRect(Layout.RIGHT, Widgets.buttonWidth(g, "同步配置", Icon.SYNC, 12.5f, 14f))
        Widgets.button(g, sync, "同步配置", ButtonStyle.BLUE_SOLID, Icon.SYNC)
        ctx.hits.add(sync, Action.Refresh)
        val tabs = Widgets.seg(g, Layout.LEFT, 66f, TABS, s.nav.settingsTab, 32f, 16f, 12.5f, 74f)
        tabs.forEachIndexed { index, rect -> ctx.hits.add(rect, Action.SettingsTab(index)) }
        when (s.nav.settingsTab) {
            0 -> connections(ctx)
            1 -> pairing(ctx)
            2 -> display(ctx)
            3 -> dataManagement(ctx)
            else -> about(ctx)
        }
    }

    private fun connections(ctx: PageCtx) {
        val g = ctx.g
        val s = ctx.s
        if (!s.paired && !s.mock) {
            val card = RectF(Layout.LEFT, TOP, Layout.RIGHT, BOTTOM)
            Widgets.neutralCard(g, card)
            g.textMid("设备尚未配对", card.centerX(), card.top + 80f, 18f, 0xFFFFFFFF.toInt(), 800, Paint.Align.CENTER)
            g.textMid("在 ${host(s.config.serverUrl)} 生成 10 分钟配对码，然后在这里输入。", card.centerX(), card.top + 110f, 12f, Palette.MUTED, 400, Paint.Align.CENTER)
            val button = RectF(card.centerX() - 60f, card.top + 132f, card.centerX() + 60f, card.top + 164f)
            Widgets.button(g, button, "输入配对码", ButtonStyle.BLUE_SOLID)
            ctx.hits.add(button, Action.Pair)
            return
        }
        val gap = 10f
        val width = (Layout.RIGHT - Layout.LEFT - gap) / 2
        listOf("claude", "codex").forEachIndexed { index, name ->
            val left = Layout.LEFT + index * (width + gap)
            providerCard(ctx, RectF(left, TOP, left + width, BOTTOM), name, s.dashboard?.providers?.firstOrNull { it.provider == name })
        }
    }

    private fun providerCard(ctx: PageCtx, c: RectF, name: String, provider: ProviderUsage?) {
        val g = ctx.g
        Widgets.neonCard(g, c, Accent.of(name))
        Widgets.logo(g, name, c.left + 13f, c.top + 12f, 23f)
        g.textMid("${if (name == "claude") "Claude" else "Codex"} 连接配置", c.left + 45f, c.top + 24f, 15f, 0xFFFFFFFF.toInt(), 800)
        val pillW = g.width(statusLabel(ctx, provider), 11.5f, 700) + 33f
        HomePage.pill(ctx, c.right - 13f - pillW, c.top + 24f, provider)
        val now = UsageLogic.effectiveNow(ctx.s.now, ctx.s.dashboard)
        val five = UsageLogic.quotaWindow(provider, 300)
        val week = UsageLogic.quotaWindow(provider, 10_080)
        val rows = listOf(
            "数据来源" to source(provider),
            "配额更新" to (provider?.observedAtMillis?.let { Fmt.stamp(it, ctx.zone) } ?: "从未"),
            "Token 更新" to (provider?.tokenUsage?.observedAtMillis?.let { Fmt.stamp(it, ctx.zone) } ?: "从未"),
            "5 小时窗口" to window(five?.usedPercent, five?.resetsAtEpochSeconds, now, HomePage.windowMissing(name, 300)),
            "7 天窗口" to window(week?.usedPercent, week?.resetsAtEpochSeconds, now, HomePage.windowMissing(name, 10_080)),
        )
        val info = ConsoleRules.updated(provider, now, ctx.quotaStaleMillis)
        val oldQuota = info.quotaOld
        val oldTokens = info.tokenAt == null || info.tokenOld
        rows.forEachIndexed { index, (label, value) ->
            val cy = c.top + 62f + index * 38f
            g.textMid(label, c.left + 14f, cy, 12f, Palette.MUTED)
            val warn = (index == 1 && oldQuota) || (index == 2 && oldTokens)
            field(g, RectF(c.left + 86f, cy - 14f, c.right - 14f, cy + 14f), value, if (warn) Palette.AMBER else 0xFFDFE4EE.toInt())
        }
    }

    private fun statusLabel(ctx: PageCtx, provider: ProviderUsage?): String =
        when (ConsoleRules.status(provider, UsageLogic.effectiveNow(ctx.s.now, ctx.s.dashboard), ctx.quotaStaleMillis)) {
            ConsoleRules.Status.OK -> "已连接"
            ConsoleRules.Status.STALE -> "数据过期"
            ConsoleRules.Status.MISSING -> "无数据"
        }

    private fun source(provider: ProviderUsage?): String {
        provider ?: return Fmt.NONE
        val quota = when (provider.source) {
            "official_statusline" -> "官方 claude -p /usage"
            "official_app_server" -> "官方 app-server"
            else -> provider.source
        }
        val tokens = when (provider.tokenUsage.source) {
            "ccusage" -> " · ccusage"
            "ccusage_codex" -> " · ccusage-codex"
            else -> ""
        }
        return quota + tokens
    }

    private fun window(percent: Double?, resetsAt: Long?, now: Long, missing: String): String {
        if (percent == null) return missing
        return if (resetsAt == null) Fmt.pct(percent) else "${Fmt.pct(percent)} · 还剩 ${Fmt.remain(resetsAt, now)}"
    }

    private fun field(g: Gfx, rect: RectF, value: String, color: Int = 0xFFDFE4EE.toInt()) {
        g.round(rect, 7f, 0x0DFFFFFF)
        g.stroke(rect, 7f, 0x14FFFFFF)
        g.textMid(g.ellipsize(value, rect.width() - 20f, 12f), rect.left + 10f, rect.centerY(), 12f, color, 400, tnum = true)
    }

    /** One plain card with label / value / optional control rows. */
    private class Rows(val ctx: PageCtx, title: String, private val spacing: Float = 42f) {
        val g = ctx.g
        val card = RectF(Layout.LEFT, TOP, Layout.RIGHT, BOTTOM)
        private var index = 0

        init {
            Widgets.neutralCard(g, card)
            g.textMid(title, card.left + 14f, card.top + 22f, 15f, 0xFFFFFFFF.toInt(), 800)
        }

        /** Draws the label and value; returns the control area on the right (for buttons / toggles). */
        fun row(label: String, value: String, valueColor: Int = 0xFFDFE4EE.toInt()): RectF {
            val cy = card.top + 60f + index * spacing
            if (index > 0) g.hline(card.left + 14f, card.right - 14f, cy - spacing / 2, 0x0FFFFFFF)
            index++
            g.textMid(label, card.left + 16f, cy, 12.5f, Palette.MUTED)
            g.textMid(g.ellipsize(value, card.width() - 150f, 12.5f), card.left + 130f, cy, 12.5f, valueColor, 500, tnum = true)
            return RectF(card.right - 170f, cy - 14f, card.right - 16f, cy + 14f)
        }

        fun button(area: RectF, label: String, action: Action, style: ButtonStyle = ButtonStyle.DARK) {
            val w = Widgets.buttonWidth(g, label, null, 12f, 12f)
            val rect = RectF(area.right - w, area.top, area.right, area.bottom)
            Widgets.button(g, rect, label, style, size = 12f)
            ctx.hits.add(rect, action)
        }

        fun toggle(area: RectF, on: Boolean, action: Action) {
            val track = RectF(area.right - 40f, area.centerY() - 11f, area.right, area.centerY() + 11f)
            if (on) {
                g.halo(track, 11f, alpha(Palette.BLUE, 0.5f), 6f, 1f)
                g.roundV(track, 11f, 0xFF3D8BFF.toInt(), 0xFF1D5FE0.toInt())
            } else {
                g.round(track, 11f, 0x1AFFFFFF)
                g.stroke(track, 11f, 0x24FFFFFF)
            }
            g.circle(if (on) track.right - 11f else track.left + 11f, track.centerY(), 8f, 0xFFFFFFFF.toInt())
            ctx.hits.add(RectF(track.left - 30f, area.top - 6f, track.right + 6f, area.bottom + 6f), action)
        }
    }

    private fun pairing(ctx: PageCtx) {
        val s = ctx.s
        val rows = Rows(ctx, "服务与配对")
        rows.button(rows.row("服务地址", s.config.serverUrl), "编辑", Action.EditServer)
        val paired = rows.row("配对状态", if (s.paired) "已配对 · 只读凭证由 Android Keystore 加密保存" else "未配对", if (s.paired) Palette.GREEN else Palette.AMBER)
        rows.button(paired, if (s.paired) "重新配对" else "输入配对码", Action.Pair, if (s.paired) ButtonStyle.DARK else ButtonStyle.BLUE_SOLID)
        val interval = rows.row("刷新间隔", "每 ${s.config.refreshMinutes} 分钟取数")
        val labels = listOf(2, 5, 10).map { "${it}分钟" }
        val segW = Widgets.segWidth(ctx.g, labels, 10f, 12f)
        Widgets.seg(ctx.g, interval.right - segW, interval.top - 1f, labels, listOf(2, 5, 10).indexOf(s.config.refreshMinutes), 30f, 10f, 12f)
            .forEachIndexed { index, rect -> ctx.hits.add(rect, Action.Interval(listOf(2, 5, 10)[index])) }
        rows.row("服务状态", if (s.healthOk) "服务正常" else s.statusMessage, if (s.healthOk) Palette.GREEN else Palette.AMBER)
    }

    private fun display(ctx: PageCtx) {
        val config = ctx.s.config
        val rows = Rows(ctx, "显示偏好", 37f)
        rows.toggle(rows.row("全屏显示", "隐藏系统状态栏与导航键"), config.immersiveMode, Action.ToggleImmersive)
        rows.toggle(rows.row("保持常亮", "看板在前台时屏幕不熄灭"), config.keepScreenOn, Action.ToggleKeepOn)
        rows.toggle(rows.row("锁屏显示", "锁屏前看板在前台时继续显示（不绕过密码）"), config.showOnLockScreen, Action.ToggleLockScreen)
        val brightness = rows.row("屏幕亮度", "${config.brightnessPercent}%")
        val plus = RectF(brightness.right - 34f, brightness.top, brightness.right, brightness.bottom)
        val minus = RectF(plus.left - 42f, brightness.top, plus.left - 8f, brightness.bottom)
        Widgets.button(rows.g, minus, "−", ButtonStyle.DARK, size = 15f)
        Widgets.button(rows.g, plus, "+", ButtonStyle.DARK, size = 15f)
        ctx.hits.add(minus, Action.Brightness(-10))
        ctx.hits.add(plus, Action.Brightness(10))
        val stale = rows.row("过期判定", "配额超时 · Token 固定 6 小时")
        val options = ConsoleRules.QUOTA_STALE_OPTIONS
        val labels = options.map { if (it % 60 == 0) "${it / 60}小时" else "${it}分钟" }
        val segW = Widgets.segWidth(rows.g, labels, 9f, 12f)
        Widgets.seg(rows.g, stale.right - segW, stale.top - 1f, labels, options.indexOf(config.quotaStaleMinutes), 30f, 9f, 12f)
            .forEachIndexed { index, rect -> ctx.hits.add(rect, Action.QuotaStale(options[index])) }
    }

    private fun dataManagement(ctx: PageCtx) {
        val s = ctx.s
        val rows = Rows(ctx, "数据管理")
        val last = s.lastSuccessMillis?.let { Fmt.stamp(it, ctx.zone) } ?: "从未"
        rows.button(rows.row("上次同步", last + if (s.offline) " · 当前离线" else ""), "立即同步", Action.Refresh)
        val size = String.format(Locale.US, "%.1f KB · 离线时显示上次成功的数据", s.cacheBytes / 1024.0)
        rows.button(rows.row("本地缓存", size), "清除缓存", Action.ClearCache)
        rows.button(rows.row("系统设置", "打开 Android 系统设置"), "打开", Action.SystemSettings)
        rows.button(rows.row("系统桌面", "回到设备桌面，看板保留在后台"), "前往", Action.SystemHome)
    }

    private fun about(ctx: PageCtx) {
        val s = ctx.s
        val rows = Rows(ctx, "关于")
        rows.row("版本", "UsageHub ${s.versionName}" + if (s.mock) " · 演示数据" else "")
        val d = s.diagnostics
        rows.row("设备", d?.let { "${it.widthPixels}×${it.heightPixels} px · ${it.densityDpi} dpi · ${it.networkLabel}" } ?: Fmt.NONE)
        rows.row("数据来源", "额度：Claude 官方 /usage（claude -p）/ Codex 官方 app-server；Token：ccusage")
        rows.row("费用口径", "费用为 API 等价估算，非账单金额")
    }

    private fun host(url: String): String = url.removePrefix("https://").trimEnd('/')
}

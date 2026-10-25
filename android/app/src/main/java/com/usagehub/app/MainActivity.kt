package com.usagehub.app

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.usagehub.app.data.ConsoleRules
import com.usagehub.app.data.DashboardClient
import com.usagehub.app.data.DiagnosticsReader
import com.usagehub.app.data.RefreshPolicy
import com.usagehub.app.pages.ConsoleView
import com.usagehub.app.ui.Action
import com.usagehub.app.ui.Nav
import com.usagehub.app.ui.Screen
import com.usagehub.app.ui.Tab
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var view: ConsoleView
    private lateinit var storage: AppStorage
    private lateinit var hub: DataHub
    private val client = DashboardClient()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var config = AppConfig("https://u.80aj.com", 45, 2, com.usagehub.app.data.DisplayMode.USED, true, false, true)
    private var refreshPolicy = RefreshPolicy()
    private var nav = Nav()
    private var mockSource: Source? = null
    private var started = false
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) handler.post { dismissNonSecureKeyguard() }
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            render()
            if (started) handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    private val scheduledFetch = Runnable { refreshAll() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = AppStorage(this)
        config = storage.loadConfig()
        refreshPolicy = RefreshPolicy(config.refreshMinutes * 60_000L)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("mock", false)) mockSource = MockFixture.source(this)
        hub = DataHub(storage, executor, handler) { render() }
        nav = initialNav(intent.getStringExtra("tab"))
        view = ConsoleView(this).apply { onAction = ::onAction }
        setContentView(view)
        applyWindowBehavior()
        applyBrightness()
        applyImmersiveMode()
        render()
    }

    private fun initialNav(tab: String?): Nav {
        if (mockSource == null && storage.readDisplayToken().isNullOrBlank()) return Nav(tab = Tab.SETTINGS, settingsTab = 0)
        tab ?: return Nav()
        if (tab.startsWith("detail-")) return Nav(detail = tab.removePrefix("detail-").takeIf { it == "claude" || it == "codex" })
        if (tab.startsWith("settings-")) return Nav(tab = Tab.SETTINGS, settingsTab = tab.removePrefix("settings-").toIntOrNull()?.coerceIn(0, 4) ?: 0)
        return Nav(tab = Tab.values().firstOrNull { it.key == tab } ?: Tab.HOME)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (!screenReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            screenReceiverRegistered = true
        }
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        scheduleRefresh(0L)
    }

    override fun onResume() {
        super.onResume()
        dismissNonSecureKeyguard()
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(scheduledFetch)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
            dismissNonSecureKeyguard()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (nav.detail != null) onAction(Action.Back) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /* ------------------------------------------------------------------ data */

    private fun source(): Source? {
        mockSource?.let { return it }
        val token = storage.readDisplayToken()?.takeIf { it.isNotBlank() } ?: return null
        return ServerSource(client, config.serverUrl, token)
    }

    private fun refreshAll() {
        if (!started) return
        val source = source()
        if (source == null) {
            hub.statusMessage = "等待配对"
            render()
            scheduleRefresh(config.refreshMinutes * 60_000L)
            return
        }
        hub.refreshDashboard(source) { ok -> scheduleRefresh(if (ok) refreshPolicy.onSuccess() else refreshPolicy.onFailure()) }
        requestPageData(source)
        render()
    }

    private fun requestPageData(source: Source? = source()) {
        source ?: return
        val now = System.currentTimeMillis()
        val detail = nav.detail
        when {
            detail != null -> {
                hub.analytics(nav.detailDays, source, now)
                hub.history(detail, nav.detailDays, nav.recordLimit, source, now)
            }
            nav.tab == Tab.USAGE -> hub.analytics(nav.usageDays, source, now)
            nav.tab == Tab.STATS -> hub.analytics(nav.statsDays, source, now)
            nav.tab == Tab.MODELS -> {
                hub.analytics(7, source, now)
                val (week, day) = boundaries()
                hub.baseline("week", week, source)
                hub.baseline("day", day, source)
            }
            else -> Unit
        }
    }

    /** Model-delta boundaries: local midnight today, and local midnight 6 days earlier (7 calendar days). */
    private fun boundaries(): Pair<Long, Long> {
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        return (midnight - 6 * ConsoleRules.DAY) to midnight
    }

    private fun scheduleRefresh(delayMillis: Long) {
        handler.removeCallbacks(scheduledFetch)
        if (started) handler.postDelayed(scheduledFetch, delayMillis)
    }

    private fun render() {
        if (!::view.isInitialized) return
        val (week, day) = boundaries()
        val detail = nav.detail
        view.screen = Screen(
            now = System.currentTimeMillis(),
            nav = nav,
            displayName = hub.displayName,
            avatar = hub.avatar,
            dashboard = hub.dashboard,
            analytics = HashMap(hub.analytics),
            history = detail?.let { hub.history(it, nav.detailDays, nav.recordLimit) },
            modelRows = hub.modelRows(week, day, nav.modelQuery),
            offline = hub.offline,
            lastSuccessMillis = hub.lastSuccessMillis,
            paired = !storage.readDisplayToken().isNullOrBlank(),
            config = config,
            diagnostics = if (nav.tab == Tab.SETTINGS) DiagnosticsReader.read(this, hub.healthOk) else null,
            healthOk = hub.healthOk,
            statusMessage = hub.statusMessage,
            versionName = BuildConfig.VERSION_NAME,
            mock = mockSource != null,
            cacheBytes = if (nav.tab == Tab.SETTINGS) storage.cacheBytes() else 0L,
        )
    }

    /* ------------------------------------------------------------------ actions */

    private fun onAction(action: Action) {
        val before = nav
        nav = when (action) {
            is Action.GoTab -> nav.copy(tab = action.tab, detail = null)
            is Action.OpenDetail -> nav.copy(detail = action.provider, recordsExpanded = false)
            Action.Back -> nav.copy(detail = null)
            is Action.UsageDays -> nav.copy(usageDays = action.days)
            is Action.StatsDays -> nav.copy(statsDays = action.days)
            is Action.DetailDays -> nav.copy(detailDays = action.days)
            Action.ToggleRecords -> nav.copy(recordsExpanded = !nav.recordsExpanded)
            is Action.ModelFilter -> nav.copy(modelFilter = action.filter)
            is Action.SettingsTab -> nav.copy(settingsTab = action.index)
            else -> nav
        }
        when (action) {
            Action.Refresh -> { refreshPolicy.reset(); refreshAll() }
            Action.EditSearch -> editSearch()
            Action.EditServer -> editServer()
            Action.Pair -> pair()
            Action.ToggleImmersive -> updateConfig(config.copy(immersiveMode = !config.immersiveMode))
            Action.ToggleKeepOn -> updateConfig(config.copy(keepScreenOn = !config.keepScreenOn))
            Action.ToggleLockScreen -> updateConfig(config.copy(showOnLockScreen = !config.showOnLockScreen))
            is Action.Brightness -> updateConfig(config.copy(brightnessPercent = (config.brightnessPercent + action.delta).coerceIn(10, 100)))
            is Action.Interval -> {
                updateConfig(config.copy(refreshMinutes = action.minutes))
                refreshPolicy = RefreshPolicy(action.minutes * 60_000L)
                scheduleRefresh(refreshPolicy.onSuccess())
            }
            is Action.QuotaStale -> updateConfig(config.copy(quotaStaleMinutes = action.minutes))
            Action.ClearCache -> {
                storage.clearCache()
                Toast.makeText(this, "已清除本地缓存", Toast.LENGTH_SHORT).show()
            }
            Action.SystemSettings -> startActivity(Intent(Settings.ACTION_SETTINGS))
            Action.SystemHome -> openSystemHome()
            else -> Unit
        }
        if (nav != before) requestPageData()
        render()
    }

    private fun updateConfig(next: AppConfig) {
        config = next
        storage.saveConfig(next)
        applyWindowBehavior()
        applyImmersiveMode()
        applyBrightness()
    }

    /* ------------------------------------------------------------------ dialogs */

    private fun dialogField(hint: String, value: String, password: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun dialog(title: String, note: String?, field: EditText): AlertDialog.Builder {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            if (note != null) addView(TextView(this@MainActivity).apply { text = note; setTextColor(Color.LTGRAY); textSize = 13f })
            addView(field)
        }
        return AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(layout)
    }

    private fun editSearch() {
        val field = dialogField("搜索模型...", nav.modelQuery, false).apply { inputType = InputType.TYPE_CLASS_TEXT }
        dialog("搜索模型", null, field)
            .setNegativeButton("清除") { _, _ -> nav = nav.copy(modelQuery = ""); render() }
            .setPositiveButton("确定") { _, _ -> nav = nav.copy(modelQuery = field.text.toString().trim().take(60)); render() }
            .show()
    }

    private fun editServer() {
        val field = dialogField("https://u.80aj.com", config.serverUrl, false)
        val dialog = dialog("服务地址", "只支持 HTTPS。修改后需要重新配对。", field)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val server = field.text.toString().trim().trimEnd('/')
                if (!isValidHttpsUrl(server)) {
                    field.error = "请输入有效的 HTTPS 地址"
                    return@setOnClickListener
                }
                updateConfig(config.copy(serverUrl = server))
                dialog.dismiss()
                refreshAll()
            }
        }
        dialog.show()
    }

    private fun pair() {
        val field = dialogField(
            if (storage.readDisplayToken().isNullOrBlank()) "输入 10 分钟配对码或 Display Token" else "输入新的配对码以重新配对", "", true,
        )
        val note = "在 ${config.serverUrl.removePrefix("https://")} 登录后生成配对码。凭证由 Android Keystore 加密保存。"
        val dialog = dialog("设备配对", note, field).setNegativeButton("取消", null).setPositiveButton("配对", null).create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val input = field.text.toString()
                if (input.isBlank()) {
                    field.error = "必须填写 Display Token 或配对码"
                    return@setOnClickListener
                }
                positive.isEnabled = false
                field.isEnabled = false
                val server = config.serverUrl
                executor.execute {
                    val resolved = runCatching { client.resolveDisplayToken(server, input, storage.installationId(this)) }
                    handler.post {
                        resolved.onFailure { error ->
                            field.error = error.message ?: "凭证验证失败"
                            field.isEnabled = true
                            positive.isEnabled = true
                        }.onSuccess { token ->
                            storage.writeDisplayToken(token)
                            dialog.dismiss()
                            nav = Nav()
                            refreshPolicy.reset()
                            refreshAll()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /* ------------------------------------------------------------------ window */

    private fun applyImmersiveMode() {
        if (BuildConfig.KIOSK_MODE || config.immersiveMode) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }

    private fun applyBrightness() {
        window.attributes = window.attributes.apply { screenBrightness = config.brightnessPercent / 100f }
    }

    private fun applyWindowBehavior() {
        setShowWhenLocked(config.showOnLockScreen)
        setTurnScreenOn(config.showOnLockScreen)
        if (config.keepScreenOn || BuildConfig.KIOSK_MODE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dismissNonSecureKeyguard() {
        if (!config.showOnLockScreen) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked && !keyguard.isDeviceSecure) keyguard.requestDismissKeyguard(this, null)
    }

    private fun openSystemHome() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked && !keyguard.isDeviceSecure) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { launchSystemHome() }
            })
        } else launchSystemHome()
    }

    private fun launchSystemHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun isValidHttpsUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank()
    }.getOrDefault(false)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

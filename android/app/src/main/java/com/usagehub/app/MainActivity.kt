package com.usagehub.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var dashboardView: DashboardView
    private lateinit var storage: AppStorage
    private val client = DashboardClient()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val fetchInFlight = AtomicBoolean(false)
    private var snapshot: DashboardSnapshot? = null
    private var config = AppConfig("https://u.80aj.com", 45, 2, DisplayMode.USED, true, false)
    private var refreshPolicy = RefreshPolicy()
    private var healthOk = false
    private var statusMessage = "正在启动"
    private var started = false

    private val clockTick = object : Runnable {
        override fun run() {
            render()
            if (started) handler.postDelayed(this, delayToNextMinute())
        }
    }

    private val scheduledFetch = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = AppStorage(this)
        config = storage.loadConfig()
        snapshot = storage.readCache()
        refreshPolicy = RefreshPolicy(config.refreshMinutes * 60_000L)
        dashboardView = DashboardView(this).apply {
            onOpenSettings = { showSettings() }
        }
        setContentView(dashboardView)
        applyWindowBehavior()
        applyBrightness()
        if (BuildConfig.KIOSK_MODE) enterImmersiveMode()
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        scheduleRefresh(0L)
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(scheduledFetch)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && BuildConfig.KIOSK_MODE) enterImmersiveMode()
    }

    private fun refresh() {
        if (!started || !fetchInFlight.compareAndSet(false, true)) return
        val requestConfig = config
        val token = storage.readDisplayToken()
        if (token.isNullOrBlank()) {
            statusMessage = "等待配置"
            healthOk = false
            fetchInFlight.set(false)
            render()
            scheduleRefresh(requestConfig.refreshMinutes * 60_000L)
            return
        }
        statusMessage = "正在同步"
        render()
        executor.execute {
            val result = runCatching {
                client.fetch(requestConfig.serverUrl, token, System.currentTimeMillis())
            }
            handler.post {
                fetchInFlight.set(false)
                result.onSuccess { fetched ->
                    snapshot = fetched.snapshot
                    healthOk = fetched.healthOk
                    storage.writeCache(fetched.snapshot)
                    statusMessage = if (healthOk) "服务正常" else "数据已更新"
                    scheduleRefresh(refreshPolicy.onSuccess())
                }.onFailure { error ->
                    healthOk = false
                    statusMessage = when (error.message) {
                        "HTTPS is required" -> "仅支持 HTTPS"
                        else -> "使用上次数据"
                    }
                    scheduleRefresh(refreshPolicy.onFailure())
                }
                render()
            }
        }
    }

    private fun scheduleRefresh(delayMillis: Long) {
        handler.removeCallbacks(scheduledFetch)
        if (started) handler.postDelayed(scheduledFetch, delayMillis)
    }

    private fun render() {
        dashboardView.state = DashboardScreenState(
            nowMillis = System.currentTimeMillis(),
            snapshot = snapshot,
            diagnostics = DiagnosticsReader.read(this, healthOk),
            statusMessage = statusMessage,
            refreshMinutes = config.refreshMinutes,
        )
    }

    private fun enterImmersiveMode() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness = config.brightnessPercent / 100f
        }
    }

    private fun applyWindowBehavior() {
        setShowWhenLocked(config.showOnLockScreen)
        setTurnScreenOn(config.showOnLockScreen)
        if (config.keepScreenOn || BuildConfig.KIOSK_MODE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showSettings() {
        val padding = dp(22)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, 0)
        }
        val serverInput = EditText(this).apply {
            hint = "https://u.80aj.com"
            setText(config.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            contentDescription = "用量服务 HTTPS 地址"
        }
        val tokenInput = EditText(this).apply {
            hint = if (storage.readDisplayToken().isNullOrBlank()) "输入 Display Token 或 10 分钟配对码" else "留空则不修改现有凭证"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            contentDescription = "UsageHub display token"
        }
        val brightnessLabel = label("屏幕亮度 ${config.brightnessPercent}%")
        val brightness = SeekBar(this).apply {
            max = 90
            progress = config.brightnessPercent - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    brightnessLabel.text = "屏幕亮度 ${progress + 10}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val intervals = listOf(2, 5, 10)
        val intervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                intervals.map { "每 $it 分钟取数" },
            )
            setSelection(intervals.indexOf(config.refreshMinutes).coerceAtLeast(0))
        }
        val showOnLockScreen = CheckBox(this).apply {
            text = "锁屏时显示用量看板"
            isChecked = config.showOnLockScreen
        }
        val keepScreenOn = CheckBox(this).apply {
            text = "保持屏幕常亮"
            isChecked = config.keepScreenOn
        }
        layout.addView(label("服务地址"))
        layout.addView(serverInput)
        layout.addView(label("Display token · encrypted by Android Keystore"))
        layout.addView(tokenInput)
        layout.addView(brightnessLabel)
        layout.addView(brightness)
        layout.addView(intervalSpinner)
        layout.addView(showOnLockScreen)
        layout.addView(keepScreenOn)

        val dialog = AlertDialog.Builder(this)
            .setTitle("UsageHub 设置")
            .setView(layout)
            .setNeutralButton("系统设置") { _, _ -> startActivity(Intent(Settings.ACTION_SETTINGS)) }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val server = serverInput.text.toString().trim().trimEnd('/')
                val token = tokenInput.text.toString()
                if (!isValidHttpsUrl(server)) {
                    serverInput.error = "请输入有效的 HTTPS 地址"
                    return@setOnClickListener
                }
                if (token.isBlank() && storage.readDisplayToken().isNullOrBlank()) {
                    tokenInput.error = "必须填写 Display Token 或配对码"
                    return@setOnClickListener
                }
                val newConfig = AppConfig(
                    serverUrl = server,
                    brightnessPercent = brightness.progress + 10,
                    refreshMinutes = intervals[intervalSpinner.selectedItemPosition],
                    displayMode = DisplayMode.USED,
                    showOnLockScreen = showOnLockScreen.isChecked,
                    keepScreenOn = keepScreenOn.isChecked,
                )
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
                tokenInput.isEnabled = false
                executor.execute {
                    val resolved = runCatching {
                        if (token.isBlank()) null else client.resolveDisplayToken(server, token, storage.installationId(this))
                    }
                    handler.post {
                        resolved.onFailure { error ->
                            tokenInput.error = error.message ?: "凭证验证失败"
                            tokenInput.isEnabled = true
                            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true
                        }.onSuccess { displayToken ->
                            storage.saveConfig(newConfig)
                            if (displayToken != null) storage.writeDisplayToken(displayToken)
                            config = newConfig
                            refreshPolicy = RefreshPolicy(config.refreshMinutes * 60_000L)
                            applyWindowBehavior()
                            applyBrightness()
                            statusMessage = "设置已保存"
                            dialog.dismiss()
                            render()
                            scheduleRefresh(0L)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun isValidHttpsUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol == "https" && url.host.isNotBlank()
    }.getOrDefault(false)

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.DKGRAY)
        textSize = 13f
        setPadding(0, dp(8), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun delayToNextMinute(nowMillis: Long = System.currentTimeMillis()): Long =
        60_000L - nowMillis % 60_000L

}

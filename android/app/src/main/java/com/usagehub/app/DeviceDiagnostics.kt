package com.usagehub.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager

data class DeviceDiagnostics(
    val widthPixels: Int,
    val heightPixels: Int,
    val density: Float,
    val densityDpi: Int,
    val networkLabel: String,
    val keepAwake: Boolean,
    val fullscreen: Boolean,
    val healthOk: Boolean,
)

object DiagnosticsReader {
    fun read(context: Context, healthOk: Boolean): DeviceDiagnostics {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val resourcesMetrics = context.resources.displayMetrics
        return DeviceDiagnostics(
            widthPixels = metrics.width(),
            heightPixels = metrics.height(),
            density = resourcesMetrics.density,
            densityDpi = resourcesMetrics.densityDpi,
            networkLabel = networkLabel(context),
            keepAwake = BuildConfig.KIOSK_MODE,
            fullscreen = BuildConfig.KIOSK_MODE,
            healthOk = healthOk,
        )
    }

    private fun networkLabel(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return "离线"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "离线"
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "离线"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            else -> "在线"
        }
    }
}

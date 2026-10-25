package com.usagehub.app

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.usagehub.app.data.ConsoleRules
import com.usagehub.app.data.DashboardJson
import com.usagehub.app.data.DashboardSnapshot
import com.usagehub.app.data.DisplayMode
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppConfig(
    val serverUrl: String,
    val brightnessPercent: Int,
    val refreshMinutes: Int,
    val displayMode: DisplayMode,
    val showOnLockScreen: Boolean,
    val keepScreenOn: Boolean,
    val immersiveMode: Boolean,
    /** Quota older than this shows as 「数据过期」 (device-side rule; see ConsoleRules.status). */
    val quotaStaleMinutes: Int = ConsoleRules.DEFAULT_QUOTA_STALE_MINUTES,
)

class AppStorage(private val context: Context) {
    private val preferences = context.getSharedPreferences("usagehub", Context.MODE_PRIVATE)
    private val secureKey = SecureKeyStorage(context)

    fun loadConfig(): AppConfig = AppConfig(
        serverUrl = preferences.getString("server_url", "https://u.80aj.com")
            ?: "https://u.80aj.com",
        brightnessPercent = preferences.getInt("brightness_percent", 45).coerceIn(10, 100),
        refreshMinutes = preferences.getInt("refresh_minutes", 2).takeIf { it in setOf(2, 5, 10) } ?: 2,
        displayMode = DisplayMode.USED,
        showOnLockScreen = preferences.getBoolean("show_on_lock_screen", true),
        keepScreenOn = preferences.getBoolean("keep_screen_on", false),
        immersiveMode = preferences.getBoolean("immersive_mode", true),
        quotaStaleMinutes = preferences.getInt("quota_stale_minutes", ConsoleRules.DEFAULT_QUOTA_STALE_MINUTES)
            .takeIf { it in ConsoleRules.QUOTA_STALE_OPTIONS } ?: ConsoleRules.DEFAULT_QUOTA_STALE_MINUTES,
    )

    fun saveConfig(config: AppConfig) {
        preferences.edit()
            .putString("server_url", config.serverUrl.trimEnd('/'))
            .putInt("brightness_percent", config.brightnessPercent.coerceIn(10, 100))
            .putInt("refresh_minutes", config.refreshMinutes)
            .putString("display_mode", DisplayMode.USED.name)
            .putBoolean("show_on_lock_screen", config.showOnLockScreen)
            .putBoolean("keep_screen_on", config.keepScreenOn)
            .putBoolean("immersive_mode", config.immersiveMode)
            .putInt("quota_stale_minutes", config.quotaStaleMinutes)
            // Leftover of the removed WebView console (0.1.7): drop the flag instead of carrying it forward.
            .remove("web_console")
            .apply()
    }

    fun readDisplayToken(): String? = secureKey.read()

    fun writeDisplayToken(value: String) = secureKey.write(value)

    fun readCache(): DashboardSnapshot? {
        val raw = preferences.getString("dashboard_cache", null) ?: return null
        return runCatching { DashboardJson.parseCache(raw) }.getOrNull()
    }

    fun writeCache(snapshot: DashboardSnapshot) {
        preferences.edit().putString("dashboard_cache", DashboardJson.encode(snapshot)).apply()
    }

    private val jsonDir: File get() = File(context.filesDir, "json-cache")

    /** Last successful raw response per request key, so the dashboard can render offline. */
    fun writeJson(key: String, raw: String) {
        runCatching {
            jsonDir.mkdirs()
            val target = File(jsonDir, "${safeName(key)}.json")
            val temp = File(jsonDir, "${safeName(key)}.tmp")
            temp.writeText(raw, Charsets.UTF_8)
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    fun readJson(key: String): String? =
        runCatching { File(jsonDir, "${safeName(key)}.json").takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()

    fun cacheBytes(): Long =
        (jsonDir.listFiles()?.sumOf { it.length() } ?: 0L) + (preferences.getString("dashboard_cache", null)?.length?.toLong() ?: 0L)

    fun clearCache() {
        jsonDir.listFiles()?.forEach { it.delete() }
        preferences.edit().remove("dashboard_cache").apply()
    }

    private fun safeName(key: String): String = key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun readDisplayName(): String = preferences.getString("display_name", "UsageHub") ?: "UsageHub"

    fun writeDisplayProfile(displayName: String, avatar: ByteArray?) {
        preferences.edit().putString("display_name", displayName.take(100)).apply()
        val avatarFile = File(context.filesDir, "profile-avatar.webp")
        if (avatar == null) avatarFile.delete() else if (avatar.size <= 2 * 1024 * 1024) avatarFile.writeBytes(avatar)
    }

    fun readAvatar(): ByteArray? {
        val avatarFile = File(context.filesDir, "profile-avatar.webp")
        return avatarFile.takeIf { it.isFile && it.length() in 1..(2L * 1024 * 1024) }?.readBytes()
    }

    fun installationId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}

private class SecureKeyStorage(private val context: Context) {
    private val preferences = context.getSharedPreferences("usagehub_secure", Context.MODE_PRIVATE)
    private val alias = "usagehub_display_token_encryption"

    fun write(plainText: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun read(): String? {
        val ciphertext = preferences.getString("ciphertext", null) ?: return null
        val iv = preferences.getString("iv", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }
}

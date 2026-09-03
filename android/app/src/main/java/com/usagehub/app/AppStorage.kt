package com.usagehub.app

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
)

class AppStorage(context: Context) {
    private val preferences = context.getSharedPreferences("usagehub", Context.MODE_PRIVATE)
    private val secureKey = SecureKeyStorage(context)

    fun loadConfig(): AppConfig = AppConfig(
        serverUrl = preferences.getString("server_url", "https://u.80aj.com")
            ?: "https://u.80aj.com",
        brightnessPercent = preferences.getInt("brightness_percent", 45).coerceIn(10, 100),
        refreshMinutes = preferences.getInt("refresh_minutes", 2).takeIf { it in setOf(2, 5, 10) } ?: 2,
        displayMode = DisplayMode.USED,
    )

    fun saveConfig(config: AppConfig) {
        preferences.edit()
            .putString("server_url", config.serverUrl.trimEnd('/'))
            .putInt("brightness_percent", config.brightnessPercent.coerceIn(10, 100))
            .putInt("refresh_minutes", config.refreshMinutes)
            .putString("display_mode", DisplayMode.USED.name)
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

package com.usagehub.app

class SettingsHoldPolicy(
    private val requiredHoldMillis: Long = 3_000L,
    private val movementLimit: Float = 28f,
) {
    private var startedAtMillis: Long? = null

    fun onDown(nowMillis: Long) {
        startedAtMillis = nowMillis
    }

    fun onMove(insideTarget: Boolean, movement: Float) {
        if (!insideTarget || movement > movementLimit) cancel()
    }

    fun onUp() {
        cancel()
    }

    fun onTimer(nowMillis: Long): Boolean {
        val startedAt = startedAtMillis ?: return false
        if (nowMillis - startedAt < requiredHoldMillis) return false
        cancel()
        return true
    }

    fun cancel() {
        startedAtMillis = null
    }
}

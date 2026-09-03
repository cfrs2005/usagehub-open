package com.usagehub.app

class RefreshPolicy(
    private val normalIntervalMillis: Long = 2L * 60L * 1000L,
) {
    private var consecutiveFailures = 0

    fun onSuccess(): Long {
        consecutiveFailures = 0
        return normalIntervalMillis
    }

    fun onFailure(): Long {
        consecutiveFailures += 1
        return when (consecutiveFailures) {
            1 -> 5L * 60L * 1000L
            2 -> 15L * 60L * 1000L
            else -> 30L * 60L * 1000L
        }
    }

    fun reset() {
        consecutiveFailures = 0
    }
}

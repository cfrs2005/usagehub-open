package com.usagehub.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshPolicyTest {
    @Test
    fun successUsesConfiguredNormalInterval() {
        val policy = RefreshPolicy(normalIntervalMillis = 120_000L)
        assertEquals(120_000L, policy.onSuccess())
    }

    @Test
    fun failuresBackOffAtFiveFifteenAndThirtyMinutes() {
        val policy = RefreshPolicy()
        assertEquals(5 * 60_000L, policy.onFailure())
        assertEquals(15 * 60_000L, policy.onFailure())
        assertEquals(30 * 60_000L, policy.onFailure())
        assertEquals(30 * 60_000L, policy.onFailure())
    }

    @Test
    fun successResetsFailureSequence() {
        val policy = RefreshPolicy()
        policy.onFailure()
        policy.onFailure()
        assertEquals(2 * 60_000L, policy.onSuccess())
        assertEquals(5 * 60_000L, policy.onFailure())
    }
}

package com.usagehub.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHoldPolicyTest {
    @Test
    fun normalTapNeverOpensSettings() {
        val policy = SettingsHoldPolicy()
        policy.onDown(1_000L)
        policy.onUp()
        assertFalse(policy.onTimer(4_500L))
    }

    @Test
    fun onlyThreeSecondHoldOpensSettings() {
        val policy = SettingsHoldPolicy()
        policy.onDown(1_000L)
        assertFalse(policy.onTimer(3_999L))
        assertTrue(policy.onTimer(4_000L))
        assertFalse(policy.onTimer(5_000L))
    }

    @Test
    fun leavingTargetOrMovingTooFarCancelsHold() {
        val outside = SettingsHoldPolicy()
        outside.onDown(1_000L)
        outside.onMove(insideTarget = false, movement = 0f)
        assertFalse(outside.onTimer(4_000L))

        val moved = SettingsHoldPolicy()
        moved.onDown(1_000L)
        moved.onMove(insideTarget = true, movement = 29f)
        assertFalse(moved.onTimer(4_000L))
    }
}

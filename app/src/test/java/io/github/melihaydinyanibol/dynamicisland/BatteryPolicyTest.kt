package io.github.melihaydinyanibol.dynamicisland

import io.github.melihaydinyanibol.dynamicisland.model.BatteryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPolicyTest {

    @Test
    fun firesWhenCrossingThresholdsDownward() {
        assertEquals(20, BatteryPolicy.crossedLowThreshold(previous = 21, current = 20, charging = false))
        assertEquals(10, BatteryPolicy.crossedLowThreshold(previous = 11, current = 9, charging = false))
        assertEquals(5, BatteryPolicy.crossedLowThreshold(previous = 6, current = 5, charging = false))
    }

    @Test
    fun ignoresChargingFirstReadingAndRises() {
        assertNull(BatteryPolicy.crossedLowThreshold(previous = 21, current = 20, charging = true))
        assertNull(BatteryPolicy.crossedLowThreshold(previous = null, current = 20, charging = false))
        assertNull(BatteryPolicy.crossedLowThreshold(previous = 19, current = 20, charging = false))
        assertNull(BatteryPolicy.crossedLowThreshold(previous = 19, current = 18, charging = false))
    }

    @Test
    fun detectsFullBattery() {
        assertTrue(BatteryPolicy.becameFull(previous = 99, current = 100, charging = true))
        assertFalse(BatteryPolicy.becameFull(previous = 100, current = 100, charging = true))
        assertFalse(BatteryPolicy.becameFull(previous = 99, current = 100, charging = false))
    }

    @Test
    fun levelColors() {
        assertEquals(BatteryPolicy.GREEN, BatteryPolicy.levelColor(5, charging = true))
        assertEquals(BatteryPolicy.RED, BatteryPolicy.levelColor(10, charging = false))
        assertEquals(BatteryPolicy.ORANGE, BatteryPolicy.levelColor(20, charging = false))
        assertEquals(BatteryPolicy.GREEN, BatteryPolicy.levelColor(80, charging = false))
    }
}

package io.github.melihaydinyanibol.dynamicisland.model

/** Decides when battery changes deserve an island alert. */
object BatteryPolicy {

    val LOW_THRESHOLDS = listOf(20, 10, 5)

    /**
     * Returns the low-battery threshold that was just crossed going down, or null.
     * Nothing fires while charging or on the first reading.
     */
    fun crossedLowThreshold(previous: Int?, current: Int, charging: Boolean): Int? {
        if (charging || previous == null || current >= previous) return null
        return LOW_THRESHOLDS.firstOrNull { current <= it && previous > it }
    }

    /** True when the battery just reached full while plugged in. */
    fun becameFull(previous: Int?, current: Int, charging: Boolean): Boolean =
        charging && previous != null && previous < 100 && current >= 100

    /** Accent colour for a battery level, matching the familiar green / orange / red scheme. */
    fun levelColor(level: Int, charging: Boolean): Int = when {
        charging -> GREEN
        level <= 10 -> RED
        level <= 20 -> ORANGE
        else -> GREEN
    }

    const val GREEN: Int = 0xFF34C759.toInt()
    const val ORANGE: Int = 0xFFFF9F0A.toInt()
    const val RED: Int = 0xFFFF453A.toInt()
}

package io.github.melihaydinyanibol.dynamicisland

import io.github.melihaydinyanibol.dynamicisland.model.MediaInfo
import io.github.melihaydinyanibol.dynamicisland.util.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun formatsMinutesAndHours() {
        assertEquals("0:00", TimeFormat.duration(0))
        assertEquals("0:05", TimeFormat.duration(5_999))
        assertEquals("3:07", TimeFormat.duration(187_000))
        assertEquals("1:02:03", TimeFormat.duration(3_723_000))
        assertEquals("0:00", TimeFormat.duration(-10))
    }

    @Test
    fun mediaPositionAdvancesWhilePlaying() {
        val playing = MediaInfo(true, 10_000, 1_000, positionSampledAt = 100, speed = 1f, controls = null)
        assertEquals(3_000, playing.positionAt(2_100))
        assertEquals(10_000, playing.positionAt(1_000_000))

        val paused = playing.copy(isPlaying = false)
        assertEquals(1_000, paused.positionAt(2_100))

        val fast = playing.copy(speed = 2f)
        assertEquals(5_000, fast.positionAt(2_100))
    }
}

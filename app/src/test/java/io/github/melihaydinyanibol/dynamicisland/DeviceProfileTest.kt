package io.github.melihaydinyanibol.dynamicisland

import io.github.melihaydinyanibol.dynamicisland.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun detectsGalaxyS25Fe() {
        listOf("SM-S731B", "SM-S731B/DS", "SM-S731U", "sm-s731w").forEach {
            assertEquals(it, DeviceProfile.GALAXY_S25_FE, DeviceProfile.detect(it))
        }
    }

    @Test
    fun detectsGalaxyA56() {
        listOf("SM-A566B", "SM-A566E/DS", " SM-A5660 ").forEach {
            assertEquals(it, DeviceProfile.GALAXY_A56, DeviceProfile.detect(it))
        }
    }

    @Test
    fun otherDevicesUseGenericProfile() {
        assertEquals(DeviceProfile.GENERIC, DeviceProfile.detect("SM-S931B"))
        assertEquals(DeviceProfile.GENERIC, DeviceProfile.detect("Pixel 9"))
        assertEquals(DeviceProfile.GENERIC, DeviceProfile.detect(null))
        assertFalse(DeviceProfile.GENERIC.isTuned)
        assertTrue(DeviceProfile.GALAXY_A56.isTuned)
    }

    @Test
    fun fallbackCutoutScalesWithResolution() {
        val fhd = DeviceProfile.GALAXY_S25_FE.fallbackCutout(1080)
        assertEquals(540, fhd.centerX)
        val hd = DeviceProfile.GALAXY_S25_FE.fallbackCutout(720)
        assertEquals(360, hd.centerX)
        assertTrue(hd.width < fhd.width)
        assertEquals(hd.width, hd.height)
    }
}

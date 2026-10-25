package io.github.melihaydinyanibol.dynamicisland

import io.github.melihaydinyanibol.dynamicisland.ui.Spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTest {

    private val springs = listOf(Spring.EXPAND, Spring.COLLAPSE, Spring.CAMERA)

    @Test
    fun startsAtZeroAndSettlesAtOne() {
        springs.forEach { spring ->
            assertEquals(0f, spring.value(0f), 1e-4f)
            assertEquals(1f, spring.value(spring.settleSeconds), 0.01f)
            assertTrue(spring.settleSeconds in 0.2f..1.2f)
        }
    }

    @Test
    fun expandBouncesMoreThanCameraSink() {
        fun peak(spring: Spring) = (1..200).maxOf { spring.value(it * spring.settleSeconds / 200) }
        assertTrue(peak(Spring.EXPAND) > 1.02f)
        assertTrue(peak(Spring.CAMERA) < 1.01f)
    }
}

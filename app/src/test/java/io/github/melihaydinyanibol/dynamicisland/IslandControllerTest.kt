package io.github.melihaydinyanibol.dynamicisland

import io.github.melihaydinyanibol.dynamicisland.model.ActivityType
import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import io.github.melihaydinyanibol.dynamicisland.model.IslandController
import io.github.melihaydinyanibol.dynamicisland.model.IslandMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandControllerTest {

    private fun activity(key: String, type: ActivityType, timestamp: Long = 0L, expand: Boolean = true) =
        IslandActivity(
            key = key,
            type = type,
            packageName = "pkg",
            title = key,
            timestamp = timestamp,
            expandOnAlert = expand,
        )

    @Test
    fun idleWhenNothingIsActive() {
        val controller = IslandController()
        assertEquals(IslandMode.IDLE, controller.state().mode)
        controller.showIdle = false
        assertEquals(IslandMode.HIDDEN, controller.state().mode)
    }

    @Test
    fun hiddenOverridesEverything() {
        val controller = IslandController()
        controller.setLive(listOf(activity("media", ActivityType.MEDIA)))
        controller.hidden = true
        assertEquals(IslandMode.HIDDEN, controller.state().mode)
    }

    @Test
    fun highestPriorityLiveActivityWins() {
        val controller = IslandController()
        controller.setLive(
            listOf(
                activity("media", ActivityType.MEDIA),
                activity("timer", ActivityType.TIMER),
                activity("download", ActivityType.PROGRESS),
            ),
        )
        val state = controller.state()
        assertEquals(IslandMode.COMPACT, state.mode)
        assertEquals("timer", state.activity?.key)
        assertEquals(3, state.liveCount)
    }

    @Test
    fun newerActivityWinsWithinSameType() {
        val ordered = IslandController.order(
            listOf(
                activity("old", ActivityType.PROGRESS, timestamp = 1),
                activity("new", ActivityType.PROGRESS, timestamp = 2),
            ),
        )
        assertEquals(listOf("new", "old"), ordered.map { it.key })
    }

    @Test
    fun incomingCallTakesOverAndExpands() {
        val controller = IslandController()
        controller.setLive(listOf(activity("media", ActivityType.MEDIA)))
        controller.setLive(listOf(activity("media", ActivityType.MEDIA), activity("call", ActivityType.CALL)))
        val state = controller.state()
        assertEquals("call", state.activity?.key)
        assertEquals(IslandMode.EXPANDED, state.mode)
    }

    @Test
    fun lowerPriorityNewcomerDoesNotSteal() {
        val controller = IslandController()
        controller.setLive(listOf(activity("timer", ActivityType.TIMER)))
        controller.expand()
        controller.setLive(listOf(activity("timer", ActivityType.TIMER), activity("media", ActivityType.MEDIA)))
        val state = controller.state()
        assertEquals("timer", state.activity?.key)
        assertEquals(IslandMode.EXPANDED, state.mode)
    }

    @Test
    fun cycleWrapsAroundLiveActivities() {
        val controller = IslandController()
        controller.setLive(listOf(activity("timer", ActivityType.TIMER), activity("media", ActivityType.MEDIA)))
        assertTrue(controller.cycle(forward = true))
        assertEquals("media", controller.state().activity?.key)
        assertTrue(controller.cycle(forward = true))
        assertEquals("timer", controller.state().activity?.key)
        assertTrue(controller.cycle(forward = false))
        assertEquals("media", controller.state().activity?.key)
    }

    @Test
    fun cycleNeedsTwoActivities() {
        val controller = IslandController()
        controller.setLive(listOf(activity("timer", ActivityType.TIMER)))
        assertFalse(controller.cycle(forward = true))
    }

    @Test
    fun selectionResetsWhenSelectedActivityEnds() {
        val controller = IslandController()
        controller.setLive(listOf(activity("timer", ActivityType.TIMER), activity("media", ActivityType.MEDIA)))
        controller.cycle(forward = true)
        controller.expand()
        controller.setLive(listOf(activity("timer", ActivityType.TIMER)))
        val state = controller.state()
        assertEquals("timer", state.activity?.key)
        assertEquals(IslandMode.COMPACT, state.mode)
    }

    @Test
    fun alertsPreemptLiveActivitiesAndQueue() {
        val controller = IslandController()
        controller.setLive(listOf(activity("media", ActivityType.MEDIA)))

        assertTrue(controller.enqueueAlert(activity("a", ActivityType.NOTIFICATION)))
        assertFalse(controller.enqueueAlert(activity("b", ActivityType.NOTIFICATION)))

        var state = controller.state()
        assertTrue(state.isAlert)
        assertEquals("a", state.activity?.key)
        assertEquals(IslandMode.EXPANDED, state.mode)

        assertEquals("b", controller.dismissAlert()?.key)
        assertNull(controller.dismissAlert())

        state = controller.state()
        assertFalse(state.isAlert)
        assertEquals("media", state.activity?.key)
    }

    @Test
    fun sameKeyAlertReplacesCurrentOne() {
        val controller = IslandController()
        controller.enqueueAlert(activity("a", ActivityType.NOTIFICATION, timestamp = 1))
        assertTrue(controller.enqueueAlert(activity("a", ActivityType.NOTIFICATION, timestamp = 2)))
        assertEquals(2L, controller.currentAlert?.timestamp)
        assertNull(controller.dismissAlert())
    }

    @Test
    fun compactAlertsStayCompact() {
        val controller = IslandController()
        controller.enqueueAlert(activity("charging", ActivityType.CHARGING, expand = false))
        assertEquals(IslandMode.COMPACT, controller.state().mode)
    }

    @Test
    fun removeAlertDropsQueuedAndCurrent() {
        val controller = IslandController()
        controller.enqueueAlert(activity("a", ActivityType.NOTIFICATION))
        controller.enqueueAlert(activity("b", ActivityType.NOTIFICATION))
        assertFalse(controller.removeAlert("b"))
        assertTrue(controller.removeAlert("a"))
        assertNull(controller.currentAlert)
    }

    @Test
    fun alertQueueIsBounded() {
        val controller = IslandController(maxQueuedAlerts = 2)
        controller.enqueueAlert(activity("current", ActivityType.NOTIFICATION))
        listOf("1", "2", "3").forEach { controller.enqueueAlert(activity(it, ActivityType.NOTIFICATION)) }
        assertEquals("2", controller.dismissAlert()?.key)
        assertEquals("3", controller.dismissAlert()?.key)
        assertNull(controller.dismissAlert())
    }

    @Test
    fun expandRequiresLiveActivity() {
        val controller = IslandController()
        assertFalse(controller.expand())
        controller.setLive(listOf(activity("media", ActivityType.MEDIA)))
        assertTrue(controller.expand())
        assertEquals(IslandMode.EXPANDED, controller.state().mode)
        controller.collapse()
        assertEquals(IslandMode.COMPACT, controller.state().mode)
    }
}

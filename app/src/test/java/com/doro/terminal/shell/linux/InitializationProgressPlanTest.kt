package com.doro.terminal.shell.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitializationProgressPlanTest {
    @Test
    fun containerProgressNeverMovesBackwards() {
        val points = listOf(
            InitializationStep.CREATE_CONTAINER.progress,
            InitializationProgressPlan.LOADER_CHECK,
            InitializationProgressPlan.GNOME_ARCHIVE_START,
            InitializationProgressPlan.GNOME_ARCHIVE_END,
            InitializationProgressPlan.GNOME_PACKAGES_START,
            InitializationProgressPlan.GNOME_PACKAGES_END,
            InitializationProgressPlan.GNOME_COMPLETE,
            InitializationProgressPlan.FASTFETCH,
            InitializationProgressPlan.VENDOR_ARCHIVE_START,
            InitializationProgressPlan.VENDOR_ARCHIVE_END,
            InitializationProgressPlan.VENDOR_TASKS_START,
            InitializationProgressPlan.VENDOR_TASKS_END,
            InitializationProgressPlan.VENDOR_COMPLETE,
        )

        assertEquals(points.sorted(), points)
        assertTrue(InitializationProgressPlan.VENDOR_COMPLETE < InitializationStep.CREATE_USER.progress)
    }

    @Test
    fun remainingSetupStepsAlwaysMoveForward() {
        val remaining = listOf(
            InitializationStep.CREATE_USER.progress,
            InitializationStep.CONFIGURE_SUDO.progress,
            InitializationStep.INITIALIZE_ENVIRONMENT.progress,
            InitializationStep.SAVE_CONFIGURATION.progress,
        )

        assertEquals(remaining.sorted(), remaining)
        assertTrue(remaining.last() < 100)
    }
}
